package com.muddassir.clearview.extractor

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.muddassir.clearview.matching.ContentSnapshot
import com.muddassir.clearview.matching.FeedVideoCard
import com.muddassir.clearview.matching.QueryConfidence
import com.muddassir.clearview.matching.QuerySource
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
        // Pre-emptive feed-title scan: cap the number of candidate titles kept
        // per scan so the identity string stays bounded and a feed with many
        // cards can't bloat memory/dedup. WebView-rendered YouTube cards sit
        // DEEP in the tree (on-device evidence: Google-app tab chips at depth
        // 99+, shared MAX_TRAVERSAL_DEPTH=50 silently missed them), so this scan
        // gets the same deep bound + node budget as the chip scan — a shallow
        // window would make the whole feature a silent no-op.
        private const val MAX_FEED_TITLES = 20
        private const val FEED_SCAN_MAX_DEPTH = 150
        private const val FEED_SCAN_NODE_BUDGET = 2000
        // Feed-card shape guard: YouTube cards span most of the screen width
        // and are TALL (16:9 thumbnail + title + metadata ≈ 250-400px on a
        // phone), while Chrome UI tiles are short. On-device evidence: Chrome's
        // NTP tiles ("Ask AI Mode", "New Incognito tab") measure 490x110 on a
        // ~2400px-tall screen — they passed the old 90px gate and were
        // cropped/analyzed as fake thumbnails while the URL bar still read
        // m.youtube.com (a stale URL during the NTP transition defeats URL-only
        // gating). The height gate is therefore a RELATIVE fraction of the
        // screen height (~8% ≈ 190px on that device) so it adapts across
        // screen sizes: it stays well above UI-tile height (NTP tiles are only
        // ~4-5% of screen height) while never excluding a real card on a small
        // or landscape screen. Combined with the YouTube-card metadata check in
        // extractYouTubeFeedCards this keeps ONLY real video cards (which span
        // their thumbnail) in the card list.
        private const val MIN_CARD_WIDTH_FRACTION = 0.45f
        private const val MIN_CARD_HEIGHT_FRACTION = 0.08f

        // The Google app's results page nests its WebView — and thus the tab
        // chips — extremely deep in the accessibility tree. On-device evidence:
        // after enabling touch-exploration capability, the "All" tab chip is
        // exposed as a WebView HTML element (class=android.view.View) at depth
        // 99, with the other chips at or just beyond that depth. The shared
        // MAX_TRAVERSAL_DEPTH=50 silently missed them entirely. The chip scan
        // therefore gets its own much deeper limit plus a node budget so the
        // 500ms polling loop stays bounded on large WebView subtrees.
        private const val CHIP_SCAN_MAX_DEPTH = 150
        private const val CHIP_SCAN_NODE_BUDGET = 2000

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

        // Known Search bar IDs (Google App). "googleapp_srp_*" are the search
        // results page (srp) search box ids — observed on the user's device as
        // googleapp_srp_search_box_text carrying the active query "women".
        // Listing them here extracts the query with HIGH confidence instead of
        // relying on the broader semantic scan.
        private val SEARCH_BAR_IDS = setOf(
            "$GOOGLE_PACKAGE:id/googleapp_search_box",
            "$GOOGLE_PACKAGE:id/googleapp_srp_search_box",
            "$GOOGLE_PACKAGE:id/googleapp_srp_search_box_text",
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
        // Normal-mode OFFER actions that START incognito — "open (in) (a new)
        // incognito (tab|window)". The "in" form is Chrome's bookmark /
        // context-menu action ("Open in Incognito tab") — a reported false
        // positive: it used to slip past this regex and match the active-state
        // tab/window pattern below, blocking normal browsing when a bookmark's
        // 3-dot menu was opened.
        private val ACTIVE_STATE_OPEN_OFFER = Regex("open\\s+(?:in\\s+)?(?:a\\s+|an\\s+|a new\\s+|new\\s+)?incognito")

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
            "shortcut", "tile",
            // NORMAL Chrome's new-tab page shows a floating action button
            // (com.android.chrome:id/incognito_button, desc "New Incognito
            // tab") to OPEN incognito — an OFFER, not an active session
            // (observed on-device as a false positive: simply opening normal
            // Chrome blocked). Real incognito-only ids (incognito_new_tab_page_title,
            // incognito_tab_switcher, incognito_close_all_button,
            // IncognitoNewTabPageView) never contain this substring, so it
            // cannot suppress real detection.
            "incognito_button"
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

        // udm layout-code parameter matchers. Raw form: &udm=2& / ?udm=2&
        // Encoded form (Chrome may URL-encode the separator): udm%3D2%26
        private val UDM_RAW = Regex("""(?:^|[?&])udm=(\d+)(?:&|$)""")
        private val UDM_ENCODED = Regex("""(?:^|[?&])udm%3D(\d+)(?:&|%26|$)""")

        // Feed card metadata separator (spaced or unspaced bullets / pipes),
        // shared by feedTitleSegment and channelFromCardText — both run per
        // candidate node / per card inside the 500ms polling hot path, so the
        // regex is compiled once here instead of on every call.
        private val CARD_SEPARATOR = Regex("\\s*[•·|]\\s*")

        /**
         * Map a Google tab chip label (as exposed by the accessibility tree)
         * to a canonical tab name, or null when the label is not a Google tab.
         * Handles the tab names used by both the Google app and Chrome.
         */
        fun googleTabFromLabel(label: String?): String? {
            if (label.isNullOrBlank()) return null
            return when (label.trim().lowercase(Locale.ROOT)) {
                "all" -> "All"
                "images" -> "Images"
                "videos" -> "Videos"
                "news" -> "News"
                "shopping" -> "Shopping"
                "books" -> "Books"
                "finance" -> "Finance"
                else -> null
            }
        }

        /**
         * Returns the Google search tab name ("Images", "Videos", "News",
         * "Shopping") from a search URL, or null when the URL is not a
         * tabbed Google search.
         *
         * Detects both the legacy `tbm` verticals (tbm=isch / tbm=vid / ...)
         * and Google's newer `udm` layout codes, which Chrome's mobile Google
         * search uses (e.g. udm=2 = Images, udm=7/39 = Videos).
         */
        fun googleTabFromUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null

            // Legacy tbm verticals first.
            val tbmTab = when {
                url.contains("tbm=isch") || url.contains("tbm%3Disch") -> "Images"
                url.contains("tbm=vid") || url.contains("tbm%3Dvid") -> "Videos"
                url.contains("tbm=nws") || url.contains("tbm%3Dnws") -> "News"
                url.contains("tbm=shop") || url.contains("tbm%3Dshop") -> "Shopping"
                else -> null
            }
            if (tbmTab != null) return tbmTab

            // Newer udm layout codes (match the parameter value with proper
            // boundaries so e.g. udm=28 cannot be mistaken for udm=2).
            val udm = UDM_RAW.find(url)?.groupValues?.get(1)
                ?: UDM_ENCODED.find(url)?.groupValues?.get(1)
            return when (udm) {
                "2" -> "Images"
                "7", "39" -> "Videos"
                "12" -> "News"
                "28", "37" -> "Shopping"
                else -> null
            }
        }

        /**
         * True when a URL belongs to any YouTube domain (youtube.com,
         * m.youtube.com, www.youtube.com, music.youtube.com, ...).
         */
        fun isYouTubeDomain(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val host = GoogleAppUrlExtractor.extractDomainFromUrl(url) ?: return false
            return host == "youtube.com" || host.endsWith(".youtube.com")
        }

        /**
         * True when a Chrome window title looks like a YouTube page.
         * Chrome appends " - YouTube" to every YouTube tab title
         * (e.g. "Video Title - YouTube"), even in incognito where the
         * URL is hidden from the accessibility tree.
         */
        fun isYouTubeTitle(title: String?): Boolean {
            if (title.isNullOrBlank()) return false
            return title.endsWith(" - YouTube", ignoreCase = true)
        }

        /**
         * YouTube UI screens whose " - YouTube" window titles are NOT videos
         * (home feed, Shorts, Subscriptions, ...). Shared by
         * [youtubeTitleFromChromeWindowTitle] and [isYouTubeCardText] so a
         * " - YouTube"-suffixed UI screen title (e.g. "Home - YouTube" from the
         * tab strip) can never be mistaken for a video card.
         */
        private val YOUTUBE_UI_SCREENS = setOf(
            "youtube", "shorts", "home", "subscriptions", "history",
            "watch later", "liked videos", "playlists", "library", "settings",
            "search"
        )

        /**
         * Extract the video title from a Chrome window title on a YouTube page.
         *
         * Chrome prefixes its accessibility window title with "Chrome: " and
         * YouTube appends " - YouTube", e.g.
         *   "Chrome: American Doctor SHOCKED By Foreign Sex Ed Videos - YouTube"
         * -> "American Doctor SHOCKED By Foreign Sex Ed Videos"
         *
         * Returns null when the title is not a video title (YouTube UI screens
         * like the home feed, Shorts, Subscriptions, ...).
         */
        fun youtubeTitleFromChromeWindowTitle(title: String?): String? {
            if (title.isNullOrBlank()) return null
            var t = title.trim()
            if (t.startsWith("Chrome:", ignoreCase = true)) {
                t = t.substringAfter(":").trim()
            }
            if (!t.endsWith(" - YouTube", ignoreCase = true)) return null
            t = t.substring(0, t.length - " - YouTube".length).trim()
            if (t.isBlank() || t.length > 200) return null
            // YouTube UI screens that are not videos.
            if (t.lowercase(Locale.ROOT) in YOUTUBE_UI_SCREENS) {
                return null
            }
            return t
        }

        /**
         * Isolate the title segment of a feed card's accessibility string.
         * Some WebViews expose a feed card as ONE combined string:
         * "Title by Channel • 1.2M views • 3 days ago". The title is the
         * segment before the first metadata separator — the rest (views, age,
         * channel) must not participate in keyword matching or dedup. Regex
         * (not literal separators) so bullets with or without surrounding
         * spaces are both split. Pure so unit tests can cover it without a tree.
         */
        fun feedTitleSegment(text: String?): String {
            if (text.isNullOrBlank()) return ""
            val trimmed = text.trim()
            val firstSegment = trimmed.split(CARD_SEPARATOR).first().trim()
            return if (firstSegment.isNotEmpty()) firstSegment else trimmed
        }

        /**
         * Extract the channel name from a feed card's combined accessibility
         * string. YouTube's WebView exposes a card as ONE string in the shape
         * "Title by Channel • 1.2M views • 3 days ago" — the channel sits
         * between the " by " separator and the first metadata bullet. Uses the
         * LAST " by " in the title segment so a title that itself contains
         * " by " (e.g. "Songs by Adele • 2M views") still yields the trailing
         * channel.
         *
         * Conservative by design: extraction requires metadata bullets (the
         * channel-by pattern is only trusted when the full card carries the
         * "views/ago" metadata that real YouTube cards expose). A title that
         * merely contains " by " with no metadata is ambiguous and ignored —
         * this prevents a sentence-y title ("Songs by Adele to Cry To") from
         * producing a junk channel. Also rejects implausibly long channels.
         *
         * Pure so unit tests can cover it without a tree.
         */
        fun channelFromCardText(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim()
            // No metadata bullets — no trusted channel-by signal.
            if (!trimmed.contains('•') && !trimmed.contains('·') && !trimmed.contains('|')) {
                return null
            }
            // Reuse feedTitleSegment: the channel lives in the same first
            // segment (title + " by " + channel) before the metadata bullets.
            val first = feedTitleSegment(trimmed)
            val byIdx = first.lastIndexOf(" by ")
            if (byIdx <= 0) return null
            // Strip leading non-alphanumerics (@, dashes, bullets, ...) so the
            // extracted name matches the blocklist's normalized channel names
            // (e.g. "— HotGirls TV" -> "HotGirls TV").
            val channel = first.substring(byIdx + " by ".length)
                .trim()
                .trimStart { !it.isLetterOrDigit() }
            if (channel.isEmpty() || channel.length > 40) return null
            return channel
        }

        /**
         * True when [text] carries the combined metadata that ONLY real
         * YouTube cards expose, in either of the two on-device shapes:
         *   1. "Title by Channel • 1.2M views • 3 days ago" — a separator
         *      bullet/pipe paired with view/age/subscriber metadata.
         *   2. "Video Title - YouTube" — the title node of a video page/card
         *      carrying the YouTube brand suffix.
         * Chrome UI tiles ("Ask AI Mode", "New Incognito tab") have neither
         * and are rejected. Pure so unit tests can cover it without a tree.
         */
        fun isYouTubeCardText(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val lower = text.lowercase(Locale.ROOT)
            val hasSeparator = text.contains('•') || text.contains('·') || text.contains('|')
            val hasViewMeta = lower.contains("views") || lower.contains(" ago") ||
                lower.contains("subscriber")
            if (hasSeparator && hasViewMeta) return true
            // "Video Title - YouTube": only trusted when the part before the
            // suffix is a real video title — a YouTube UI screen ("Home -
            // YouTube", "Shorts - YouTube") from the tab strip is NOT a card
            // and must not pass the gate at any height.
            if (lower.endsWith(" - youtube")) {
                val base = text.substring(0, text.length - " - YouTube".length).trim()
                return base.length in 1..200 &&
                    base.lowercase(Locale.ROOT) !in YOUTUBE_UI_SCREENS
            }
            return false
        }

        /**
         * Conservative profile for a YouTube feed/search card title, used by
         * the pre-emptive feed-title scan ([extractYouTubeFeedCards]) to pick
         * candidate video titles out of the tree without flagging UI labels,
         * metadata, or URLs.
         *
         * Criteria (all must hold):
         *  - 10..200 chars — video titles are longer than chips/labels but
         *    shorter than whole pages or descriptions
         *  - not a URL / web address
         *  - not a search-box placeholder or app-chrome text
         *  - no feed metadata markers (view counts, "ago", Subscribe, Shorts)
         *
         * Pure so unit tests can cover the filter rules without a tree.
         */
        fun isLikelyVideoTitle(text: String?): Boolean {
            val trimmed = feedTitleSegment(text)
            if (trimmed.isEmpty()) return false
            if (trimmed.length !in 10..200) return false
            val lower = trimmed.lowercase(Locale.ROOT)
            if (lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("www.")
            ) return false
            // url-ish (dots, no spaces, short): e.g. "m.youtube.com"
            if (lower.contains(".") && !lower.contains(" ") && lower.length > 4) return false
            if (lower in setOf(
                    "search", "ask anything", "ask google", "type here", "search query",
                    "search or type url", "search google or type url", "search or type web address",
                    "home", "shorts", "subscriptions", "library", "history", "watch later",
                    "liked videos", "playlists", "settings", "trending", "explore", "music",
                    // Chrome/YouTube UI chrome observed on-device being picked up
                    // as fake feed cards (defense in depth — the card-size bounds
                    // gate in extractYouTubeFeedCards is the primary guard).
                            "open the home page", "search with your voice",
                    // Chrome NTP tiles observed on-device as fake cards
                    // (490x110, no thumbnail): the height + metadata gate is the
                    // primary guard; these deny the exact observed labels too.
                    // (Short entries like "share"/"downloads"/"incognito" are
                    // already excluded by the 10-char minimum and are not listed.)
                    "ask ai mode", "new incognito tab",
                    "recent tabs", "add to home screen", "customize chrome",
                    "all bookmarks", "settings and more",
                    "find in page", "request desktop site"
                )) return false
            if (lower.contains("views") || lower.contains(" ago") ||
                lower.contains("subscribe") || lower.contains("subscriber")
            ) return false
            if (lower.matches(Regex("^[\\d:., ]+$"))) return false
            if (lower.startsWith("#")) return false
            return true
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

            // Description (best-effort): a secondary keyword signal, read from
            // the native tree (the YouTube app exposes the description text to
            // accessibility).
            val ytDescription = extractYouTubeDescription(rootNode)

            return ContentSnapshot(
                packageName = packageName,
                url = ytUrl,  // null on normal YouTube screens (no in-app browser)
                query = queryFromSignals,
                title = title,
                queryConfidence = if (queryFromSignals != null) QueryConfidence.MEDIUM else QueryConfidence.NONE,
                querySource = QuerySource.NONE,
                description = ytDescription
            )
        }

        // 1. Try to extract URL explicitly from known URL bars (Chrome)
        //    Reject placeholder/hint text (e.g. "Search Google or type URL"):
        //    a new-tab page's omnibox shows the placeholder, and using it as
        //    the URL both reports a bogus URL and shadows the real one (the
        //    in-app extractors below only run when url == null).
        url = findTextByResourceIds(rootNode, URL_BAR_IDS) { text ->
            if (text.length >= MIN_URL_LENGTH && !isGenericSearchHint(text)) text else null
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

        // Chrome YouTube window-title fallback: Chrome's compositor frequently
        // exposes "Web View" (or nothing) as the active window title instead of
        // "Chrome: <Video Title> - YouTube", leaving the title check with
        // nothing to match (the user's observed "no overlay" on m.youtube.com).
        // When the window title carries no parseable video title but the URL
        // is a YouTube WATCH page, read the video title from the on-page tree
        // and synthesize the standard Chrome window-title shape so the
        // existing matcher path fires unchanged.
        //
        // Deliberately restricted to watch/shorts URLs (never the home feed or
        // search results): the tree scan picks the FIRST visible text block,
        // which on feed/search pages is a suggestion/result caption — checking
        // that against the strict keyword set would falsely block a clean page.
        val titleParses = title?.let(ContentExtractor::youtubeTitleFromChromeWindowTitle) != null
        val isWatchUrl = url?.let { u ->
            u.contains("/watch") || u.contains("/shorts/")
        } == true
        if (!titleParses && packageName in CHROME_PACKAGES && isWatchUrl) {
            val treeTitle = extractChromeYouTubeTreeTitle(rootNode, true)
            if (treeTitle != null) {
                Log.i(TAG, "CHROME_YOUTUBE_TITLE_FROM_TREE=$treeTitle")
                title = "Chrome: $treeTitle - YouTube"
            }
        }

        val incognito = packageName in CHROME_PACKAGES &&
            detectIncognitoMode(rootNode, windowTitle, event)

        // Active Google tab. The Google app never exposes the search URL, so the
        // tree-detected tab chip is the primary signal there; Chrome's URL is
        // available, so URL parsing is its primary signal. Keep both: the tree
        // chip is authoritative when present, otherwise parse the URL.
        val googleTab = if (packageName == GOOGLE_PACKAGE) {
            // A genuine search-results page (a query is present or the window
            // title parses as a Google search) enables the relaxed chip
            // fallback and the GOOGLE_TAB_TEXT_NODE diagnostics — so a website
            // opened inside the app's in-app browser can never set the tab and
            // enable gender-word blocking on ordinary content.
            val resultsPage = query != null ||
                (windowTitle != null && GoogleSignalParser.queryFromWindowTitle(windowTitle) != null)
            detectGoogleTabChip(rootNode, resultsPage) ?: googleTabFromUrl(url)
        } else {
            googleTabFromUrl(url)
        }

        // Pre-emptive feed blocking: on YouTube feed/search pages (NOT watch
        // pages) the window title is just "Chrome: YouTube", so the watch-title
        // check can never fire before the user opens a video. Read candidate
        // cards (title + bounds) from the page tree so a blocked card is caught
        // and marked BEFORE navigation. Deliberately watch-only-excluded: on
        // watch pages the related/suggestion rail would otherwise be scanned as
        // a feed.
        val feedCards = if (packageName in CHROME_PACKAGES &&
            ContentExtractor.isYouTubeDomain(url) && !isWatchUrl
        ) {
            extractYouTubeFeedCards(rootNode)
        } else {
            emptyList()
        }
        if (feedCards.isNotEmpty()) {
            Log.i(TAG, "YOUTUBE_FEED_CARDS count=${feedCards.size}")
        }

        return ContentSnapshot(
            packageName = packageName,
            url = url,
            query = query,
            title = title,
            queryConfidence = queryConfidence,
            querySource = querySource,
            incognito = incognito,
            googleTab = googleTab,
            feedCards = feedCards
        )
    }

    /**
     * Conservative on-page video-title scan for YouTube pages in Chrome when
     * the window title is unavailable (Chrome often exposes "Web View" as the
     * active window title).
     *
     * Returns the FIRST visible text node in tree order that fits a
     * video-title profile (top-of-page = the title area on YouTube watch
     * pages). Only used when the window title carried no video title, so
     * ordinary Chrome pages are unaffected. Returns null when the WebView
     * does not expose page content to accessibility (the fallback then simply
     * does not fire).
     */
    private fun extractChromeYouTubeTreeTitle(rootNode: AccessibilityNodeInfo, useDeepScan: Boolean = false): String? {
        // YouTube's mobile WebView nests its content deeply (observed at depth
        // 99+ for tab chips). The deep scan path uses a much higher limit so
        // the video title is reachable even on heavily-nested pages.
        val maxDepth = if (useDeepScan) 150 else MAX_TRAVERSAL_DEPTH
        val nodeBudget = if (useDeepScan) 2000 else 0  // unlimited when not deep
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        while (queue.isNotEmpty() && depth < maxDepth &&
            (nodeBudget == 0 || visited < nodeBudget)
        ) {
            visited++
            val node = queue.removeFirst()
            val text = try { extractNodeText(node)?.trim() } catch (e: Exception) { null }
            if (text != null) {
                val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
                if (visible && text.length in 8..200 &&
                    !isGenericSearchHint(text) &&
                    !isAppUiText(text) &&
                    !looksLikeUrl(text) &&
                    !text.contains("Subscribe", ignoreCase = true) &&
                    !text.contains("subscriber", ignoreCase = true) &&
                    !text.contains("views", ignoreCase = true) &&
                    !text.contains("likes", ignoreCase = true) &&
                    !text.contains("ago", ignoreCase = true) &&
                    !text.startsWith("#") &&
                    !text.matches(Regex("^[\\d:.kKMbB ]+$"))
                ) {
                    return text
                }
            }
            val childCount = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            depth++
        }
        return null
    }

    /**
     * Collect candidate video cards (title + bounds + extra text signals) from
     * a YouTube feed/search page tree (Chrome). The window title on feed pages
     * is just "Chrome: YouTube", so titles must be read from the page content
     * itself for pre-emptive blocking. Conservative: only visible text nodes
     * that look like video titles are kept (see
     * [ContentExtractor.isLikelyVideoTitle]), deduplicated and capped at
     * [MAX_FEED_TITLES]. The screen bounds are used by the service to draw a
     * floating "blocked" marker over each card; the content description
     * (often a thumbnail caption) and the channel name (from the combined
     * "Title by Channel • views • ago" string) are extra signals that catch
     * deceptive videos with "safe" titles. Every node access is guarded —
     * this runs inside the 500ms polling loop on possibly-recycled nodes.
     */
    private fun extractYouTubeFeedCards(rootNode: AccessibilityNodeInfo): List<FeedVideoCard> {
        val seen = LinkedHashMap<String, FeedVideoCard>()
        // Reference width for the card-shape guard: the root node's bounds span
        // the active window ≈ the screen width (context-free and accurate).
        val rootRect = android.graphics.Rect()
        val hasRootBounds = try {
            rootNode.getBoundsInScreen(rootRect)
            !rootRect.isEmpty
        } catch (e: Exception) {
            false
        }
        val screenWidth = if (hasRootBounds) rootRect.width()
            else android.content.res.Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = if (hasRootBounds) rootRect.height()
            else android.content.res.Resources.getSystem().displayMetrics.heightPixels
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < FEED_SCAN_MAX_DEPTH &&
            visited < FEED_SCAN_NODE_BUDGET && seen.size < MAX_FEED_TITLES
        ) {
            visited++
            val node = queue.removeFirst()
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { extractNodeText(node)?.trim() } catch (e: Exception) { null }
                if (text != null && ContentExtractor.isLikelyVideoTitle(text)) {
                    val title = ContentExtractor.feedTitleSegment(text)
                    if (title.isNotEmpty() && !seen.containsKey(title)) {
                        val bounds = android.graphics.Rect()
                        val hasBounds = try {
                            node.getBoundsInScreen(bounds)
                            !bounds.isEmpty
                        } catch (e: Exception) {
                            false
                        }
                        // Card-shape guard: drop UI chrome that passes the title
                        // profile but is NOT a video card — it would otherwise be
                        // cropped as a fake thumbnail and analyzed/badged.
                        //   - Width: cards span most of the screen.
                        //   - Height: cards are TALL (thumbnail + title + meta ≈
                        //     8%+ of screen height). Chrome NTP tiles measured
                        //     490x110 (~4.5%) on-device and passed the old 90px
                        //     gate ("Ask AI Mode", "New Incognito tab" were
                        //     analyzed as fake thumbnails). A node either is
                        //     full-card tall (>= MIN_CARD_HEIGHT_FRACTION of the
                        //     screen, RELATIVE so small/landscape screens keep
                        //     real cards) OR carries the combined "Title by
                        //     Channel • views • ago" metadata that only real
                        //     YouTube cards expose — so a compact layout that
                        //     exposes the title row alone (short node) still
                        //     qualifies when the metadata is present, while UI
                        //     tiles (no metadata, short) never do.
                        val isCardSized = hasBounds &&
                            bounds.width() >= screenWidth * MIN_CARD_WIDTH_FRACTION &&
                            (bounds.height() >= screenHeight * MIN_CARD_HEIGHT_FRACTION ||
                                ContentExtractor.isYouTubeCardText(text))
                        if (!isCardSized) continue
                        // Extra text signals: the node's own content description
                        // (thumbnail caption on many WebView feeds — the user's
                        // "safe title, revealing thumbnail" case) and the
                        // channel name from the combined card string. The
                        // description is skipped when it IS the title text
                        // (extractNodeText falls back to the description, so
                        // they'd be identical and re-checking is redundant).
                        val contentDesc = try {
                            node.contentDescription?.toString()?.trim()
                        } catch (e: Exception) {
                            null
                        }?.takeIf { it.isNotEmpty() && it != text }
                        val channel = ContentExtractor.channelFromCardText(text)
                        seen[title] = FeedVideoCard(
                            title,
                            if (hasBounds) bounds else null,
                            contentDesc,
                            channel
                        )
                    }
                }
            }
            val childCount = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            depth++
        }
        return seen.values.toList()
    }

    /**
     * Best-effort extraction of the YouTube video description from the app's
     * accessibility tree. Returns null when no description-like text block is
     * found (the app only exposes the full description once expanded / for
     * certain renderers), so this is purely an additional signal, never a
     * requirement for blocking.
     *
     * CONSERVATIVE by design: only text nodes whose class name or view id
     * explicitly marks them as the description / metadata / about container
     * are considered. A naive "longest text node" heuristic would pick up
     * comments or suggested-video captions and, with strict keyword blocking
     * (no educational exceptions), over-block clean videos. If no such node
     * exists in the tree, null is returned and description matching simply
     * doesn't fire.
     */
    private fun extractYouTubeDescription(rootNode: AccessibilityNodeInfo): String? {
        var best: String? = null
        var bestLen = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        while (queue.isNotEmpty() && depth < MAX_TRAVERSAL_DEPTH) {
            val node = queue.removeFirst()
            val className = try { node.className?.toString() } catch (e: Exception) { null } ?: ""
            val viewId = try { node.viewIdResourceName } catch (e: Exception) { null } ?: ""
            val isDescContainer = className.contains("description", ignoreCase = true) ||
                className.contains("metadata", ignoreCase = true) ||
                viewId.contains("description", ignoreCase = true) ||
                viewId.contains("metadata", ignoreCase = true) ||
                viewId.contains("_about", ignoreCase = true)

            // Only ACCEPT text from description-flagged nodes — but ALWAYS
            // keep traversing children (a description TextView nests deep under
            // generic RecyclerView/ViewGroup containers whose class/view ids do
            // not mention "description"). Gating traversal here would make the
            // scan unable to ever reach the description.
            if (isDescContainer) {
                val text = extractNodeText(node)
                if (text != null) {
                    val trimmed = text.trim()
                    if (trimmed.length > 100 && trimmed.length > bestLen &&
                        !isGenericSearchHint(trimmed) &&
                        !isAppUiText(trimmed) &&
                        !looksLikeUrl(trimmed) &&
                        !trimmed.contains("Subscribe", ignoreCase = true) &&
                        !trimmed.contains("views", ignoreCase = true)
                    ) {
                        bestLen = trimmed.length
                        best = trimmed
                    }
                }
            }
            // Guard every node access: this runs inside the 500ms polling loop
            // on possibly-recycled nodes; an unguarded childCount/getChild
            // would crash the whole accessibility service.
            val childCount = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            depth++
        }
        if (best != null) {
            Log.d(TAG, "YOUTUBE_DESCRIPTION_FOUND len=$bestLen")
        }
        return best
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
        // Only the resource-name portion (text after the last '/') matters. The
        // Google package name "com.google.android.googlequicksearchbox" contains
        // the substring "search", so matching the full view id made EVERY node
        // in the Google app match — the extractor then grabbed junk labels like
        // the "Predictions" suggestions container instead of the real query.
        val resourceName = viewId.substringAfterLast('/', viewId)
        val lower = resourceName.lowercase()
        // The search box's CLEAR button (googleapp_search_box_clear_button,
        // visible label "Clear") matches "search" in its id and would be
        // extracted as the query, polluting the query cache (observed on-device:
        // GOOGLE_QUERY_SOURCE=SEMANTIC_SEARCH_ID query=Clear while typing).
        if (lower.contains("clear")) return false
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
        // Same resource-name scoping as isGoogleSearchId: the package name
        // "googlequicksearchbox" contains "search" and would otherwise make
        // every Google-app event look like a search-box event.
        val rawViewId = event.source?.viewIdResourceName ?: ""
        val viewId = rawViewId.substringAfterLast('/', rawViewId).lowercase()
        // Same clear-button guard as isGoogleSearchId: its label is "Clear"
        // and its id contains "search", so without this a VIEW_TEXT_CHANGED /
        // VIEW_FOCUSED event from the clear button becomes a junk query.
        if (viewId.contains("clear")) return false
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
     * Detect the active Google search tab by scanning the accessibility tree
     * for the tab chip (e.g. a node labelled "Images") that is marked
     * selected/checked. The Google app's search-results page does NOT expose
     * the search URL, so URL-based tab detection (googleTabFromUrl) cannot see
     * the tab there — the chip in the tree is the reliable signal.
     *
     * Single BFS traversal that tracks two candidate matches and prefers the
     * strong one:
     *   1. STRONG (chip-like): a selected/checked node whose label maps to a
     *      tab AND that looks like a chip/tab/button (class name or view id).
     *      This avoids matching arbitrary selected webpage text.
     *   2. FALLBACK (relaxed): any selected/checked node whose label maps to a
     *      tab. Covers Google app versions that expose the chip with a generic
     *      class name.
     *
     * The whole tree is scanned (including WebView subtrees): the Google app's
     * tab bar has been observed as BOTH native UI and WebView-rendered content
     * depending on version, so excluding WebView nodes would risk the chip
     * never being found — exactly the failure this feature is meant to fix.
     * False positives are instead prevented by the strong gates: the node must
     * be selected/checked AND its label must exactly match a known tab name
     * (single-word, case-insensitive), and the strong match additionally
     * requires a chip/tab/button class or view id.
     *
     * This scan deliberately goes deeper (CHIP_SCAN_MAX_DEPTH + a node budget)
     * than every other BFS scan: the Google app's results page nests its
     * WebView — and with it the tab chips — far below MAX_TRAVERSAL_DEPTH, so
     * a shallower window silently misses them. On-device evidence: the "All"
     * tab chip was found as a WebView HTML element at depth 99, with the other
     * chips at or just beyond that depth. Do NOT revert this scan to the
     * shared depth limit.
     *
     * Both isSelected and isChecked are accepted because Google app versions
     * differ in which state they expose on the active chip.
     *
     * RELAXED fallback: some Google app versions render the tab bar without any
     * selected/checked state at all. When no selected/checked chip exists, a
     * visible, chip-like node (chip/tab/button class or view id) whose label
     * exactly matches a tab name is used — but ONLY when [resultsPage] (the
     * caller enables it on a genuine search-results page, so websites inside
     * the app's in-app browser can never set the tab). The FIRST candidate in
     * tree order wins, which biases toward "All" (Google's first tab) when the
     * active tab is genuinely unknowable — the safe direction, since gender
     * words are only blocked on Images/Videos.
     *
     * When [resultsPage] is true, every node whose label exactly matches a tab
     * name is also logged (GOOGLE_TAB_TEXT_NODE, capped at 5 per scan) so
     * logcat shows definitively whether the tab chips exist in the tree on the
     * current device — and at what depth — when detection fails.
     */
    fun detectGoogleTabChip(rootNode: AccessibilityNodeInfo?, resultsPage: Boolean): String? {
        if (rootNode == null) return null

        var chipLikeMatch: String? = null
        var anyMatch: String? = null
        var relaxedMatch: String? = null
        var loggedTextNodes = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < CHIP_SCAN_MAX_DEPTH && visited < CHIP_SCAN_NODE_BUDGET) {
            val node = queue.removeFirst()
            visited++
            val selected = try { node.isSelected } catch (e: Exception) { false }
            val checked = try { node.isChecked } catch (e: Exception) { false }
            val label = googleTabFromLabel(extractNodeText(node))
            if (label != null) {
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""
                val chipLike = className.contains("Chip", ignoreCase = true) ||
                    className.contains("Tab", ignoreCase = true) ||
                    className.contains("Button", ignoreCase = true) ||
                    viewId.contains("chip", ignoreCase = true) ||
                    viewId.contains("tab", ignoreCase = true) ||
                    viewId.contains("filter", ignoreCase = true)
                if (selected || checked) {
                    if (chipLike) {
                        chipLikeMatch = label
                        break
                    }
                    if (anyMatch == null) {
                        anyMatch = label
                    }
                } else if (resultsPage && relaxedMatch == null) {
                    // Relaxed fallback (no selection state exposed): a visible
                    // node with an exact tab label on a results page. Chip-like
                    // native views are preferred, but generic WebView HTML
                    // elements (class=android.view.View) are accepted too — the
                    // Google app's tab bar is web-rendered on this device and
                    // the found "All" chip carries no chip-like class and no
                    // selected/checked state. resultsPage + exact single-word
                    // label keep ordinary page content from matching, and the
                    // tab bar (top of page, shallowest in BFS) is found first.
                    val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
                    if (visible && (chipLike || className == "android.view.View")) {
                        relaxedMatch = label
                        Log.d(TAG, "GOOGLE_TAB_CHIP_CANDIDATE label=$label class=$className viewId=$viewId (not selected)")
                    }
                }
                // Ground-truth diagnostic on results pages: every node whose
                // label matches a tab name, so a missing detection is debuggable.
                if (resultsPage && loggedTextNodes < 5) {
                    loggedTextNodes++
                    Log.d(
                        TAG,
                        "GOOGLE_TAB_TEXT_NODE label=$label class=$className viewId=$viewId " +
                            "selected=$selected checked=$checked chipLike=$chipLike depth=$depth"
                    )
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            depth++
        }

        val result = chipLikeMatch ?: anyMatch ?: relaxedMatch
        if (result != null) {
            Log.i(TAG, "GOOGLE_TAB_CHIP_DETECTED label=$result chipLike=${chipLikeMatch != null}")
        }
        return result
    }

    /**
     * Check if a URL is any Google search URL with a specific tab (Images, Videos, News).
     * Returns the tab name (e.g., "Images", "Videos") or null if not a tab search.
     *
     * This is used to detect when a user is on Google Images/Videos/News tab
     * and ensure blocked queries are caught regardless of which tab they use.
     */
    fun isGoogleTabSearch(url: String?): String? = googleTabFromUrl(url)

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
        // "ask google" is the Google app's search-box placeholder on the user's
        // device (logs showed it being extracted as a junk query). Treating it
        // as a hint keeps a placeholder from shadowing the real query via the
        // HIGH-confidence SEARCH_BAR_IDS path. "search google or type url" is
        // Chrome's omnibox placeholder on a new-tab page — it was observed being
        // extracted as the URL (Bug 7), which also shadowed the real URL.
        return lower in setOf(
            "search",
            "ask anything",
            "ask google",
            "type here",
            "search query",
            "search or type url",
            "search google or type url",
            "search or type web address"
        )
    }

    private fun isAppUiText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("google") ||
               lower.startsWith("chrome") ||
               lower in setOf("search", "settings", "discover", "saved", "updates", "collections", "new tab", "bookmarks", "history", "downloads") ||
               lower.length > 100
    }
}
