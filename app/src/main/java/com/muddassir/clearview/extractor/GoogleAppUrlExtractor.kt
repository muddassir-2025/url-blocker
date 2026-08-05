package com.muddassir.clearview.extractor

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URI

/**
 * Result of extracting website identity from the Google app's in-app browser.
 *
 * A non-null [domain] is only reported when a strong in-app-browser signal was
 * found (address-bar URL or close-button domain), so the caller can rely on
 * it directly without an extra "active" flag.
 */
data class GoogleAppExtraction(
    val url: String? = null,
    val domain: String? = null
)

/**
 * Dedicated extractor for websites opened inside the Google app
 * (com.google.android.googlequicksearchbox).
 *
 * Background
 * ----------
 * When the user taps a Google Search result, the Google app opens the page in its
 * own in-app browser. The accessibility event package name STAYS
 * com.google.android.googlequicksearchbox — the page is NOT rendered by Chrome,
 * so Chrome-specific URL-bar IDs (com.android.chrome:id/url_bar) are usually
 * absent. In this surface the reliable accessibility signals are:
 *
 *   1. The toolbar's "X" close button, whose contentDescription commonly includes
 *      the site domain, e.g. "Close, youtube.com" or "Close youtube.com".
 *   2. A bare-domain chip node in the toolbar ("youtube.com", "www.youtube.com").
 *   3. The window title, which becomes the page title (e.g. "YouTube").
 *   4. An expanded address bar (EditText) that exposes the full URL.
 *
 * Strategy & priority
 * -------------------
 *   1. Full URL from a real address/editable control (EditText or url_bar-like
 *      view IDs).
 *   2. Full URL text node — used only when the in-app-browser toolbar close
 *      button already exposed a domain (we then know we are NOT on the search
 *      results page).
 *   3. Domain from the close-button content description (strong, in-app-browser
 *      only signal — never present on the search-results page).
 *
 * IMPORTANT — anti-false-positive design:
 * The Google search results page is itself often rendered in an embedded
 * WebView and shows a bare-domain label under every result (e.g. "youtube.com").
 * Therefore bare-domain text and WebView presence alone must NEVER drive a
 * blocking decision. They are logged as diagnostics only, so the behavior can
 * be verified on-device via Logcat before any future tightening.
 *
 * The caller (ContentExtractor) decides whether to surface the result as a URL
 * for KeywordMatcher. Blocking decisions stay unchanged in KeywordMatcher.
 */
class GoogleAppUrlExtractor {

    companion object {
        const val TAG = "GoogleAppUrlExtractor"
        private const val MAX_TRAVERSAL_DEPTH = 60

        /** e.g. "Close, youtube.com", "Close youtube.com", "Close: m.youtube.com" */
        private val CLOSE_DOMAIN_REGEX =
            Regex("""(?i)close[\s,:\-–—]*(?:in\s)?([a-z0-9][a-z0-9\-]*(?:\.[a-z0-9\-]+)+)""")

        /** A bare domain such as "youtube.com", "www.youtube.com", "9gag.tv". */
        private val BARE_DOMAIN_REGEX =
            Regex("""^[a-z0-9][a-z0-9\-]*(?:\.[a-z0-9\-]+)*\.[a-z]{2,63}$""", RegexOption.IGNORE_CASE)

        private val URL_BAR_ID_PATTERNS = listOf(
            "url_bar", "location_bar", "omnibox", "address_bar",
            "address_field", "web_url", "toolbar_url"
        )

        /** Parse a domain out of a close-button content description. */
        fun parseDomainFromCloseDescription(desc: String): String? {
            val m = CLOSE_DOMAIN_REGEX.find(desc.trim()) ?: return null
            return m.groupValues[1].trim().trimEnd('.').lowercase()
        }

        /** Whether text looks like a bare hostname (no scheme, no spaces). */
        fun looksLikeBareDomain(text: String): Boolean = BARE_DOMAIN_REGEX.matches(text.trim())

        /** Whether text looks like an explicit full URL. */
        fun looksLikeFullUrl(text: String): Boolean = GoogleSignalParser.isExplicitUrl(text)

        /** Build a domain-matching URL from a bare domain. */
        fun toDomainUrl(domain: String): String = "https://${domain.trim().trimStart('.')}/"

        /**
         * Extract the hostname (without www.) from a URL or bare domain string.
         * Uses java.net.URI (not android.net.Uri) so this stays unit-testable on
         * the JVM — it deliberately mirrors KeywordMatcher.extractHost().
         */
        fun extractDomainFromUrl(url: String): String? {
            var s = url.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            val host = try { URI(s).host } catch (e: Exception) { null } ?: return null
            return host.lowercase().removePrefix("www.")
        }

        /** True when the window title looks like a Google search-results title. */
        internal fun isGoogleSearchTitle(title: String?): Boolean =
            !title.isNullOrBlank() && GoogleSignalParser.queryFromWindowTitle(title) != null

        /**
         * Pure decision logic: returns (url, inAppBrowserActive).
         *
         * Anti-false-positive rule embodied here:
         * - urlBarUrl (address/editable control) is always usable.
         * - httpCandidate (full-URL text node) is usable ONLY when closeDomain
         *   proves we are in the in-app browser — never on the search-results
         *   page, which can expose arbitrary http text.
         * - bareDomain is intentionally not passed in: it must never drive a
         *   blocking decision.
         */
        internal fun decide(
            urlBarUrl: String?,
            httpCandidate: String?,
            closeDomain: String?
        ): Pair<String?, Boolean> {
            val url = urlBarUrl ?: httpCandidate?.takeIf { closeDomain != null }
            val inAppBrowserActive = closeDomain != null || url != null
            return url to inAppBrowserActive
        }
    }

    /**
     * Walk the Google app accessibility tree and extract in-app-browser signals.
     *
     * @param packageName the event package (must be the Google app package).
     */
    fun extract(
        packageName: String,
        rootNode: AccessibilityNodeInfo?,
        windowTitle: String?,
        event: AccessibilityEvent?
    ): GoogleAppExtraction {
        if (rootNode == null) {
            Log.d(TAG, "GOOGLE_APP_EXTRACT: rootNode is null (package=$packageName)")
            return GoogleAppExtraction()
        }

        var hasWebView = false
        var closeDomain: String? = null
        var bareDomain: String? = null
        var urlBarUrl: String? = null      // full URL from an address/editable control
        var httpCandidate: String? = null  // any explicit http(s) text node

        Log.d(
            TAG,
            "GOOGLE_APP_EVENT_RECEIVED package=$packageName eventType=${event?.eventType} " +
                "windowTitle=$windowTitle isSearchResultsTitle=${isGoogleSearchTitle(windowTitle)} " +
                "rootText=${try { rootNode.text?.toString() } catch (e: Exception) { null }}"
        )

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(rootNode))
        var depth = 0

        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val text = try { extractNodeText(node) } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }

            // WebView node => a webpage is being rendered inside the Google app
            if (cls.contains("WebView", ignoreCase = true)) {
                hasWebView = true
                Log.d(TAG, "GOOGLE_APP_WEBVIEW_NODE cls=$cls viewId=$viewId")
            }

            // Full URL in a real address/editable control
            if (text != null && looksLikeFullUrl(text)) {
                val isUrlControl = cls.contains("EditText", ignoreCase = true) ||
                    URL_BAR_ID_PATTERNS.any { viewId.contains(it, ignoreCase = true) }
                if (urlBarUrl == null && isUrlControl) {
                    urlBarUrl = text.trim()
                    Log.i(
                        TAG,
                        "GOOGLE_APP_URL_CANDIDATE_ADDRESS_BAR url=$urlBarUrl (cls=$cls, viewId=$viewId, text=$text)"
                    )
                }
                if (httpCandidate == null && !isUrlControl) {
                    httpCandidate = text.trim()
                    Log.d(TAG, "GOOGLE_APP_URL_CANDIDATE_HTTP_TEXT url=$httpCandidate (cls=$cls, viewId=$viewId)")
                }
            }

            // Domain in close-button content description (strong in-app-browser signal)
            if (closeDomain == null && !cls.contains("WebView", ignoreCase = true)) {
                val parsed = parseDomainFromCloseDescription(desc.orEmpty())
                    ?: parseDomainFromCloseDescription(text.orEmpty())
                if (parsed != null) {
                    closeDomain = parsed
                    Log.i(
                        TAG,
                        "GOOGLE_APP_CLOSE_DOMAIN_DETECTED domain=$parsed (cls=$cls, viewId=$viewId, desc=$desc, text=$text)"
                    )
                }
            }

            // Bare-domain text node (weak signal; gated on in-app-browser context)
            if (bareDomain == null && text != null && looksLikeBareDomain(text) && !looksLikeFullUrl(text)) {
                bareDomain = text.trim().lowercase()
                Log.d(TAG, "GOOGLE_APP_DOMAIN_CANDIDATE_BARE domain=$bareDomain (cls=$cls, viewId=$viewId)")
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            try { node.recycle() } catch (e: Exception) {}
            depth++
        }

        // Decide result. bareDomain is deliberately NOT part of the decision
        // (diagnostic only) — the search-results page renders one domain label
        // per result, so bare-domain text alone is not a reliable signal.
        val (url, inAppBrowserActive) = decide(urlBarUrl, httpCandidate, closeDomain)
        val domain = when {
            url != null -> extractDomainFromUrl(url)
            closeDomain != null -> closeDomain
            else -> null
        }

        if (!inAppBrowserActive && hasWebView) {
            // Triage aid: a WebView was rendered but we deliberately made no
            // decision because neither a close-domain nor an exposed URL was
            // found. This avoids treating the search-results page as a website.
            Log.d(
                TAG,
                "GOOGLE_APP_WEBVIEW_BUT_NOT_INAPP: WebView present but no close-domain/URL signal; " +
                    "not treated as in-app browser (avoids search-results false positives)"
            )
        }

        Log.i(
            TAG,
            "GOOGLE_APP_EXTRACT_RESULT package=$packageName inAppBrowserActive=$inAppBrowserActive " +
                "url=$url domain=$domain (webView=$hasWebView, closeDomain=$closeDomain, " +
                "bareDomainDiagnosticOnly=$bareDomain)"
        )

        return GoogleAppExtraction(url = url, domain = domain)
    }

    private fun extractNodeText(node: AccessibilityNodeInfo): String? {
        if (!node.text.isNullOrBlank()) return node.text.toString()
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) return desc
        return null
    }
}
