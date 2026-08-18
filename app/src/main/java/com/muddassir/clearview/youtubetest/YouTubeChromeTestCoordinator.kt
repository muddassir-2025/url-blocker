package com.muddassir.clearview.youtubetest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.muddassir.clearview.extractor.ContentExtractor
import com.muddassir.clearview.matching.KeywordMatcher
import com.muddassir.clearview.repository.BlockRepository
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-video enforcement state for a blocked YouTube Short.
 *
 *  - NORMAL: no blocked Short active.
 *  - BLOCKED_NEEDS_PAUSE: the Short matched a test keyword; pause has not yet
 *    been confirmed. Sequence: reveal player controls once, then one physical
 *    pause request (center tap), rescanning between steps. Bounded retries.
 *  - BLOCKED_ENFORCING: playback is confirmed paused (a Play control is
 *    exposed, or a Pause control was clicked). Enforcement continues: if the
 *    user taps the player and playback resumes, it is paused again. If the
 *    controls disappear for a while, escalate to BLOCKED_UNKNOWN_STATE so the
 *    player is physically protected.
 *  - BLOCKED_UNKNOWN_STATE: the tree exposes no Play/Pause control, so the
 *    player state cannot be observed. A gesture-aware accessibility overlay
 *    covers ONLY the player bounds: taps are consumed (the blocked video can
 *    never be tapped back on) but vertical swipes pass through — they are
 *    replayed into Chrome so the user can still swipe between Shorts.
 */
enum class YoutubeBlockState {
    NORMAL,
    BLOCKED_NEEDS_PAUSE,
    BLOCKED_ENFORCING,
    BLOCKED_UNKNOWN_STATE
}

/**
 * Internal phases of the BLOCKED_NEEDS_PAUSE pause sequence. The player node
 * and its bounds can appear ASYNCHRONOUSLY after the Short is detected, so
 * the sequence waits for the player, reveals the controls, waits for the tree
 * to update, sends exactly ONE physical pause gesture, and then observes the
 * result — before any overlay protection is enabled.
 */
enum class PausePhase {
    WAIT_FOR_PLAYER,
    PLAYER_READY,
    WAIT_FOR_CONTROLS,
    PAUSE_REQUEST,
    OBSERVE
}

/**
 * ── STAGE 1 FEASIBILITY TEST ── YouTube Shorts detection in Google Chrome.
 *
 * Experimental coordinator, gated by the "YouTube Chrome Test" master toggle
 * (Block tab). When enabled it ONLY watches Google Chrome
 * ([CHROME_PACKAGE]) and runs:
 *
 *   Chrome foreground → read URL / window title → YouTube? → Shorts? →
 *   visible-text extraction → match against the SEPARATE test-keyword list →
 *   persistent per-video enforcement (real pause + monitoring + protection)
 *
 * Hard separation from the normal ClearView blocker:
 *  - the normal blocked-keyword list is NEVER consulted — only
 *    [YoutubeTestKeywordRepository] (youtube_test_keywords)
 *  - a match pauses the video — it NEVER launches BlockOverlayActivity,
 *    clears/closes Chrome, or navigates anywhere
 *  - this coordinator never touches the blocking pipeline (blockingState,
 *    dedup cache, overlay, ...) — it is fully independent
 *
 * ENFORCEMENT MODEL (idempotent — calling it 100x while paused leaves it
 * paused):
 *   1. A matched Short → BLOCKED_NEEDS_PAUSE.
 *   2. Real pause attempt, never an unconditional blind tap:
 *        Pause control exposed  -> click Pause, rescan
 *        Play  control exposed  -> already paused, do nothing, keep enforcing
 *        no control exposed     -> reveal controls (player click) once, then
 *                                  one physical center tap as the pause
 *                                  request, rescan, bounded retries
 *   3. If the playback state still cannot be observed → BLOCKED_UNKNOWN_STATE
 *      and a gesture-aware TYPE_ACCESSIBILITY_OVERLAY covers ONLY the player
 *      bounds: taps are consumed, vertical swipes are replayed into Chrome so
 *      the user can still swipe to the next Short. The overlay is a fallback
 *      protection layer, not the pause mechanism.
 *   4. A single enforcement loop (~300ms) keeps watching the SAME blocked
 *      video id. If the user taps the player (VIEW_CLICKED) or playback
 *      restarts, it is paused again. The blocked state NEVER self-releases
 *      because of transient windows (System UI, IME, ClearView's own
 *      activity/overlay): state is only reset when Chrome/YouTube content is
 *      genuinely gone (verified against the window list).
 *
 * A dispatched gesture / performAction()==true is NEVER treated as pause
 * success — only visible UI evidence (Play/Pause control state) is.
 *
 * All output goes to logcat under tag "ClearViewYTTest" (YT_BLOCK_* state
 * machine logs).
 */
class YouTubeChromeTestCoordinator(
    private val service: AccessibilityService,
    private val repository: BlockRepository,
    private val testKeywordRepository: YoutubeTestKeywordRepository
) {

    companion object {
        private const val TAG = "ClearViewYTTest"
        private const val CHROME_PACKAGE = "com.android.chrome"

        // Detection cadences.
        private const val POLL_INTERVAL_MS = 800L
        private const val EVENT_SCAN_MIN_INTERVAL_MS = 600L
        private const val WINDOW_RESCAN_DELAY_MS = 400L
        // Throttle the diagnostic tree dump (logcat flood guard).
        private const val TREE_DUMP_MIN_INTERVAL_MS = 10_000L
        // Throttle per-event YT_TEST_ENTER / SERVICE_EVENT lines.
        private const val EVENT_LOG_MIN_INTERVAL_MS = 500L

        // Enforcement cadences.
        private const val ENFORCEMENT_INTERVAL_MS = 300L
        private const val PAUSE_RETRY_DELAY_MS = 150L
        private const val MAX_PAUSE_ATTEMPTS = 4
        // Pause-phase cadences. The player node can appear AFTER the Short is
        // detected (Chrome renders the page asynchronously), so player
        // acquisition is a bounded retry window — WAIT_FOR_PLAYER polls for
        // valid player bounds at PLAYER_WAIT_RETRY_MS intervals for up to
        // PLAYER_ACQUIRE_TIMEOUT_MS total before giving up and escalating to
        // the UNKNOWN/protection fallback. Post-gesture observation is also a
        // bounded time window.
        private const val PLAYER_ACQUIRE_TIMEOUT_MS = 1500L
        private const val PLAYER_WAIT_RETRY_MS = 150L
        private const val CONTROLS_WAIT_MS = 500L
        private const val PAUSE_OBSERVE_MS = 800L
        // YT_BLOCK_REENFORCE log throttle (the loop runs every 300ms).
        private const val REENFORCE_LOG_INTERVAL_MS = 1000L
        // While BLOCKED_ENFORCING with no Play/Pause control exposed, how many
        // consecutive empty scans before escalating to BLOCKED_UNKNOWN_STATE
        // (controls hidden ⇒ resume can no longer be detected ⇒ protect).
        private const val NO_CONTROL_ESCALATE_TICKS = 5
        // After this many consecutive non-Chrome active-window scans with no
        // Chrome window anywhere, Chrome is genuinely gone → reset.
        private const val NON_CHROME_RESET_TICKS = 5

        // Gesture values.
        private const val SWIPE_THRESHOLD_PX = 80f
        private const val SWIPE_MIN_DY_PX = 120f
        private const val SWIPE_DIAGONAL_RATIO = 1.15f
        private const val GESTURE_STROKE_MS = 80L
        private const val SWIPE_REPLAY_DURATION_MS = 300L
        // Alpha (0-255) of the black wash painted over a protected player —
        // 255 = fully opaque: the protected player is completely hidden
        // behind black (the overlay also carries the centered "SWIPE / FEAR
        // GOD / BLOCK BRAIN ROT" message). Visual only; the gesture logic is
        // untouched.
        private const val BLACK_OVERLAY_ALPHA = 255
        // Delay between removing the overlay and dispatching the replayed
        // swipe — lets the overlay surface finish tearing down (Surface::
        // disconnect) so Chrome receives the gesture, not the dying overlay.
        private const val SWIPE_REPLAY_DELAY_MS = 80L
        // Bounded settling window after the replayed swipe while Shorts
        // navigation completes (Chrome does not navigate synchronously).
        private const val SWIPE_REPLAY_SETTLE_MS = 1000L
        private const val SWIPE_REPLAY_POLL_MS = 150L

        // Tree-walk budgets (mirror the existing deep scans in ContentExtractor).
        private const val MAX_DEPTH = 150
        private const val NODE_BUDGET = 1500
        private const val MAX_TEXT_SNIPPETS = 40
        private const val MAX_TEXT_LEN = 240
        private const val MAX_COMBINED_LEN = 4000
        private const val MAX_PLAYER_NODES_LOGGED = 15

        // ACTION_* constants removed from AccessibilityNodeInfo in newer SDKs
        // (still valid framework values — used only to pretty-print the
        // actions bitmask in diagnostics).
        private const val ACTION_CONTEXT_CLICK = 0x00300000
        private const val ACTION_SCROLL_TO_POSITION = 0x00400000
        private const val ACTION_SHOW_ON_SCREEN = 0x00800000
        private const val ACTION_MOVE_WINDOW = 0x01000000
        private const val ACTION_DRAG_START = 0x02000000
        private const val ACTION_DRAG_DROP = 0x04000000
        private const val ACTION_DRAG_CANCEL = 0x08000000
        private const val ACTION_PRESS_AND_HOLD = 0x10000000
        private const val ACTION_SCROLL_IN_DIRECTION = 0x20000000

        // Visible text that is Chrome chrome, never page content.
        private val GENERIC_SKIP_TEXTS = setOf(
            "web view",
            "search or type url",
            "search google or type url",
            "search or type web address",
            "type a url",
            "loading"
        )

        // Browser/accessibility UI strings that must never be treated as the
        // Short's content title. Chrome's window title is "Chrome: YouTube"
        // (or similar) while a Short is still loading, and the tree exposes
        // these strings in every frame — matching against them causes false
        // blocks (and stale-tree false matches) during Short transitions.
        private val GENERIC_UI_TITLES = setOf(
            "youtube",
            "open the home page",
            "new tab",
            "customize and control google chrome",
            "connection is secure",
            "back",
            "previous video",
            "next video",
            "search youtube",
            "more actions",
            "share this video",
            "share",
            "subscribe",
            "see more videos using this sound",
            "go to channel",
            "view comments",
            "watch on youtube",
            "youtube home",
            "see 1 tab"
        )

        /**
         * True when [title] is plausible real YouTube Short content rather than
         * a generic Chrome/YouTube UI string or an empty fragment. The matcher
         * prefers such a title over raw tree text, because the accessibility
         * tree can still contain the PREVIOUS Short's text while Chrome is
         * swapping in the new one (observed: "Spider Mahn…" window title with
         * "Ishq de Fanniyar … Aesthetic status …" still in the tree → the
         * stale tree text caused a false "aesthetic" block).
         */
        private fun isValidYouTubeShortTitle(title: String?): Boolean {
            if (title.isNullOrBlank()) return false
            val t = title.trim()
            if (t.length < 3) return false
            val lower = t.lowercase(Locale.ROOT)
            if (lower in GENERIC_UI_TITLES) return false
            return true
        }

    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val contentExtractor = ContentExtractor()

    private var pollJob: Job? = null
    private var rescanJob: Job? = null
    private var enforcementJob: Job? = null

    private var foregroundPackage: String? = null

    // ── Chrome/YouTube visibility (decoupled from the foreground package) ──
    // The active window may briefly be System UI, the IME, or ClearView's own
    // activity/overlay while Chrome/YouTube content is still on screen. Block
    // enforcement only resets when Chrome is GENUINELY gone (no Chrome window
    // anywhere), never because of a transient foreground package.
    private var chromeYoutubeVisible = false

    // ── Detection state (current Short) ────────────────────────────
    private var onYouTube = false
    private var onShorts = false
    private var shortUrl: String? = null
    private var currentVideoId: String? = null
    private var lastVisibleText: String? = null
    private var lastMatchedTestKeyword: String? = null
    private var lastNotShortsUrl: String? = null

    // ── Per-video BLOCK state machine ──────────────────────────────
    private var blockedVideoId: String? = null
    private var blockState: YoutubeBlockState = YoutubeBlockState.NORMAL
    private var lastPauseAttemptAt = 0L
    private var pauseAttempts = 0
    private var lastReenforceLogAt = 0L
    private var playerNodesDiagnosed = false
    // Consecutive enforcement scans with no Play/Pause control while ENFORCING.
    private var noControlTicks = 0
    // Consecutive scans whose active window root is not Chrome.
    private var nonChromeRootTicks = 0

    // ── Pause-phase machine (BLOCKED_NEEDS_PAUSE) ─────────────────
    // The pause sequence is asynchronous: the player node/bounds can appear
    // after the Short is detected. Phases: WAIT_FOR_PLAYER → PLAYER_READY
    // (reveal) → WAIT_FOR_CONTROLS → PAUSE_REQUEST (ONE tap) → OBSERVE.
    private var pausePhase = PausePhase.WAIT_FOR_PLAYER
    private var pausePhaseSince = 0L
    // The authoritative visible playback instance. currentVisibleVideoId is the
    // video id of the Short currently on screen; visibleInstanceGeneration is
    // bumped ONLY when the visible video id genuinely transitions (A → B → A
    // gives A-gen1, B-gen2, A-gen3; A → A stays gen1). Nothing else — scans,
    // WINDOW_CONTENT_CHANGED, text changes, repeated matching, player bounds —
    // may start a new instance. The pause bookkeeping below is tied to this
    // generation, so the same visible Short is never re-paused or re-revealed.
    private var currentVisibleVideoId: String? = null
    private var visibleInstanceGeneration = 0L
    // Exactly ONE physical pause gesture per VISIBLE INSTANCE. Set only
    // immediately before the actual pause dispatch; cleared when the visible
    // instance changes. A → B → A therefore gets a fresh pause attempt for the
    // second A, while A → A → A never re-pauses.
    private var pauseGestureSentGeneration: Long? = null
    // Player bounds captured when the player node first became available —
    // reused for the tap so the gesture does not depend on a fresh tree.
    private var lastKnownPlayerBounds: Rect? = null
    // Bounded WAIT_FOR_PLAYER retry: while the pause phase waits for the
    // player node to appear, a dedicated retry job polls the tree every
    // PLAYER_WAIT_RETRY_MS for up to PLAYER_ACQUIRE_TIMEOUT_MS. It is
    // generation/video guarded — every retry verifies the same visible
    // instance is still current, and it is cancelled by resetPausePhase() on
    // any instance change / release / reset. Repeated scans of the same video
    // merely advance this one pending wait; they never restart the sequence.
    private var playerWaitJob: Job? = null
    private var playerWaitVideoId: String? = null
    private var playerWaitGeneration = 0L
    private var playerWaitStartedAt = 0L
    private var playerWaitAttempts = 0


    // ── Fallback accessibility overlay (only when state is unknown) ─
    private var overlayView: View? = null

    /**
     * Visual-only: whether the player overlay currently shows the translucent
     * black tint. Kept SEPARATE from the block states so existing state
     * transitions are untouched — it is set when the overlay is enabled in a
     * protected state (confirmed-paused or protected-unknown) and cleared
     * whenever the overlay goes away (new Short / release / reset / swipe
     * replay). Never true during classification, waiting, or on allowed
     * videos, because the overlay itself only exists in protected states.
     */
    private var protectedPausedOverlayVisible = false
    // True while a replayed swipe is being dispatched into Chrome — the
    // enforcement loop must not re-add the overlay mid-gesture (the injected
    // gesture would land on the overlay and loop forever). Held for the WHOLE
    // replay: from swipe detection until either the video id changes (the
    // swipe navigated) or the bounded settling window expires.
    private var swipeReplayInFlight = false
    // The single finalization job for the current swipe replay. Only one may
    // exist; a new swipe cancels the previous one (idempotent finalization).
    private var replayFinalizeJob: Job? = null

    private var lastScanAt = 0L
    private var lastEventLogAt = 0L
    private var lastTreeDumpAt = 0L

    // ── Service hooks ──────────────────────────────────────────────

    /**
     * Called from the service whenever the foreground package changes.
     *
     * Transient windows (ClearView's own package/overlay, System UI, IME,
     * keyguard, launcher) NEVER reset the block state — Chrome/YouTube may
     * still be visible underneath. Only a genuine Chrome exit (no Chrome
     * window anywhere) resets.
     */
    fun onForegroundPackageChanged(newPackage: String) {
        val old = foregroundPackage
        foregroundPackage = newPackage
        Log.i(TAG, "PACKAGE_CHANGED from=${old ?: "none"} to=$newPackage")
        if (newPackage == CHROME_PACKAGE) {
            Log.i(TAG, "CHROME_FOREGROUND")
            chromeYoutubeVisible = true
            nonChromeRootTicks = 0
            if (old != CHROME_PACKAGE) {
                // Returning to Chrome — rescan and reapply blocking immediately
                // (never assume the previous Short is safe).
                startPolling()
                startEnforcementLoop()
                scan("foreground", force = true)
            }
            return
        }
        if (isTransientWindowPackage(newPackage)) {
            Log.i(TAG, "YT_BLOCK_TEMP_SYSTEM_WINDOW_IGNORED package=$newPackage")
            if (blockState != YoutubeBlockState.NORMAL) {
                Log.i(
                    TAG,
                    "YT_BLOCK_CHROME_CONTENT_STILL_ACTIVE videoId=${blockedVideoId ?: currentVideoId ?: "null"}"
                )
            }
            return
        }
        // Genuinely left Chrome.
        Log.i(TAG, "CHROME_LEFT_FOREGROUND pkg=$newPackage")
        if (chromeWindowStillPresent()) {
            Log.i(
                TAG,
                "YT_BLOCK_CHROME_CONTENT_STILL_ACTIVE videoId=${blockedVideoId ?: currentVideoId ?: "null"}"
            )
            return
        }
        stopPolling()
        stopEnforcementLoop()
        resetState("chrome left foreground")
    }

    /**
     * Called from the service for every accessibility event. Cheap: no tree
     * work here. The YT_TEST_ENTER / YT_TEST_ENABLED lines are logged BEFORE
     * the early-return gates so a silent no-op is never possible. A
     * VIEW_CLICKED event while a Short is blocked triggers an immediate
     * enforcement scan (the user may have tapped the player to resume).
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        val now = now()
        val isWindowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (isWindowEvent || now - lastEventLogAt >= EVENT_LOG_MIN_INTERVAL_MS) {
            lastEventLogAt = now
            Log.i(TAG, "YT_TEST_ENTER package=$packageName type=${typeName(event.eventType)}")
            Log.i(TAG, "YT_TEST_ENABLED ${repository.youTubeChromeTest}")
        }

        // Self-heal foreground tracking: if Chrome events are arriving, Chrome
        // content is active — adopt it instead of silently dropping events.
        if (foregroundPackage != CHROME_PACKAGE) {
            Log.i(TAG, "YT_TEST_FOREGROUND_ADOPTED previous=${foregroundPackage ?: "null"}")
            foregroundPackage = CHROME_PACKAGE
            chromeYoutubeVisible = true
            startPolling()
            startEnforcementLoop()
        }

        if (!repository.youTubeChromeTest) return
        maybeLogServiceEvent(event.eventType)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                Log.i(TAG, "WINDOW_EVENT type=${typeName(event.eventType)}")
                scan("window", force = true)
                scheduleSettledScan()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (blockState != YoutubeBlockState.NORMAL) {
                    maybeLogPlayerInteraction(event)
                    enforceBlockedVideo()
                }
            }
            else -> scan("event", force = false)
        }
    }

    /** Called from the service on destroy / interrupt. */
    fun stop() {
        scope.cancel()
        pollJob = null
        rescanJob = null
        enforcementJob = null
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        playerWaitJob?.cancel()
        playerWaitJob = null
        swipeReplayInFlight = false
        removePlayerOverlay("service stopped")
        Log.i(TAG, "STOPPED")
    }

    // ── Detection scan (unchanged pipeline) ────────────────────────

    private fun scan(reason: String, force: Boolean) {
        if (!chromeYoutubeVisible) return
        val enabled = repository.youTubeChromeTest
        if (!force && now() - lastScanAt < EVENT_SCAN_MIN_INTERVAL_MS && enabled) return
        lastScanAt = now()
        Log.i(TAG, "YT_TEST_ENABLED $enabled")
        if (!enabled) return

        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "ERROR rootInActiveWindow failed: ${e.message}")
            null
        } ?: return

        try {
            doScan(root, reason)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR scan failed: ${e.message}", e)
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    private fun doScan(root: AccessibilityNodeInfo, reason: String) {
        // Guard: if the active window is NOT Chrome (System UI, IME, ClearView's
        // own activity/overlay), this is a transient window — keep the YouTube
        // detection and block state untouched.
        val rootPkg = try { root.packageName?.toString() } catch (e: Exception) { null }
        if (rootPkg != null && rootPkg != CHROME_PACKAGE) {
            Log.i(TAG, "YT_BLOCK_TEMP_SYSTEM_WINDOW_IGNORED package=$rootPkg — YouTube state kept")
            return
        }
        nonChromeRootTicks = 0

        val activeWindow = try { service.windows?.firstOrNull { it.isActive } } catch (e: Exception) { null }
        val windowTitle = activeWindow?.title?.toString()
        val windowId = activeWindow?.id
        val rootClass = try { root.className?.toString() } catch (e: Exception) { null }

        val snapshot = contentExtractor.extract(CHROME_PACKAGE, root, null, windowTitle)
        val url = snapshot.url

        Log.i(
            TAG,
            "SCAN reason=$reason rootPkg=${rootPkg ?: "null"} rootCls=${rootClass ?: "null"} " +
                "winId=${windowId ?: "null"} title=${windowTitle ?: "null"} url=${url ?: "null"}"
        )

        // ── YouTube detection ────────────────────────────────────
        val urlIsYouTube = ContentExtractor.isYouTubeDomain(url)
        val titleIsYouTube = !urlIsYouTube &&
            (windowTitle?.contains(" - YouTube", ignoreCase = true) == true)
        val isYouTubeNow = urlIsYouTube || titleIsYouTube

        if (isYouTubeNow && !onYouTube) {
            Log.i(
                TAG,
                "YOUTUBE_DETECTED url=${url ?: "null"} source=${if (urlIsYouTube) "url-bar" else "window-title"}"
            )
        }
        onYouTube = isYouTubeNow

        if (!isYouTubeNow) {
            if (onShorts || shortUrl != null) {
                Log.i(TAG, "SHORT_LEFT url=${url ?: "null"}")
            }
            onShorts = false
            shortUrl = null
            lastVisibleText = null
            lastMatchedTestKeyword = null
            lastNotShortsUrl = null
            if (blockState != YoutubeBlockState.NORMAL) {
                // Left YouTube while a Short was blocked — release enforcement.
                releaseBlock(currentVideoId, "left youtube")
            }
            currentVideoId = null
            return
        }

        // ── Visible-text extraction (YouTube pages only) ─────────
        val texts = extractVisibleTexts(root)
        val combined = texts.joinToString(" ").take(MAX_COMBINED_LEN)
        val textChanged = combined != lastVisibleText
        lastVisibleText = combined
        if (texts.isNotEmpty()) {
            Log.i(TAG, "ACCESSIBILITY_TEXT count=${texts.size} changed=$textChanged")
            if (textChanged) {
                for ((i, t) in texts.take(MAX_TEXT_SNIPPETS).withIndex()) {
                    Log.i(TAG, "TEXT#$i \"${t.take(MAX_TEXT_LEN)}\"")
                }
            }
        } else {
            Log.i(TAG, "ACCESSIBILITY_TEXT count=0 — WebView exposes no visible text")
        }

        // ── Shorts detection ─────────────────────────────────────
        val urlIsShorts = urlIsYouTube && url?.contains("/shorts/") == true
        val textIsShorts = !urlIsShorts && combined.lowercase(Locale.ROOT).contains("#shorts")
        val isShortsNow = urlIsShorts || textIsShorts

        Log.i(TAG, "YT_TEST_URL ${url ?: "null"}")
        Log.i(TAG, "YT_TEST_IS_SHORT $isShortsNow")

        if (isShortsNow && !onShorts) {
            Log.i(
                TAG,
                "SHORT_DETECTED url=${url ?: "null"} signal=${if (urlIsShorts) "url:/shorts/" else "text:#shorts"}"
            )
            maybeDumpTree(root, "SHORT_DETECTED")
        } else if (!isShortsNow && onShorts) {
            Log.i(TAG, "SHORT_LEFT url=${url ?: "null"}")
        }
        if (isYouTubeNow && !isShortsNow && url != lastNotShortsUrl) {
            lastNotShortsUrl = url
            Log.i(TAG, "SHORT_NOT_DETECTED url=${url ?: "null"} — YouTube page but not a Short")
        }
        onShorts = isShortsNow
        shortUrl = url?.takeIf { urlIsShorts }

        if (!isShortsNow) {
            // Still on YouTube but no longer on a Short (e.g. regular video or
            // home feed) — the blocked-player enforcement must not follow the
            // user onto normal YouTube content.
            if (blockState != YoutubeBlockState.NORMAL) {
                releaseBlock(currentVideoId, "left shorts")
            }
            currentVideoId = null
            return
        }

        // ── Video id + per-video transition ──────────────────────
        val videoId = extractVideoId(url)
        val stateId = videoId ?: "text#${combined.hashCode()}"
        Log.i(TAG, "YT_TEST_SHORT_DETECTED videoId=${videoId ?: "unknown"}")
        Log.i(TAG, "YT_TEST_VIDEO_ID ${videoId ?: "unknown"}")

        if (stateId != currentVideoId) {
            Log.i(TAG, "YT_TEST_NEW_SHORT videoId=${videoId ?: "unknown"} (stateId=$stateId)")
            handleVideoChanged(stateId, videoId)
            currentVideoId = stateId
        }

        // ── Title (existing Chrome/YouTube extraction) ───────────
        val title = rawTitle(snapshot.title) ?: texts.firstOrNull()
        Log.i(TAG, "YT_TEST_TITLE ${title ?: "null"}")

        // ── Test-keyword matching (test list ONLY) ───────────────
        val testKeywords = testKeywordRepository.getKeywords()
        Log.i(TAG, "YT_TEST_KEYWORDS_LOADED count=${testKeywords.size}")
        Log.i(TAG, "YT_TEST_KEYWORDS $testKeywords")

        Log.i(TAG, "YT_TEST_MATCH_INPUT videoId=${videoId ?: "unknown"}")
        Log.i(TAG, "YT_TEST_MATCH_INPUT title=\"${title ?: "null"}\"")

        // Match against the FRESH window title whenever it is genuine Short
        // content. The raw tree text (combined) can lag behind the visible
        // Short during transitions — it may still contain the previous Short's
        // title, which produced a false block for a clean Short. Only fall
        // back to tree text when the title is generic UI text or unavailable.
        val matchInput = if (isValidYouTubeShortTitle(title)) {
            title!!
        } else if (combined.isNotBlank()) {
            combined
        } else {
            title.orEmpty()
        }
        Log.i(TAG, "YT_TEST_MATCH_SOURCE ${if (isValidYouTubeShortTitle(title)) "title" else "tree-text"}")
        // Normalize both sides (NFKC + homoglyphs + leetspeak + separator
        // collapsing — identity for plain text, so this changes nothing for
        // ordinary keywords) so disguised spellings of a test keyword are
        // caught generically instead of by manual list entries.
        val normalizedMatchInput = KeywordMatcher.normalizeForMatching(matchInput)
        val matched = testKeywords.firstOrNull { kw ->
            normalizedMatchInput.contains(KeywordMatcher.normalizeForMatching(kw))
        }

        if (matched != null) {
            lastMatchedTestKeyword = matched
            Log.w(TAG, "YT_TEST_KEYWORD_MATCH keyword=$matched")
            Log.w(TAG, "YT_TEST_MATCHED_KEYWORD $matched")
            if (blockState == YoutubeBlockState.NORMAL) {
                // Not blocked yet for the current visible instance → enter the
                // pause flow (BLOCKED_NEEDS_PAUSE). handleVideoChanged already
                // reset the per-instance pause bookkeeping when the visible
                // video id transitioned, so this runs at most once per
                // instance: repeated scans of the SAME Short can never restart
                // the pause sequence.
                blockVideo(stateId, videoId, matched)
            } else if (blockedVideoId == stateId) {
                // Same visible instance, already blocked — the enforcement loop
                // keeps monitoring/pausing as needed. NEVER resets the pause
                // phase, never re-reveals controls, never re-sends the tap.
                Log.i(TAG, "YT_BLOCK_REENFORCE videoId=${videoId ?: "unknown"} (still matched)")
            }
        } else {
            lastMatchedTestKeyword = null
            Log.i(TAG, "YT_TEST_KEYWORD_MATCH none")
            if (blockState != YoutubeBlockState.NORMAL && blockedVideoId == stateId) {
                // The keyword no longer matches this video → release enforcement.
                releaseBlock(videoId, "keyword no longer matches")
            }
        }
    }

    // ── Video transitions ─────────────────────────────────────────

    /**
     * The current visible Short changed. Only a REAL video-id transition may
     * start a new visible instance — this is the ONLY place the generation is
     * bumped and the pause bookkeeping is reset. Repeated scans, accessibility
     * text changes, or repeated keyword matches of the same Short never reach
     * here (doScan guards on stateId != currentVideoId, and the stateId is
     * derived from the video id).
     */
    private fun handleVideoChanged(newStateId: String, videoId: String?) {
        val oldVideoId = currentVisibleVideoId
        // Authoritative visible identity: prefer the extracted video id; only
        // fall back to the text-derived stateId when the URL has no /shorts/
        // (text changes of the SAME Short then keep the same stateId, so this
        // still does not fire on every scan).
        val newVisibleId = videoId ?: newStateId
        if (newVisibleId != oldVideoId) {
            currentVisibleVideoId = newVisibleId
            visibleInstanceGeneration++
            Log.i(
                TAG,
                "YT_BLOCK_VISIBLE_INSTANCE_CHANGED old=${oldVideoId ?: "null"} new=$newVisibleId " +
                    "generation=$visibleInstanceGeneration"
            )
            removePlayerOverlay("video changed")
            blockedVideoId = newStateId
            transitionBlockState(YoutubeBlockState.NORMAL, videoId)
            pauseAttempts = 0
            lastPauseAttemptAt = 0L
            lastReenforceLogAt = 0L
            playerNodesDiagnosed = false
            noControlTicks = 0
            nonChromeRootTicks = 0
            resetPausePhase()
        } else {
            // Same visible video id re-detected (e.g. transient text-only
            // stateId fluctuation) — NOT a new instance. Keep all state.
            Log.i(TAG, "YT_BLOCK_SAME_VISIBLE_INSTANCE videoId=${videoId ?: "unknown"} generation=$visibleInstanceGeneration")
        }
    }

    /** Resets the pause-phase machine (new visible instance / release / reset). */
    private fun resetPausePhase() {
        playerWaitJob?.cancel()
        playerWaitJob = null
        playerWaitVideoId = null
        playerWaitGeneration = 0L
        playerWaitStartedAt = 0L
        playerWaitAttempts = 0
        pausePhase = PausePhase.WAIT_FOR_PLAYER
        // Start the phase clock NOW — never 0. The WAIT_FOR_PLAYER bounded
        // window is measured from when the pause phase actually begins; a 0
        // here makes the first missing-bounds check look like a long-elapsed
        // timeout and kills the pause sequence before the player exists.
        pausePhaseSince = now()
        pauseGestureSentGeneration = null
        lastKnownPlayerBounds = null
    }

    /** Marks the current video as blocked and starts the safe-pause flow. */
    private fun blockVideo(stateId: String, videoId: String?, matched: String) {
        blockedVideoId = stateId
        pauseAttempts = 0
        lastPauseAttemptAt = 0L
        noControlTicks = 0
        resetPausePhase()
        Log.w(TAG, "YT_BLOCK_DETECTED videoId=${videoId ?: "unknown"} keyword=$matched")
        transitionBlockState(YoutubeBlockState.BLOCKED_NEEDS_PAUSE, videoId)
        enforceBlockedVideo()
    }

    /** Releases enforcement for the current video (never resets foreground tracking). */
    private fun releaseBlock(videoId: String?, reason: String) {
        if (blockState == YoutubeBlockState.NORMAL) return
        Log.i(TAG, "YT_BLOCK_RELEASED videoId=${videoId ?: "unknown"} — $reason")
        removePlayerOverlay("$reason")
        transitionBlockState(YoutubeBlockState.NORMAL, videoId)
        pauseAttempts = 0
        noControlTicks = 0
        resetPausePhase()
    }

    // ── Enforcement loop ──────────────────────────────────────────

    /** The single YouTube enforcement loop; started with Chrome foreground. */
    private fun startEnforcementLoop() {
        if (enforcementJob?.isActive == true) return
        Log.d(TAG, "YT_ENFORCEMENT_LOOP_STARTED interval=${ENFORCEMENT_INTERVAL_MS}ms")
        enforcementJob = scope.launch {
            while (isActive) {
                delay(ENFORCEMENT_INTERVAL_MS)
                enforcementTick()
            }
        }
    }

    private fun stopEnforcementLoop() {
        enforcementJob?.cancel()
        enforcementJob = null
    }

    private fun enforcementTick() {
        if (!chromeYoutubeVisible) return
        if (!repository.youTubeChromeTest) return
        if (blockState == YoutubeBlockState.NORMAL) return
        if (currentVideoId == null) return
        enforceBlockedVideo()
    }

    /**
     * Idempotent safe-pause enforcement for the currently blocked video.
     *
     *   Pause control exposed -> click it (video is playing), rescan later
     *   Play  control exposed -> already paused, do nothing
     *   no control exposed    -> reveal controls once, then ONE physical
     *                            center tap as the pause request, rescan,
     *                            bounded retries, then gesture-aware overlay
     *
     * NEVER taps the player unconditionally on every poll: an unconditional
     * tap toggles PLAYING -> PAUSED -> PLAYING.
     */
    private fun enforceBlockedVideo() {
        if (blockState == YoutubeBlockState.NORMAL) return
        if (!chromeYoutubeVisible) return
        if (!repository.youTubeChromeTest) return
        // Hard replay lock: while a swipe replay is in flight (overlay removed,
        // gesture being handed back to Chrome, Shorts navigation settling), NO
        // enforcement path may pause or re-protect the player. Every
        // enforcement caller — polling tick, VIEW_CLICKED event, blockVideo,
        // window-settled re-scan — funnels through this method, so this single
        // gate covers all of them. enablePlayerOverlay additionally guards
        // itself for any direct caller.
        if (swipeReplayInFlight) {
            Log.i(TAG, "YT_BLOCK_ENFORCE_SKIP videoId=${blockedVideoId ?: "null"} reason=swipe_replay_in_flight")
            return
        }
        val videoId = currentVideoId ?: return
        val now = now()
        if (now - lastPauseAttemptAt < PAUSE_RETRY_DELAY_MS) return

        val root = try { service.rootInActiveWindow } catch (e: Exception) { null }
        if (root == null) return
        try {
            // Transient active window (System UI / IME / our own overlay or
            // activity): touch nothing, keep enforcement armed. If Chrome is
            // genuinely gone, reset.
            val rootPkg = try { root.packageName?.toString() } catch (e: Exception) { null }
            if (rootPkg != null && rootPkg != CHROME_PACKAGE) {
                nonChromeRootTicks++
                Log.i(TAG, "YT_BLOCK_TEMP_SYSTEM_WINDOW_IGNORED package=$rootPkg — enforcement kept")
                if (nonChromeRootTicks >= NON_CHROME_RESET_TICKS && !chromeWindowStillPresent()) {
                    Log.i(TAG, "YT_BLOCK_RESET reason=chrome_window_gone active=$rootPkg")
                    stopPolling()
                    stopEnforcementLoop()
                    resetState("chrome window gone")
                }
                return
            }
            nonChromeRootTicks = 0

            val button = findMediaButton(root)
            when (button.label) {
                "pause" -> {
                    // Visible evidence the video is PLAYING → pause it.
                    Log.i(TAG, "YT_BLOCK_PAUSE_CONTROL_FOUND videoId=$videoId")
                    if (blockState == YoutubeBlockState.BLOCKED_NEEDS_PAUSE) {
                        Log.i(TAG, "YT_BLOCK_PAUSE_CONTROLS_FOUND videoId=$videoId label=pause")
                        Log.i(TAG, "YT_BLOCK_PAUSE_OBSERVE_CHECK videoId=$videoId state=PLAYING")
                    }
                    Log.i(TAG, "YT_BLOCK_PAUSE_CLICK videoId=$videoId")
                    val ok = try {
                        button.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                    } catch (e: Exception) {
                        Log.e(TAG, "ERROR pause control click: ${e.message}")
                        false
                    }
                    recycleIfNotRoot(button.node, root)
                    lastPauseAttemptAt = now
                    if (ok) {
                        if (blockState != YoutubeBlockState.BLOCKED_ENFORCING) {
                            transitionBlockState(YoutubeBlockState.BLOCKED_ENFORCING, videoId)
                            pauseAttempts = 0
                        }
                        noControlTicks = 0
                        // Confirmed paused → raise the black protection overlay
                        // (idempotent; already up in UNKNOWN). It must stay up
                        // in the protected state so taps can never reveal the
                        // controls or resume the video.
                        enablePlayerOverlay(root)
                    } else {
                        pauseAttempts++
                        noControlTicks = 0
                        if (blockState == YoutubeBlockState.BLOCKED_NEEDS_PAUSE &&
                            pauseAttempts >= MAX_PAUSE_ATTEMPTS
                        ) {
                            Log.i(TAG, "YT_BLOCK_PLAYBACK_UNKNOWN videoId=$videoId")
                            transitionBlockState(YoutubeBlockState.BLOCKED_UNKNOWN_STATE, videoId)
                            enablePlayerOverlay(root)
                        }
                    }
                }
                "play" -> {
                    // Visible evidence the video is PAUSED → leave it untouched.
                    Log.i(TAG, "YT_BLOCK_PLAY_CONTROL_FOUND videoId=$videoId")
                    if (blockState == YoutubeBlockState.BLOCKED_NEEDS_PAUSE) {
                        Log.i(TAG, "YT_BLOCK_PAUSE_CONTROLS_FOUND videoId=$videoId label=play")
                        Log.i(TAG, "YT_BLOCK_PAUSE_OBSERVE_CHECK videoId=$videoId state=PAUSED")
                        Log.i(TAG, "YT_BLOCK_PAUSE_CONFIRMED videoId=$videoId")
                    }
                    Log.i(TAG, "YT_BLOCK_ALREADY_PAUSED videoId=$videoId")
                    recycleIfNotRoot(button.node, root)
                    if (blockState != YoutubeBlockState.BLOCKED_ENFORCING) {
                        transitionBlockState(YoutubeBlockState.BLOCKED_ENFORCING, videoId)
                        pauseAttempts = 0
                    }
                    noControlTicks = 0
                    // Already paused → raise the black protection overlay.
                    enablePlayerOverlay(root)
                }
                null -> {
                    // No Play/Pause control exposed.
                    when (blockState) {
                        YoutubeBlockState.BLOCKED_NEEDS_PAUSE -> {
                            runPausePhase(videoId, root)
                            lastPauseAttemptAt = now
                        }
                        YoutubeBlockState.BLOCKED_UNKNOWN_STATE -> {
                            // Overlay active; keep re-checking controls each tick
                            // (if a Pause control appears we can still click it).
                            if (overlayView == null) enablePlayerOverlay(root)
                            else if (now - lastReenforceLogAt >= REENFORCE_LOG_INTERVAL_MS) {
                                lastReenforceLogAt = now
                                Log.i(TAG, "YT_BLOCK_REENFORCE videoId=$videoId — protected, state unknown")
                            }
                        }
                        YoutubeBlockState.BLOCKED_ENFORCING -> {
                            // Was paused; controls are now hidden so resume can no
                            // longer be observed. Escalate to protection rather
                            // than letting a hidden resume slip through.
                            noControlTicks++
                            if (noControlTicks >= NO_CONTROL_ESCALATE_TICKS) {
                                Log.i(
                                    TAG,
                                    "YT_BLOCK_PLAYBACK_UNKNOWN videoId=$videoId — controls hidden, escalating to protection"
                                )
                                transitionBlockState(YoutubeBlockState.BLOCKED_UNKNOWN_STATE, videoId)
                                enablePlayerOverlay(root)
                            } else if (now - lastReenforceLogAt >= REENFORCE_LOG_INTERVAL_MS) {
                                lastReenforceLogAt = now
                                Log.i(TAG, "YT_BLOCK_REENFORCE videoId=$videoId — no controls exposed, state held")
                            }
                        }
                        YoutubeBlockState.NORMAL -> {}
                    }
                }
            }
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * Asynchronous pause-phase machine for BLOCKED_NEEDS_PAUSE. Handles the
     * case where the player node/bounds appear AFTER the Short is detected:
     *
     *   WAIT_FOR_PLAYER   → poll for valid player bounds (bounded). A missing
     *                       player is NOT "playback unknown" — keep waiting.
     *   PLAYER_READY      → one controlled reveal click.
     *   WAIT_FOR_CONTROLS → bounded wait for the tree to update after reveal.
     *   PAUSE_REQUEST     → exactly ONE physical center tap (per Short).
     *   OBSERVE           → bounded observation of Play/Pause controls; only
     *                       after this may the state become UNKNOWN + protect.
     *
     * A Pause/Play control visible at any point is handled by the caller
     * (findMediaButton) before this machine runs, so no phase needs to click
     * a control — and no overlay is ever enabled while this machine runs.
     */
    private fun runPausePhase(videoId: String, root: AccessibilityNodeInfo) {
        val now = now()
        when (pausePhase) {
            PausePhase.WAIT_FOR_PLAYER -> {
                // The player node may not exist yet (Chrome renders the Short
                // page asynchronously). Start (or keep advancing) the single
                // pending bounded wait — repeated scans of the SAME video must
                // not restart or abandon it. All retries run inside
                // [playerWaitJob]; this branch only ensures that job exists.
                if (playerWaitJob?.isActive != true) {
                    startPlayerWait(videoId, root, now)
                }
                // No immediate timeout here: the wait job owns the bounded
                // window and the transition out of WAIT_FOR_PLAYER.
            }
            PausePhase.PLAYER_READY -> {
                revealPlayerControls(videoId, root)
                pausePhase = PausePhase.WAIT_FOR_CONTROLS
                pausePhaseSince = now
            }
            PausePhase.WAIT_FOR_CONTROLS -> {
                // No control exposed yet; give the tree time to update after
                // the reveal before dispatching the physical tap.
                Log.i(TAG, "YT_BLOCK_PAUSE_CONTROLS_WAIT videoId=$videoId")
                if (now - pausePhaseSince >= CONTROLS_WAIT_MS) {
                    pausePhase = PausePhase.PAUSE_REQUEST
                    pausePhaseSince = now
                }
            }
            PausePhase.PAUSE_REQUEST -> {
                // Single-shot guard tied to the CURRENT VISIBLE INSTANCE
                // generation. Cleared only by resetPausePhase() on a genuine
                // video-id transition, so within one instance the physical
                // pause tap can never fire twice — even across many
                // WINDOW_CONTENT_CHANGED events and scans.
                if (pauseGestureSentGeneration != visibleInstanceGeneration) {
                    requestPhysicalPause(videoId, root, lastKnownPlayerBounds)
                    pauseGestureSentGeneration = visibleInstanceGeneration
                    Log.i(TAG, "YT_BLOCK_PAUSE_OBSERVE_START videoId=$videoId")
                    pausePhase = PausePhase.OBSERVE
                    pausePhaseSince = now
                } else {
                    // Defensive: already sent for this visible instance —
                    // observe instead, never re-toggle.
                    Log.i(TAG, "YT_BLOCK_PAUSE_SUPPRESSED videoId=$videoId reason=already_paused_current_instance")
                    pausePhase = PausePhase.OBSERVE
                    pausePhaseSince = now
                }
            }
            PausePhase.OBSERVE -> {
                Log.i(TAG, "YT_BLOCK_PAUSE_OBSERVE_CHECK videoId=$videoId state=UNKNOWN")
                if (now - pausePhaseSince >= PAUSE_OBSERVE_MS) {
                    Log.i(
                        TAG,
                        "YT_BLOCK_PAUSE_OBSERVE_TIMEOUT videoId=$videoId — no Play/Pause controls exposed"
                    )
                    playbackUnknown(videoId, root)
                }
            }
        }
    }

    /** Playback state genuinely cannot be observed → protect the player. */
    private fun playbackUnknown(videoId: String, root: AccessibilityNodeInfo) {
        Log.i(TAG, "YT_BLOCK_PLAYBACK_UNKNOWN videoId=$videoId")
        transitionBlockState(YoutubeBlockState.BLOCKED_UNKNOWN_STATE, videoId)
        enablePlayerOverlay(root)
    }

    /**
     * Starts the single bounded WAIT_FOR_PLAYER retry for the current visible
     * instance. Polls the tree for valid player bounds every
     * PLAYER_WAIT_RETRY_MS for up to PLAYER_ACQUIRE_TIMEOUT_MS. Every retry is
     * generation/video guarded — if the visible instance changed (swipe,
     * navigation) or the block state moved on, the job cancels itself and
     * does nothing. Only after the bounded window is genuinely exhausted does
     * it escalate to playbackUnknown(). One job per visible instance; a new
     * one is never started while the previous is still pending, and
     * resetPausePhase() cancels it on any instance change.
     */
    private fun startPlayerWait(videoId: String, root: AccessibilityNodeInfo, phaseStartedAt: Long) {
        playerWaitVideoId = videoId
        playerWaitGeneration = visibleInstanceGeneration
        playerWaitStartedAt = phaseStartedAt
        playerWaitAttempts = 0
        playerWaitJob = scope.launch {
            var jobStart = now()
            while (isActive) {
                // Generation/video guard: the visible instance must still be
                // the one this wait started for, and we must still be in the
                // pause phase. Otherwise the wait is stale — cancel silently.
                if (playerWaitVideoId != videoId ||
                    playerWaitGeneration != visibleInstanceGeneration ||
                    blockState != YoutubeBlockState.BLOCKED_NEEDS_PAUSE
                ) {
                    playerWaitJob = null
                    return@launch
                }

                val currentRoot = try { service.rootInActiveWindow } catch (e: Exception) { null }
                val player = currentRoot?.let { findVideoPlayerNode(it) }
                val bounds = player?.let { boundsOf(it.node) }
                try { player?.node?.recycle() } catch (e: Exception) {}
                try { currentRoot?.recycle() } catch (e: Exception) {}

                val elapsed = now() - playerWaitStartedAt
                playerWaitAttempts++
                Log.i(
                    TAG,
                    "YT_BLOCK_PAUSE_WAITING_FOR_PLAYER videoId=$videoId " +
                        "attempt=$playerWaitAttempts elapsedMs=$elapsed boundsFound=${bounds != null} " +
                        "generation=$visibleInstanceGeneration"
                )

                if (bounds != null) {
                    lastKnownPlayerBounds = bounds
                    Log.i(TAG, "YT_BLOCK_PAUSE_PLAYER_BOUNDS_FOUND videoId=$videoId bounds=$bounds")
                    pausePhase = PausePhase.PLAYER_READY
                    pausePhaseSince = now()
                    playerWaitJob = null
                    return@launch
                }

                if (now() - jobStart >= PLAYER_ACQUIRE_TIMEOUT_MS) {
                    Log.i(TAG, "YT_BLOCK_PAUSE_PLAYER_BOUNDS_TIMEOUT videoId=$videoId")
                    val freshRoot = try { service.rootInActiveWindow } catch (e: Exception) { null }
                    if (freshRoot != null) {
                        try {
                            playbackUnknown(videoId, freshRoot)
                        } finally {
                            try { freshRoot.recycle() } catch (e: Exception) {}
                        }
                    }
                    playerWaitJob = null
                    return@launch
                }
                delay(PLAYER_WAIT_RETRY_MS)
            }
            playerWaitJob = null
        }
    }




    /** One controlled player click — reveals the controls, never a pause. */
    private fun revealPlayerControls(videoId: String, root: AccessibilityNodeInfo) {
        val player = findVideoPlayerNode(root)
        if (player != null) {
            Log.i(TAG, "YT_BLOCK_PLAYER_CLICK videoId=$videoId (reveal controls)")
            try {
                player.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                Log.e(TAG, "ERROR reveal click: ${e.message}")
            } finally {
                try { player.node.recycle() } catch (e: Exception) {}
            }
        } else {
            Log.i(TAG, "YT_BLOCK_PLAYER_CLICK videoId=$videoId skipped (no player node)")
        }
    }

    /**
     * One physical pause request: a short tap at the player center. Fired at
     * most once per VISIBLE INSTANCE of a Short (guarded by
     * [pauseGestureSentGeneration], reset whenever the visible instance
     * changes), right after the reveal click showed the on-screen controls,
     * so it acts as a pause rather than a blind toggle. dispatchGesture()
     * acceptance is logged — never treated as proof that playback paused; the
     * observation phase decides. Falls back to [fallbackBounds] captured when
     * the player first became available, so a tree that briefly hides the
     * player cannot abort the tap.
     */
    private fun requestPhysicalPause(videoId: String, root: AccessibilityNodeInfo, fallbackBounds: Rect?) {
        val player = findVideoPlayerNode(root)
        val bounds = player?.let { boundsOf(it.node) } ?: fallbackBounds
        try {
            if (bounds == null) {
                Log.i(TAG, "YT_BLOCK_PAUSE_REQUEST videoId=$videoId skipped — no player bounds")
                return
            }
            val x = (bounds.left + bounds.right) / 2
            val y = (bounds.top + bounds.bottom) / 2
            Log.i(TAG, "YT_BLOCK_PAUSE_REQUEST videoId=$videoId x=$x y=$y bounds=$bounds")
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_STROKE_MS))
                .build()
            val ok = try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.i(TAG, "YT_BLOCK_PAUSE_DISPATCH_ACCEPTED true (input accepted — not proof of pause)")
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.i(TAG, "YT_BLOCK_PAUSE_DISPATCH_ACCEPTED false")
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "ERROR pause gesture: ${e.message}")
                false
            }
            if (!ok) Log.i(TAG, "YT_BLOCK_PAUSE_DISPATCH_ACCEPTED false")
        } finally {
            try { player?.node?.recycle() } catch (e: Exception) {}
        }
    }



    private fun recycleIfNotRoot(node: AccessibilityNodeInfo?, root: AccessibilityNodeInfo) {
        if (node != null && node !== root) {
            try { node.recycle() } catch (e: Exception) {}
        }
    }

    /** Logs when a click event while blocked looks like a player interaction. */
    private fun maybeLogPlayerInteraction(event: AccessibilityEvent) {
        var playerLike = false
        val source = try { event.source } catch (e: Exception) { null }
        if (source != null) {
            try {
                val hay = listOf(
                    try { source.viewIdResourceName } catch (e: Exception) { null },
                    try { source.className?.toString() } catch (e: Exception) { null },
                    try { source.text?.toString() } catch (e: Exception) { null },
                    try { source.contentDescription?.toString() } catch (e: Exception) { null }
                ).joinToString(" ").lowercase(Locale.ROOT)
                playerLike = hay.contains("movie_player") ||
                    hay.contains("youtube video player") ||
                    hay.contains("shorts-video") ||
                    hay.contains("gesture-layer") ||
                    hay.contains("player")
            } finally {
                try { source.recycle() } catch (e: Exception) {}
            }
        }
        Log.i(TAG, "YT_BLOCK_PLAYER_INTERACTION videoId=${currentVideoId ?: "unknown"} playerLike=$playerLike")
    }

    // ── Fallback accessibility overlay (gesture-aware) ─────────────

    /**
     * Transparent overlay over ONLY the player bounds. Taps are consumed (the
     * blocked video can never be tapped back on). Vertical swipes are passed
     * through: the overlay reports the gesture, temporarily removes itself,
     * and replays the swipe into Chrome via dispatchGesture() so the user can
     * still swipe between Shorts. Horizontal drags (seeking) are consumed like
     * taps — they are player playback interaction.
     */
    private inner class GestureAwarePlayerOverlay(
        context: android.content.Context,
        tinted: Boolean
    ) : View(context) {
        private var downX = 0f
        private var downY = 0f
        private var touching = false
        private val tintPaint = if (tinted) {
            android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                alpha = BLACK_OVERLAY_ALPHA
            }
        } else {
            null
        }
        // Centered message paints (created once; sizes set per-frame in
        // onDraw so they scale with the player bounds).
        private val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        }
        private val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        private val dividerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = 90
            strokeWidth = 2f
        }

        // Visual only: a fully opaque black wash over the protected player,
        // with the "SWIPE / FEAR GOD / BLOCK BRAIN ROT" message centered on
        // it. The view itself has no background; this fills exactly the player
        // bounds. No buttons/icons — gesture handling is untouched by drawing.
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            tintPaint?.let {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it)
                drawProtectionMessage(canvas)
            }
        }

        /** Draws the centered "SWIPE — FEAR GOD — BLOCK BRAIN ROT" message. */
        private fun drawProtectionMessage(canvas: android.graphics.Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val unit = min(width, height)

            // Title: large with wide letter-spacing.
            titlePaint.textSize = unit * 0.095f
            titlePaint.letterSpacing = 0.20f
            val title = "SWIPE"
            val titleY = cy - unit * 0.115f
            canvas.drawText(title, cx, titleY, titlePaint)
            val titleWidth = titlePaint.measureText(title)

            // Hairline divider under the title.
            val dividerY = cy - unit * 0.035f
            canvas.drawLine(
                cx - titleWidth * 0.75f, dividerY,
                cx + titleWidth * 0.75f, dividerY,
                dividerPaint
            )

            // Middle line.
            bodyPaint.alpha = 220
            bodyPaint.textSize = unit * 0.052f
            bodyPaint.letterSpacing = 0.12f
            canvas.drawText("FEAR GOD", cx, cy + unit * 0.03f, bodyPaint)

            // Bottom line: quieter, more transparent.
            bodyPaint.alpha = 150
            bodyPaint.textSize = unit * 0.04f
            bodyPaint.letterSpacing = 0.08f
            canvas.drawText("BLOCK BRAIN ROT", cx, cy + unit * 0.10f, bodyPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!touching) return true
                    touching = false
                    val upX = event.rawX
                    val upY = event.rawY
                    val dx = upX - downX
                    val dy = upY - downY
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < SWIPE_THRESHOLD_PX) {
                        // TAP → consume, never reach the player.
                        onOverlayTapConsumed()
                    } else if (abs(dy) >= SWIPE_MIN_DY_PX && abs(dy) > abs(dx) * SWIPE_DIAGONAL_RATIO) {
                        // Vertical swipe (diagonal-tolerant) → pass to Chrome.
                        onOverlayVerticalSwipe(downX, downY, upX, upY)
                    } else {
                        // Horizontal drag (seek) on a blocked player → consume.
                        onOverlayTapConsumed()
                    }
                    return true
                }
            }
            return true
        }
    }

    /** A tap on the blocked player was consumed. */
    private fun onOverlayTapConsumed() {
        Log.i(TAG, "YT_BLOCK_TAP_CONSUMED videoId=${currentVideoId ?: "unknown"}")
    }

    /** A vertical swipe on the blocked player — replay it into Chrome. */
    private fun onOverlayVerticalSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        Log.i(TAG, "YT_BLOCK_VERTICAL_SWIPE start=($startX,$startY) end=($endX,$endY)")
        if (protectedPausedOverlayVisible) {
            Log.i(TAG, "YT_BLOCK_BLACK_OVERLAY_SWIPE_ALLOWED videoId=${currentVideoId ?: "unknown"}")
        }
        replayVerticalSwipe(startX, startY, endX, endY)
    }

    /**
     * Replay state machine for a vertical swipe on the blocked player.
     *
     * Ordering is strict:
     *   detect swipe → lock (swipeReplayInFlight = true) → remove overlay →
     *   wait ~80ms for the overlay surface to tear down → dispatch the ORIGINAL
     *   gesture into Chrome → keep enforcement disabled while Shorts
     *   navigation settles → either the video id changes (replay succeeded,
     *   let the normal detection path evaluate the new Short) or a bounded
     *   settling window expires with the same blocked Short (restore
     *   protection).
     *
     * dispatchGesture() returning true only means Android accepted the gesture
     * — the replay lock is therefore NOT released by the gesture callback; it
     * is released only by [finishSwipeReplay].
     */
    private fun replayVerticalSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val originalVideoId = blockedVideoId
        // Lock BEFORE touching the overlay, and cancel any stale finalizer so
        // only one replay state machine can be active.
        swipeReplayInFlight = true
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        Log.i(TAG, "YT_BLOCK_SWIPE_REPLAY_LOCKED videoId=${originalVideoId ?: "null"}")
        removePlayerOverlay("replaying swipe")
        scope.launch {
            delay(SWIPE_REPLAY_DELAY_MS)
            if (!isActive) return@launch
            Log.i(TAG, "YT_BLOCK_SWIPE_REPLAY_DELAY_COMPLETE videoId=${originalVideoId ?: "null"}")
            dispatchVerticalSwipe(startX, startY, endX, endY, originalVideoId)
        }
    }

    /** Dispatches the original swipe gesture into Chrome and starts finalization. */
    private fun dispatchVerticalSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        originalVideoId: String?
    ) {
        Log.i(TAG, "YT_BLOCK_VERTICAL_SWIPE_REPLAY videoId=${originalVideoId ?: "unknown"}")
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_REPLAY_DURATION_MS))
            .build()
        val ok = try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        // Gesture delivered — Chrome may still be navigating.
                        // The replay lock stays on; finalization observes the
                        // video id and decides when to restore protection.
                        Log.i(TAG, "YT_BLOCK_VERTICAL_SWIPE_REPLAY_RESULT=true")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "YT_BLOCK_VERTICAL_SWIPE_REPLAY_RESULT=false")
                        // The gesture never reached Chrome — restore protection
                        // right away (the swipe was consumed by the overlay).
                        scope.launch { finishSwipeReplay(restore = true) }
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "ERROR swipe replay: ${e.message}")
            false
        }
        if (!ok) {
            Log.i(TAG, "YT_BLOCK_VERTICAL_SWIPE_REPLAY_RESULT=false")
            finishSwipeReplay(restore = true)
        } else {
            // Observe Chrome/YouTube for the video-id change / settling timeout.
            startSwipeReplayFinalize(originalVideoId)
        }
    }

    /**
     * Watches for the Shorts navigation result of the replayed swipe. The
     * replay lock stays up for the whole window: the video id change is picked
     * up from the normal scan pipeline ([currentVideoId] is updated by
     * [doScan] as events/polls arrive); if no change occurs within the bounded
     * settling window, the same blocked Short is still current → restore
     * protection. Only ONE finalization job exists at a time; starting a new
     * swipe cancels the previous one.
     */
    private fun startSwipeReplayFinalize(originalVideoId: String?) {
        replayFinalizeJob?.cancel()
        replayFinalizeJob = scope.launch {
            val deadline = now() + SWIPE_REPLAY_SETTLE_MS
            while (isActive && now() < deadline) {
                delay(SWIPE_REPLAY_POLL_MS)
                val current = currentVideoId
                Log.i(
                    TAG,
                    "YT_BLOCK_SWIPE_REPLAY_CHECK originalVideoId=${originalVideoId ?: "null"} currentVideoId=${current ?: "null"}"
                )
                if (current != originalVideoId) {
                    Log.i(
                        TAG,
                        "YT_BLOCK_SWIPE_REPLAY_VIDEO_CHANGED old=${originalVideoId ?: "null"} new=${current ?: "null"}"
                    )
                    finishSwipeReplay(restore = false)
                    return@launch
                }
            }
            // Settling window expired and the same Short is still current.
            Log.i(TAG, "YT_BLOCK_SWIPE_REPLAY_SETTLE_TIMEOUT videoId=${originalVideoId ?: "null"}")
            finishSwipeReplay(restore = true)
        }
    }

    /**
     * Single, idempotent replay finalization. Clears the replay lock and, if
     * requested and still justified, restores the player overlay. Safe to be
     * called from the finalize job, the gesture callback, or a dispatch
     * failure — the first call wins, later calls are no-ops.
     */
    private fun finishSwipeReplay(restore: Boolean) {
        if (replayFinalizeJob == null && !swipeReplayInFlight) {
            // Already finalized.
            return
        }
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
        if (restore) {
            // The overlay belongs ONLY to the protected states
            // (BLOCKED_ENFORCING / BLOCKED_UNKNOWN_STATE) and only for the
            // SAME blocked Short. An allowed new Short, a NEEDS_PAUSE
            // sequence, or a released block must never be covered.
            if (chromeYoutubeVisible &&
                (blockState == YoutubeBlockState.BLOCKED_ENFORCING ||
                    blockState == YoutubeBlockState.BLOCKED_UNKNOWN_STATE) &&
                blockedVideoId == currentVideoId &&
                overlayView == null
            ) {
                Log.i(TAG, "YT_BLOCK_SWIPE_REPLAY_REPROTECTED videoId=${currentVideoId ?: "null"}")
                val root = try { service.rootInActiveWindow } catch (e: Exception) { null }
                if (root != null) {
                    try {
                        enablePlayerOverlay(root)
                    } finally {
                        try { root.recycle() } catch (e: Exception) {}
                    }
                }
            }
        }
        Log.i(TAG, "YT_BLOCK_SWIPE_REPLAY_FINISHED")
    }

    /**
     * Fallback protection when the player state cannot be observed: a
     * TYPE_ACCESSIBILITY_OVERLAY covering ONLY the player bounds. Taps are
     * consumed; vertical swipes are replayed to Chrome. The rest of
     * Chrome/YouTube stays usable.
     */
    private fun enablePlayerOverlay(root: AccessibilityNodeInfo) {
        if (overlayView != null) return
        // Never re-add mid-replay: the injected swipe would land on the overlay
        // and loop forever.
        if (swipeReplayInFlight) return
        val player = findVideoPlayerNode(root)
        val bounds = player?.let { boundsOf(it.node) }
        try {
            if (bounds == null) {
                Log.i(TAG, "YT_BLOCK_OVERLAY_SKIP videoId=${currentVideoId ?: "unknown"} — no player bounds")
                return
            }
            // The overlay exists only in protected states (confirmed-paused or
            // protected-unknown), so it always carries the translucent black
            // tint. Gesture handling is identical either way.
            val view = GestureAwarePlayerOverlay(service, tinted = true)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left
                y = bounds.top
                width = bounds.width()
                height = bounds.height()
            }
            val wm = service.getSystemService(WindowManager::class.java)
            wm.addView(view, params)
            overlayView = view
            protectedPausedOverlayVisible = true
            Log.i(TAG, "YT_BLOCK_OVERLAY_ENABLED videoId=${currentVideoId ?: "unknown"} bounds=$bounds")
            Log.i(TAG, "YT_BLOCK_PLAYER_PROTECTED videoId=${currentVideoId ?: "unknown"} bounds=$bounds")
            Log.i(TAG, "YT_BLOCK_BLACK_OVERLAY_ENABLED videoId=${currentVideoId ?: "unknown"}")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR overlay addView: ${e.message}")
        } finally {
            try { player?.node?.recycle() } catch (e: Exception) {}
        }
    }

    private fun removePlayerOverlay(reason: String) {
        val view = overlayView ?: return
        try {
            service.getSystemService(WindowManager::class.java).removeView(view)
            Log.i(TAG, "YT_BLOCK_OVERLAY_REMOVED reason=$reason")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR overlay removeView: ${e.message}")
        }
        overlayView = null
        if (protectedPausedOverlayVisible) {
            protectedPausedOverlayVisible = false
            Log.i(TAG, "YT_BLOCK_BLACK_OVERLAY_REMOVED videoId=${currentVideoId ?: "unknown"} reason=$reason")
        }
    }

    // ── Chrome/YouTube visibility helpers ──────────────────────────

    /**
     * Packages that can never mean "the user left Chrome": ClearView's own
     * activity/overlay window, System UI (notification shade), IMEs, keyguard
     * and launchers. Events from these must not clear the block state.
     */
    private fun isTransientWindowPackage(pkg: String): Boolean {
        if (pkg == service.packageName) return true
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.contains("systemui") ||
            lower.contains("inputmethod") ||
            lower.contains("keyguard") ||
            lower.contains("launcher") ||
            lower == "android"
    }

    /** True when any application window in the window list belongs to Chrome. */
    private fun chromeWindowStillPresent(): Boolean {
        val windows = try { service.windows } catch (e: Exception) { return true }
        if (windows == null) return true
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try { w.root } catch (e: Exception) { null }
            if (root != null) {
                try {
                    if (root.packageName?.toString() == CHROME_PACKAGE) return true
                } catch (e: Exception) {
                    // ignore — check the next window
                } finally {
                    try { root.recycle() } catch (e: Exception) {}
                }
            }
        }
        return false
    }

    // ── State transitions ─────────────────────────────────────────

    private fun transitionBlockState(to: YoutubeBlockState, videoId: String?) {
        val from = blockState
        if (from == to) return
        blockState = to
        Log.i(TAG, "YT_BLOCK_STATE $from -> $to videoId=${videoId ?: "unknown"}")
    }

    // ── Polling / scheduling ──────────────────────────────────────

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        Log.d(TAG, "POLL_STARTED interval=${POLL_INTERVAL_MS}ms")
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                scan("poll", force = false)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        rescanJob?.cancel()
        rescanJob = null
    }

    /**
     * Chrome may not have finished rendering when the window event fires, so a
     * one-shot delayed re-scan sees the settled tree (same pattern as the main
     * service's CHROME_DELAYED_SCAN).
     */
    private fun scheduleSettledScan() {
        if (rescanJob?.isActive == true) return
        rescanJob = scope.launch {
            delay(WINDOW_RESCAN_DELAY_MS)
            rescanJob = null
            if (!isActive) return@launch
            scan("window-settled", force = true)
        }
    }

    /** Full reset — only for a genuine Chrome exit / service stop. */
    private fun resetState(reason: String) {
        Log.i(TAG, "YT_BLOCK_RESET reason=$reason")
        Log.i(TAG, "STATE_RESET")
        chromeYoutubeVisible = false
        removePlayerOverlay("state reset")
        onYouTube = false
        onShorts = false
        shortUrl = null
        currentVideoId = null
        lastVisibleText = null
        lastMatchedTestKeyword = null
        lastNotShortsUrl = null
        blockedVideoId = null
        blockState = YoutubeBlockState.NORMAL
        pauseAttempts = 0
        lastPauseAttemptAt = 0L
        lastReenforceLogAt = 0L
        playerNodesDiagnosed = false
        noControlTicks = 0
        nonChromeRootTicks = 0
        resetPausePhase()
        playerWaitJob?.cancel()
        playerWaitJob = null
        playerWaitVideoId = null
        playerWaitGeneration = 0L
        playerWaitStartedAt = 0L
        playerWaitAttempts = 0
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
    }

    // ── Parsing helpers ───────────────────────────────────────────

    /**
     * Extract the Short video id from a URL like
     * "https://m.youtube.com/shorts/<videoId>" — the segment right after
     * "/shorts/", cut at the next "/", "?" or "#". Returns null when the URL
     * is missing or has no /shorts/ path.
     */
    private fun extractVideoId(url: String?): String? {
        if (url == null) return null
        val marker = "/shorts/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        var id = url.substring(idx + marker.length)
        for (stop in charArrayOf('/', '?', '#')) {
            val cut = id.indexOf(stop)
            if (cut >= 0) id = id.substring(0, cut)
        }
        return id.takeIf { it.isNotBlank() && it.length <= 32 }
    }

    /** Best-effort raw Short title: "Chrome: <T> - YouTube" → "<T>". */
    private fun rawTitle(snapshotTitle: String?): String? {
        if (snapshotTitle.isNullOrBlank()) return null
        val parsed = ContentExtractor.youtubeTitleFromChromeWindowTitle(snapshotTitle)
        if (parsed != null) return parsed
        return snapshotTitle.trim().removePrefix("Chrome:").trim()
    }

    // ── Player/control discovery ──────────────────────────────────

    /** Result of scanning for the player's play/pause control. */
    private class MediaButton(val node: AccessibilityNodeInfo?, val label: String?)

    /**
     * Find the video player's play/pause control. YouTube's mobile-web player
     * exposes the button with contentDescription/text exactly "Pause" (playing)
     * or "Play" (paused). Returns the FIRST "Pause" found, else the first
     * "Play", else null. The caller acts on the label so a toggle is never
     * issued blindly.
     */
    private fun findMediaButton(root: AccessibilityNodeInfo): MediaButton {
        var playNode: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { node.text?.toString()?.trim() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString()?.trim() } catch (e: Exception) { null }
                if (isPauseLabel(desc) || isPauseLabel(text)) {
                    return MediaButton(node, "pause")
                }
                if (playNode == null && (isPlayLabel(desc) || isPlayLabel(text))) {
                    playNode = node
                }
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        if (playNode != null) return MediaButton(playNode, "play")
        return MediaButton(null, null)
    }

    /** True for labels like "Pause" / "Pause video" (case-insensitive). */
    private fun isPauseLabel(s: String?): Boolean {
        if (s == null) return false
        val lower = s.trim().lowercase(Locale.ROOT)
        return lower == "pause" || lower == "pause video"
    }

    /** True for labels like "Play" / "Play video" (case-insensitive). */
    private fun isPlayLabel(s: String?): Boolean {
        if (s == null) return false
        val lower = s.trim().lowercase(Locale.ROOT)
        return lower == "play" || lower == "play video"
    }

    /** A player/playback-related node with its key properties (diagnostics). */
    private class PlayerNode(
        val node: AccessibilityNodeInfo,
        val cls: String?,
        val viewId: String?,
        val desc: String?,
        val text: String?,
        val clickable: Boolean,
        val focusable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val isVideoPlayer: Boolean
    )

    /**
     * The node representing the video player itself (for reveal + overlay).
     * The returned node is NOT recycled — the caller must recycle it. All
     * other collected nodes are recycled inside [findPlayerNodes].
     */
    private fun findVideoPlayerNode(root: AccessibilityNodeInfo): PlayerNode? {
        val players = findPlayerNodes(root)
        val selected = players.firstOrNull { it.isVideoPlayer }
            ?: players.firstOrNull { it.clickable }
        for (p in players) {
            if (p !== selected) {
                try { p.node.recycle() } catch (e: Exception) {}
            }
        }
        return selected
    }

    /**
     * BFS collecting nodes that look like the player or playback controls.
     * Collected nodes are NOT recycled (the caller decides); everything else
     * walked is recycled as usual.
     */
    private fun findPlayerNodes(root: AccessibilityNodeInfo): List<PlayerNode> {
        val out = ArrayList<PlayerNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val text = try { node.text?.toString()?.trim() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString()?.trim() } catch (e: Exception) { null }
            val cls = try { node.className?.toString() } catch (e: Exception) { null }
            if (isPlayerNode(text, desc, cls)) {
                val clickable = try { node.isClickable } catch (e: Exception) { false }
                val focusable = try { node.isFocusable } catch (e: Exception) { false }
                val enabled = try { node.isEnabled } catch (e: Exception) { false }
                val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
                val viewId = try { node.viewIdResourceName } catch (e: Exception) { null }
                val isVideoPlayer = listOf(desc, text, cls).any {
                    it?.lowercase(Locale.ROOT)?.contains("youtube video player") == true
                }
                out.add(
                    PlayerNode(
                        node = node, cls = cls, viewId = viewId, desc = desc, text = text,
                        clickable = clickable, focusable = focusable, enabled = enabled,
                        visible = visible, isVideoPlayer = isVideoPlayer
                    )
                )
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot && !out.any { it.node === node }) {
                try { node.recycle() } catch (e: Exception) {}
            }
            depth++
        }
        return out
    }

    /** True for nodes that look like the player or a playback control. */
    private fun isPlayerNode(text: String?, desc: String?, cls: String?): Boolean {
        val hay = ((text ?: "") + " " + (desc ?: "") + " " + (cls ?: "")).lowercase(Locale.ROOT)
        if (hay.contains("youtube video player")) return true
        if (hay.contains("player")) return true
        if (hay.contains("pause")) return true
        if (hay.contains("more actions")) return true
        if (hay.contains("share this video")) return true
        if (isPlayLabel(text) || isPlayLabel(desc) || isPauseLabel(text) || isPauseLabel(desc)) return true
        return false
    }

    /** "0x<hex>:<NAME>,..." for each set bit of the actions bitmask. */
    private fun actionsDetailed(actions: Int): String {
        if (actions == 0) return "none"
        val parts = mutableListOf<String>()
        var known = 0
        fun add(bit: Int, name: String) {
            if ((actions and bit) != 0) {
                parts.add("0x${bit.toString(16)}:$name")
                known = known or bit
            }
        }
        add(AccessibilityNodeInfo.ACTION_FOCUS, "FOCUS")
        add(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS, "CLEAR_FOCUS")
        add(AccessibilityNodeInfo.ACTION_SELECT, "SELECT")
        add(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, "CLEAR_SELECTION")
        add(AccessibilityNodeInfo.ACTION_CLICK, "CLICK")
        add(AccessibilityNodeInfo.ACTION_LONG_CLICK, "LONG_CLICK")
        add(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "ACCESSIBILITY_FOCUS")
        add(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, "CLEAR_ACCESSIBILITY_FOCUS")
        add(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, "NEXT_AT_MOVEMENT_GRANULARITY")
        add(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, "PREVIOUS_AT_MOVEMENT_GRANULARITY")
        add(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT, "NEXT_HTML_ELEMENT")
        add(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT, "PREVIOUS_HTML_ELEMENT")
        add(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "SCROLL_FORWARD")
        add(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "SCROLL_BACKWARD")
        add(AccessibilityNodeInfo.ACTION_COPY, "COPY")
        add(AccessibilityNodeInfo.ACTION_PASTE, "PASTE")
        add(AccessibilityNodeInfo.ACTION_CUT, "CUT")
        add(AccessibilityNodeInfo.ACTION_SET_SELECTION, "SET_SELECTION")
        add(AccessibilityNodeInfo.ACTION_EXPAND, "EXPAND")
        add(AccessibilityNodeInfo.ACTION_COLLAPSE, "COLLAPSE")
        add(AccessibilityNodeInfo.ACTION_DISMISS, "DISMISS")
        add(AccessibilityNodeInfo.ACTION_SET_TEXT, "SET_TEXT")
        add(ACTION_CONTEXT_CLICK, "CONTEXT_CLICK")
        add(ACTION_SCROLL_TO_POSITION, "SCROLL_TO_POSITION")
        add(ACTION_SHOW_ON_SCREEN, "SHOW_ON_SCREEN")
        add(ACTION_MOVE_WINDOW, "MOVE_WINDOW")
        add(ACTION_DRAG_START, "DRAG_START")
        add(ACTION_DRAG_DROP, "DRAG_DROP")
        add(ACTION_DRAG_CANCEL, "DRAG_CANCEL")
        add(ACTION_PRESS_AND_HOLD, "PRESS_AND_HOLD")
        add(ACTION_SCROLL_IN_DIRECTION, "SCROLL_IN_DIRECTION")
        val leftover = actions and known.inv()
        if (leftover != 0) parts.add("0x${leftover.toString(16)}:UNKNOWN")
        return parts.joinToString(",")
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        val r = Rect()
        return try {
            node.getBoundsInScreen(r)
            if (r.isEmpty) null else r
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Budgeted BFS collecting visible text + content descriptions from the
     * tree. Returns a de-duplicated list of readable strings (order preserved).
     */
    private fun extractVisibleTexts(root: AccessibilityNodeInfo): List<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { node.text?.toString() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                if (text != null) addCandidate(out, text)
                if (desc != null) addCandidate(out, desc)
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return out.take(MAX_TEXT_SNIPPETS).toList()
    }

    private fun addCandidate(out: MutableSet<String>, raw: String) {
        val s = raw.trim()
        if (s.isEmpty() || s.length < 2 || s.length > MAX_TEXT_LEN) return
        val lower = s.lowercase(Locale.ROOT)
        if (lower in GENERIC_SKIP_TEXTS) return
        if (looksLikeUrl(s)) return
        out.add(s)
    }

    private fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) return true
        // url-ish: dotted, no spaces, plausible length (e.g. "m.youtube.com")
        return lower.contains(".") && !lower.contains(" ") && lower.length in 5..150
    }

    // ── Diagnostics / logging ─────────────────────────────────────

    /** Throttled per-event line so the event flow is visible without spamming logcat. */
    private fun maybeLogServiceEvent(eventType: Int) {
        val now = now()
        val isWindowEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (isWindowEvent || now - lastEventLogAt >= EVENT_LOG_MIN_INTERVAL_MS) {
            lastEventLogAt = now
            Log.d(TAG, "SERVICE_EVENT type=${typeName(eventType)}")
        }
    }

    /**
     * Debug mechanism: throttled dump of informative nodes (text, content
     * description, view id, class, visibility) triggered when a Short is
     * first detected, so the actual tree Chrome exposes on a Shorts page can
     * be inspected from logcat.
     */
    private fun maybeDumpTree(root: AccessibilityNodeInfo, trigger: String) {
        val now = now()
        if (now - lastTreeDumpAt < TREE_DUMP_MIN_INTERVAL_MS) return
        lastTreeDumpAt = now
        Log.i(TAG, "TREE_DUMP_START trigger=$trigger")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var shown = 0
        while (queue.isNotEmpty() && depth < 80 && shown < 60) {
            val node = queue.removeFirst()
            val isRoot = node === root
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null }
            val cls = try { node.className?.toString() } catch (e: Exception) { null }
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (text != null || desc != null || viewId != null) {
                shown++
                Log.i(
                    TAG,
                    "DUMP_NODE#$shown cls=${cls ?: "null"} " +
                        "text=${if (text != null) "\"$text\"" else "null"} " +
                        "desc=${if (desc != null) "\"$desc\"" else "null"} " +
                        "vid=${viewId ?: "null"} visible=$visible"
                )
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        Log.i(TAG, "TREE_DUMP_END")
    }

    private fun typeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> "EVENT_$type"
    }

    private fun now(): Long = System.currentTimeMillis()
}
