package com.muddassir.clearview.youtubetest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.muddassir.clearview.extractor.ContentExtractor
import com.muddassir.clearview.matching.ContentSnapshot
import com.muddassir.clearview.matching.KeywordMatcher
import com.muddassir.clearview.matching.MatchResult
import com.muddassir.clearview.repository.BlockRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Per-video enforcement state for a blocked LONG (non-Short) YouTube video
 * watched inside Google Chrome.
 *
 *  - LONG_NORMAL: no long-video watch page active.
 *  - LONG_CLASSIFYING: watch page detected, waiting for the real title /
 *    description to appear in the accessibility tree before deciding.
 *  - LONG_ALLOWED: title+description matched NO blocked keyword — normal
 *    playback, no pause, no overlay. Kept monitoring: if late-loaded content
 *    (e.g. an expanded description) later matches, the video is blocked.
 *  - LONG_BLOCKED_NEEDS_PAUSE: blocked keyword found; the single-shot pause
 *    has not been issued yet.
 *  - LONG_BLOCKED_PAUSED: the one pause action was issued; observing the tree
 *    for the visible Play control that CONFIRMS playback is paused.
 *  - LONG_BLOCKED_PROTECTED: pause confirmed (or playback state unknowable)
 *    and the full-screen dark overlay is up — taps are consumed, vertical
 *    swipes pass through to Chrome, and the "Go to YouTube Home" button
 *    dismisses to m.youtube.com.
 */
enum class LongVideoBlockState {
    LONG_NORMAL,
    LONG_CLASSIFYING,
    LONG_ALLOWED,
    LONG_BLOCKED_NEEDS_PAUSE,
    LONG_BLOCKED_PAUSED,
    LONG_BLOCKED_PROTECTED
}

/**
 * Isolated LONG-video (watch-page) blocker for YouTube inside Chrome — see
 * [LongVideoBlockState] for the per-video state machine.
 *
 * ── HARD SEPARATION FROM SHORTS ─────────────────────────────────
 * This coordinator is 100% isolated from [YouTubeChromeTestCoordinator]:
 * it only acts on LONG-VIEW watch pages (/watch, or a YouTube page whose
 * window title carries a real video title) and deliberately ignores
 * /shorts/ URLs. The Shorts coordinator is never called, modified or
 * consulted here. Pause + overlay patterns mirror the proven Shorts
 * mechanism but are re-implemented locally so nothing shared changes.
 *
 * ── SINGLE-SHOT PAUSE ───────────────────────────────────────────
 * Exactly ONE pause action per visible video instance (generation): either a
 * click on the exposed Pause control, or — when no control is exposed — one
 * physical center tap after a single controls reveal. A video already paused
 * (Play control visible) is NEVER toggled. While PROTECTED, no pause actions
 * are ever sent; a new pause only happens for a NEW video instance, which
 * bumps the generation and re-enters the flow.
 *
 * dispatchGesture()/performAction() returning true is NEVER treated as pause
 * success — LONG_VIDEO_PAUSED_CONFIRMED is logged only when a visible Play
 * control is observed.
 *
 * All output goes to logcat under tag "ClearViewLongVideo" (LONG_VIDEO_*
 * state-machine logs).
 */
class LongVideoBlockCoordinator(
    private val service: AccessibilityService,
    private val repository: BlockRepository,
    private val keywordMatcher: KeywordMatcher
) {

    companion object {
        private const val TAG = "ClearViewLongVideo"
        private const val CHROME_PACKAGE = "com.android.chrome"

        // Detection cadences.
        private const val POLL_INTERVAL_MS = 600L
        private const val EVENT_SCAN_MIN_INTERVAL_MS = 600L
        private const val WINDOW_RESCAN_DELAY_MS = 400L
        // Enforcement pacing (single pause + observation ticks).
        private const val ENFORCEMENT_INTERVAL_MS = 400L
        private const val PAUSE_OBSERVE_MS = 1200L
        private const val CONTROLS_WAIT_MS = 500L
        // While a watch page is loading, Chrome may briefly expose no watch
        // signal (bare-domain address bar, title not rendered). Only reset the
        // long-video state after this grace window with no watch signal — a
        // transient gap must never drop a live block or re-pause a video.
        private const val WATCH_ENTER_GRACE_MS = 4000L
        private const val REENFORCE_LOG_INTERVAL_MS = 1500L

        // Gesture values.
        private const val SWIPE_THRESHOLD_PX = 80f
        private const val SWIPE_MIN_DY_PX = 120f
        private const val SWIPE_DIAGONAL_RATIO = 1.15f
        private const val GESTURE_STROKE_MS = 80L
        private const val SWIPE_REPLAY_DELAY_MS = 80L
        private const val SWIPE_REPLAY_SETTLE_MS = 1000L

        // Alpha (0-255) of the black wash painted over the whole window —
        // 255 = fully opaque: the blocked video is completely hidden.
        private const val BLACK_OVERLAY_ALPHA = 255

        // Tree-walk budgets (mirror the existing deep scans in ContentExtractor).
        private const val MAX_DEPTH = 150
        private const val NODE_BUDGET = 1500
        private const val MAX_TEXT_SNIPPETS = 40
        private const val MAX_TEXT_LEN = 240
        private const val MAX_COMBINED_LEN = 4000
        private const val MAX_DESC_PARTS = 12

        // Browser/accessibility UI strings that must never be treated as the
        // video's title or description content. Matching against these would
        // cause false blocks during page transitions (and they are exactly the
        // strings the long-video spec lists as "avoid").
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
            "watch on youtube",
            "youtube home",
            "see 1 tab",
            "web view",
            "loading",
            "comments",
            "add a comment",
            "up next",
            "autoplay",
            "related videos",
            "show more",
            "show less",
            "report",
            "save",
            "download",
            "clip",
            "playlist",
            "channel",
            "home",
            "shorts",
            "subscriptions",
            "library",
            "history",
            "settings"
        )

        // Visible text that is Chrome chrome, never page content.
        private val GENERIC_SKIP_TEXTS = setOf(
            "web view",
            "search or type url",
            "search google or type url",
            "search or type web address",
            "type a url",
            "loading"
        )
    }

    /** Extracted watch-page content (title + description/text from the tree). */
    private class WatchPageContent(val title: String?, val description: String?)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val contentExtractor = ContentExtractor()

    private var pollJob: Job? = null
    private var rescanJob: Job? = null
    private var replayFinalizeJob: Job? = null

    private var foregroundPackage: String? = null

    // ── Chrome/YouTube visibility (decoupled from the foreground package) ──
    // The active window may briefly be System UI, the IME, or ClearView's own
    // overlay while Chrome content is still on screen. Enforcement only resets
    // when Chrome is GENUINELY gone (no Chrome window anywhere).
    private var chromeYoutubeVisible = false

    // ── Long-video state (tracked per instance/generation) ─────────
    private var longVideoState = LongVideoBlockState.LONG_NORMAL
    /** Current watch-page state id (video id, else a title/url-derived key). */
    private var longVideoId: String? = null
    /** Bumped ONLY on a genuine video-id transition. Async work captures the
     *  generation it started under and refuses to act when it changed. */
    private var longVideoGeneration = 0L
    private var longVideoTitle: String? = null
    private var longVideoDescription: String? = null
    private var longVideoMatchedKeyword: String? = null
    private var longVideoClassificationDone = false
    private var longVideoBlocked = false
    /** True once the SINGLE pause action (control click or physical tap) has
     *  been sent for this generation. Cleared only by a new instance. */
    private var longVideoPauseAttempted = false
    /** True only when a visible Play control confirmed the paused state. */
    private var longVideoPauseConfirmed = false
    private var longVideoOverlayActive = false

    // Pause-phase pacing (single instance).
    private var pauseObserveSince = 0L
    private var controlsRevealGeneration = -1L
    private var controlsRevealAt = 0L
    private var instanceSince = 0L
    private var lastContentKey: String? = null

    // Scan/enforcement pacing.
    private var lastScanAt = 0L
    private var lastEnforceAt = 0L
    private var lastProtectedLogAt = 0L

    // ── Overlay ──────────────────────────────────────────────────
    private var overlayView: View? = null
    // True while a replayed swipe is being dispatched into Chrome — the
    // enforcement loop must not re-add the overlay mid-gesture.
    private var swipeReplayInFlight = false

    // ── Lifecycle hooks (called from UrlBlockerService) ───────────

    fun onForegroundPackageChanged(newPackage: String) {
        val old = foregroundPackage
        foregroundPackage = newPackage
        if (newPackage == CHROME_PACKAGE) {
            chromeYoutubeVisible = true
            if (old != CHROME_PACKAGE) {
                startPolling()
                scan("foreground", force = true)
            }
            return
        }
        if (isTransientWindowPackage(newPackage)) {
            Log.i(TAG, "LONG_VIDEO_TEMP_WINDOW_IGNORED package=$newPackage — state kept")
            return
        }
        // Genuinely left Chrome.
        if (chromeWindowStillPresent()) {
            Log.i(TAG, "LONG_VIDEO_CHROME_CONTENT_STILL_ACTIVE videoId=${longVideoId ?: "null"}")
            return
        }
        stopPolling()
        resetAll("chrome left foreground")
        chromeYoutubeVisible = false
    }

    /**
     * Called from the service for every accessibility event. Cheap: no tree
     * work here. A VIEW_CLICKED event while a long video is blocked triggers
     * an immediate enforcement scan (the overlay may have been temporarily
     * removed during a swipe replay).
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Self-heal foreground tracking: if Chrome events are arriving, Chrome
        // content is active — adopt it instead of silently dropping events.
        if (foregroundPackage != CHROME_PACKAGE) {
            foregroundPackage = CHROME_PACKAGE
            chromeYoutubeVisible = true
            startPolling()
        }

        if (!repository.youTubeChromeTest) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scan("window", force = true)
                scheduleSettledScan()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isBlockedState()) {
                    val root = try { service.rootInActiveWindow } catch (e: Exception) { null }
                    if (root != null) {
                        try {
                            enforceBlockedVideo(root)
                        } finally {
                            try { root.recycle() } catch (e: Exception) {}
                        }
                    }
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
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
        removeOverlay("service stopped")
        Log.i(TAG, "STOPPED")
    }

    // ── Detection scan ───────────────────────────────────────────

    private fun scan(reason: String, force: Boolean) {
        if (!chromeYoutubeVisible) return
        if (!repository.youTubeChromeTest) return
        if (!force && now() - lastScanAt < EVENT_SCAN_MIN_INTERVAL_MS) return
        lastScanAt = now()

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
        // Guard: if the active window is NOT Chrome (System UI, IME, or our
        // own full-screen overlay), this is a transient window — keep the
        // long-video state untouched.
        val rootPkg = try { root.packageName?.toString() } catch (e: Exception) { null }
        if (rootPkg != null && rootPkg != CHROME_PACKAGE) {
            Log.i(TAG, "LONG_VIDEO_TEMP_WINDOW_IGNORED package=$rootPkg — state kept")
            return
        }

        val activeWindow = try { service.windows?.firstOrNull { it.isActive } } catch (e: Exception) { null }
        val windowTitle = activeWindow?.title?.toString()
        val snapshot = contentExtractor.extract(CHROME_PACKAGE, root, null, windowTitle)
        val url = snapshot.url

        val urlIsYouTube = ContentExtractor.isYouTubeDomain(url)
        val titleIsYouTube = !urlIsYouTube && (windowTitle?.contains(" - YouTube", ignoreCase = true) == true)
        val isYouTubeContext = urlIsYouTube || titleIsYouTube
        val isShortsUrl = urlIsYouTube && url?.contains("/shorts/") == true
        val parsedTitle = snapshot.title?.let(ContentExtractor::youtubeTitleFromChromeWindowTitle)
        val urlIsWatch = url?.contains("/watch") == true
        // A watch page: explicit /watch URL, OR a YouTube page whose window
        // title carries a real video title (Chrome's address bar often shows
        // only the bare m.youtube.com domain, dropping the watch path).
        val isWatchNow = isYouTubeContext && !isShortsUrl && (urlIsWatch || parsedTitle != null)

        if (!isWatchNow) {
            // Not a long-video watch page (feed, search, home, or still
            // loading). Reset only when genuinely off the watch page — never
            // on a transient gap while the page is rendering.
            if (longVideoState != LongVideoBlockState.LONG_NORMAL) {
                if (isYouTubeContext && !isShortsUrl && (urlIsWatch || parsedTitle != null)) {
                    Log.i(TAG, "LONG_VIDEO_TRANSIENT url=${url ?: "null"} — state kept")
                } else if (now() - instanceSince < WATCH_ENTER_GRACE_MS) {
                    Log.i(TAG, "LONG_VIDEO_TRANSIENT url=${url ?: "null"} — grace window, state kept")
                } else {
                    Log.i(TAG, "LONG_VIDEO_LEFT_WATCH_PAGE url=${url ?: "null"} state=$longVideoState")
                    resetAll("left watch page")
                }
            }
            return
        }

        val videoId = extractVideoId(url)
        val stateId = videoId ?: "title#${(parsedTitle ?: url ?: "").hashCode()}"

        if (stateId != longVideoId) {
            Log.i(TAG, "LONG_VIDEO_DETECTED videoId=${videoId ?: "unknown"}")
            Log.i(TAG, "LONG_VIDEO_NEW_INSTANCE stateId=$stateId gen=${longVideoGeneration + 1}")
            beginNewInstance(stateId, videoId)
        }

        when (longVideoState) {
            LongVideoBlockState.LONG_CLASSIFYING -> classify(root, snapshot, parsedTitle)
            LongVideoBlockState.LONG_ALLOWED -> recheckAllowedContent(root, snapshot, parsedTitle)
            LongVideoBlockState.LONG_BLOCKED_NEEDS_PAUSE,
            LongVideoBlockState.LONG_BLOCKED_PAUSED,
            LongVideoBlockState.LONG_BLOCKED_PROTECTED -> enforceBlockedVideo(root)
            LongVideoBlockState.LONG_NORMAL -> {}
        }
    }

    // ── Instance transitions ─────────────────────────────────────

    /**
     * A new long-video instance: bump the generation and reset ALL per-video
     * bookkeeping (including the single-shot pause guard) so late async work
     * from the previous video can never act on this one.
     */
    private fun beginNewInstance(stateId: String, videoId: String?) {
        Log.i(TAG, "LONG_VIDEO_STATE_RESET reason=new_instance (old gen invalidated)")
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
        removeOverlay("new video instance")
        longVideoGeneration++
        longVideoId = stateId
        longVideoTitle = null
        longVideoDescription = null
        longVideoMatchedKeyword = null
        longVideoClassificationDone = false
        longVideoBlocked = false
        longVideoPauseAttempted = false
        longVideoPauseConfirmed = false
        pauseObserveSince = 0L
        controlsRevealGeneration = -1L
        controlsRevealAt = 0L
        lastContentKey = null
        instanceSince = now()
        // Force into CLASSIFYING regardless of the previous video's state (the
        // old video may have been PROTECTED when this one opened — a guarded
        // transition would no-op and leave stale protection over the new
        // unclassified video).
        val fromState = longVideoState
        longVideoState = LongVideoBlockState.LONG_CLASSIFYING
        Log.i(TAG, "LONG_VIDEO_STATE $fromState -> LONG_CLASSIFYING videoId=${longVideoId ?: "unknown"}")
    }

    /** Full state cleanup (left watch page / Chrome gone / home button). */
    private fun resetAll(reason: String) {
        Log.i(TAG, "LONG_VIDEO_STATE_RESET reason=$reason")
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
        removeOverlay("state reset: $reason")
        longVideoState = LongVideoBlockState.LONG_NORMAL
        longVideoId = null
        longVideoTitle = null
        longVideoDescription = null
        longVideoMatchedKeyword = null
        longVideoClassificationDone = false
        longVideoBlocked = false
        longVideoPauseAttempted = false
        longVideoPauseConfirmed = false
        longVideoOverlayActive = false
        pauseObserveSince = 0L
        controlsRevealGeneration = -1L
        controlsRevealAt = 0L
        instanceSince = 0L
        lastContentKey = null
        lastProtectedLogAt = 0L
    }

    private fun transition(to: LongVideoBlockState) {
        val from = longVideoState
        if (from == to) return
        longVideoState = to
        Log.i(TAG, "LONG_VIDEO_STATE $from -> $to videoId=${longVideoId ?: "unknown"}")
    }

    private fun isBlockedState(): Boolean =
        longVideoState == LongVideoBlockState.LONG_BLOCKED_NEEDS_PAUSE ||
            longVideoState == LongVideoBlockState.LONG_BLOCKED_PAUSED ||
            longVideoState == LongVideoBlockState.LONG_BLOCKED_PROTECTED

    // ── Classification (authoritative check on the real watch page) ──

    private fun classify(root: AccessibilityNodeInfo, snapshot: ContentSnapshot, parsedTitle: String?) {
        val content = extractWatchPageContent(root, snapshot, parsedTitle)
        val title = content.title
        if (title == null || !isValidVideoTitle(title)) {
            Log.i(TAG, "LONG_VIDEO_CONTENT_WAIT videoId=${longVideoId ?: "unknown"} — real title not ready yet")
            return // stay LONG_CLASSIFYING until real video content is available
        }
        longVideoTitle = title
        val description = content.description
        if (description != null) {
            longVideoDescription = description
            Log.i(TAG, "LONG_VIDEO_DESCRIPTION_READY videoId=${longVideoId ?: "unknown"}")
        }
        longVideoClassificationDone = true
        Log.i(TAG, "LONG_VIDEO_CONTENT_READY videoId=${longVideoId ?: "unknown"} title=\"$title\"")

        val result = keywordMatcher.checkLongVideoContent(title, description)
        if (result is MatchResult.Blocked) {
            longVideoMatchedKeyword = result.matchedItem
            longVideoBlocked = true
            Log.w(TAG, "LONG_VIDEO_MATCH keyword=${result.matchedItem} source=${result.matchSource}")
            Log.w(TAG, "LONG_VIDEO_BLOCKED videoId=${longVideoId ?: "unknown"} keyword=${result.matchedItem}")
            transition(LongVideoBlockState.LONG_BLOCKED_NEEDS_PAUSE)
            enforceBlockedVideo(root)
        } else {
            Log.i(TAG, "LONG_VIDEO_ALLOWED videoId=${longVideoId ?: "unknown"}")
            transition(LongVideoBlockState.LONG_ALLOWED)
        }
    }

    /**
     * An allowed video keeps playing untouched. If content that loads LATER
     * (e.g. an expanded description) introduces a blocked keyword, the video
     * transitions to the block flow. Only re-checks when the content changed,
     * so a static page is never re-matched on every poll.
     */
    private fun recheckAllowedContent(root: AccessibilityNodeInfo, snapshot: ContentSnapshot, parsedTitle: String?) {
        val content = extractWatchPageContent(root, snapshot, parsedTitle)
        val title = content.title
        if (title == null || !isValidVideoTitle(title)) return
        val key = "$title|${content.description ?: ""}"
        if (key == lastContentKey) return
        lastContentKey = key
        val result = keywordMatcher.checkLongVideoContent(title, content.description)
        if (result is MatchResult.Blocked) {
            longVideoTitle = title
            longVideoDescription = content.description
            longVideoMatchedKeyword = result.matchedItem
            longVideoBlocked = true
            Log.w(TAG, "LONG_VIDEO_MATCH keyword=${result.matchedItem} source=${result.matchSource} (late content)")
            Log.w(TAG, "LONG_VIDEO_BLOCKED videoId=${longVideoId ?: "unknown"} keyword=${result.matchedItem}")
            transition(LongVideoBlockState.LONG_BLOCKED_NEEDS_PAUSE)
            enforceBlockedVideo(root)
        }
    }

    /**
     * Watch-page content extraction: title from the Chrome window title
     * (falling back to the first tree text node that fits a video-title
     * profile), description/text from visible tree nodes, excluding browser UI
     * strings, metadata fragments and the title itself.
     */
    private fun extractWatchPageContent(
        root: AccessibilityNodeInfo,
        snapshot: ContentSnapshot,
        parsedTitle: String?
    ): WatchPageContent {
        var title = parsedTitle
            ?: snapshot.title?.let(ContentExtractor::youtubeTitleFromChromeWindowTitle)
        if (title != null && !isValidVideoTitle(title)) title = null

        val texts = extractVisibleTexts(root)
        if (title == null) {
            title = texts.firstOrNull { isValidVideoTitle(it) }
        }

        val description = texts
            .filter { it != title && isDescriptionCandidate(it) }
            .distinct()
            .take(MAX_DESC_PARTS)
            .joinToString(" | ")
            .take(MAX_COMBINED_LEN)
            .ifBlank { null }

        return WatchPageContent(title, description)
    }

    /** True for a plausible real video title (not browser/UI chrome). */
    private fun isValidVideoTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val t = title.trim()
        if (t.length < 3 || t.length > 200) return false
        val lower = t.lowercase(Locale.ROOT)
        if (lower in GENERIC_UI_TITLES) return false
        if (looksLikeUrl(t)) return false
        if (lower.contains("views") || lower.contains(" ago") || lower.contains("likes")) return false
        return true
    }

    /** True for visible text that plausibly belongs to the video description. */
    private fun isDescriptionCandidate(raw: String): Boolean {
        val t = raw.trim()
        if (t.length < 20) return false
        val lower = t.lowercase(Locale.ROOT)
        if (lower in GENERIC_UI_TITLES) return false
        if (lower.startsWith("subscribe") || lower.startsWith("share") ||
            lower.startsWith("save") || lower.startsWith("download") ||
            lower.startsWith("report")
        ) return false
        if (lower.contains("views") && lower.contains("ago")) return false
        if (lower.contains("comments") || lower.contains("replies")) return false
        if (lower.matches(Regex("^[\\d,.kKmMbB ]+$"))) return false
        if (looksLikeUrl(t)) return false
        return true
    }

    // ── Single-shot pause + protection ───────────────────────────

    private fun enforceBlockedVideo(root: AccessibilityNodeInfo) {
        if (!chromeYoutubeVisible) return
        if (!repository.youTubeChromeTest) return
        // Hard replay lock: while a swipe replay is in flight, no enforcement
        // path may re-add the overlay (the injected gesture would land on it).
        if (swipeReplayInFlight) {
            Log.i(TAG, "LONG_VIDEO_ENFORCE_SKIP videoId=${longVideoId ?: "null"} reason=swipe_replay_in_flight")
            return
        }
        val now = now()
        if (now - lastEnforceAt < ENFORCEMENT_INTERVAL_MS) return
        lastEnforceAt = now

        when (longVideoState) {
            LongVideoBlockState.LONG_BLOCKED_NEEDS_PAUSE -> runPauseAttempt(root, now)
            LongVideoBlockState.LONG_BLOCKED_PAUSED -> runPauseObserve(root, now)
            LongVideoBlockState.LONG_BLOCKED_PROTECTED -> keepProtected(root)
            else -> {}
        }
    }

    /**
     * Pause, exactly once per instance:
     *   Pause control exposed  -> click it once (playing), observe
     *   Play  control exposed  -> already paused — never toggle, protect now
     *   no control exposed     -> reveal controls once, then ONE physical
     *                             center tap, observe
     */
    private fun runPauseAttempt(root: AccessibilityNodeInfo, now: Long) {
        val videoId = longVideoId ?: return
        val button = findMediaButton(root)
        when (button.label) {
            "pause" -> {
                if (!longVideoPauseAttempted) {
                    longVideoPauseAttempted = true
                    Log.i(TAG, "LONG_VIDEO_PAUSE_REQUEST videoId=$videoId (Pause control click)")
                    val ok = try {
                        button.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                    } catch (e: Exception) {
                        Log.e(TAG, "ERROR pause control click: ${e.message}")
                        false
                    }
                    Log.i(
                        TAG,
                        "LONG_VIDEO_PAUSE_DISPATCH_ACCEPTED videoId=$videoId accepted=$ok — dispatch acceptance only, pause confirmed by visible Play control"
                    )
                } else {
                    Log.i(TAG, "LONG_VIDEO_PAUSE_SKIP videoId=$videoId reason=already_attempted_single_shot")
                }
                recycleIfNotRoot(button.node, root)
                pauseObserveSince = now
                transition(LongVideoBlockState.LONG_BLOCKED_PAUSED)
            }
            "play" -> {
                // Visible evidence the video is ALREADY paused — do not toggle
                // it (test case: blocked video already paused).
                longVideoPauseAttempted = true
                longVideoPauseConfirmed = true
                Log.i(TAG, "LONG_VIDEO_PAUSED_CONFIRMED videoId=$videoId (Play control visible — already paused, no toggle)")
                recycleIfNotRoot(button.node, root)
                transition(LongVideoBlockState.LONG_BLOCKED_PROTECTED)
                enableOverlay()
            }
            null -> {
                // No control exposed: reveal once, then one physical center tap
                // as the single-shot pause request (proven Shorts mechanism).
                if (controlsRevealGeneration != longVideoGeneration) {
                    controlsRevealGeneration = longVideoGeneration
                    revealPlayerControls(root)
                    controlsRevealAt = now
                } else if (now - controlsRevealAt >= CONTROLS_WAIT_MS && !longVideoPauseAttempted) {
                    longVideoPauseAttempted = true
                    if (requestPhysicalPause(root)) {
                        pauseObserveSince = now
                        transition(LongVideoBlockState.LONG_BLOCKED_PAUSED)
                    } else {
                        // No player bounds — cannot send the tap; protect anyway.
                        Log.i(TAG, "LONG_VIDEO_PAUSE_REQUEST videoId=$videoId skipped — no player bounds, protecting")
                        transition(LongVideoBlockState.LONG_BLOCKED_PROTECTED)
                        enableOverlay()
                    }
                }
            }
        }
    }

    /**
     * Bounded observation after the single pause action: only a visible Play
     * control CONFIRMS the pause. After the window, protect anyway (the
     * overlay blocks all interaction even if playback state is unknowable).
     */
    private fun runPauseObserve(root: AccessibilityNodeInfo, now: Long) {
        val videoId = longVideoId ?: return
        val button = findMediaButton(root)
        when (button.label) {
            "play" -> {
                longVideoPauseConfirmed = true
                Log.i(TAG, "LONG_VIDEO_PAUSED_CONFIRMED videoId=$videoId (Play control visible after pause)")
                recycleIfNotRoot(button.node, root)
                transition(LongVideoBlockState.LONG_BLOCKED_PROTECTED)
                enableOverlay()
            }
            else -> {
                recycleIfNotRoot(button.node, root)
                if (now - pauseObserveSince >= PAUSE_OBSERVE_MS) {
                    Log.i(TAG, "LONG_VIDEO_PAUSE_NOT_CONFIRMED videoId=$videoId — protecting anyway (single-shot pause already sent)")
                    transition(LongVideoBlockState.LONG_BLOCKED_PROTECTED)
                    enableOverlay()
                } else {
                    Log.i(TAG, "LONG_VIDEO_PAUSE_OBSERVE videoId=$videoId — waiting for visible Play control")
                }
            }
        }
    }

    /**
     * Protected: overlay stays up, NO pause actions are ever sent for this
     * instance. A new visible video is a new generation and re-enters the
     * pause flow from scratch.
     */
    private fun keepProtected(root: AccessibilityNodeInfo) {
        val videoId = longVideoId ?: return
        if (overlayView == null && !swipeReplayInFlight) {
            enableOverlay()
        }
        if (now() - lastProtectedLogAt >= REENFORCE_LOG_INTERVAL_MS) {
            lastProtectedLogAt = now()
            Log.i(TAG, "LONG_VIDEO_REENFORCE videoId=$videoId — protected, no pause actions for this instance")
        }
    }

    /** One controlled click on the player to expose the transport controls. */
    private fun revealPlayerControls(root: AccessibilityNodeInfo) {
        val videoId = longVideoId ?: return
        val player = findVideoPlayerNode(root)
        if (player == null) {
            Log.i(TAG, "LONG_VIDEO_REVEAL_SKIP videoId=$videoId — no player node")
            return
        }
        try {
            Log.i(TAG, "LONG_VIDEO_REVEAL videoId=$videoId (player click to expose controls)")
            player.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR reveal click: ${e.message}")
        } finally {
            try { player.node?.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * The single physical pause request when no control is exposed: a center
     * tap on the player bounds via dispatchGesture(). Returns true when the
     * gesture was dispatched. Acceptance is NOT proof of pause — confirmation
     * comes only from a visible Play control (see runPauseObserve).
     */
    private fun requestPhysicalPause(root: AccessibilityNodeInfo): Boolean {
        val videoId = longVideoId ?: return false
        val player = findVideoPlayerNode(root)
        val bounds = player?.let { boundsOf(it.node) }
        try { player?.node?.recycle() } catch (e: Exception) {}
        if (bounds == null) return false
        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        Log.i(TAG, "LONG_VIDEO_PAUSE_REQUEST videoId=$videoId x=$x y=$y bounds=$bounds (physical center tap)")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_STROKE_MS))
            .build()
        val ok = try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {}
                    override fun onCancelled(gestureDescription: GestureDescription?) {}
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "ERROR physical pause dispatch: ${e.message}")
            false
        }
        Log.i(
            TAG,
            "LONG_VIDEO_PAUSE_DISPATCH_ACCEPTED videoId=$videoId accepted=$ok — dispatch acceptance only; pause confirmed by visible Play control"
        )
        return ok
    }

    // ── Overlay ──────────────────────────────────────────────────

    /**
     * Full-screen dark overlay over the blocked long video. Taps and
     * horizontal drags are consumed (the player can never be interacted
     * with); vertical swipes are replayed into Chrome so vertical navigation
     * still works; the "Go to YouTube Home" button dismisses to m.youtube.com.
     */
    private inner class LongVideoBlockOverlay(context: Context) : View(context) {
        // Touch origin in VIEW-LOCAL coordinates (event.x/y) — the button hit
        // test uses local coords because the overlay window can be offset from
        // the screen origin (e.g. below the status bar); rawX/rawY are kept
        // separately because dispatchGesture() needs SCREEN coordinates.
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var touching = false
        private val washPaint = Paint().apply {
            color = Color.BLACK
            alpha = BLACK_OVERLAY_ALPHA
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 210
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 90
            strokeWidth = 2f
        }
        private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 235
        }
        private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val buttonRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)

            val cx = width / 2f
            val unit = min(width, height)

            // "FEAR GOD" — serif hero.
            titlePaint.textSize = unit * 0.09f
            titlePaint.letterSpacing = 0.15f
            val titleY = height * 0.42f
            canvas.drawText("FEAR GOD", cx, titleY, titlePaint)

            // Hairline divider.
            val dividerY = titleY + unit * 0.05f
            canvas.drawLine(cx - unit * 0.22f, dividerY, cx + unit * 0.22f, dividerY, dividerPaint)

            // "BLOCKED" — wide-tracked caps.
            statusPaint.textSize = unit * 0.045f
            statusPaint.letterSpacing = 0.12f
            canvas.drawText("BLOCKED", cx, dividerY + unit * 0.08f, statusPaint)

            // "Go to YouTube Home" button.
            val bw = min(width * 0.7f, unit * 1.4f)
            val bh = unit * 0.11f
            val bx = cx - bw / 2f
            val by = height * 0.78f - bh / 2f
            buttonRect.set(bx, by, bx + bw, by + bh)
            val r = bh / 2f
            canvas.drawRoundRect(buttonRect, r, r, buttonPaint)
            buttonTextPaint.textSize = unit * 0.042f
            buttonTextPaint.letterSpacing = 0.02f
            val baseline = by + bh / 2f - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
            canvas.drawText("Go to YouTube Home", cx, baseline, buttonTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = event.x
                    downY = event.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> return true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!touching) return true
                    touching = false
                    val upX = event.x
                    val upY = event.y
                    val dx = upX - downX
                    val dy = upY - downY
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < SWIPE_THRESHOLD_PX) {
                        // TAP → button (local coords) or consume.
                        if (buttonRect.contains(downX, downY)) {
                            Log.i(TAG, "LONG_VIDEO_HOME_BUTTON_TAP x=$downX y=$downY rect=$buttonRect")
                            onHomeButtonClicked()
                        } else {
                            onOverlayTapConsumed()
                        }
                    } else if (abs(dy) >= SWIPE_MIN_DY_PX && abs(dy) > abs(dx) * SWIPE_DIAGONAL_RATIO) {
                        // Vertical swipe → pass to Chrome (screen coords).
                        onOverlayVerticalSwipe(downRawX, downRawY, event.rawX, event.rawY)
                    } else {
                        // Horizontal drag (seek) → consume.
                        onOverlayTapConsumed()
                    }
                    return true
                }
            }
            return true
        }
    }

    private fun onOverlayTapConsumed() {
        Log.i(TAG, "LONG_VIDEO_TAP_CONSUMED videoId=${longVideoId ?: "unknown"}")
    }

    /** "Go to YouTube Home" tapped → reset state, remove overlay, go home. */
    private fun onHomeButtonClicked() {
        Log.i(TAG, "LONG_VIDEO_HOME_BUTTON_CLICKED")
        goToYouTubeHome()
    }

    private fun goToYouTubeHome() {
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
        removeOverlay("home button")
        resetAll("home button")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            Log.i(TAG, "LONG_VIDEO_HOME_NAVIGATION intent=ACTION_VIEW https://m.youtube.com")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR home navigation: ${e.message}")
        }
    }

    private fun enableOverlay() {
        if (overlayView != null) return
        if (swipeReplayInFlight) return
        if (longVideoState != LongVideoBlockState.LONG_BLOCKED_PROTECTED) {
            Log.i(TAG, "LONG_VIDEO_OVERLAY_SKIP videoId=${longVideoId ?: "unknown"} — not in protected state")
            return
        }
        try {
            val view = LongVideoBlockOverlay(service)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            val wm = service.getSystemService(WindowManager::class.java)
            wm.addView(view, params)
            overlayView = view
            longVideoOverlayActive = true
            Log.i(TAG, "LONG_VIDEO_OVERLAY_ENABLED videoId=${longVideoId ?: "unknown"}")
            Log.i(TAG, "LONG_VIDEO_PROTECTED videoId=${longVideoId ?: "unknown"}")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR overlay addView: ${e.message}")
        }
    }

    private fun removeOverlay(reason: String) {
        val view = overlayView ?: return
        try {
            service.getSystemService(WindowManager::class.java).removeView(view)
            Log.i(TAG, "LONG_VIDEO_OVERLAY_REMOVED reason=$reason")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR overlay removeView: ${e.message}")
        }
        overlayView = null
        longVideoOverlayActive = false
    }

    // ── Vertical swipe pass-through (replayed into Chrome) ───────

    /** A vertical swipe on the blocked overlay — replay it into Chrome. */
    private fun onOverlayVerticalSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        Log.i(TAG, "LONG_VIDEO_VERTICAL_SWIPE start=($startX,$startY) end=($endX,$endY)")
        val gen = longVideoGeneration
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = true
        removeOverlay("swipe replay")
        scope.launch {
            delay(SWIPE_REPLAY_DELAY_MS)
            if (!isActive) return@launch
            dispatchVerticalSwipe(startX, startY, endX, endY, gen)
        }
    }

    private fun dispatchVerticalSwipe(startX: Float, startY: Float, endX: Float, endY: Float, gen: Long) {
        Log.i(TAG, "LONG_VIDEO_VERTICAL_SWIPE_REPLAY gen=$gen")
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_STROKE_MS))
            .build()
        val ok = try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {}
                    override fun onCancelled(gestureDescription: GestureDescription?) {}
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "ERROR swipe replay: ${e.message}")
            false
        }
        if (!ok) {
            finishSwipeReplay(gen, restore = true)
        } else {
            replayFinalizeJob = scope.launch {
                delay(SWIPE_REPLAY_SETTLE_MS)
                finishSwipeReplay(gen, restore = true)
            }
        }
    }

    /**
     * Single, idempotent replay finalization. Restores the overlay only when
     * the SAME generation is still protected (the generation guard means a
     * stale replay can never re-cover a different video).
     */
    private fun finishSwipeReplay(gen: Long, restore: Boolean) {
        if (replayFinalizeJob == null && !swipeReplayInFlight) {
            return
        }
        replayFinalizeJob?.cancel()
        replayFinalizeJob = null
        swipeReplayInFlight = false
        if (restore &&
            gen == longVideoGeneration &&
            longVideoState == LongVideoBlockState.LONG_BLOCKED_PROTECTED &&
            overlayView == null &&
            chromeYoutubeVisible
        ) {
            Log.i(TAG, "LONG_VIDEO_SWIPE_REPLAY_REPROTECTED videoId=${longVideoId ?: "null"}")
            enableOverlay()
        }
        Log.i(TAG, "LONG_VIDEO_SWIPE_REPLAY_FINISHED")
    }

    // ── Chrome/YouTube visibility helpers ─────────────────────────

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

    // ── Polling / scheduling ─────────────────────────────────────

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
     * Chrome may not have finished rendering when the window event fires, so
     * a one-shot delayed re-scan sees the settled tree (same pattern as the
     * Shorts coordinator).
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

    // ── Parsing helpers ──────────────────────────────────────────

    /** Video id from "…/watch?v=<id>&…" — the value of the v= parameter. */
    private fun extractVideoId(url: String?): String? {
        if (url == null) return null
        val watchIdx = url.indexOf("/watch")
        if (watchIdx < 0) return null
        val qIdx = url.indexOf('?', watchIdx)
        if (qIdx < 0) return null
        for (param in url.substring(qIdx + 1).split('&')) {
            val eq = param.indexOf('=')
            if (eq <= 0) continue
            if (param.substring(0, eq) != "v") continue
            val id = param.substring(eq + 1)
            if (id.isNotBlank() && id.length <= 32) return id
        }
        return null
    }

    // ── Player/control discovery (mirrors the proven Shorts mechanism) ──

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

    /** The node representing the video player (for the reveal click / tap). */
    private fun findVideoPlayerNode(root: AccessibilityNodeInfo): MediaButton? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val cls = try { node.className?.toString() } catch (e: Exception) { null }
            val hay = ((text ?: "") + " " + (desc ?: "") + " " + (cls ?: "")).lowercase(Locale.ROOT)
            if (hay.contains("youtube video player") || hay.contains("player")) {
                return MediaButton(node, "player")
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        val r = Rect()
        return try {
            node.getBoundsInScreen(r)
            if (r.isEmpty) null else r
        } catch (e: Exception) {
            null
        }
    }

    private fun recycleIfNotRoot(node: AccessibilityNodeInfo?, root: AccessibilityNodeInfo) {
        if (node == null || node === root) return
        try { node.recycle() } catch (e: Exception) {}
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

    private fun now(): Long = System.currentTimeMillis()
}
