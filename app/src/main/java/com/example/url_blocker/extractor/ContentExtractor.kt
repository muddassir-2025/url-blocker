package com.example.url_blocker.extractor

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.url_blocker.matching.ContentSnapshot
import com.example.url_blocker.matching.QueryConfidence
import com.example.url_blocker.matching.QuerySource
import java.util.Locale

/**
 * Unified extractor for URLs, queries, and titles from Chrome and Google apps.
 */
class ContentExtractor {

    /** Extracts websites opened inside the Google app's in-app browser. */
    private val googleAppUrlExtractor = GoogleAppUrlExtractor()

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

        /**
         * Strong text phrases that only appear on Chrome's actual incognito
         * new-tab page. Deliberately narrow so ordinary page text (e.g. an
         * article merely discussing "incognito mode") never triggers a false
         * block — the app's rule for Chrome is that page text is NEVER used to
         * block, and incognito detection must respect that too.
         */
        val INCOGNITO_STRONG_TEXTS = listOf(
            "you've gone incognito",
            "you have gone incognito",
            "now you can browse privately"
        )

        /** True when [text] contains a strong incognito-only phrase. */
        fun matchesStrongIncognitoText(text: String): Boolean {
            val lower = text.lowercase(Locale.ROOT)
            return INCOGNITO_STRONG_TEXTS.any { lower.contains(it) }
        }

        // Pre-compiled patterns for isActiveIncognitoStateText — this runs on
        // every node in the hot BFS during 500ms polling, so regex compilation
        // must happen once, not per call.
        private val ACTIVE_STATE_TAB_OR_WINDOW = Regex("incognito\\s+(tab|tabs|window|windows)\\b")
        private val ACTIVE_STATE_TAB_OR_WINDOW_REV = Regex("\\b(tab|tabs|window|windows)\\s+incognito\\b")
        private val ACTIVE_STATE_COUNT = Regex("incognito\\s*[,:]?\\s*\\d")
        private val ACTIVE_STATE_COUNT_REV = Regex("\\d+\\s+incognito")
        private val ACTIVE_STATE_OPEN_OFFER = Regex("open\\s+(a|an|a new|new)?\\s*incognito")

        // Normal-mode offer markers for isIncognitoChromeIdentifier — matched
        // against both underscore resource ids and CamelCase class names (see
        // the helper). Hoisted so the hot BFS doesn't rebuild the list per node.
        // "menu" excludes Chrome's overflow-menu items (e.g. "New Incognito tab"
        // carries an incognito-named menu id) and "new_incognito" covers menu
        // ids that omit the "menu" token (e.g. com.android.chrome:id/new_incognito_tab)
        // — both are offers, not an active session. Real incognito-only chrome
        // (incognito_new_tab_page_title, IncognitoNewTabPageView) never contains
        // the "new_incognito" substring, so these markers cannot suppress it.
        private val INCOGNITO_OFFER_MARKERS = listOf(
            "toggle",
            "menu",
            "new_incognito",
            "model_selector", "mode_selector",
            "modelselector", "modeselector", "tabmodelselector",
            // Normal-Chrome NTP offers: the new-tab page shows an "Incognito"
            // shortcut tile whose resource id mentions "incognito" but is an
            // OFFER, not an active session — opening normal Chrome must never
            // block. None of these appear in the genuine incognito-only ids
            // (incognito_new_tab_page_title, incognito_tab_switcher,
            // incognito_close_all_button, IncognitoNewTabPageView).
            // NOTE: "ntp" is deliberately NOT a marker — a real incognito NTP
            // id (e.g. com.android.chrome:id/incognito_ntp_*) could contain it
            // and must keep matching.
            "shortcut", "tile"
        )

        /**
         * True when [text] describes an ACTIVE incognito session (a tab count,
         * the tab-switcher chip, or a "Close Incognito tabs" action) rather
         * than an OFFER to start incognito.
         *
         * This is the critical discriminator that keeps NORMAL Chrome from
         * being blocked. Chrome's normal new-tab page and overflow menu expose
         * the word "incognito" in offer contexts:
         *   - NTP footer card: "You can also browse privately with Incognito mode"
         *   - Shortcut tile: bare "Incognito"
         *   - Tile action: "Open an Incognito window"
         *   - Overflow menu item: "New Incognito tab"
         *   - Tab switcher: "Switch to Incognito"
         * None of those may ever trigger a block. Only wording that describes
         * existing incognito state is trusted (e.g. "Incognito, 2 tabs",
         * "2 Incognito tabs", "Close Incognito tabs", "Incognito tabs").
         */
        fun isActiveIncognitoStateText(text: String): Boolean {
            val lower = text.lowercase(Locale.ROOT)
            if (!lower.contains("incognito")) return false

            // ── Normal-Chrome OFFER text: never match ──────────────────────
            if (lower.contains("you can also")) return false                 // NTP footer card
            if (lower.contains("switch to incognito")) return false          // tab-switcher offer
            if (lower.startsWith("new incognito")) return false              // overflow menu item
            if (ACTIVE_STATE_OPEN_OFFER.containsMatchIn(lower)) {
                return false                                                  // tile action offers
            }

            // ── ACTIVE-state context markers ──────────────────────────────
            val hasTabOrWindow =
                ACTIVE_STATE_TAB_OR_WINDOW.containsMatchIn(lower) ||
                    ACTIVE_STATE_TAB_OR_WINDOW_REV.containsMatchIn(lower)
            val hasCount =
                ACTIVE_STATE_COUNT.containsMatchIn(lower) ||
                    ACTIVE_STATE_COUNT_REV.containsMatchIn(lower)
            val hasClose = lower.contains("close incognito") || lower.contains("close all incognito")
            return hasTabOrWindow || hasCount || hasClose
        }

        /**
         * True when a node's resource id or class name marks it as Chrome's own
         * incognito UI chrome (the incognito new-tab page view, incognito tab
         * switcher, incognito NTP title, "Close Incognito tabs" button...).
         *
         * Web page content inside a WebView never carries Chrome resource ids
         * or incognito-named class names, so this signal cannot be confused
         * with article text — it detects the incognito state itself, even when
         * Chrome doesn't expose the visible heading text. Views that exist in
         * NORMAL Chrome too (the tab-switcher's "Incognito" toggle pill and
         * tab-model selector) are explicitly excluded so opening the normal
         * tab switcher never blocks.
         */
        fun isIncognitoChromeIdentifier(viewId: String?, className: String?): Boolean {
            val id = viewId?.lowercase(Locale.ROOT) ?: ""
            val cls = className?.lowercase(Locale.ROOT) ?: ""
            if (!id.contains("incognito") && !cls.contains("incognito")) return false
            val combined = "$id $cls"
            // Normal-mode offers that merely mention incognito: the tab-switcher
            // toggle pill and the tab-model selector exist when viewing NORMAL
            // tabs too and must never block. Chromium uses BOTH underscore view
            // ids (com.android.chrome:id/incognito_tab_model_selector) and
            // CamelCase class names (org.chromium...IncognitoTabModelSelector), so
            // match against the underscore-stripped form as well.
            val compact = combined.replace("_", "")
            return INCOGNITO_OFFER_MARKERS.none { combined.contains(it) || compact.contains(it) }
        }
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
                querySource = if (queryFromTitle != null) QuerySource.WINDOW_TITLE else QuerySource.NONE,
                incognito = packageName in CHROME_PACKAGES &&
                    detectIncognitoMode(null, windowTitle, event)
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

            // YouTube's in-app browser: tapping a link in a video description or
            // comment opens the site in an embedded WebView while the package stays
            // YouTube. Reuse the embedded-browser extractor (address bar / close
            // button domain) so those websites are detected like any other URL.
            var ytUrl: String? = null
            val ytInApp = googleAppUrlExtractor.extract(packageName, rootNode, windowTitle, event)
            if (ytInApp.url != null) {
                ytUrl = ytInApp.url
                Log.i(TAG, "YOUTUBE_INAPP_URL_DETECTED url=$ytUrl")
            } else if (ytInApp.domain != null) {
                ytUrl = GoogleAppUrlExtractor.toDomainUrl(ytInApp.domain)
                Log.i(TAG, "YOUTUBE_INAPP_DOMAIN_DETECTED domain=${ytInApp.domain} -> url=$ytUrl")
            }

            return ContentSnapshot(
                packageName = packageName,
                url = ytUrl,  // null on normal YouTube screens (no in-app browser)
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

        // 6. IN-APP / EMBEDDED BROWSER URL (any package)
        //    The Google app, YouTube, and third-party apps render websites in an
        //    embedded WebView while the accessibility package stays the host app.
        //    GoogleAppUrlExtractor only reports a URL/domain when a STRONG signal
        //    exists (close-button domain, exposed address/editable bar), so running
        //    it for every package cannot create false positives from ordinary app
        //    UI or search-results pages.
        val inApp = googleAppUrlExtractor.extract(packageName, rootNode, windowTitle, event)
        // Guard with url == null: never clobber a URL already extracted from
        // URL_BAR_IDS or the window title (steps 1/5a). Without this guard, a
        // real full URL (with path/query) could be replaced by a bare-domain
        // URL, breaking e.g. youtube.com/shorts path matching.
        if (url == null && inApp.url != null) {
            url = inApp.url
            Log.i(TAG, "INAPP_URL_DETECTED pkg=$packageName url=$url")
        } else if (url == null && inApp.domain != null) {
            // A non-null domain implies an embedded browser is active (the
            // extractor only reports it when a strong signal was found).
            url = GoogleAppUrlExtractor.toDomainUrl(inApp.domain)
            Log.i(TAG, "INAPP_DOMAIN_DETECTED pkg=$packageName domain=${inApp.domain} -> url=$url")
        }

        if (title == null && !windowTitle.isNullOrBlank()) {
            title = windowTitle
        }
        val incognito = packageName in CHROME_PACKAGES &&
            detectIncognitoMode(rootNode, windowTitle, event)
        return ContentSnapshot(packageName, url, query, title, queryConfidence, querySource, incognito)
    }

    /**
     * Detect Chrome incognito / private browsing mode with a single-pass,
     * early-exit BFS so it is cheap even during the 500ms polling loop.
     *
     * Multi-signal, WebView-aware:
     *   1. STRONG NTP phrases ("You've gone incognito", "Now you can browse
     *      privately") — trusted only OUTSIDE a WebView subtree. The real
     *      incognito NTP heading is NATIVE Chrome UI (its container carries the
     *      incognito_new_tab_page_title view id / IncognitoNewTabPageView
     *      class), so restricting to native nodes cannot suppress real
     *      detection — while a NORMAL page that merely QUOTES the phrase
     *      (e.g. an article explaining incognito mode) is WebView content and
     *      must never cause a block.
     *   2. ACTIVE-STATE chrome text/contentDescriptions (tab counter "Incognito,
     *      2 tabs", tab-switcher chip "Incognito tabs", "Close Incognito tabs")
     *      — trusted only OUTSIDE a WebView subtree (webpage image alt-text and
     *      article text are also surfaced as description/text and live under a
     *      WebView), and for descriptions only on nodes the user can see.
     *   3. CHROME incognito UI chrome identified by resource id / class name
     *      (isIncognitoChromeIdentifier) — detects the incognito state itself
     *      even when Chrome doesn't expose the visible heading text.
     *
     * The offer-vs-state discriminator (isActiveIncognitoStateText) keeps NORMAL
     * Chrome safe: the normal NTP footer ("You can also browse privately with
     * Incognito mode"), the "Incognito" shortcut tile, "New Incognito tab" menu
     * item and "Switch to Incognito" are all offers and never match. This
     * respects the app's rule that Chrome page text is never used to block.
     */
    private fun detectIncognitoMode(
        rootNode: AccessibilityNodeInfo?,
        windowTitle: String?,
        event: AccessibilityEvent?
    ): Boolean {
        // NOTE: the window title and event text are deliberately NOT trusted for
        // strong-phrase matching. For NORMAL Chrome the active accessibility
        // window title IS the page's <title>, and window-state events often carry
        // it too — so an ordinary page/article whose title merely QUOTES a
        // strong phrase (e.g. "You've gone incognito: what really happens to
        // your data") would false-block normal Chrome when that page opens or
        // is restored. The real incognito NTP never sets its window title to a
        // strong phrase (it's "New Tab" / "Incognito"), so these checks are
        // near-pure false-positive risk. Detection relies solely on the
        // native (non-WebView) tree scan below.
        if (rootNode == null) return false

        // NOTE: there is deliberately NO tree-wide "overflow menu open" state
        // gate here. An earlier version scanned the whole tree for menu-like
        // class names (AppMenu, MenuPopup, ListMenuItemView...) and suppressed
        // the weak incognito signals while it believed the ⋮ menu was open.
        // That broke detection completely: Chrome's toolbar overflow BUTTON
        // class (org.chromium.chrome.browser.appmenu.AppMenuButton) is always
        // present in the tree — normal AND incognito — so the gate was
        // effectively always "menu open" and every weak incognito signal got
        // suppressed. The three-dot-menu false positive is instead prevented
        // by precise, deterministic rules inside the signals themselves:
        //   - "New Incognito tab"/"New Incognito window" menu text is rejected
        //     by isActiveIncognitoStateText (startsWith "new incognito").
        //   - menu item view ids contain "menu" and are excluded by the
        //     INCOGNITO_OFFER_MARKERS list in isIncognitoChromeIdentifier.
        //   - the tab-switcher Incognito toggle / tab-model selector are
        //     excluded by the toggle / model_selector markers.
        // None of those rules can fire on a real incognito window, so detection
        // stays live for shortcut and menu-driven opens alike.

        // BFS carrying an "inside WebView" flag so web-page content (which lives
        // under a WebView node) can never be mistaken for Chrome's own incognito
        // UI chrome.
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(rootNode to false)
        var depth = 0
        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val (node, insideWebView) = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            val childInsideWebView = insideWebView || className.contains("WebView", ignoreCase = true)

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            // ALL signals (1. strong NTP phrases, 2. active-state text/desc,
            // 3. incognito chrome id/class) are trusted ONLY outside WebView
            // subtrees. Webpage content lives under a WebView node and is never
            // Chrome's own incognito UI — a normal page that merely QUOTES
            // "You've gone incognito" (e.g. an article explaining incognito
            // mode) must never cause a block. The real incognito NTP heading is
            // NATIVE Chrome UI (incognito_new_tab_page_title view id /
            // IncognitoNewTabPageView class), so this cannot suppress real
            // detection.
            if (!childInsideWebView) {
                // 1. Strong NTP phrases ("You've gone incognito", ...).
                if (!text.isNullOrBlank() && matchesStrongIncognitoText(text)) {
                    Log.i(TAG, "INCOGNITO_DETECTED via strong text: $text")
                    return true
                }
                if (!desc.isNullOrBlank() && matchesStrongIncognitoText(desc)) {
                    Log.i(TAG, "INCOGNITO_DETECTED via strong description: $desc")
                    return true
                }

                val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
                if (!text.isNullOrBlank() && visible && isActiveIncognitoStateText(text)) {
                    Log.i(TAG, "INCOGNITO_DETECTED via active-state text: $text")
                    return true
                }
                if (!desc.isNullOrBlank() && visible && isActiveIncognitoStateText(desc)) {
                    Log.i(TAG, "INCOGNITO_DETECTED via active-state description: $desc")
                    return true
                }
                // Chrome's own incognito UI chrome (view id / class name).
                // Catches the incognito state even when Chrome doesn't expose
                // the NTP heading text to accessibility (the user's reported
                // gap: direct open of an incognito tab wasn't detected).
                if (isIncognitoChromeIdentifier(node.viewIdResourceName, className)) {
                    Log.i(TAG, "INCOGNITO_DETECTED via chrome view id/class: viewId=${node.viewIdResourceName} class=$className")
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child to childInsideWebView)
            }
            depth++
        }
        return false
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

    /**
     * True when the active accessibility tree contains a WebView node, which
     * indicates the foreground app may be rendering a website in an embedded
     * browser (in-app browser). Used by the service to extend monitoring to
     * non-target packages that show web content.
     */
    fun hasWebView(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            if (node.className?.toString()?.contains("WebView", ignoreCase = true) == true) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            depth++
        }
        return false
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
