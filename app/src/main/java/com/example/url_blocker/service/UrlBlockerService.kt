package com.example.url_blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.url_blocker.extractor.ContentExtractor
import com.example.url_blocker.matching.ContentSnapshot
import com.example.url_blocker.matching.KeywordMatcher
import com.example.url_blocker.matching.MatchResult
import com.example.url_blocker.matching.MatchType
import com.example.url_blocker.matching.MatchSource
import com.example.url_blocker.repository.BlockRepository
import kotlinx.coroutines.*

enum class BlockingState {
    NORMAL,
    CLEARING_TARGET,
    WAITING_FOR_SAFE_STATE,
    RETURNING_HOME
}

/**
 * Accessibility Service that continuously monitors Chrome and Google app
 * for blocked keywords and domains.
 */
class UrlBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "UrlBlockerService"
        private const val POLLING_INTERVAL_MS = 800L
        private const val GOOGLE_POLLING_INTERVAL_MS = 500L
        private const val SAFE_STATE_TIMEOUT_MS = 2500L
        private const val OUR_PACKAGE = "com.example.url_blocker"
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    }

    private lateinit var repository: BlockRepository
    private lateinit var keywordMatcher: KeywordMatcher
    private val contentExtractor = ContentExtractor()

    private var currentForegroundPackage: String? = null
    private var lastCheckedSnapshotId: String? = null

    private var blockingState = BlockingState.NORMAL
    private var lastBlockedResult: MatchResult.Blocked? = null
    private var safeStateTimeoutJob: Job? = null

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

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        repository = BlockRepository(applicationContext)
        keywordMatcher = KeywordMatcher(repository)
        Log.i(TAG, "UrlBlockerService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "UrlBlockerService connected and running")
    }

    override fun onInterrupt() {
        Log.w(TAG, "UrlBlockerService interrupted")
        stopPolling()
        stopGooglePolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "UrlBlockerService destroyed")
    }

    // ── Event Handling ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // 1. Update foreground package tracking
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handlePackageChange(packageName)
        }

        // 2. Ignore our own app (overlay)
        if (currentForegroundPackage == OUR_PACKAGE) {
            return
        }

        // 3. Process events from target apps immediately.
        //    Events provide the fastest detection (text changes, window changes).
        //    The dedicated Google polling (500ms) serves as a safety net for cases
        //    where no new events fire (e.g., reopening Google with blocked content).
        if (contentExtractor.isTargetPackage(packageName)) {
            evaluateCurrentState(packageName, event)
        }
    }

    // ── Package Change Handling ────────────────────────────────────

    private fun handlePackageChange(newPackage: String) {
        if (currentForegroundPackage != newPackage) {
            Log.d(TAG, "Foreground package changed: $currentForegroundPackage -> $newPackage")

            if (newPackage == OUR_PACKAGE) {
                // We reached the block overlay
                blockingState = BlockingState.NORMAL
                stopPolling()
                stopGooglePolling()
                currentForegroundPackage = newPackage
                return
            }

            if (contentExtractor.isGooglePackage(newPackage)) {
                // ── GOOGLE FOREGROUND DETECTED ──────────────────────────
                googleForegroundSession++
                lastGoogleForegroundTime = System.currentTimeMillis()
                forceGoogleReevaluate = true
                lastCheckedSnapshotId = null

                Log.i(TAG, "GOOGLE_FOREGROUND_DETECTED (session=$googleForegroundSession, forceReeval=true)")
                Log.i(TAG, "GOOGLE_FORCED_RECHECK")

                // Reset diagnostic flag so tree dump runs on this new session
                googleDiagnosticDumped = false

                // Stop any existing Google polling to ensure only ONE polling job
                stopGooglePolling()
                stopPolling()

                // Start dedicated Google polling (handles both monitoring and safe-state checks)
                // This is the ONLY polling job when Google is foreground.
                startGooglePolling()

                // Immediately perform a forced scan (synchronous — called from main-thread event handler)
                Log.i(TAG, "GOOGLE_INITIAL_SCAN_STARTED")
                forceEvaluateGoogle()
            } else if (contentExtractor.isTargetPackage(newPackage)) {
                // ── CHROME (or other target) FOREGROUND ──────────────────
                lastCheckedSnapshotId = null
                startPolling(newPackage)
            } else {
                // ── NON-TARGET APP ───────────────────────────────────────
                stopPolling()
                stopGooglePolling()
                lastCheckedSnapshotId = null
                blockingState = BlockingState.NORMAL
                forceGoogleReevaluate = false
            }

            currentForegroundPackage = newPackage
        }
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
                                val snapshot = contentExtractor.extract(GOOGLE_PACKAGE, rootNode, null)
                                handleSafeStateCheck(snapshot, GOOGLE_PACKAGE)
                            } finally {
                                try { rootNode.recycle() } catch (e: Exception) {}
                            }
                        }
                    }
                    BlockingState.CLEARING_TARGET, BlockingState.RETURNING_HOME -> {
                        // In clearing or returning, wait
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

            // Get window title from accessibility event data (passed through extract path)
            val snapshot = contentExtractor.extract(
                GOOGLE_PACKAGE,
                rootNode,
                null,
                null
            )

            Log.i(TAG, "GOOGLE_CONTENT_EXTRACTED: url=${snapshot.url}, query=${snapshot.query}, title=${snapshot.title}")
            Log.i(TAG, "GOOGLE_EXTRACTED_QUERY=${snapshot.query ?: "null"}")
            Log.i(TAG, "GOOGLE_CURRENT_STATE: ${snapshot.toIdentityString()}")

            // Check against keywords
            var blocked = false
            if (snapshot.url != null || snapshot.query != null || snapshot.title != null) {
                val result = keywordMatcher.check(snapshot, GOOGLE_PACKAGE)

                if (result is MatchResult.Blocked) {
                    blocked = true
                    Log.w(TAG, "GOOGLE_BLOCKED_MATCH: matched=${result.matchedItem} (${result.matchType}) source=${result.matchSource}")
                    Log.i(TAG, "GOOGLE_MATCHED_KEYWORD=${result.matchedItem}")
                    Log.i(TAG, "GOOGLE_BLOCK_DECISION=true")
                    repository.addLogEntry("BLOCKED: ${result.matchedItem} in Google app")
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

            // If query was not found and no block occurred, try focusing the search box.
            // This triggers a TYPE_VIEW_FOCUSED event that carries the query text.
            // The event handler will process it on the next cycle.
            if (!blocked && snapshot.query == null && snapshot.url == null) {
                val focused = tryFocusGoogleSearchBox()
                Log.i(TAG, "GOOGLE_FOCUS_ATTEMPT: $focused (triggered because query=null)")
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

    private fun startPolling(packageName: String) {
        if (pollingJob?.isActive == true) return

        Log.d(TAG, "Starting polling for $packageName")
        pollingJob = serviceScope.launch {
            while (isActive) {
                val pkg = currentForegroundPackage ?: packageName
                if (pkg != OUR_PACKAGE && blockingState == BlockingState.NORMAL) {
                    evaluateCurrentState(pkg, null)
                }
                delay(POLLING_INTERVAL_MS)
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
        if (blockingState != BlockingState.NORMAL) {
            return
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val snapshot = contentExtractor.extract(packageName, rootNode, event, null)
            val snapshotId = snapshot.toIdentityString()

            // ── Determine if we should evaluate ─────────────────────────
            val shouldEvaluate = when {
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
                        if (snapshot.url != null || snapshot.query != null || snapshot.title != null) {
                            val result = keywordMatcher.check(snapshot, packageName)

                            if (result is MatchResult.Blocked) {
                                Log.w(TAG, "BLOCK DETECTED in $packageName! Matched: ${result.matchedItem} (${result.matchType})")
                                repository.addLogEntry("BLOCKED: ${result.matchedItem} in $packageName")
                                lastBlockedResult = result
                                initiateBlockingSequence(rootNode, packageName)
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

    private fun handleSafeStateCheck(snapshot: ContentSnapshot, packageName: String) {
        val result = keywordMatcher.check(snapshot, packageName)
        if (result is MatchResult.Allowed) {
            Log.i(TAG, "SAFE STATE DETECTED for $packageName")
            if (packageName == GOOGLE_PACKAGE) {
                Log.i(TAG, "GOOGLE_SAFE_STATE_CONFIRMED")
            }
            transitionToHome(packageName)
        } else {
            Log.d(TAG, "Still waiting for safe state in $packageName...")
        }
    }

    // ── Blocking Sequence ──────────────────────────────────────────

    private fun initiateBlockingSequence(rootNode: AccessibilityNodeInfo, packageName: String) {
        Log.i(TAG, "BLOCKING STARTED for Target App: $packageName")
        blockingState = BlockingState.CLEARING_TARGET

        if (packageName == GOOGLE_PACKAGE) {
            Log.i(TAG, "GOOGLE_SAFE_STATE_CLEAR_STARTED")
            clearGoogleApp(rootNode)
        } else {
            clearTargetApp(rootNode, packageName)
        }

        blockingState = BlockingState.WAITING_FOR_SAFE_STATE

        // Failsafe: if we don't detect a safe state within a reasonable time, forcefully transition
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
            if (contentExtractor.isTargetPackage(packageName)) {
                Log.i(TAG, "SAFE URL REQUESTED via Intent for $packageName")
                val safeUrl = if (contentExtractor.isGooglePackage(packageName)) {
                    "https://www.google.com"
                } else {
                    "https://www.google.com"
                }
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

    private fun showBlockOverlay(result: MatchResult.Blocked, sourcePackage: String) {
        try {
            val intent = Intent(this, com.example.url_blocker.ui.BlockOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra("blocked_item", result.matchedItem)
                putExtra("blocked_type", result.matchType.name)
                putExtra("source_package", sourcePackage)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show block overlay: ${e.message}", e)
            goToHome()
        }
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
