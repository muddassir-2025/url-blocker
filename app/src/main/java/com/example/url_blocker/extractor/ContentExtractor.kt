package com.example.url_blocker.extractor

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.url_blocker.matching.ContentSnapshot
import com.example.url_blocker.matching.QueryConfidence
import com.example.url_blocker.matching.QuerySource

/**
 * Unified extractor for URLs, queries, and titles from Chrome and Google apps.
 */
class ContentExtractor {

    companion object {
        private const val TAG = "ContentExtractor"

        // Target packages
        val CHROME_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

        val YOUTUBE_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.vanced.android.youtube"
        )
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        private const val MAX_TRAVERSAL_DEPTH = 50
        private const val MIN_URL_LENGTH = 4
        private const val MIN_QUERY_LENGTH = 2

        // Known URL bar IDs (Chrome + Google Custom Tabs)
        private val URL_BAR_IDS = setOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/location_bar",
            "com.android.chrome:id/omnibox",
            "com.android.chrome:id/search_box_text",
            "com.chrome.beta:id/url_bar",
            "com.chrome.beta:id/location_bar",
            "com.chrome.beta:id/omnibox",
            "com.chrome.dev:id/url_bar",
            "com.chrome.dev:id/location_bar",
            "com.chrome.dev:id/omnibox",
            "com.chrome.canary:id/url_bar",
            "com.chrome.canary:id/location_bar",
            "com.chrome.canary:id/omnibox",
            "$GOOGLE_PACKAGE:id/url_bar", // Custom Tab in Google App
            "$GOOGLE_PACKAGE:id/location_bar"
        )

        // Known Search bar IDs (Google App)
        private val SEARCH_BAR_IDS = setOf(
            "$GOOGLE_PACKAGE:id/googleapp_search_box",
            "$GOOGLE_PACKAGE:id/search_box",
            "$GOOGLE_PACKAGE:id/omnibox",
            "$GOOGLE_PACKAGE:id/search_src_text",
            "$GOOGLE_PACKAGE:id/search_box_text"
        )
    }

    /**
     * Extracts a full snapshot of the current visible content.
     *
     * @param packageName The package being inspected
     * @param rootNode The root node of the accessibility tree
     * @param event The accessibility event that triggered this extraction (may be null)
     * @param windowTitle The window title from AccessibilityWindowInfo (may be null).
     *        For Google, this is the most reliable source of the search query.
     */
    fun extract(
        packageName: String,
        rootNode: AccessibilityNodeInfo?,
        event: AccessibilityEvent?,
        windowTitle: String? = null
    ): ContentSnapshot {
        if (rootNode == null) {
            val titleFromEvent = extractFromEvent(event) ?: windowTitle
            val queryFromTitle = if (packageName == GOOGLE_PACKAGE) {
                windowTitle?.let(GoogleSignalParser::queryFromWindowTitle)
            } else {
                null
            }
            val urlFromTitle = windowTitle?.takeIf(GoogleSignalParser::isExplicitUrl)
            return ContentSnapshot(
                packageName = packageName,
                url = urlFromTitle,
                query = queryFromTitle,
                title = titleFromEvent,
                queryConfidence = if (queryFromTitle != null) QueryConfidence.HIGH else QueryConfidence.NONE,
                querySource = if (queryFromTitle != null) QuerySource.WINDOW_TITLE else QuerySource.NONE
            )
        }

        var url: String? = null
        var query: String? = null
        var title: String? = null
        var queryConfidence = QueryConfidence.NONE
        var querySource = QuerySource.NONE

        // ── YOUTUBE CONTENT EXTRACTION ───────────────────────────
        // YouTube has no exposed URL bar and no search query input.
        // Instead, extract video title and Shorts/hashtag signals
        // from visible page content. Title-based blocking is appropriate
        // for YouTube because the video title IS the primary content
        // identifier (unlike Chrome where page titles are arbitrary).
        if (packageName in YOUTUBE_PACKAGES) {
            title = extractYouTubeTitle(rootNode, windowTitle, event)
            val extraSignals = extractYouTubeSignals(rootNode)
            val queryFromSignals = if (extraSignals.isNotEmpty()) {
                extraSignals.joinToString(" ")
            } else null
            return ContentSnapshot(
                packageName = packageName,
                url = null,  // No URL bar in YouTube app
                query = queryFromSignals,
                title = title,
                queryConfidence = if (queryFromSignals != null) QueryConfidence.MEDIUM else QueryConfidence.NONE,
                querySource = QuerySource.NONE
            )
        }

        // 1. Try to extract URL explicitly from known URL bars (Chrome)
        url = findTextByResourceIds(rootNode, URL_BAR_IDS) { text ->
            if (text.length >= MIN_URL_LENGTH) text else null
        }

        // Some Chrome Custom Tabs and Google embedded browser surfaces use
        // version-dependent URL-bar IDs. Only inspect nodes whose IDs clearly
        // identify an address/omnibox control, and still require URL-shaped text.
        if (url == null) {
            url = findTextByViewIdPattern(rootNode) { viewId, text ->
                isUrlBarId(viewId) && looksLikeUrl(text) && text.length >= MIN_URL_LENGTH
            }
        }

        // 2. Try to extract Query explicitly from known Search bars (Google)
        if (packageName == GOOGLE_PACKAGE) {
            val searchBarQuery = findTextByResourceIds(rootNode, SEARCH_BAR_IDS) { text ->
                if (text.length >= MIN_QUERY_LENGTH && !isGenericSearchHint(text)) text else null
            }
            if (searchBarQuery != null) {
                query = searchBarQuery
                queryConfidence = QueryConfidence.HIGH
                querySource = QuerySource.SEARCH_BAR
            }

            if (query == null) {
                val semanticSearchQuery = findTextByViewIdPattern(rootNode) { viewId, text ->
                    isGoogleSearchId(viewId) && isUsableGoogleQuery(text)
                }
                if (semanticSearchQuery != null) {
                    query = GoogleSignalParser.cleanQuery(semanticSearchQuery)
                    queryConfidence = QueryConfidence.HIGH
                    querySource = QuerySource.SEARCH_BAR
                    Log.d(TAG, "GOOGLE_QUERY_SOURCE=SEMANTIC_SEARCH_ID query=$query")
                }
            }

            if (query == null) {
                val semanticSearchQuery = findGoogleSemanticQuery(rootNode)
                if (semanticSearchQuery != null) {
                    query = semanticSearchQuery
                    queryConfidence = QueryConfidence.MEDIUM
                    querySource = QuerySource.SEARCH_BAR
                    Log.d(TAG, "GOOGLE_QUERY_SOURCE=SEMANTIC_SEARCH_NODE query=$query")
                }
            }
        }

        // 3a. PRIMARY APPROACH FOR GOOGLE: extract query from window title
        //     This is the most reliable source when the search results page is displayed
        //     and the search bar is not focused.
        if (query == null && packageName == GOOGLE_PACKAGE && windowTitle != null) {
                    val extractedQuery = GoogleSignalParser.queryFromWindowTitle(windowTitle)
            if (extractedQuery != null) {
                query = extractedQuery
                queryConfidence = QueryConfidence.HIGH
                querySource = QuerySource.WINDOW_TITLE
                Log.d(TAG, "GOOGLE_QUERY_SOURCE=WINDOW_TITLE query=$extractedQuery")
            }
        }

        // 3b. Use event data to extract query, URL, or title
        if (event != null) {
            val eventTitle = extractFromEvent(event)
            if (eventTitle != null && !isGenericSearchHint(eventTitle)) {
                if (url == null && looksLikeUrl(eventTitle)) {
                    url = eventTitle
                } else if (query == null && packageName == GOOGLE_PACKAGE) {
                    // For Google, try to extract query from window title like "keyword - Google Search"
                    val extractedQuery = GoogleSignalParser.queryFromWindowTitle(eventTitle)
                    if (extractedQuery != null) {
                        query = extractedQuery
                        queryConfidence = QueryConfidence.HIGH
                        querySource = QuerySource.WINDOW_TITLE
                        Log.d(TAG, "GOOGLE_QUERY_SOURCE=EVENT_TITLE query=$extractedQuery")
                    } else if ((event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) &&
                               isGoogleSearchEvent(event) &&
                               eventTitle.length in 2..100 &&
                               !isGenericSearchHint(eventTitle) &&
                               !looksLikeUrl(eventTitle)) {
                        // Fallback: if the event text is a raw query (not a formatted title),
                        // use it directly. This handles TYPE_VIEW_FOCUSED events from the
                        // search box where event.text = "porn" (raw query, no suffix).
                        query = eventTitle
                        queryConfidence = QueryConfidence.MEDIUM
                        querySource = QuerySource.ACCESSIBILITY_EVENT
                        Log.d(TAG, "GOOGLE_QUERY_SOURCE=EVENT_RAW_TEXT query=$query")
                    }
                } else if (title == null && !looksLikeUrl(eventTitle)) {
                    title = eventTitle
                }
            }
        }

        // 4. Fallback: only for known EditText views (URL/query bars that weren't found by resource ID)
        if (url == null || query == null) {
            traverseForFallbackEditTexts(rootNode, packageName) { foundUrl, foundQuery ->
                if (url == null) url = foundUrl
                if (query == null && foundQuery != null) {
                    query = foundQuery
                    queryConfidence = QueryConfidence.MEDIUM
                    querySource = QuerySource.EDITABLE_FIELD
                }
            }
        }

        // 5a. Check if the window title is an explicit URL (e.g., Custom Tab address)
        if (url == null && windowTitle != null && GoogleSignalParser.isExplicitUrl(windowTitle)) {
            url = windowTitle.trim()
            Log.d(TAG, "URL_SOURCE=WINDOW_TITLE url=$url")
        }

        // 5b. When Google opens content in a Custom Tab (e.g., YouTube video),
        //     the window title may be "Video Title - YouTube" even if no URL bar
        //     is exposed. Extract the video title for keyword matching.
        if (packageName == GOOGLE_PACKAGE && url == null && query == null && title == null &&
            windowTitle != null && windowTitle.lowercase().endsWith(" - youtube")
        ) {
            val youtubeTitle = windowTitle.substring(0, windowTitle.length - " - youtube".length).trim()
            if (youtubeTitle.length in 2..200 && !isGenericSearchHint(youtubeTitle)) {
                // Populate as query so the Google keyword matcher checks it.
                // Using query slot (not title) because for Google we block on query.
                query = youtubeTitle
                queryConfidence = QueryConfidence.MEDIUM
                querySource = QuerySource.WINDOW_TITLE
                Log.i(TAG, "GOOGLE_CUSTOM_TAB_YOUTUBE_DETECTED: title=$youtubeTitle")
            }
        }

        if (title == null && !windowTitle.isNullOrBlank()) {
            title = windowTitle
        }
        return ContentSnapshot(packageName, url, query, title, queryConfidence, querySource)
    }

    /**
     * Extract the search query from a Google search window title.
     * Examples:
     *   "blockedkeyword - Google Search"  -> "blockedkeyword"
     *   "something - Google"              -> "something"
     *   "blockedkeyword - Google Chrome"  -> "blockedkeyword"
     */
    private fun extractQueryFromGoogleTitle(title: String): String? =
        GoogleSignalParser.queryFromWindowTitle(title)?.also {
            Log.d(TAG, "Extracted query from Google title: $it")
        }

    /**
     * For Google app: find the current search query from the accessibility tree
     * by scanning in order of reliability:
     *
     * 1. Views with search-related resource IDs (omnibox, search_box, etc.)
     * 2. Editable text fields (EditText, AutoComplete)
     * 3. ANY view with non-generic, non-UI, non-URL text (broad fallback)
     *
     * On Google's search results page, the search box may be:
     * - An EditText with the query as its text
     * - A custom view with the query in a child TextView
     * - A view with content description containing the query
     * - The query text appears in a window title or search chip
     */
    private fun extractQueryFromGooglePage(rootNode: AccessibilityNodeInfo): String? {
        return findGoogleSemanticQuery(rootNode)
    }

    /**
     * Find text matching a view-ID-based predicate.
     */
    private fun findTextByViewIdPattern(
        rootNode: AccessibilityNodeInfo,
        predicate: (viewId: String, text: String) -> Boolean
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName ?: ""
            val text = extractNodeText(node)

            if (text != null && predicate(viewId, text)) {
                Log.d(TAG, "Found semantic text by viewId: $text ($viewId)")
                return text
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        return null
    }

    /**
     * Find text matching a className-based predicate.
     */
    private fun findTextByClassName(
        rootNode: AccessibilityNodeInfo,
        predicate: (className: String, text: String) -> Boolean
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            val text = extractNodeText(node)

            if (text != null && predicate(className, text)) {
                Log.d(TAG, "Found Google query by className: $text ($className)")
                return text
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        return null
    }

    /**
     * Scan ALL nodes and return the LONGEST text that matches the predicate.
     * This is useful for Phase 3 where we want to prefer longer text (which is
     * more likely to be the actual search query) over short UI labels.
     */
    /**
     * Scan ALL nodes and return the SHORTEST text matching the predicate.
     * The search query (e.g., "porn") is typically short and would be preferred
     * over longer text like "About 1,240,000 results" or result snippets.
     * No early exit — we scan the full tree to find the globally shortest text.
     */
    private fun findFirstQueryLikeText(
        rootNode: AccessibilityNodeInfo,
        maxDepth: Int = MAX_TRAVERSAL_DEPTH,
        predicate: (text: String) -> Boolean
    ): String? {
        var bestText: String? = null
        var bestLen = Int.MAX_VALUE

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < maxDepth) {
            val node = queue.removeFirst()
            val text = extractNodeText(node)

            if (text != null && predicate(text) && text.length < bestLen) {
                bestLen = text.length
                bestText = text
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        return bestText
    }

    private fun findTextByResourceIds(
        node: AccessibilityNodeInfo,
        ids: Set<String>,
        extractor: (String) -> String?
    ): String? {
        if (node.viewIdResourceName in ids) {
            val text = extractNodeText(node)
            if (text != null) {
                val res = extractor(text)
                if (res != null) return res
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByResourceIds(child, ids, extractor)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * Fallback that ONLY looks for EditText nodes (URL bars, search bars)
     * that weren't caught by resource ID matching. This avoids using
     * arbitrary page text as the URL.
     */
    private fun traverseForFallbackEditTexts(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        onFound: (String?, String?) -> Unit
    ) {
        var url: String? = null
        var query: String? = null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val text = extractNodeText(node)

            if (text != null && text.length >= MIN_QUERY_LENGTH) {
                val isEditText = node.className?.toString() == "android.widget.EditText"

                if (isEditText && !isGenericSearchHint(text)) {
                    if (url == null && looksLikeUrl(text) && text.length >= MIN_URL_LENGTH) {
                        url = text
                    } else if (query == null && packageName == GOOGLE_PACKAGE && !looksLikeUrl(text)) {
                        query = text
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        onFound(url, query)
    }

    private fun extractNodeText(node: AccessibilityNodeInfo): String? {
        if (!node.text.isNullOrBlank()) return node.text.toString()
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) return desc
        return null
    }

    private fun extractFromEvent(event: AccessibilityEvent?): String? {
        if (event == null) return null
        if (event.text.isNotEmpty()) {
            val combined = event.text.joinToString(" ")
            if (combined.isNotBlank()) return combined
        }
        val desc = event.contentDescription?.toString()
        if (!desc.isNullOrBlank()) return desc
        return null
    }

    private fun findGoogleSemanticQuery(rootNode: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val className = node.className?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val contentDescription = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = extractNodeText(node)

            val isSearchControl = className.contains("edittext") ||
                    className.contains("autocomplete") ||
                    className.contains("searchview") ||
                    isGoogleSearchId(viewId) ||
                    contentDescription.contains("search query") ||
                    contentDescription.contains("current query") ||
                    contentDescription.contains("search for")

            if (isSearchControl && text != null && isUsableGoogleQuery(text)) {
                return GoogleSignalParser.cleanQuery(text)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        return null
    }

    private fun isGoogleSearchId(viewId: String): Boolean {
        val lower = viewId.lowercase()
        return lower.contains("search") ||
                lower.contains("omnibox") ||
                lower.contains("query")
    }

    private fun isUrlBarId(viewId: String): Boolean {
        val lower = viewId.lowercase()
        return lower.contains("url_bar") ||
                lower.contains("location_bar") ||
                lower.contains("omnibox") ||
                lower.contains("address_bar") ||
                lower.contains("address_field") ||
                lower.contains("web_url")
    }

    private fun isUsableGoogleQuery(text: String): Boolean {
        return text.length >= MIN_QUERY_LENGTH &&
                text.length <= 200 &&
                !isGenericSearchHint(text) &&
                !looksLikeUrl(text) &&
                !isAppUiText(text)
    }

    private fun isGoogleSearchEvent(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase() ?: ""
        val viewId = event.source?.viewIdResourceName?.lowercase() ?: ""
        return className.contains("edittext") ||
                className.contains("autocomplete") ||
                viewId.contains("search") ||
                viewId.contains("omnibox") ||
                viewId.contains("query")
    }

    // ── YouTube Content Extraction ───────────────────────────────

    /**
     * Extract the video title from the YouTube app's accessibility tree.
     *
     * Strategy:
     * 1. Window title (often contains video title, especially for Custom Tabs)
     * 2. Page scan for the longest non-UI TextView text
     * 3. Event text as fallback
     *
     * We specifically filter out UI labels (Subscribe, views, likes, timestamps)
     * to avoid false matches on non-content text.
     */
    private fun extractYouTubeTitle(
        rootNode: AccessibilityNodeInfo,
        windowTitle: String?,
        event: AccessibilityEvent?
    ): String? {
        // Strategy 1: Window title
        // YouTube video pages and Custom Tabs often set the window title to
        // the video title (e.g., "How to code - YouTube").
        if (windowTitle != null && windowTitle.isNotBlank() &&
            !isAppUiText(windowTitle) && !looksLikeUrl(windowTitle)
        ) {
            return windowTitle.trim()
        }

        // Strategy 2: Scan page text for a likely video title
        // YouTube can use custom view classes (ChipView, custom containers)
        // for the title, not just android.widget.TextView. So we check any
        // visible non-UI text of suitable length.
        var bestTitle: String? = null
        var bestLen = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val text = extractNodeText(node)
            val className = node.className?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (text != null) {
                val trimmed = text.trim()
                // Accept any visible text that fits a video-title profile.
                // We don't restrict to "textview" class because YouTube's
                // custom UI components use various class names.
                if (trimmed.length in 3..200 &&
                    !isGenericSearchHint(trimmed) &&
                    !isAppUiText(trimmed) &&
                    !looksLikeUrl(trimmed) &&
                    !trimmed.contains("Subscribe", ignoreCase = true) &&
                    !trimmed.contains("subscriber", ignoreCase = true) &&
                    !trimmed.contains("views", ignoreCase = true) &&
                    !trimmed.contains("likes", ignoreCase = true) &&
                    !trimmed.contains("ago", ignoreCase = true) &&
                    !trimmed.startsWith("#") &&  // Skip standalone hashtags
                    !trimmed.matches(Regex("^[\\d:.kKMbB]+$")) &&
                    // Prefer nodes with text-related class names, but don't require them
                    (className.contains("textview") ||
                     className.contains("text") ||
                     className.contains("label") ||
                     className.contains("title") ||
                     className.contains("description") ||
                     // Also accept nodes that clearly aren't buttons/controls
                     (!className.contains("button") &&
                      !className.contains("imageview") &&
                      !className.contains("edittext")))) {
                    // Longer text is more likely to be the video title
                    if (trimmed.length > bestLen) {
                        bestLen = trimmed.length
                        bestTitle = trimmed
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }

        if (bestTitle != null) {
            Log.d(TAG, "YOUTUBE_TITLE_FOUND=$bestTitle")
            return bestTitle
        }

        // Strategy 3: Event text fallback
        if (event != null) {
            val eventText = extractFromEvent(event)
            if (eventText != null && !isGenericSearchHint(eventText) && !isAppUiText(eventText)) {
                Log.d(TAG, "YOUTUBE_TITLE_FROM_EVENT=$eventText")
                return eventText.trim()
            }
        }

        return null
    }

    /**
     * Extract additional signals from the YouTube page:
     * - "Shorts" / "#shorts" label
     * - Hashtags (#topic)
     *
     * These are returned as a list of string signals that KeywordMatcher
     * can use for detection even when the URL is unavailable.
     */
    private fun extractYouTubeSignals(rootNode: AccessibilityNodeInfo): List<String> {
        val signals = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val text = extractNodeText(node)

            if (text != null) {
                val trimmed = text.trim()
                val lower = trimmed.lowercase()

                // Shorts indicator (button/chip or label)
                if (lower == "shorts" || lower == "#shorts") {
                    if (!signals.contains("shorts")) {
                        signals.add("shorts")
                        Log.d(TAG, "YOUTUBE_SIGNAL_DETECTED=shorts")
                    }
                }

                // Hashtags (e.g., "#gaming", "#tutorial")
                if (trimmed.startsWith("#") && trimmed.length in 2..50 &&
                    !trimmed.contains(" ") && !signals.contains(trimmed.lowercase())
                ) {
                    signals.add(trimmed.lowercase())
                    Log.d(TAG, "YOUTUBE_SIGNAL_DETECTED_HASHTAG=$trimmed")
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        return signals
    }

    fun isTargetPackage(packageName: String): Boolean {
        return packageName in CHROME_PACKAGES || packageName == GOOGLE_PACKAGE || packageName in YOUTUBE_PACKAGES
    }

    fun isChromePackage(packageName: String): Boolean {
        return packageName in CHROME_PACKAGES
    }

    fun isGooglePackage(packageName: String): Boolean {
        return packageName == GOOGLE_PACKAGE
    }

    /**
     * Check if a URL is any Google search URL with a specific tab (Images, Videos, News).
     * Returns the tab name (e.g., "Images", "Videos") or null if not a tab search.
     *
     * This is used to detect when a user is on Google Images/Videos/News tab
     * and ensure blocked queries are caught regardless of which tab they use.
     */
    fun isGoogleTabSearch(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.contains("tbm=isch") || url.contains("tbm%3Disch") -> "Images"
            url.contains("tbm=vid") || url.contains("tbm%3Dvid") -> "Videos"
            url.contains("tbm=nws") || url.contains("tbm%3Dnws") -> "News"
            url.contains("tbm=shop") || url.contains("tbm%3Dshop") -> "Shopping"
            else -> null
        }
    }

    fun isYouTubePackage(packageName: String): Boolean {
        return packageName in YOUTUBE_PACKAGES
    }

    private fun looksLikeUrl(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                (lower.contains(".") && !lower.contains(" ") && lower.length > 4) ||
                lower.startsWith("www.")
    }

    private fun isGenericSearchHint(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf("search", "ask anything", "type here", "search query", "search or type url")
    }

    private fun isAppUiText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("google") ||
               lower.startsWith("chrome") ||
               lower in setOf("search", "settings", "discover", "saved", "updates", "collections", "new tab", "bookmarks", "history", "downloads") ||
               lower.length > 100
    }
}
