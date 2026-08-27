package com.muddassir.clearview.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.muddassir.clearview.backend.ChannelBlockRepository
import com.muddassir.clearview.extractor.ContentExtractor
import java.util.Locale
import com.muddassir.clearview.matching.ContentSnapshot
import com.muddassir.clearview.matching.KeywordMatcher
import com.muddassir.clearview.matching.MatchResult
import com.muddassir.clearview.matching.MatchType
import com.muddassir.clearview.matching.MatchSource
import com.muddassir.clearview.repository.BlockRepository
import com.muddassir.clearview.youtubetest.LongVideoBlockCoordinator
import com.muddassir.clearview.youtubetest.YouTubeChromeTestCoordinator
import com.muddassir.clearview.youtubetest.YoutubeTestKeywordRepository
import kotlinx.coroutines.*

enum class BlockingState {
    NORMAL,
    CLEARING_TARGET,
    WAITING_FOR_SAFE_STATE,
    RETURNING_HOME,
    OVERLAY_ACTIVE
}

/**
 * Accessibility Service that continuously monitors Chrome and Google app
 * for blocked keywords and domains.
 */
class UrlBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "UrlBlockerService"

        /**
         * Live reference to the running service, set in [onCreate] and cleared
         * in [onDestroy]. Lets the block overlay — a plain activity, not an
         * accessibility service — ask the service to press the REAL Home key
         * via [pressHome] instead of launching a Home intent of its own.
         */
        @Volatile private var instance: UrlBlockerService? = null

        /**
         * Presses the HOME key through the accessibility service
         * ([AccessibilityService.GLOBAL_ACTION_HOME]) — exactly the same
         * transition as the user tapping the navigation-bar Home button: the
         * existing launcher task is simply brought to the front, never
         * recreated, so the launcher does not re-render its icon grid.
         * Returns false when no service instance is running, so callers can
         * fall back to a Home intent.
         */
        fun pressHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        private const val POLLING_INTERVAL_MS = 500L
        private const val GOOGLE_POLLING_INTERVAL_MS = 500L
        // Embedded browsers (in-app WebViews in non-target apps) are monitored at
        // a slower rate — full-tree scans of arbitrary apps are heavier, and a
        // lower cadence keeps battery impact reasonable.
        private const val EMBEDDED_POLLING_INTERVAL_MS = 1500L
        private const val GOOGLE_QUERY_CACHE_WINDOW_MS = 15_000L
        private const val SAFE_STATE_TIMEOUT_MS = 2500L
        // After an incognito block is initiated, suppress further incognito
        // re-blocks for this long. During the closing sequence (Back presses that
        // keep changing the tree) and right after Chrome settles, incognito
        // signals can still be present; without a lock the block would fire in a
        // tight loop and the user could never reach normal Chrome.
        private const val INCOGNITO_BLOCK_COOLDOWN_MS = 4000L
        // Adaptive Back sequence when closing incognito tabs: maximum number of
        // Back presses, the pause between a press and the re-scan that checks
        // whether Chrome is still showing incognito UI, and the pause before the
        // overlay is shown when Chrome had to be relaunched in normal mode.
        private const val INCOGNITO_CLOSE_MAX_BACK_PRESSES = 8
        private const val INCOGNITO_CLOSE_SCAN_DELAY_MS = 400L
        // Chrome may not have finished rendering the incognito UI when the window
        // event fires, so a one-shot delayed re-scan is scheduled this long after
        // the event to catch the rendered state.
        private const val CHROME_DELAYED_SCAN_MS = 400L
        // Safe-tab redirect for NORMAL Chrome blocks: the CURRENT tab is
        // navigated to this URL so reopening Chrome never shows the blocked
        // page. Delay between the omnibox SET_TEXT and pressing the IME enter
        // key (the soft keyboard needs a moment to appear).
        private const val SAFE_REDIRECT_URL = "https://www.google.com"
        private const val SAFE_REDIRECT_SCAN_DELAY_MS = 400L
        // Throttle Chrome diagnostic tree dumps so they don't spam logcat.
        private const val CHROME_DIAG_INTERVAL_MS = 2000L
        // Event-driven full-tree scans are expensive (a full accessibility-tree
        // walk + keyword matching on the main thread). Chrome WebViews storm
        // TYPE_WINDOW_CONTENT_CHANGED and typing storms TYPE_VIEW_TEXT_CHANGED;
        // a scan per event janks the main thread (observed "Skipped 41 frames").
        // Cap ALL event-driven scans to one per 250ms — the 500ms poll loop and
        // the immediate window-event path cover the rest.
        private const val EVENT_SCAN_MIN_INTERVAL_MS = 250L
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    }

    private lateinit var repository: BlockRepository
    private lateinit var keywordMatcher: KeywordMatcher
    private val contentExtractor = ContentExtractor()

    /**
     * STAGE 1 FEASIBILITY TEST coordinator (YouTube Shorts in Chrome), gated
     * by the "YouTube Chrome Test" toggle. Fully independent of the blocking
     * pipeline below — it never touches blockingState, the dedup cache, or
     * the overlay. See [YouTubeChromeTestCoordinator].
     */
    private var youtubeChromeTest: YouTubeChromeTestCoordinator? = null

    /**
     * Long-video (watch-page) blocker — fully isolated from the Shorts
     * coordinator above. Owns Chrome YouTube /watch pages while the
     * experiment toggle is on: pauses blocked videos once, raises the dark
     * full-screen overlay with "Go to YouTube Home", and never touches
     * Shorts logic. See [LongVideoBlockCoordinator].
     */
    private var longVideoBlock: LongVideoBlockCoordinator? = null

    // Per-event/per-poll debug logging is EXPENSIVE (string formatting + logcat
    // I/O on the accessibility/main thread). Release builds keep the detection
    // logic but drop these hot diagnostics; debug builds (the ones used for
    // on-device debugging) keep them. FLAG_DEBUGGABLE is checked instead of
    // BuildConfig so the gate works even with R8/minification disabled.
    // NOTE: computed lazily (NOT a field initializer) — applicationInfo is null
    // until the context is attached, so an eager init would NPE in onCreate.
    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private var currentForegroundPackage: String? = null
    private var lastCheckedSnapshotId: String? = null

    private var blockingState = BlockingState.NORMAL
    private var lastBlockedResult: MatchResult.Blocked? = null
    private var safeStateTimeoutJob: Job? = null

    /**
     * Last known YouTube URL (for Chrome SPA navigation detection). YouTube's
     * SPA changes the URL without triggering a WINDOW_STATE_CHANGED event and
     * often without updating the window title. When the URL changes but the
     * identity string didn't, we force re-evaluation so the new video's title
     * and channel are checked.
     */
    private var lastYouTubeUrl: String? = null

    /** Last event-driven full-tree scan (throttle for content-changed storms). */
    private var lastEventScanAt = 0L

    /**
     * Timestamp until which INCOGNITO re-blocks are suppressed (loop guard).
     * Set when an incognito block is initiated and checked before initiating
     * another one. See [INCOGNITO_BLOCK_COOLDOWN_MS].
     */
    private var incognitoBlockCooldownUntil = 0L

    /**
     * True only while OUR OWN incognito-close sequence is still running (or
     * Chrome is still settling right after it). INCOGNITO re-blocks are
     * suppressed ONLY during this window. Once the close sequence completes and
     * we land on Home, a fresh incognito detection is a NEW session the user
     * opened — it must be blocked again even if the timestamp cooldown hasn't
     * expired yet. This closes the reported bypass: fast re-opening of incognito
     * within the old 4s cooldown slipped in and was never re-checked afterwards
     * (once past the NTP, incognito pages expose no detection signal).
     */
    private var incognitoCloseInProgress = false

    /** Whether a one-shot delayed Chrome re-scan is already scheduled. */
    private var chromeDelayedScanScheduled = false

    /** Timestamp of the last Chrome diagnostic tree dump (throttle). */
    private var lastChromeDiagTime = 0L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null
    private var googlePollingJob: Job? = null

    // ── Google Foreground Detection ────────────────────────────────

    /** Forces re-evaluation on the next poll cycle regardless of content equality. */
    private var forceGoogleReevaluate = false

    /** The last known foreground session for Google. Incremented on each foreground detection. */
    private var googleForegroundSession = 0L

    /** Timestamp of last Google foreground detection. */
    private var lastGoogleForegroundTime = 0L

    /** Whether we've performed the one-time diagnostic tree dump for the current session. */
    private var googleDiagnosticDumped = false

    /** Tracks whether a delayed re-scan has been scheduled for the current session. */
    private var googleDelayedRescanScheduled = false

    /** Prevents the fallback focus action from repeatedly changing Google UI state. */
    private var googleFocusAttempted = false

    /**
     * The Google results screen can temporarily stop exposing its search chip
     * while the page is being rebuilt. Keep the last observed query briefly so
     * that a content update cannot create a detection gap.
     */
    private var lastGoogleQuery: String? = null
    private var lastGoogleQueryTime = 0L

    // Last tab chip the user tapped in the Google app's WebView tab bar, plus
    // the query that was active at tap time. The WebView exposes ALL tab chips
    // with no selected/checked state, so the chip the user actually tapped is
    // the only reliable active-tab signal (applied in prepareGoogleSnapshot).
    private var lastGoogleTabTap: String? = null
    private var lastGoogleTabTapQuery: String? = null

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = BlockRepository(applicationContext)
        keywordMatcher = KeywordMatcher(repository)
        // Separate test-only keyword list for the YouTube-in-Chrome Shorts
        // experiment — never touches the normal blocked keywords. The
        // coordinator matches ONLY against this list with its own simple
        // matcher (the normal KeywordMatcher is deliberately not used).
        val testKeywordRepository = YoutubeTestKeywordRepository(applicationContext)
        youtubeChromeTest = YouTubeChromeTestCoordinator(this, repository, testKeywordRepository)
        // Shared-backend channel blocking (best-effort; a dead backend keeps
        // the existing keyword-only protection — never blocks on the network).
        val channelBlockRepository = ChannelBlockRepository(applicationContext)
        longVideoBlock = LongVideoBlockCoordinator(this, repository, keywordMatcher, channelBlockRepository)
        Log.i(TAG, "UrlBlockerService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "UrlBlockerService connected and running")
        Log.i(com.muddassir.clearview.extractor.GoogleAppUrlExtractor.TAG, "Accessibility Service connected")
    }

    override fun onInterrupt() {
        Log.w(TAG, "UrlBlockerService interrupted")
        stopPolling()
        stopGooglePolling()
        youtubeChromeTest?.stop()
        longVideoBlock?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        youtubeChromeTest?.stop()
        longVideoBlock?.stop()
        instance = null
        Log.i(TAG, "UrlBlockerService destroyed")
    }

    // ── Event Handling ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Stage 1 experiment hook: the coordinator decides for itself whether
        // the toggle is on and whether the package/event matters. Cheap call.
        // The full event is passed so the coordinator can detect player clicks
        // (VIEW_CLICKED) for immediate re-enforcement of a blocked Short.
        youtubeChromeTest?.onAccessibilityEvent(event)

        // Long-video blocker hook (isolated from Shorts). Also cheap.
        longVideoBlock?.onAccessibilityEvent(event)

        // Debug: log every event for target packages so detection issues (why an
        // incognito window was / wasn't caught) can be diagnosed from logcat.
        // Fires on EVERY accessibility event from target apps — debug builds only.
        if (isDebugBuild && contentExtractor.isTargetPackage(packageName)) {
            Log.d(TAG, "ACCESSIBILITY_EVENT pkg=$packageName type=${eventTypeName(event.eventType)}")
        }

        // 1. Update foreground package tracking. Both window-level events are
        //    treated as signals that the active Chrome window may have changed
        //    (opening an incognito window/tab fires WINDOWS_CHANGED and/or
        //    WINDOW_STATE_CHANGED), so they reset the dedup cache to force a
        //    fresh evaluation on the next scan.
        val isWindowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (isWindowEvent) {
            if (currentForegroundPackage == packageName && contentExtractor.isTargetPackage(packageName)) {
                lastCheckedSnapshotId = null
                if (packageName == GOOGLE_PACKAGE) forceGoogleReevaluate = true
            }
            // For Chrome: window events fire exactly when a new tab/window opens
            // (including an incognito session). Inspect the tree immediately AND
            // schedule a delayed re-scan (~400ms) — Chrome may not have finished
            // rendering the incognito UI when the event arrives, so the first
            // scan can run against a still-transitional tree. Only Chrome-owned
            // events are considered, so chatty system/IME window changes are
            // ignored here.
            if (contentExtractor.isChromePackage(packageName) &&
                currentForegroundPackage == packageName) {
                serviceScope.launch {
                    evaluateCurrentState(packageName, event)
                }
                scheduleChromeDelayedScan(packageName)
                maybeDumpChromeTree(packageName, event.eventType)
            }
            handlePackageChange(packageName)
        }

        // 2. Ignore our own app (overlay). The service's own package is used
        //    (not a hardcoded constant) so the app keeps working under any
        //    applicationId — including the ".debug"-suffixed dev build that
        //    installs side-by-side with the Play Store release.
        if (currentForegroundPackage == packageName) {
            return
        }

        // 3. Process events from target apps immediately.
        //    Events provide the fastest detection (text changes, window changes).
        //    The dedicated Google polling (500ms) serves as a safety net for cases
        //    where no new events fire (e.g., reopening Google with blocked content).
        //
        //    Full-tree scans per event jank the main thread ("Skipped 41 frames").
        //    Throttle ALL event-driven scans to one per EVENT_SCAN_MIN_INTERVAL_MS:
        //    Chrome WebViews storm TYPE_WINDOW_CONTENT_CHANGED, and typing into
        //    Chrome's address bar / Google's search box storms TYPE_VIEW_TEXT_CHANGED
        //    (one event per keystroke, each previously triggering a full-tree
        //    extraction + match on the main thread). The 500ms poll loop and the
        //    immediate window-event path above still guarantee detection with the
        //    same cadence as before — this only bounds CPU between polls, it never
        //    skips a state the poll would catch.
        if (contentExtractor.isTargetPackage(packageName)) {
            val now = System.currentTimeMillis()
            if (now - lastEventScanAt >= EVENT_SCAN_MIN_INTERVAL_MS) {
                lastEventScanAt = now
                evaluateCurrentState(packageName, event)
            }
        }

        // 4. Google app tab-bar taps: the WebView exposes the tab chips with no
        //    selected/checked state, so the chip the user tapped is the only
        //    reliable active-tab signal. Record it (with the query active at tap
        //    time) so the next scan's block check uses it.
        if (packageName == GOOGLE_PACKAGE &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED)
        ) {
            // event.source is owned by the caller and must be recycled.
            val source = try { event.source } catch (e: Exception) { null }
            val eventText = event.text.takeIf { it.isNotEmpty() }?.joinToString(" ")
            var tappedLabel: String? = null
            var sourceClass: String? = null
            var sourceViewId: String? = null
            var sourceText: String? = null
            var sourceDesc: String? = null
            try {
                sourceClass = source?.className?.toString()
                sourceViewId = source?.viewIdResourceName
                sourceText = source?.text?.toString()
                sourceDesc = source?.contentDescription?.toString()
                tappedLabel = ContentExtractor.googleTabFromLabel(sourceText)
                    ?: ContentExtractor.googleTabFromLabel(sourceDesc)
                    ?: ContentExtractor.googleTabFromLabel(eventText)
                    ?: ContentExtractor.googleTabFromLabel(event.contentDescription?.toString())
                // The click can land on a container whose child holds the chip
                // label (WebView HTML chip rows). Scan immediate children.
                if (tappedLabel == null) {
                    val childCount = (try { source?.childCount } catch (e: Exception) { 0 }) ?: 0
                    for (i in 0 until childCount.coerceAtMost(8)) {
                        val child = try { source?.getChild(i) } catch (e: Exception) { null } ?: continue
                        try {
                            tappedLabel = ContentExtractor.googleTabFromLabel(
                                child.text?.toString() ?: child.contentDescription?.toString()
                            )
                            if (tappedLabel != null) break
                        } finally {
                            try { child.recycle() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore — not a tab tap
            } finally {
                try { source?.recycle() } catch (e: Exception) {}
            }
            // Diagnostic: shows exactly what a Google VIEW_CLICKED/VIEW_SELECTED
            // event carries (source class / viewId / text / desc / event.text),
            // so a missing tap record is debuggable from logcat — the chips are
            // WebView HTML and the event source must expose the chip label for
            // tap detection.
            Log.d(
                TAG,
                "GOOGLE_CLICK_SOURCE type=${eventTypeName(event.eventType)} class=$sourceClass " +
                    "viewId=$sourceViewId text=${if (sourceText != null) "\"$sourceText\"" else "null"} " +
                    "desc=${if (sourceDesc != null) "\"$sourceDesc\"" else "null"} " +
                    "eventText=${if (eventText != null) "\"$eventText\"" else "null"} tabLabel=$tappedLabel"
            )
            // Only meaningful when a search query is active — otherwise the tap
            // would be cleared moments later by the query-change reset anyway.
            if (tappedLabel != null && lastGoogleQuery != null) {
                lastGoogleTabTap = tappedLabel
                lastGoogleTabTapQuery = lastGoogleQuery
                Log.i(TAG, "GOOGLE_TAB_TAP label=$tappedLabel query=${lastGoogleQuery}")
            }
        }
    }

    // ── Package Change Handling ────────────────────────────────────

    private fun handlePackageChange(newPackage: String) {
        if (currentForegroundPackage != newPackage) {
            Log.d(TAG, "Foreground package changed: $currentForegroundPackage -> $newPackage")

            // Stage 1 experiment hook: track the foreground package so the
            // coordinator only runs while Chrome is the active app.
            youtubeChromeTest?.onForegroundPackageChanged(newPackage)

            // Long-video blocker hook (isolated from Shorts).
            longVideoBlock?.onForegroundPackageChanged(newPackage)

            if (newPackage == packageName) {
                // We reached the block overlay
                blockingState = BlockingState.OVERLAY_ACTIVE
                stopPolling()
                stopGooglePolling()
                currentForegroundPackage = newPackage
                return
            }

            if (currentForegroundPackage == packageName) {
                blockingState = BlockingState.NORMAL
                lastCheckedSnapshotId = null
                lastBlockedResult = null
                lastGoogleQuery = null
                lastGoogleQueryTime = 0L
                lastGoogleTabTap = null
                lastGoogleTabTapQuery = null
            }

            if (contentExtractor.isGooglePackage(newPackage)) {
                // ── GOOGLE FOREGROUND DETECTED ──────────────────────────
                googleForegroundSession++
                lastGoogleForegroundTime = System.currentTimeMillis()
                forceGoogleReevaluate = true
                lastCheckedSnapshotId = null

                Log.i(TAG, "GOOGLE_FOREGROUND_DETECTED (session=$googleForegroundSession, forceReeval=true)")
                Log.i(TAG, "GOOGLE_FORCED_RECHECK")

                // Reset diagnostic flag and delayed-rescan flag for this new session
                googleDiagnosticDumped = false
                googleDelayedRescanScheduled = false
                googleFocusAttempted = false
                lastGoogleQuery = null
                lastGoogleQueryTime = 0L
                lastGoogleTabTap = null
                lastGoogleTabTapQuery = null

                // Stop any existing Google polling to ensure only ONE polling job
                stopGooglePolling()
                stopPolling()

                // Start dedicated Google polling (handles both monitoring and safe-state checks)
                // This is the ONLY polling job when Google is foreground.
                startGooglePolling()

                // Immediately perform a forced scan (synchronous — called from main-thread event handler)
                Log.i(TAG, "GOOGLE_INITIAL_SCAN_STARTED")
                forceEvaluateGoogle()
            } else if (contentExtractor.isYouTubePackage(newPackage)) {
                // ── YOUTUBE APP FOREGROUND ──────────────────────────────
                lastCheckedSnapshotId = null
                Log.i(TAG, "YOUTUBE_FOREGROUND_DETECTED")
                // Restart polling at the fast cadence — an embedded-browser poll
                // (1500ms) may still be active from the previous app.
                stopPolling()
                startPolling(newPackage)
            } else if (contentExtractor.isTargetPackage(newPackage)) {
                // ── CHROME (or other target) FOREGROUND ──────────────────
                lastCheckedSnapshotId = null
                // Restart polling at the fast cadence — an embedded-browser poll
                // (1500ms) may still be active from the previous app.
                stopPolling()
                startPolling(newPackage)
                // Immediate scan — same as Google, don't wait 500ms for first poll
                serviceScope.launch {
                    evaluateCurrentState(newPackage, null)
                }
            } else {
                // ── NON-TARGET APP ───────────────────────────────────────
                stopPolling()
                stopGooglePolling()
                lastCheckedSnapshotId = null
                blockingState = BlockingState.NORMAL
                forceGoogleReevaluate = false
                // Chrome is no longer foreground; a stale delayed re-scan would
                // be a no-op anyway (it checks currentForegroundPackage), but
                // reset the flag so a future Chrome session can schedule anew.
                chromeDelayedScanScheduled = false

                // Embedded-browser coverage: many apps (Twitter, Facebook, news,
                // in-app browsers) render websites in a WebView while the package
                // stays the host app. If the foreground app shows a WebView, treat
                // it as a monitored target so blocked sites opened inside any app
                // are still caught. ContentExtractor only extracts a URL when a
                // strong in-app-browser signal exists, so this cannot cause false
                // blocks on ordinary app UI.
                //
                // IME/keyboard packages are NOT browsers: Gboard exposes WebView
                // nodes for its emoji/GIF search, which triggered a false
                // EMBEDDED_BROWSER_DETECTED (observed on-device) and pointless
                // polling of the keyboard. Skip any package whose id looks like
                // an input-method. Same for launcher/system UI packages
                // (com.motorola.launcher3, com.android.systemui — observed on
                // this device): their app-drawer / recents surfaces expose
                // WebView-like nodes and would otherwise be polled forever.
                if (isNonBrowserSystemPackage(newPackage)) {
                    Log.d(TAG, "EMBEDDED_PROBE_SKIPPED: non-browser system package $newPackage")
                } else {
                    val probeRoot = try { rootInActiveWindow } catch (e: Exception) { null }
                    if (probeRoot != null) {
                        try {
                            if (contentExtractor.hasWebView(probeRoot)) {
                                Log.i(TAG, "EMBEDDED_BROWSER_DETECTED in $newPackage — monitoring in-app WebView")
                                lastCheckedSnapshotId = null
                                startPolling(newPackage, EMBEDDED_POLLING_INTERVAL_MS)
                                serviceScope.launch {
                                    evaluateCurrentState(newPackage, null)
                                }
                            }
                        } finally {
                            try { probeRoot.recycle() } catch (e: Exception) {}
                        }
                    }
                }
            }

            currentForegroundPackage = newPackage
        }
    }

    /**
     * Packages that are never browsers even though they may expose
     * WebView-like accessibility nodes (launchers, system UI, keyguard,
     * settings, IMEs). The embedded-browser probe skips them so it doesn't
     * start pointless polling (observed on-device: com.motorola.launcher3
     * and com.android.systemui both triggered EMBEDDED_BROWSER_DETECTED).
     */
    private fun isNonBrowserSystemPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        // `launcher` covers the known launcher ids (com.motorola.launcher3,
        // com.google.android.apps.nexuslauncher, com.android.launcher3, ...).
        return lower.contains("inputmethod") ||
            lower.contains("launcher") ||
            lower.contains("systemui") ||
            lower.contains("keyguard") ||
            lower == "android" ||
            lower == "com.android.settings"
    }

    // ── Google-Specific Polling ────────────────────────────────────

    /**
     * Dedicated Google polling job. This is the ONLY polling coroutine
     * active when Google is in the foreground. It handles:
     * - Normal monitoring (state = NORMAL) via forceEvaluateGoogle()
     * - Safe-state checking (state = WAITING_FOR_SAFE_STATE)
     *
     * When Google leaves foreground, this job is cancelled by
     * handlePackageChange or stopGooglePolling().
     */
    private fun startGooglePolling() {
        if (googlePollingJob?.isActive == true) return

        Log.i(TAG, "GOOGLE_POLLING_STARTED (interval=${GOOGLE_POLLING_INTERVAL_MS}ms)")
        googlePollingJob = serviceScope.launch {
            while (isActive) {
                if (currentForegroundPackage != GOOGLE_PACKAGE) {
                    // Google left foreground; this poller should be cancelled externally
                    // but just in case, skip work
                    delay(GOOGLE_POLLING_INTERVAL_MS)
                    continue
                }

                when (blockingState) {
                    BlockingState.NORMAL -> {
                        forceEvaluateGoogle()
                    }
                    BlockingState.WAITING_FOR_SAFE_STATE -> {
                        // Check safe state during the Google blocking sequence
                        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null }
                        if (rootNode != null) {
                            try {
                                val snapshot = contentExtractor.extract(
                                    GOOGLE_PACKAGE,
                                    rootNode,
                                    null,
                                    activeWindowTitle()
                                )
                                handleSafeStateCheck(snapshot, GOOGLE_PACKAGE)
                            } finally {
                                try { rootNode.recycle() } catch (e: Exception) {}
                            }
                        }
                    }
                    BlockingState.CLEARING_TARGET, BlockingState.RETURNING_HOME -> {
                        // In clearing or returning, wait
                    }
                    BlockingState.OVERLAY_ACTIVE -> {
                        // The overlay owns the foreground until the user leaves it.
                    }
                }
                delay(GOOGLE_POLLING_INTERVAL_MS)
            }
        }
    }

    private fun stopGooglePolling() {
        if (googlePollingJob?.isActive == true) {
            Log.d(TAG, "GOOGLE_POLLING_STOPPED")
            googlePollingJob?.cancel()
            googlePollingJob = null
        }
    }

    /**
     * Force a fresh evaluation of Google's current state.
     * This always performs a fresh Accessibility tree scan and does NOT
     * skip based on lastCheckedSnapshotId.
     */
    private fun forceEvaluateGoogle() {
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "GOOGLE_ROOT_FETCH_FAILED: ${e.message}")
            null
        } ?: return

        try {
            Log.i(TAG, "GOOGLE_SCAN_STARTED")
            Log.i(TAG, "GOOGLE_ROOT_FOUND")

            // One-time diagnostic tree dump per Google foreground session
            if (!googleDiagnosticDumped) {
                googleDiagnosticDumped = true
                diagnoseGoogleTree(rootNode)
            }

            // ── Get window title from AccessibilityWindowInfo ─────────────
            // Google search results window titles look like:
            //   "blockedkeyword - Google Search"
            //   "something - Google"
            // ContentExtractor.extractQueryFromGoogleTitle() can parse these.
            // This is the MOST RELIABLE way to detect the search query on
            // the results page when the search box is not focused.
            val windowTitle = try {
                windows?.firstOrNull { it.isActive }?.title?.toString()
            } catch (e: Exception) {
                null
            }
            if (windowTitle != null) {
                Log.i(TAG, "GOOGLE_WINDOW_TITLE=$windowTitle")
            } else {
                Log.d(TAG, "GOOGLE_WINDOW_TITLE=null")
            }

            val extractedSnapshot = contentExtractor.extract(
                GOOGLE_PACKAGE,
                rootNode,
                null,
                windowTitle
            )
            val snapshot = prepareGoogleSnapshot(extractedSnapshot)

            Log.i(TAG, "GOOGLE_CONTENT_EXTRACTED: url=${snapshot.url}, query=${snapshot.query}, title=${snapshot.title}")
            Log.i(TAG, "GOOGLE_EXTRACTED_QUERY=${snapshot.query ?: "null"}")
            Log.i(TAG, "GOOGLE_CURRENT_STATE: ${snapshot.toIdentityString()}")

            // Check against keywords
            var blocked = false
            if (snapshot.url != null || snapshot.query != null || snapshot.title != null) {
                // Log which Google tab is being used (Images, Videos, etc.)
                // Prefer the tree-detected tab chip; fall back to URL parsing
                // (the Google app rarely exposes the search URL).
                val googleTab = snapshot.googleTab
                    ?: contentExtractor.isGoogleTabSearch(snapshot.url)
                if (googleTab != null) {
                    Log.i(TAG, "GOOGLE_${googleTab.uppercase()}_TAB_DETECTED (tab=${snapshot.googleTab ?: "url"}, url=${snapshot.url})")
                }

                val result = keywordMatcher.check(snapshot, GOOGLE_PACKAGE)

                if (result is MatchResult.Blocked) {
                    blocked = true
                    val tabSuffix = if (googleTab != null) " in $googleTab tab" else ""
                    Log.w(TAG, "GOOGLE_BLOCKED_MATCH: matched=${result.matchedItem} (${result.matchType}) source=${result.matchSource}$tabSuffix")
                    Log.i(TAG, "GOOGLE_MATCHED_KEYWORD=${result.matchedItem}")
                    Log.i(TAG, "GOOGLE_BLOCK_DECISION=true")
                    lastBlockedResult = result
                    forceGoogleReevaluate = false

                    // Initiate blocking sequence
                    initiateBlockingSequence(rootNode, GOOGLE_PACKAGE)
                } else {
                    Log.d(TAG, "GOOGLE_BLOCKED=false")
                    Log.i(TAG, "GOOGLE_BLOCK_DECISION=false")
                }
            } else {
                Log.d(TAG, "GOOGLE_NO_CONTENT_EXTRACTED")
                Log.i(TAG, "GOOGLE_BLOCK_DECISION=false")
            }

            // If no block occurred and query was not found, try focusing the search box.
            // This triggers a TYPE_VIEW_FOCUSED event that carries the query text.
            // The event handler will process it on the next cycle.
            if (!blocked && snapshot.query == null && !googleFocusAttempted) {
                googleFocusAttempted = true
                val focused = tryFocusGoogleSearchBox()
                Log.i(TAG, "GOOGLE_FOCUS_ATTEMPT: $focused (triggered because query=null)")
            }

            // ── Delayed Re-scan Safety Net ───────────────────────────────
            // If no block occurred and no query was found, schedule a one-shot
            // delayed re-scan. This handles cases where the accessibility tree
            // or window title hasn't fully populated yet (e.g., page still loading).
            if (!blocked && snapshot.query == null && snapshot.title == null && !googleDelayedRescanScheduled) {
                googleDelayedRescanScheduled = true
                Log.i(TAG, "GOOGLE_DELAYED_RESCAN_SCHEDULED")
                serviceScope.launch {
                    delay(700L)
                    if (currentForegroundPackage == GOOGLE_PACKAGE && blockingState == BlockingState.NORMAL) {
                        Log.i(TAG, "GOOGLE_DELAYED_RESCAN_EXECUTING")
                        // Do NOT reset googleDelayedRescanScheduled here to prevent an
                        // infinite re-scan loop. The regular 500ms polling loop provides
                        // continuous coverage. This delayed scan is a one-shot safety net.
                        forceEvaluateGoogle()
                    }
                }
            }

            // Reset force flag after evaluation
            forceGoogleReevaluate = false
        } catch (e: Exception) {
            Log.e(TAG, "GOOGLE_EVALUATION_ERROR: ${e.message}", e)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * As a last resort, programmatically focus the Google search box.
     * This triggers a TYPE_VIEW_FOCUSED accessibility event which carries
     * the search query in event.text. Our existing event handler
     * (onAccessibilityEvent → evaluateCurrentState) will then extract
     * the query and detect the block on the next cycle.
     *
     * NOTE: This may cause the keyboard to briefly appear.
     * Returns true if the focus action was sent successfully.
     */
    private fun tryFocusGoogleSearchBox(): Boolean {
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) { null } ?: return false

        try {
            // Find the Google search box — any EditText or search-related view
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(AccessibilityNodeInfo.obtain(rootNode))
            var depth = 0

            while (queue.isNotEmpty() && depth < 30) {
                val node = queue.removeFirst()
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                // Look for the search input field
                val isSearchField = className.contains("EditText") ||
                        className.contains("AutoComplete") ||
                        viewId.contains("search") ||
                        viewId.contains("omnibox")

                if (isSearchField) {
                    // Already focused? Skip
                    if (node.isFocused) {
                        Log.d(TAG, "GOOGLE_FOCUS_ATTEMPT: search box already focused")
                        node.recycle()
                        return false
                    }

                    // Send ACTION_FOCUS to trigger TYPE_VIEW_FOCUSED event
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    if (success) {
                        Log.i(TAG, "GOOGLE_FOCUS_ATTEMPT: focus sent successfully (className=$className, viewId=$viewId)")
                        node.recycle()
                        return true
                    }
                    Log.w(TAG, "GOOGLE_FOCUS_ATTEMPT: ACTION_FOCUS failed on $className ($viewId)")
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
                node.recycle()
                depth++
            }
        } catch (e: Exception) {
            Log.e(TAG, "GOOGLE_FOCUS_ATTEMPT: error: ${e.message}")
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
        Log.w(TAG, "GOOGLE_FOCUS_ATTEMPT: search box not found")
        return false
    }

    // ── General Polling ────────────────────────────────────────────

    private fun startPolling(packageName: String, intervalMs: Long = POLLING_INTERVAL_MS) {
        if (pollingJob?.isActive == true) return

        Log.d(TAG, "Starting polling for $packageName (interval=${intervalMs}ms)")
        pollingJob = serviceScope.launch {
            while (isActive) {
                val pkg = currentForegroundPackage ?: packageName
                if (pkg != packageName && (blockingState == BlockingState.NORMAL ||
                            blockingState == BlockingState.WAITING_FOR_SAFE_STATE)) {
                    evaluateCurrentState(pkg, null)
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopPolling() {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "Stopping polling")
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    // ── State Evaluation ───────────────────────────────────────────

    private fun evaluateCurrentState(packageName: String, event: AccessibilityEvent?) {
        // If we're in a blocking sequence, don't re-evaluate (let the state machine handle it)
        if (blockingState == BlockingState.CLEARING_TARGET ||
            blockingState == BlockingState.RETURNING_HOME ||
            blockingState == BlockingState.OVERLAY_ACTIVE) {
            return
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        try {
            // Get window title from AccessibilityWindowInfo
            // This is especially important for Google search results pages
            // where the title contains the search query (e.g., "keyword - Google Search")
            val windowTitle = try {
                windows?.firstOrNull { it.isActive }?.title?.toString()
            } catch (e: Exception) {
                null
            }

            // Incognito detection is a permanent built-in feature: the scan
            // always runs for Chrome packages.
            val extractedSnapshot = contentExtractor.extract(
                packageName, rootNode, event, windowTitle
            )
            val snapshot = if (packageName == GOOGLE_PACKAGE) {
                prepareGoogleSnapshot(extractedSnapshot)
            } else {
                extractedSnapshot
            }
            val snapshotId = snapshot.toIdentityString()

            // Debug: report the incognito verdict on every scan for Chrome so
            // logcat shows exactly what was detected (or why nothing matched),
            // including the active window id for multi-window Chrome sessions.
            // Runs on every 500ms poll + every event-driven scan — debug builds only.
            if (isDebugBuild && contentExtractor.isChromePackage(packageName)) {
                val windowId = try { windows?.firstOrNull { it.isActive }?.id } catch (e: Exception) { null }
                Log.d(TAG, "CHROME_SCAN pkg=$packageName winId=$windowId incognito=${snapshot.incognito} url=${snapshot.url ?: "null"} title=${snapshot.title ?: "null"} eventType=${event?.let { eventTypeName(it.eventType) } ?: "poll"}")
            }

            // ── YouTube SPA navigation detection in Chrome ────────────
            // YouTube's SPA changes the URL without triggering a window event
            // and often without updating the window title. When the URL changes
            // (even if the identity string didn't), force re-evaluation.
            var youtubeUrlChanged = false
            if (packageName in ContentExtractor.CHROME_PACKAGES &&
                ContentExtractor.isYouTubeDomain(snapshot.url)
            ) {
                val currentUrl = snapshot.url
                if (currentUrl != lastYouTubeUrl) {
                    youtubeUrlChanged = lastYouTubeUrl != null
                    lastYouTubeUrl = currentUrl
                }
            } else {
                // Not a YouTube Chrome page: reset the tracker so a future
                // YouTube navigation is detected as a change.
                lastYouTubeUrl = null
            }

            // ── Determine if we should evaluate ─────────────────────────
            val shouldEvaluate = when {
                // YouTube SPA navigation: URL changed — force re-evaluation
                // even if the snapshot identity didn't change (window title
                // may be stale).
                youtubeUrlChanged -> {
                    lastCheckedSnapshotId = null
                    true
                }
                // Google forced re-evaluation (from handlePackageChange)
                packageName == GOOGLE_PACKAGE && forceGoogleReevaluate -> {
                    forceGoogleReevaluate = false
                    true
                }
                // Normal case: content changed
                snapshotId != lastCheckedSnapshotId -> {
                    lastCheckedSnapshotId = snapshotId
                    true
                }
                else -> false
            }

            if (shouldEvaluate) {
                when (blockingState) {
                    BlockingState.NORMAL -> {
                        // incognito snapshots may carry no URL/query/title (e.g. the
                        // incognito new-tab page), so evaluate them too.
                        if (snapshot.url != null || snapshot.query != null ||
                            snapshot.title != null || snapshot.incognito
                        ) {
                            val result = keywordMatcher.check(snapshot, packageName)

                            if (result is MatchResult.Blocked) {
                                // Loop guard: while the incognito cooldown is
                                // active, ignore incognito blocks (the closing
                                // sequence is still settling / Chrome may still
                                // show incognito chrome while returning to a
                                // normal tab). Normal URL/keyword blocks are NOT
                                // affected by this guard.
                                if (result.matchType == MatchType.INCOGNITO &&
                                    incognitoCloseInProgress &&
                                    System.currentTimeMillis() < incognitoBlockCooldownUntil
                                ) {
                                    Log.d(TAG, "INCOGNITO_BLOCK_SUPPRESSED (close sequence in progress until $incognitoBlockCooldownUntil) — still settling after previous block")
                                    // Clear the dedup cache so that once the cooldown
                                    // expires the very next poll re-evaluates and
                                    // re-blocks if the user is still in incognito
                                    // (otherwise a static incognito tree would stay
                                    // cached as "already checked" and never re-fire).
                                    lastCheckedSnapshotId = null
                                    return
                                }

                                // ── LONG-VIDEO EXPERIMENT: DELEGATE WATCH-PAGE TITLE BLOCKS ──
                                // While the YouTube-in-Chrome experiment is on, the
                                // coordinator owns LONG-video blocking:
                                //  - a TITLE match on a /watch page is delegated to
                                //    LongVideoBlockCoordinator (pause + dark overlay +
                                //    "Go to YouTube Home") — the legacy full-block overlay
                                //    must not fire for the same video or the two flows
                                //    would fight over it.
                                //  - FEED matches are NEVER delegated: the coordinator only
                                //    handles watch-page content, so a delegated feed match
                                //    would be silently dropped (thumbnails on the home page,
                                //    search results, and watch-page recommendations would
                                //    never be blocked).
                                // Domain rules, incognito and search-query blocks are
                                // untouched.
                                // In incognito Chrome the URL is hidden from the
                                // accessibility tree (snapshot.url == null), but the
                                // window title still carries " - YouTube".
                                val onYouTubeInChrome = ContentExtractor.isYouTubeDomain(snapshot.url) ||
                                    ContentExtractor.isYouTubeTitle(snapshot.title)
                                val delegateLongVideoBlock = longVideoBlock != null &&
                                    repository.youTubeChromeTest &&
                                    onYouTubeInChrome &&
                                    result.matchSource == MatchSource.TITLE &&
                                    (snapshot.url?.contains("/watch") == true ||
                                        snapshot.title?.contains(" - YouTube", ignoreCase = true) == true)
                                if (delegateLongVideoBlock) {
                                    Log.i(TAG, "LONG_VIDEO_LEGACY_BLOCK_SUPPRESSED url=${snapshot.url} source=${result.matchSource} keyword=${result.matchedItem} — coordinator owns long-video blocking")
                                } else {
                                    // ── ALL BLOCKS ARE FULL BLOCKS ──
                                    // Every match — whether from a URL, search query,
                                    // video title, or feed card — triggers the full
                                    // block overlay (BlockOverlayActivity). Feed cards
                                    // used to get a floating badge marker, but that
                                    // behavior has been removed: every blocked keyword
                                    // triggers a full block screen.
                                    Log.w(TAG, "BLOCK DETECTED in $packageName! Matched: ${result.matchedItem} (${result.matchType}) source=${result.matchSource}")
                                    lastBlockedResult = result
                                    initiateBlockingSequence(rootNode, packageName)
                                }
                            }
                        }
                    }
                    BlockingState.CLEARING_TARGET -> {
                        // Waiting for clearing actions to dispatch
                    }
                    BlockingState.WAITING_FOR_SAFE_STATE -> {
                        handleSafeStateCheck(snapshot, packageName)
                    }
                    BlockingState.RETURNING_HOME -> {
                        // In transit to home, ignore checks
                    }
                    BlockingState.OVERLAY_ACTIVE -> {
                        // The overlay owns the foreground until the user leaves it.
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating state: ${e.message}", e)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun activeWindowTitle(): String? = try {
        windows?.firstOrNull { it.isActive }?.title?.toString()
    } catch (e: Exception) {
        null
    }

    /** Human-readable name for an [AccessibilityEvent] type (for logcat). */
    private fun eventTypeName(type: Int): String = when (type) {
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

    // ── Chrome Incognito: Delayed Re-scan & Diagnostics ─────────────

    /**
     * One-shot delayed re-scan of Chrome's tree ~400ms after a window event.
     * Chrome can take a moment to render the incognito UI (new-tab heading,
     * toolbar badge, incognito view ids), so the immediate scan can run against
     * a still-transitional tree. This re-scan catches the rendered state and is
     * the safety net that makes DIRECT incognito opens (three-dot menu, keyboard
     * shortcut) detectable without relying on the tab switcher.
     */
    private fun scheduleChromeDelayedScan(packageName: String) {
        if (chromeDelayedScanScheduled) return
        chromeDelayedScanScheduled = true
        serviceScope.launch {
            delay(CHROME_DELAYED_SCAN_MS)
            chromeDelayedScanScheduled = false
            if (!isActive) return@launch
            if (currentForegroundPackage == packageName && blockingState == BlockingState.NORMAL) {
                Log.d(TAG, "CHROME_DELAYED_SCAN after window event for $packageName")
                lastCheckedSnapshotId = null
                evaluateCurrentState(packageName, null)
            }
        }
    }

    /**
     * Throttled diagnostic dump of Chrome's accessibility tree, triggered on
     * window events. Logs every informative node (text, contentDescription,
     * view id, class name, visibility) and marks any that mention "incognito",
     * so detection gaps can be diagnosed directly from logcat.
     */
    private fun maybeDumpChromeTree(packageName: String, eventType: Int) {
        if (!contentExtractor.isChromePackage(packageName)) return
        val now = System.currentTimeMillis()
        if (now - lastChromeDiagTime < CHROME_DIAG_INTERVAL_MS) return
        lastChromeDiagTime = now
        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            diagnoseChromeTree(rootNode, eventType)
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    private fun diagnoseChromeTree(rootNode: AccessibilityNodeInfo, eventType: Int) {
        val windowId = try { windows?.firstOrNull { it.isActive }?.id } catch (e: Exception) { null }
        Log.i(TAG, "CHROME_DIAG_START eventType=${eventTypeName(eventType)} winId=$windowId")
        var nodeCount = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        while (queue.isNotEmpty() && depth < 60 && nodeCount < 40) {
            val node = queue.removeFirst()
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null }
            val className = try { node.className?.toString() } catch (e: Exception) { null }
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }

            if (text != null || desc != null || viewId != null) {
                nodeCount++
                val haystack = ((text ?: "") + " " + (desc ?: "") + " " + (viewId ?: "") + " " + (className ?: ""))
                    .lowercase(Locale.ROOT)
                val marker = if (haystack.contains("incognito")) " <<< INC" else ""
                Log.i(TAG, "DIAG_CHROME_NODE#$nodeCount: cls=$className " +
                        "text=${if (text != null) "\"$text\"" else "null"} " +
                        "desc=${if (desc != null) "\"$desc\"" else "null"} " +
                        "vid=$viewId " +
                        "visible=$visible$marker")
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) queue.add(child)
            }
            depth++
        }
        Log.i(TAG, "CHROME_DIAG_END: scanned depth=$depth, logged=$nodeCount nodes")
    }

    private fun prepareGoogleSnapshot(snapshot: ContentSnapshot): ContentSnapshot {
        val observedQuery = snapshot.query?.trim().orEmpty()
        var result = snapshot
        if (observedQuery.isNotEmpty()) {
            // A genuinely new search query invalidates any previously tapped tab
            // (the user is back on the default All tab for the new search).
            if (lastGoogleQuery != observedQuery) {
                lastGoogleTabTap = null
                lastGoogleTabTapQuery = null
            }
            lastGoogleQuery = observedQuery
            lastGoogleQueryTime = System.currentTimeMillis()
        } else {
            val cachedQuery = lastGoogleQuery
            val cacheAge = System.currentTimeMillis() - lastGoogleQueryTime
            if (!cachedQuery.isNullOrBlank() && cacheAge in 0..GOOGLE_QUERY_CACHE_WINDOW_MS) {
                Log.d(TAG, "GOOGLE_QUERY_CACHE_REUSED ageMs=$cacheAge")
                result = result.copy(query = cachedQuery)
            }
        }

        // Active-tab resolution: the Google app's WebView exposes ALL tab chips
        // (All/Images/Videos/...) with NO selected/checked state, so the tree
        // cannot say which tab is active. The reliable signal is the chip the
        // user tapped (TYPE_VIEW_CLICKED/SELECTED from the WebView tab bar).
        // Apply it only when the current tree still contains tab chips
        // (result.googleTab != null — i.e. we're still on a results page) and
        // the query hasn't changed since the tap.
        val tapHint = if (lastGoogleTabTap != null && lastGoogleTabTapQuery != null &&
            lastGoogleTabTapQuery == lastGoogleQuery
        ) {
            lastGoogleTabTap
        } else {
            null
        }
        if (tapHint != null && result.googleTab != null && tapHint != result.googleTab) {
            Log.i(TAG, "GOOGLE_TAB_TAP_APPLIED label=$tapHint (chip scan guessed ${result.googleTab})")
            result = result.copy(googleTab = tapHint)
        }
        return result
    }

    private fun handleSafeStateCheck(snapshot: ContentSnapshot, packageName: String) {
        val result = keywordMatcher.check(snapshot, packageName)
        val confirmedSafe = result is MatchResult.Allowed && when {
            contentExtractor.isChromePackage(packageName) -> !snapshot.url.isNullOrBlank()
            contentExtractor.isYouTubePackage(packageName) -> {
                // YouTube safe state: both the in-app-browser URL and the video
                // title must differ from the blocked content (or be null,
                // meaning the user navigated away from the blocked page/video).
                val blockedItem = lastBlockedResult?.matchedItem?.lowercase()
                if (blockedItem == null) {
                    // Defensive: if lastBlockedResult is somehow null, wait for
                    // the timeout fallback rather than prematurely declaring safe.
                    false
                } else {
                    val urlSafe = snapshot.url?.lowercase()?.let { url ->
                        !url.contains(blockedItem)
                    } ?: true // null url means the in-app browser was closed
                    val titleSafe = snapshot.title?.lowercase()?.let { title ->
                        !title.contains(blockedItem)
                    } ?: true // null title means navigation away from the video
                    urlSafe && titleSafe
                }
            }
            else -> snapshot.url != null || (snapshot.query == null && snapshot.title != null)
        }

        if (confirmedSafe) {
            Log.i(TAG, "SAFE STATE DETECTED for $packageName")
            if (packageName == GOOGLE_PACKAGE) {
                Log.i(TAG, "GOOGLE_SAFE_STATE_CONFIRMED")
            } else if (contentExtractor.isYouTubePackage(packageName)) {
                Log.i(TAG, "YOUTUBE_SAFE_STATE_CONFIRMED")
            }
            transitionToHome(packageName)
        } else {
            Log.d(TAG, "Still waiting for safe state in $packageName...")
        }
    }

    // ── Blocking Sequence ──────────────────────────────────────────

    private fun initiateBlockingSequence(rootNode: AccessibilityNodeInfo, packageName: String) {
        // Loop guard: only NORMAL may START a blocking sequence. The event
        // handler, the delayed Chrome re-scan and the poll loop can all call
        // evaluateCurrentState/forceEvaluateGoogle close together; without this
        // guard two of them could each initiate a sequence and stack overlays
        // (the reported "block shows multiple times").
        if (blockingState != BlockingState.NORMAL) {
            Log.d(TAG, "BLOCK_INITIATION_SKIPPED state=$blockingState (already blocking)")
            return
        }
        Log.i(TAG, "BLOCKING STARTED for Target App: $packageName")
        blockingState = BlockingState.CLEARING_TARGET

        if (lastBlockedResult?.matchType == MatchType.INCOGNITO) {
            // Arm the cooldown BEFORE the closing sequence starts: the Back
            // presses continuously change the tree, and without the lock the
            // next scan (which may still see incognito chrome while Chrome
            // settles) would re-initiate the block in a loop.
            incognitoBlockCooldownUntil = System.currentTimeMillis() + INCOGNITO_BLOCK_COOLDOWN_MS
            Log.i(TAG, "INCOGNITO_BLOCK_ARMED cooldownUntil=$incognitoBlockCooldownUntil")
            // Mark our close sequence as in progress: re-blocks are suppressed
            // only while this flag is set (see the suppression check in
            // evaluateCurrentState). Cleared when the sequence completes.
            incognitoCloseInProgress = true

            // Incognito must be killed, not just navigated away from: the
            // adaptive Back sequence closes ALL incognito tabs, then the overlay
            // appears and the user is returned to NORMAL Chrome automatically.
            // Chrome is only relaunched after incognito is confirmed closed
            // (Chrome never restores an incognito session across an exit), and
            // the cooldown suppresses any stray re-block while Chrome settles.
            Log.i(TAG, "INCOGNITO_BLOCK: closing incognito tabs and returning to normal Chrome")
            closeIncognitoTabsAndBlock(packageName)
            return
        }

        if (packageName == GOOGLE_PACKAGE) {
            Log.i(TAG, "GOOGLE_SAFE_STATE_CLEAR_STARTED")
            clearGoogleApp(rootNode)
            blockingState = BlockingState.WAITING_FOR_SAFE_STATE
            armSafeStateTimeout(packageName)
        } else if (contentExtractor.isChromePackage(packageName)) {
            // Normal (non-incognito) Chrome block: redirect the CURRENT tab to
            // a safe page (Google) instead of closing tabs — closing Chrome's
            // tabs is unreliable on many devices (the tab is restored on the
            // next launch anyway). Redirecting the tab itself leaves Chrome
            // with a clean, safe tab: when the overlay is dismissed and Chrome
            // is reopened, the tab is still there but its content is Google.
            redirectChromeTabAndBlock(packageName)
        } else {
            clearTargetApp(rootNode, packageName)
            blockingState = BlockingState.WAITING_FOR_SAFE_STATE
            armSafeStateTimeout(packageName)
        }
    }

    /**
     * Arm the failsafe timeout for the standard blocking sequence: if the
     * target doesn't reach a safe state within [SAFE_STATE_TIMEOUT_MS], force
     * a transition (safe intent + Home).
     */
    private fun armSafeStateTimeout(packageName: String) {
        safeStateTimeoutJob?.cancel()
        safeStateTimeoutJob = serviceScope.launch {
            delay(SAFE_STATE_TIMEOUT_MS)
            if (blockingState == BlockingState.WAITING_FOR_SAFE_STATE) {
                Log.w(TAG, "SAFE STATE NOT CONFIRMED within timeout. USING FALLBACK.")
                if (packageName == GOOGLE_PACKAGE) {
                    Log.w(TAG, "GOOGLE_SAFE_STATE_TIMEOUT - using fallback")
                }
                // Navigate to safe intent
                navigateToSafeIntent(packageName)
                transitionToHome(packageName)
            }
        }
    }

    /**
     * Clear the Google app to return it to a clean state.
     *
     * Strategy:
     * 1. Find and focus text-editable nodes including custom search box views
     * 2. Clear their text to empty
     * 3. Navigate back to clear search results
     * 4. The fallback timeout in initiateBlockingSequence handles launching a safe intent
     */
    private fun clearGoogleApp(rootNode: AccessibilityNodeInfo) {
        Log.d(TAG, "CLEARING GOOGLE APP STATE")
        var cleared = false

        // 1. Find ALL editable/focusable views that contain text (not just EditText)
        //    Google's search box on results page may be a custom view class.
        val textNodes = findTextEditableNodes(rootNode)
        for (node in textNodes) {
            try {
                // Focus the node
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // Clear text using SET_TEXT with empty string
                val clearArgs = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        ""
                    )
                }
                val clearSuccess = node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    clearArgs
                )
                if (clearSuccess) {
                    Log.i(TAG, "GOOGLE_SEARCH_FIELD_FOUND - text cleared")
                    cleared = true
                }

                // Also try ACTION_CUT as a secondary clear
                node.performAction(AccessibilityNodeInfo.ACTION_CUT)

            } catch (e: Exception) {
                Log.e(TAG, "GOOGLE_SEARCH_FIELD_ERROR: ${e.message}")
            } finally {
                node.recycle()
            }
        }

        if (cleared) {
            Log.i(TAG, "GOOGLE_SEARCH_FIELD_CLEARED")
        } else {
            Log.w(TAG, "GOOGLE_SEARCH_FIELD_NOT_FOUND - cannot clear search box")
        }

        // 2. Navigate back to exit the search results page
        Log.i(TAG, "GOOGLE_SAFE_NAVIGATION_STARTED")
        val backResult = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "GOOGLE_GLOBAL_ACTION_BACK: $backResult")

        // 3. Second back attempt (let the event loop handle it)
        val backResult2 = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "GOOGLE_GLOBAL_ACTION_BACK (2nd): $backResult2")

        if (!cleared && !backResult && !backResult2) {
            Log.w(TAG, "GOOGLE_CLEAR_FAILED - both clear and back actions failed")
        }
    }

    /**
     * One-time diagnostic dump of the Google app's accessibility tree.
     * Logs key info about the first ~50 nodes to help identify where the
     * search query exists (or doesn't) in the unfocused/results state.
     */
    private fun diagnoseGoogleTree(rootNode: AccessibilityNodeInfo) {
        Log.i(TAG, "GOOGLE_DIAGNOSTIC_START")
        var nodeCount = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < 70 && nodeCount < 50) {
            val node = queue.removeFirst()
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null }
            val className = try { node.className?.toString() } catch (e: Exception) { null }
            val isFocused = try { node.isFocused } catch (e: Exception) { false }
            val isVisible = try { node.isVisibleToUser } catch (e: Exception) { false }

            // Only log nodes with useful info (skip empty containers)
            if (text != null || desc != null || viewId != null ||
                (className != null && (className.contains("EditText") || className.contains("TextView")))) {
                nodeCount++
                Log.i(TAG, "DIAG_NODE#$nodeCount: cls=$className " +
                        "text=${if (text != null) "\"$text\"" else "null"} " +
                        "desc=${if (desc != null) "\"$desc\"" else "null"} " +
                        "vid=$viewId " +
                        "focused=$isFocused " +
                        "visible=$isVisible")
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) queue.add(child)
            }
            depth++
        }

        Log.i(TAG, "GOOGLE_DIAGNOSTIC_END: scanned depth=$depth, logged=$nodeCount nodes")
    }

    /**
     * Find the Google search box or any editable text field, using multiple strategies
     * in a single traversal to avoid traversing the tree twice.
     *
     * Strategies (in priority order):
     * 1. Standard EditText class name
     * 2. Any class containing "EditText", "TextField", or "AutoComplete"
     * 3. Any view with an ID containing "search", "omnibox", or "url_bar"
     * 4. Any view with content description matching "search" (case-insensitive)
     */
    private fun findTextEditableNodes(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val seenIds = mutableSetOf<Int>() // avoid returning the same node twice
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))

        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

            // Multiple strategies to detect an editable/search text field
            val isEditable =
                // Strategy 1: Standard EditText
                className == "android.widget.EditText" ||
                // Strategy 2: Alternative text field class names
                className.contains("EditText") ||
                className.contains("TextField") ||
                className.contains("AutoComplete") ||
                // Strategy 3: View ID-based detection (Google search box, Chrome URL bar)
                viewId.contains("search") ||
                viewId.contains("omnibox") ||
                viewId.contains("url_bar") ||
                viewId.contains("query") ||
                // Strategy 4: Content description suggests a search field
                contentDesc.contains("search")

            if (isEditable) {
                // Avoid duplicates by tracking hashCode
                if (seenIds.add(node.hashCode())) {
                    results.add(AccessibilityNodeInfo.obtain(node))
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
            node.recycle()
            depth++
        }
        return results
    }

    /**
     * Incognito fast path (simple, clean behavior per user request):
     * close ALL incognito tabs, then land on HOME. No block screen, no Chrome
     * relaunch. The accessibility service keeps running, so the next time
     * Chrome is opened, any still-present incognito session is detected and
     * blocked again.
     *
     * Two strategies, in order:
     *   1. Chrome's own "Close all Incognito tabs" affordance — clicked
     *      directly when visible, or the tab switcher is opened first to reach
     *      it. This closes every incognito tab at once (faster and more
     *      reliable than one Back press per tab, since a Back press on a
     *      webpage first walks back through its history).
     *   2. An adaptive Back sequence that re-scans Chrome's tree after every
     *      press and stops as soon as no incognito signals remain. Pressing
     *      Back a fixed number of times blindly is unsafe: extra presses would
     *      close the user's NORMAL tabs (or exit Chrome entirely).
     *
     * Runs off the main thread so the actions can be spaced out.
     */
    private fun closeIncognitoTabsAndBlock(packageName: String) {
        blockingState = BlockingState.CLEARING_TARGET
        safeStateTimeoutJob?.cancel()
        serviceScope.launch {
            // Preferred: close ALL incognito tabs at once via Chrome's own UI.
            val closedViaChromeUi = tryCloseAllIncognitoTabs(packageName)
            if (closedViaChromeUi) {
                Log.i(TAG, "INCOGNITO_CLOSE: closed all incognito tabs via Chrome UI")
            } else {
                Log.i(TAG, "INCOGNITO_CLOSE: close-all button not reachable — using adaptive Back sequence")
                for (i in 1..INCOGNITO_CLOSE_MAX_BACK_PRESSES) {
                    if (!isActive) {
                        incognitoCloseInProgress = false
                        return@launch
                    }
                    // Chrome left the foreground (the last Back closed the final
                    // tab and exited Chrome): the incognito session is gone.
                    if (currentForegroundPackage != packageName) {
                        Log.i(TAG, "INCOGNITO_CLOSE: Chrome left foreground after ${i - 1} press(es); incognito session gone")
                        break
                    }
                    if (!isChromeStillIncognito(packageName)) {
                        Log.i(TAG, "INCOGNITO_CLOSE: incognito cleared after ${i - 1} press(es)")
                        break
                    }
                    Log.i(TAG, "INCOGNITO_CLOSE: back press #$i (still incognito)")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                }
            }
            if (!isActive) {
                incognitoCloseInProgress = false
                return@launch
            }

            // Re-arm the cooldown AFTER the closing sequence, not only at block
            // INITIATION: a settle scan right after the presses can still see
            // incognito chrome, and without the lock the next poll would
            // re-block in a loop. Re-arming here covers the whole close +
            // land-on-Home window.
            incognitoBlockCooldownUntil = System.currentTimeMillis() + INCOGNITO_BLOCK_COOLDOWN_MS
            Log.i(TAG, "INCOGNITO_COOLDOWN_REARMED after close until=$incognitoBlockCooldownUntil")

            if (currentForegroundPackage == packageName && isChromeStillIncognito(packageName)) {
                // Close failed on this Chrome version — the session is still
                // there in the background. Log it; the user lands on Home and
                // the next Chrome open will be blocked again.
                Log.w(TAG, "INCOGNITO_CLOSE: still incognito after all close attempts — next Chrome open will be blocked again")
            }

            // Simple, clean: incognito tab closed → land on HOME. The service
            // stays alive (accessibility remains enabled), so opening Chrome
            // again re-enables monitoring. lastBlockedResult is cleared too:
            // the old overlay round-trip used to clear it via the service's own
            // package (packageName) handlePackageChange, which no longer runs
            // for incognito — leaving
            // a stale INCOGNITO result behind.
            // The close sequence is done: the incognito session is gone and the
            // user is being sent Home. Clear the flag so the NEXT incognito
            // detection (a fresh session the user opens) blocks immediately —
            // the timestamp cooldown alone must never let a fast re-open in.
            incognitoCloseInProgress = false
            blockingState = BlockingState.NORMAL
            lastCheckedSnapshotId = null
            lastBlockedResult = null
            goToHome()
            Log.i(TAG, "INCOGNITO_CLOSE: closed incognito tabs — landed on Home")
        }
    }

    /**
     * Try to close ALL incognito tabs at once via Chrome's own UI. Attempts:
     *   1. Click a visible "Close all Incognito tabs" node in the current tree.
     *   2. Otherwise click the tab-switcher button to open the tab overview and
     *      look for the close-all button there (one retry).
     * Returns true when the tree no longer shows incognito afterwards; false
     * when the affordance couldn't be reached (the caller falls back to the
     * adaptive Back sequence).
     */
    private suspend fun tryCloseAllIncognitoTabs(packageName: String): Boolean {
        var attempts = 0
        while (attempts < 2) {
            attempts++
            val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
            try {
                val closeNode = findIncognitoCloseAllNode(rootNode)
                if (closeNode != null) {
                    val clicked = try {
                        closeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        Log.e(TAG, "INCOGNITO_CLOSE: close-all click failed: ${e.message}")
                        false
                    } finally {
                        try { closeNode.recycle() } catch (e: Exception) {}
                    }
                    if (clicked) {
                        Log.i(TAG, "INCOGNITO_CLOSE: clicked 'Close all Incognito tabs'")
                        delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                        return !isChromeStillIncognito(packageName)
                    }
                }

                // Not found / not clickable: open the tab switcher and retry.
                val tabSwitcher = findTabSwitcherButton(rootNode)
                if (tabSwitcher != null) {
                    val clicked = try {
                        tabSwitcher.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        Log.e(TAG, "INCOGNITO_CLOSE: tab-switcher click failed: ${e.message}")
                        false
                    } finally {
                        try { tabSwitcher.recycle() } catch (e: Exception) {}
                    }
                    if (clicked) {
                        Log.i(TAG, "INCOGNITO_CLOSE: opened tab switcher to reach close-all")
                        delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                        continue
                    }
                }
                return false
            } finally {
                try { rootNode.recycle() } catch (e: Exception) {}
            }
        }
        return false
    }

    /**
     * Find a node that is Chrome's "Close all Incognito tabs" action, matched
     * by its text / content description or an incognito close-all view id.
     * Prefers the clickable button over a plain text label (a label's
     * ACTION_CLICK returns false, which just wastes one attempt).
     */
    private fun findIncognitoCloseAllNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var fallbackLabel: AccessibilityNodeInfo? = null
        var depth = 0
        while (queue.isNotEmpty() && depth < 60) {
            val node = queue.removeFirst()
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null } ?: ""
            val lowerText = ((text ?: "") + " " + (desc ?: "")).lowercase(Locale.ROOT)
            val lowerId = viewId.lowercase(Locale.ROOT)
            val isCloseAll = lowerText.contains("close all incognito") ||
                lowerText.contains("close incognito tabs") ||
                lowerId.contains("close_all_incognito") ||
                lowerId.contains("close_incognito_tabs")
            if (isCloseAll) {
                val className = try { node.className?.toString() } catch (e: Exception) { null } ?: ""
                val clickable = (try { node.isClickable } catch (e: Exception) { false }) ||
                    className.contains("Button", ignoreCase = true)
                if (clickable) {
                    if (fallbackLabel != null) {
                        try { fallbackLabel.recycle() } catch (e: Exception) {}
                    }
                    return node
                }
                // Remember the first plain label as a last resort.
                if (fallbackLabel == null) {
                    fallbackLabel = AccessibilityNodeInfo.obtain(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            node.recycle()
            depth++
        }
        return fallbackLabel
    }

    /** Find Chrome's visible tab-switcher (tab counter) button. */
    private fun findTabSwitcherButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var depth = 0
        while (queue.isNotEmpty() && depth < 60) {
            val node = queue.removeFirst()
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null } ?: ""
            val lowerDesc = desc?.lowercase(Locale.ROOT) ?: ""
            val lowerId = viewId.lowercase(Locale.ROOT)
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            val isTabSwitcher = visible && (
                lowerDesc.contains("tab switcher") ||
                lowerDesc.contains("switch tabs") ||
                Regex("\\d+\\s+tabs?").containsMatchIn(lowerDesc.trim()) ||
                lowerId.contains("tab_switcher_button") ||
                lowerId.contains("tab_counter") ||
                lowerId.contains("tab_switcher_mode_buttons"))
            if (isTabSwitcher) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            node.recycle()
            depth++
        }
        return null
    }

    /**
     * Re-scan Chrome's tree and report whether it still shows incognito UI.
     * Used by the adaptive Back sequence to decide when to stop pressing.
     * Returns true (keep pressing) when the tree can't be read, so a
     * mid-transition snapshot can't end the sequence prematurely.
     */
    private fun isChromeStillIncognito(packageName: String): Boolean {
        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return true
        return try {
            val windowTitle = activeWindowTitle()
            contentExtractor.extract(packageName, rootNode, null, windowTitle).incognito
        } catch (e: Exception) {
            Log.e(TAG, "INCOGNITO_CLOSE: re-scan failed: ${e.message}")
            true
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * SUPERSEDED by [redirectChromeTabAndBlock] — kept only as reference; no
     * longer called from the block sequence (closing all tabs is unreliable
     * and the blocked tab is restored on the next launch anyway).
     *
     * Old behavior: close ALL of Chrome's tabs, then land Home via
     * the block overlay. This fixed the user's report that after a block,
     * reopening Chrome restored the same blocked tab — with every tab closed,
     * Chrome starts fresh next time.
     *
     * Runs off the main thread so the UI actions can be spaced out. The
     * overlay (and Home) is reached whether or not closing fully succeeded; a
     * surviving blocked tab is re-blocked the next time Chrome is opened.
     */
    private fun closeAllChromeTabsAndBlock(packageName: String) {
        blockingState = BlockingState.CLEARING_TARGET
        safeStateTimeoutJob?.cancel()
        serviceScope.launch {
            val closed = tryCloseAllTabs(packageName)
            Log.i(TAG, "CLOSE_ALL_TABS: closed=$closed")
            if (!isActive) return@launch
            transitionToHome(packageName)
        }
    }

    /**
     * Try to close ALL of Chrome's normal tabs via its own UI. Attempts:
     *   1. Click a visible "Close all tabs" node (text / content description /
     *      view id) in the current tree.
     *   2. Otherwise open the tab switcher (tab counter button) and look again.
     *   3. Otherwise open the tab-switcher overflow menu (⋮) and look again.
     * Returns true when the tree no longer shows any open tab afterwards;
     * false when the affordance couldn't be reached.
     */
    private suspend fun tryCloseAllTabs(packageName: String): Boolean {
        var switcherOpened = false
        var menuOpened = false
        var attempts = 0
        while (attempts < 6) {
            attempts++
            // Chrome left the foreground (user escaped mid-sequence): stop
            // driving the UI — scanning the launcher's tree would waste the
            // remaining attempts and only delay landing Home.
            if (currentForegroundPackage != packageName) {
                Log.d(TAG, "CLOSE_ALL_TABS: Chrome left foreground — aborting tab close")
                return false
            }
            val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
            try {
                // 1. Direct "Close all tabs" affordance. The matched node is
                //    often a plain text label (Chrome menu rows expose their
                //    label on a leaf TextView); clickNodeOrClickableAncestor
                //    walks up to the clickable row so the click dispatches.
                val closeNode = findCloseAllTabsNode(rootNode)
                if (closeNode != null) {
                    val clicked = try {
                        clickNodeOrClickableAncestor(closeNode)
                    } finally {
                        try { closeNode.recycle() } catch (e: Exception) {}
                    }
                    if (clicked) {
                        Log.i(TAG, "CLOSE_ALL_TABS: clicked 'Close all tabs'")
                        delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                        if (!chromeTabsStillOpen(packageName)) {
                            Log.i(TAG, "CLOSE_ALL_TABS: verified closed after close-all click")
                            return true
                        }
                        Log.d(TAG, "CLOSE_ALL_TABS: close-all clicked but tabs remain — retrying")
                        continue
                    }
                }

                // 2. Open the tab switcher (once) to reach the close-all action.
                if (!switcherOpened) {
                    val tabSwitcher = findTabSwitcherButton(rootNode)
                    if (tabSwitcher != null) {
                        val clicked = try {
                            tabSwitcher.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (e: Exception) {
                            Log.e(TAG, "CLOSE_ALL_TABS: tab-switcher click failed: ${e.message}")
                            false
                        } finally {
                            try { tabSwitcher.recycle() } catch (e: Exception) {}
                        }
                        if (clicked) {
                            switcherOpened = true
                            Log.i(TAG, "CLOSE_ALL_TABS: opened tab switcher")
                            delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                            continue
                        }
                    }
                }

                // 3. Open the tab-switcher overflow menu (once) to reveal
                //    "Close all tabs" (it lives behind the ⋮ button).
                if (!menuOpened) {
                    val menuButton = findTabSwitcherMenuButton(rootNode)
                    if (menuButton != null) {
                        val clicked = try {
                            menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (e: Exception) {
                            Log.e(TAG, "CLOSE_ALL_TABS: menu click failed: ${e.message}")
                            false
                        } finally {
                            try { menuButton.recycle() } catch (e: Exception) {}
                        }
                        if (clicked) {
                            menuOpened = true
                            Log.i(TAG, "CLOSE_ALL_TABS: opened tab-switcher menu")
                            delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                            continue
                        }
                    }
                }

                // 4. Per-tab fallback: close one visible tab at a time via each
                //    card's "Close tab" X button in the switcher grid.
                val tabClose = findTabCloseButton(rootNode)
                if (tabClose != null) {
                    val clicked = try {
                        clickNodeOrClickableAncestor(tabClose)
                    } finally {
                        try { tabClose.recycle() } catch (e: Exception) {}
                    }
                    if (clicked) {
                        Log.i(TAG, "CLOSE_ALL_TABS: closed one tab via 'Close tab' button")
                        delay(INCOGNITO_CLOSE_SCAN_DELAY_MS)
                        continue
                    }
                }

                // 5. Nothing actionable. If no tab remains (Chrome's empty
                //    "No tabs" state), the close succeeded after all. Only trust
                //    this optimistic verdict when the switcher was actually
                //    opened — otherwise we never saw tab UI and any "no tabs"
                //    text is almost certainly webpage content.
                if (switcherOpened && !chromeTabsStillOpen(packageName)) {
                    Log.i(TAG, "CLOSE_ALL_TABS: no tabs remain")
                    return true
                }
                return false
            } finally {
                try { rootNode.recycle() } catch (e: Exception) {}
            }
        }
        return false
    }

    /**
     * Find a node that is Chrome's "Close all tabs" action for NORMAL tabs,
     * matched by text / content description ("Close all tabs") or a close-all
     * view id. The incognito close-all affordance is deliberately excluded.
     * Prefers the clickable button over a plain text label.
     */
    private fun findCloseAllTabsNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var fallbackLabel: AccessibilityNodeInfo? = null
        var depth = 0
        while (queue.isNotEmpty() && depth < 60) {
            val node = queue.removeFirst()
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null } ?: ""
            val lowerText = ((text ?: "") + " " + (desc ?: "")).lowercase(Locale.ROOT)
            val lowerId = viewId.lowercase(Locale.ROOT)
            val isCloseAll = !lowerText.contains("incognito") && (
                lowerText.contains("close all tabs") ||
                    (lowerText.contains("close all") && lowerText.contains("tab")) ||
                    lowerId.contains("close_all_tabs") ||
                    (lowerId.contains("close_all") && lowerId.contains("tab") && !lowerId.contains("incognito")))
            if (isCloseAll) {
                val className = try { node.className?.toString() } catch (e: Exception) { null } ?: ""
                val clickable = (try { node.isClickable } catch (e: Exception) { false }) ||
                    className.contains("Button", ignoreCase = true)
                if (clickable) {
                    if (fallbackLabel != null) {
                        try { fallbackLabel.recycle() } catch (e: Exception) {}
                    }
                    return node
                }
                // Remember the first plain label as a last resort.
                if (fallbackLabel == null) {
                    fallbackLabel = AccessibilityNodeInfo.obtain(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            node.recycle()
            depth++
        }
        return fallbackLabel
    }

    /**
     * Find Chrome's tab-switcher overflow menu button (⋮) — the button that
     * reveals the "Close all tabs" item when the tab switcher is open.
     */
    private fun findTabSwitcherMenuButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var depth = 0
        while (queue.isNotEmpty() && depth < 60) {
            val node = queue.removeFirst()
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null } ?: ""
            val lowerDesc = desc?.lowercase(Locale.ROOT) ?: ""
            val lowerId = viewId.lowercase(Locale.ROOT)
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            val isMenuButton = visible && (
                lowerId.contains("menu_button") ||
                    lowerId.contains("tab_switcher_menu") ||
                    lowerDesc.contains("menu"))
            if (isMenuButton) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            node.recycle()
            depth++
        }
        return null
    }

    /**
     * Re-scan Chrome's tree and report whether any normal tab is still open.
     * Used by [tryCloseAllTabs] to confirm the close-all worked. Returns true
     * (tabs remain) when the tree can't be read, so a mid-transition snapshot
     * can't end the sequence prematurely.
     */
    private fun chromeTabsStillOpen(packageName: String): Boolean {
        if (currentForegroundPackage != packageName) return false
        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return true
        return try {
            // Chrome's empty-tab state shows a short heading literally titled
            // "No tabs" — a definitive zero-tab signal. Match the heading EXACTLY
            // (trimmed, case-insensitive), never a substring scan: a webpage
            // merely containing the phrase "no tabs" must not be read as the
            // empty state.
            if (treeHasExactText(rootNode, "no tabs")) {
                return false
            }
            // The tab counter ("See N tabs" / "N tabs"): N >= 1 means open.
            val switcher = findTabSwitcherButton(rootNode)
            if (switcher != null) {
                try {
                    val desc = switcher.contentDescription?.toString() ?: ""
                    val m = Regex("(\\d+)\\s+tabs?").find(desc.lowercase(Locale.ROOT))
                    val count = m?.groupValues?.get(1)?.toIntOrNull()
                    if (count != null) return count > 0
                } finally {
                    try { switcher.recycle() } catch (e: Exception) {}
                }
            }
            // Per-tab close buttons visible → at least one tab open.
            if (findTabCloseButton(rootNode) != null) return true
            // A close-all affordance only exists while there is at least one tab.
            if (findCloseAllTabsNode(rootNode) != null) return true
            // Cannot tell (e.g. a fresh page view without switcher UI) — assume
            // closed so the sequence can land Home.
            false
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * Find a visible per-tab "Close tab" button (the X on each tab card in the
     * tab switcher grid). Matches by content description / view id, excluding
     * incognito-affiliated nodes so the NORMAL-tab flow never closes incognito
     * tabs.
     */
    private fun findTabCloseButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var depth = 0
        while (queue.isNotEmpty() && depth < 60) {
            val node = queue.removeFirst()
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null } ?: ""
            val lowerDesc = desc?.lowercase(Locale.ROOT) ?: ""
            val lowerId = viewId.lowercase(Locale.ROOT)
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            val isTabClose = visible &&
                !lowerDesc.contains("incognito") && !lowerId.contains("incognito") && (
                lowerDesc.contains("close tab") ||        // the X on each card
                    lowerDesc.contains("close this tab") ||
                    lowerId.contains("close_tab") ||
                    lowerId.contains("tab_close") ||
                    lowerId.contains("tab_switcher_close"))
            if (isTabClose) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            node.recycle()
            depth++
        }
        return null
    }

    /**
     * Click [node], walking up to its nearest clickable ancestor when the node
     * itself isn't clickable. Chrome menu rows expose their label on a leaf
     * TextView whose ACTION_CLICK returns false; the row container above the
     * label is the real clickable target. Returns true when a click dispatched.
     * The input node is NOT recycled here — the caller owns it.
     */
    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        var clicked = false
        while (current != null && hops < 5 && !clicked) {
            hops++
            val isClickable = try { current.isClickable } catch (e: Exception) { false }
            if (isClickable) {
                clicked = try {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Exception) {
                    Log.e(TAG, "CLOSE_ALL_TABS: click failed: ${e.message}")
                    false
                }
            }
            val parent = try { current.parent } catch (e: Exception) { null }
            if (current !== node) {
                try { current.recycle() } catch (e: Exception) {}
            }
            current = parent
        }
        // current is the last fetched-but-unrecycled ancestor (never the input).
        if (current != null && current !== node) {
            try { current.recycle() } catch (e: Exception) {}
        }
        return clicked
    }

    /**
     * Whether any node's text/contentDescription equals the needle exactly
     * (after trimming, case-insensitive). Used for Chrome's "No tabs" empty
     * state heading — a substring match would false-positive on webpage text.
     */
    private fun treeHasExactText(rootNode: AccessibilityNodeInfo, needle: String): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var found = false
        var depth = 0
        while (queue.isNotEmpty() && depth < 60) {
            val node = queue.removeFirst()
            try {
                if (!found) {
                    val text = try { node.text?.toString() } catch (e: Exception) { null }
                    val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                    val matches = listOfNotNull(text, desc).any {
                        it.trim().equals(needle, ignoreCase = true)
                    }
                    found = matches
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    queue.add(child)
                }
            } finally {
                try { node.recycle() } catch (e: Exception) {}
            }
            depth++
        }
        return found
    }

    /**
     * Clear a generic target app (Chrome).
     */
    private fun clearTargetApp(rootNode: AccessibilityNodeInfo, packageName: String) {
        Log.i(TAG, "CLEARING URL/QUERY")
        var textCleared = false

        // Attempt to find EditText nodes (URL bar, search bar) and clear them
        val editTexts = findEditTexts(rootNode)
        for (node in editTexts) {
            try {
                // Focus the node
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                // Set text to empty
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (success) {
                    textCleared = true
                    Log.d(TAG, "Cleared text in Accessibility Node")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing text: ${e.message}")
            } finally {
                node.recycle()
            }
        }

        // Navigate BACK to escape the blocked page/overlay
        val backSuccess = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "Executed GLOBAL_ACTION_BACK: $backSuccess")

        if (!textCleared && !backSuccess) {
            Log.w(TAG, "Failed to actively clear target via Accessibility. Waiting for fallback.")
        }
    }

    private fun transitionToHome(sourcePackage: String) {
        Log.i(TAG, "RETURNING HOME")
        if (sourcePackage == GOOGLE_PACKAGE) {
            Log.i(TAG, "GOOGLE_RETURN_HOME")
        }
        blockingState = BlockingState.RETURNING_HOME
        safeStateTimeoutJob?.cancel()

        // Clear cache so that the next time the app opens, it checks fresh
        lastCheckedSnapshotId = null
        forceGoogleReevaluate = false

        val result = lastBlockedResult
            ?: MatchResult.Blocked("Unknown", MatchType.DOMAIN, MatchSource.NONE)

        showBlockOverlay(result, sourcePackage)
    }

    private fun navigateToSafeIntent(packageName: String) {
        try {
            // Incognito blocks: never reopen Chrome via a safe intent — Chrome may
            // restore the last session in an incognito tab, re-triggering the block
            // and causing a loop. Go straight Home instead.
            if (lastBlockedResult?.matchType == MatchType.INCOGNITO) {
                Log.i(TAG, "INCOGNITO_SAFE_FALLBACK: going Home (skip safe intent)")
                goToHome()
                return
            }
            if (contentExtractor.isYouTubePackage(packageName)) {
                // YouTube app cannot open a URL via intent-setPackage (no browser capability).
                // Navigate to Home instead.
                Log.i(TAG, "YOUTUBE_SAFE_FALLBACK: navigating to Home")
                goToHome()
                return
            }
            if (contentExtractor.isTargetPackage(packageName)) {
                Log.i(TAG, "SAFE URL REQUESTED via Intent for $packageName")
                val safeUrl = "https://www.google.com"
                val safeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(safeIntent)
                Log.i(TAG, "SAFE_INTENT_SENT to $packageName: $safeUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send safe intent to $packageName: ${e.message}")
        }
    }

    /**
     * Normal-Chrome block path: redirect the CURRENT tab to the safe Google
     * home page, then land Home via the block overlay. Closing all of
     * Chrome's tabs proved unreliable (Chrome often restores the blocked tab
     * on the next launch), so instead the tab itself is navigated to a safe
     * URL: when the user dismisses the overlay and reopens Chrome, the tab is
     * still there (tabs can't always be closed) but its content is Google.
     *
     * Runs off the main thread so the UI actions can be spaced out. If the
     * same-tab redirect can't be driven (omnibox or IME not reachable), a
     * safe intent opens Google in Chrome as a fallback so the blocked page is
     * never the visible tab.
     */
    private fun redirectChromeTabAndBlock(packageName: String) {
        blockingState = BlockingState.CLEARING_TARGET
        safeStateTimeoutJob?.cancel()
        serviceScope.launch {
            val redirected = redirectChromeTabToSafeUrl(packageName)
            Log.i(TAG, "SAFE_REDIRECT: redirected=$redirected")
            if (!isActive) return@launch
            if (!redirected) {
                Log.w(TAG, "SAFE_REDIRECT: omnibox redirect unavailable — safe-intent fallback")
                navigateToSafeIntent(packageName)
                delay(SAFE_REDIRECT_SCAN_DELAY_MS)
            }
            transitionToHome(packageName)
        }
    }

    /**
     * Redirect the CURRENT Chrome tab to [SAFE_REDIRECT_URL] by typing it
     * into the omnibox and submitting with the soft keyboard's enter key.
     * Chrome's omnibox (address bar) is always present in the tree, so this
     * is the reliable same-tab redirect. Returns true when the navigation was
     * initiated.
     */
    private suspend fun redirectChromeTabToSafeUrl(packageName: String): Boolean {
        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        val urlBar = try { findChromeOmnibox(rootNode) } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
        if (urlBar == null) {
            Log.w(TAG, "SAFE_REDIRECT: omnibox not found in tree")
            return false
        }
        val typed = try {
            urlBar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    SAFE_REDIRECT_URL
                )
            }
            urlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "SAFE_REDIRECT: SET_TEXT failed: ${e.message}")
            false
        } finally {
            try { urlBar.recycle() } catch (e: Exception) {}
        }
        if (!typed) return false
        Log.i(TAG, "SAFE_REDIRECT: omnibox SET_TEXT -> $SAFE_REDIRECT_URL")
        // Give the IME + omnibox suggestions a moment to appear, then submit
        // the navigation IN THE SAME TAB:
        //   1. press the soft keyboard's enter key, else
        //   2. click Chrome's first omnibox suggestion (the typed URL is always
        //      the top suggestion, and clicking it navigates the current tab).
        delay(SAFE_REDIRECT_SCAN_DELAY_MS)
        var submitted = pressImeEnterKey()
        if (!submitted) {
            Log.i(TAG, "SAFE_REDIRECT: IME enter not found — clicking first omnibox suggestion")
            submitted = clickOmniboxSuggestion()
        }
        Log.i(TAG, "SAFE_REDIRECT: submitted=$submitted")
        return submitted
    }

    /**
     * Find Chrome's address bar (omnibox): an editable node whose class or
     * view id marks it as the URL/location bar.
     */
    private fun findChromeOmnibox(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < 60 && visited < 2000) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === rootNode
            val cls: String = try { node.className?.toString() ?: "" } catch (e: Exception) { "" }
            val viewId: String = try { node.viewIdResourceName?.toString() ?: "" } catch (e: Exception) { "" }
            val editable = try { node.isEditable } catch (e: Exception) { false }
            // The omnibox is any EditText (always editable) OR an editable node
            // whose view id marks it as the URL/location bar.
            val isOmnibox = cls.contains("EditText") || cls.contains("AutoCompleteTextView") ||
                (editable && (viewId.contains("url_bar") || viewId.contains("omnibox") ||
                    viewId.contains("url-bar") || viewId.contains("location_bar")))
            if (isOmnibox) return node
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

    /**
     * Press the soft keyboard's enter/search key via the IME window's
     * accessibility tree (Gboard exposes its key nodes). Returns true when a
     * key was clicked.
     */
    private fun pressImeEnterKey(): Boolean {
        val windows = try { windows } catch (e: Exception) { return false }
        if (windows == null) return false
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = try { w.root } catch (e: Exception) { null } ?: continue
            try {
                if (clickImeEnterKey(root)) return true
            } finally {
                try { root.recycle() } catch (e: Exception) {}
            }
        }
        return false
    }

    private fun clickImeEnterKey(root: AccessibilityNodeInfo): Boolean {
        val enterLabels = setOf("go", "enter", "search", "done", "arrow", "next", "return", "→", "↵", "✓")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < 30 && visited < 1000) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val text = try { node.text?.toString()?.trim() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString()?.trim() } catch (e: Exception) { null }
            val label = (text ?: "").lowercase(Locale.ROOT)
            val descLabel = (desc ?: "").lowercase(Locale.ROOT)
            // Match the enter/search key by its label; click the key itself or
            // walk up to its clickable ancestor (some keyboards expose the
            // label on a leaf node).
            if (label in enterLabels || descLabel in enterLabels) {
                val ok = clickNodeOrClickableAncestor(node)
                Log.i(TAG, "SAFE_REDIRECT: IME enter key click=$ok text=$text desc=$desc")
                if (!isRoot) try { node.recycle() } catch (e: Exception) {}
                return ok
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return false
    }

    /**
     * Click Chrome's omnibox suggestion for the typed safe URL — the top
     * suggestion is always the URL itself ("Go to https://www.google.com"),
     * and clicking it navigates the CURRENT tab (same-tab redirect). Returns
     * true when a suggestion row was clicked.
     */
    private fun clickOmniboxSuggestion(): Boolean {
        val rootNode = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        return try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(rootNode)
            var depth = 0
            var visited = 0
            while (queue.isNotEmpty() && depth < 60 && visited < 2000) {
                visited++
                val node = queue.removeFirst()
                val isRoot = node === rootNode
                val text = try { node.text?.toString() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                val combined = ((text ?: "") + " " + (desc ?: "")).lowercase(Locale.ROOT)
                if (combined.contains("google.com")) {
                    val clicked = clickNodeOrClickableAncestor(node)
                    Log.i(TAG, "SAFE_REDIRECT: suggestion click=$clicked text=\"$text\"")
                    if (clicked) {
                        if (!isRoot) try { node.recycle() } catch (e: Exception) {}
                        return true
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
            false
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    private fun showBlockOverlay(result: MatchResult.Blocked, sourcePackage: String) {
        try {
            val intent = Intent(this, com.muddassir.clearview.ui.BlockOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra("blocked_item", result.matchedItem)
                putExtra("blocked_type", result.matchType.name)
                putExtra("source_package", sourcePackage)
                // The overlay explains Strict Mode when a broad keyword caused
                // this block (see isStrictModeBlock).
                putExtra("strict_hit", isStrictModeBlock(result))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show block overlay: ${e.message}", e)
            goToHome()
        }
    }

    /**
     * True when [result] is a built-in keyword block whose keyword belongs to
     * Strict Mode's curated discovery set AND Strict Mode is on — i.e.
     * the block happened because of Strict Mode, not a base/custom/domain
     * rule. The overlay uses this to explain how to search the term
     * legitimately (turn Strict Mode off).
     */
    private fun isStrictModeBlock(result: MatchResult.Blocked): Boolean {
        if (result.matchType != MatchType.BUILT_IN_KEYWORD) return false
        if (!repository.isStrictMode) return false
        val keyword = result.matchedItem.trim().lowercase(Locale.ROOT)
        if (keyword !in BlockRepository.STRICT_MODE_KEYWORDS) {
            return false
        }
        // Words that are ALSO always-block keywords block without Strict Mode —
        // the note must not imply that turning it off would unblock them.
        return keyword !in BlockRepository.ALWAYS_BLOCK_KEYWORDS
    }

    private fun goToHome() {
        try {
            Log.i(TAG, "GLOBAL_ACTION_HOME")
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.w(TAG, "GLOBAL_ACTION_HOME failed: ${e.message}")
        }
    }

    private fun findEditTexts(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))

        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            val node = queue.removeFirst()

            if (node.className?.toString() == "android.widget.EditText") {
                results.add(AccessibilityNodeInfo.obtain(node))
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
            node.recycle()
            depth++
        }
        return results
    }

    fun isServiceEnabled(): Boolean {
        val serviceStr = "${packageName}/.service.UrlBlockerService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(serviceStr, ignoreCase = true) }
    }
}
