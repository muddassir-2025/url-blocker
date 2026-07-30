package com.example.url_blocker.extractor

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.url_blocker.matching.ContentSnapshot

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
            return ContentSnapshot(packageName, null, null, titleFromEvent)
        }

        var url: String? = null
        var query: String? = null
        var title: String? = null

        // 1. Try to extract URL explicitly from known URL bars (Chrome)
        url = findTextByResourceIds(rootNode, URL_BAR_IDS) { text ->
            if (text.length >= MIN_URL_LENGTH) text else null
        }

        // 2. Try to extract Query explicitly from known Search bars (Google)
        if (packageName == GOOGLE_PACKAGE) {
            query = findTextByResourceIds(rootNode, SEARCH_BAR_IDS) { text ->
                if (text.length >= MIN_QUERY_LENGTH && !isGenericSearchHint(text)) text else null
            }
        }

        // 3a. PRIMARY APPROACH FOR GOOGLE: extract query from window title
        //     This is the most reliable source when the search results page is displayed
        //     and the search bar is not focused.
        if (query == null && packageName == GOOGLE_PACKAGE && windowTitle != null) {
            val extractedQuery = extractQueryFromGoogleTitle(windowTitle)
            if (extractedQuery != null) {
                query = extractedQuery
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
                    val extractedQuery = extractQueryFromGoogleTitle(eventTitle)
                    if (extractedQuery != null) {
                        query = extractedQuery
                        Log.d(TAG, "GOOGLE_QUERY_SOURCE=EVENT_TITLE query=$extractedQuery")
                    } else if (eventTitle.length in 2..100 &&
                               !isGenericSearchHint(eventTitle) &&
                               !looksLikeUrl(eventTitle)) {
                        // Fallback: if the event text is a raw query (not a formatted title),
                        // use it directly. This handles TYPE_VIEW_FOCUSED events from the
                        // search box where event.text = "porn" (raw query, no suffix).
                        query = eventTitle
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
                if (query == null) query = foundQuery
            }
        }

        // 5. If still no url or query for Google, try extracting from page content
        if (packageName == GOOGLE_PACKAGE && query == null) {
            val pageQuery = extractQueryFromGooglePage(rootNode)
            if (pageQuery != null) {
                query = pageQuery
                Log.d(TAG, "GOOGLE_QUERY_SOURCE=PAGE_SCAN query=$pageQuery")
            }
        }

        return ContentSnapshot(packageName, url, query, title)
    }

    /**
     * Extract the search query from a Google search window title.
     * Examples:
     *   "blockedkeyword - Google Search"  -> "blockedkeyword"
     *   "something - Google"              -> "something"
     *   "blockedkeyword - Google Chrome"  -> "blockedkeyword"
     */
    private fun extractQueryFromGoogleTitle(title: String): String? {
        val lower = title.lowercase()
        // Match patterns like "query - Google Search" or "query - Google"
        val googleSuffixes = listOf(" - google search", " - google", " - google chrome")
        for (suffix in googleSuffixes) {
            if (lower.endsWith(suffix)) {
                val query = title.substring(0, title.length - suffix.length).trim()
                if (query.length >= MIN_QUERY_LENGTH) {
                    Log.d(TAG, "Extracted query from Google title: $query")
                    return query
                }
            }
        }
        return null
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
        // Phase 1: Try to find text from search-related view IDs
        val searchRelated = findTextByViewIdPattern(rootNode) { viewId, text ->
            (viewId.contains("search") || viewId.contains("omnibox") || viewId.contains("query")) &&
            text.length >= MIN_QUERY_LENGTH && !isGenericSearchHint(text) && !isAppUiText(text)
        }
        if (searchRelated != null) return searchRelated

        // Phase 2: Try to find text from editable fields (search box input)
        val editText = findTextByClassName(rootNode) { className, text ->
            (className.contains("EditText") || className.contains("AutoComplete")) &&
            text.length >= MIN_QUERY_LENGTH && !isGenericSearchHint(text) && !looksLikeUrl(text)
        }
        if (editText != null) return editText

        // Phase 3: Broad fallback - scan ALL nodes and return the first text that
        // looks like a search query (short, not a generic hint, not a URL).
        // We use FIRST found (not longest) because the search box is near the top
        // of the accessibility tree, so its text would appear early in BFS order.
        // We also prefer SHORTER text (query is typically 1-5 words, not a sentence).
        val broadText = findFirstQueryLikeText(rootNode, maxDepth = 80) { text ->
            text.length in 3..40 && !isGenericSearchHint(text) && !looksLikeUrl(text)
        }
        if (broadText != null) {
            Log.d(TAG, "Found Google query (broad fallback - first query-like): $broadText")
            return broadText
        }

        return null
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

            if (text != null && viewId.startsWith(GOOGLE_PACKAGE) && predicate(viewId, text)) {
                Log.d(TAG, "Found Google query by viewId: $text ($viewId)")
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

    fun isTargetPackage(packageName: String): Boolean {
        return packageName in CHROME_PACKAGES || packageName == GOOGLE_PACKAGE
    }

    fun isChromePackage(packageName: String): Boolean {
        return packageName in CHROME_PACKAGES
    }

    fun isGooglePackage(packageName: String): Boolean {
        return packageName == GOOGLE_PACKAGE
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
