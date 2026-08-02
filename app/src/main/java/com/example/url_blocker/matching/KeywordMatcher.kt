package com.example.url_blocker.matching

import android.net.Uri
import android.util.Log
import com.example.url_blocker.extractor.ContentExtractor
import com.example.url_blocker.extractor.GoogleSignalParser
import com.example.url_blocker.repository.BlockRepository
import java.util.Locale

/**
 * Result of a block check.
 */
sealed class MatchResult {
    data object Allowed : MatchResult()
    data class Blocked(
        val matchedItem: String,
        val matchType: MatchType,
        val matchSource: MatchSource,
        /**
         * For pre-emptive feed blocks (matchSource == FEED): the cards whose
         * titles matched, with their on-screen bounds, so the service can draw
         * a floating marker over each one. Empty for all other sources.
         */
        val feedCards: List<FeedVideoCard> = emptyList()
    ) : MatchResult()
}

/**
 * A video card visible on a YouTube feed/search page (Chrome), with the
 * title text, its on-screen bounds (screen coordinates — used to place a
 * floating "blocked" marker over the card), and the extra text signals that
 * catch deceptive videos — a "safe" title hiding revealing content:
 *  - [contentDesc]: the node's content description (often a thumbnail caption
 *    like "Woman in bikini on beach"), checked against the keyword set.
 *  - [channel]: the channel name from the combined card string
 *    ("Title by Channel • views • ago"), checked against the permanent
 *    channel blocklist.
 */
data class FeedVideoCard(
    val title: String,
    val bounds: android.graphics.Rect? = null,
    /** Content description captured with the card (thumbnail caption etc.). */
    val contentDesc: String? = null,
    /** Channel name extracted from the card's combined text, when present. */
    val channel: String? = null,
    /**
     * The primary keyword/channel shown on the block card when this card is
     * blocked (set by the matcher's risk-score evaluation; the overlay draws
     * "🚫 Blocked: <this>"). Null for unblocked cards.
     */
    val blockedKeyword: String? = null,
    /**
     * On-device NSFW thumbnail score 0..1 from the TFLite image analyzer, when
     * the service ran the image pipeline for this card (Android 11+ with the
     * model present). Null when image analysis wasn't possible; the matcher
     * adds the +0.4 thumbnail weight when the score crosses the threshold.
     */
    val nsfwScore: Float? = null,
    /**
     * Best-effort bounds of the card's THUMBNAIL IMAGE (the 16:9 image above
     * the title row), in screen coordinates — computed by ContentExtractor
     * from the card node's shape so the NSFW pipeline crops the actual image
     * and never the title text (observed on-device: an 800x79 title strip was
     * analyzed as a "thumbnail"). Null when the computed region failed the
     * size sanity gate; the service then skips image analysis for this card.
     */
    val thumbnailBounds: android.graphics.Rect? = null
)

enum class MatchType {
    DOMAIN,
    BUILT_IN_KEYWORD,
    USER_KEYWORD,
    INCOGNITO,
    /** A permanently blocked YouTube channel. */
    CHANNEL
}

enum class MatchSource {
    URL,
    QUERY,
    DOMAIN,
    TITLE,
    DESCRIPTION,
    /** A video title matched pre-emptively on a YouTube feed/search page. */
    FEED,
    NONE
}

enum class QueryConfidence {
    HIGH,
    MEDIUM,
    NONE
}

enum class QuerySource {
    SEARCH_BAR,
    WINDOW_TITLE,
    EDITABLE_FIELD,
    ACCESSIBILITY_EVENT,
    NONE
}

/**
 * Snapshot of the currently extracted content from an app.
 */
data class ContentSnapshot(
    val packageName: String,
    val url: String?,
    val query: String?,
    val title: String?,
    val queryConfidence: QueryConfidence = QueryConfidence.NONE,
    val querySource: QuerySource = QuerySource.NONE,
    val incognito: Boolean = false,
    /** Active Google search tab ("Images", "Videos", "All", ...), when known. */
    val googleTab: String? = null,
    /** YouTube channel name/handle currently on screen (watch page), when known. */
    val channel: String? = null,
    /** YouTube video description text (best-effort from the app tree), when known. */
    val description: String? = null,
    /**
     * Candidate video cards (title + on-screen bounds) visible on a YouTube
     * feed/search page (Chrome), extracted pre-emptively so a blocked card is
     * caught (and marked with a floating badge) before the user opens the
     * video. Empty on watch pages and non-YouTube pages.
     */
    val feedCards: List<FeedVideoCard> = emptyList()
) {
    // Generate an identity for deduplication. The description is included so a
    // description that only appears on a later scan (e.g. after the user
    // expands it) is not skipped by dedup — without it the identity would be
    // unchanged and the description-based block would never fire. Feed titles
    // are included as a hash digest so a feed that loads new cards (new
    // titles) re-triggers evaluation.
    fun toIdentityString(): String {
        return "$packageName|${url ?: ""}|${query ?: ""}|${title ?: ""}|incognito=$incognito|tab=${googleTab ?: ""}|channel=${channel ?: ""}|desc=${description?.length ?: 0}|feed=${feedCards.joinToString("|") { "${it.title.hashCode()}:${it.contentDesc?.hashCode()}:${it.channel?.hashCode()}" }.take(240)}"
    }
}

/**
 * Matches extracted content against blocked keywords and domains.
 *
 * Matching strategy per app type:
 * - Chrome packages: URL-based only. We block only when the current URL or its hostname
 *   matches a blocked domain or contains a blocked keyword in the URL string.
 *   Page text/title/content is NEVER used to block Chrome.
 * - Google app: URL-based and query-based. We block when the search query URL or
 *   the extracted search query contains a blocked keyword.
 */
class KeywordMatcher(
    private val repository: BlockRepository,
    private val channelBlocklist: com.example.url_blocker.repository.ChannelBlocklist
) {

    companion object {
        private const val TAG = "KeywordMatcher"

        // ── Weighted risk-score model for pre-emptive feed blocking ──
        // The user's requested flow scores each feed card's signals and
        // blocks when the weighted sum crosses the threshold (instead of
        // any-single-match): thumbnail caption +0.4, channel name +0.3.
        // Feed cards don't expose a separate description, so the flow's
        // 0.2 description slot is unavailable on feed cards.
        //
        // NOTE: the TITLE is deliberately NOT part of the score — an explicit
        // keyword in the title is a HARD block (checked before scoring). Weakening
        // title blocking to a 0.1 weight would let explicitly-titled videos (e.g.
        // "Porn Compilation") sail through the feed unmarked — the opposite of the
        // user's core goal. The score exists to catch DECEPTIVE videos (safe title
        // + revealing thumbnail caption / suggestive channel), where no single
        // signal is trustworthy.
        private const val FEED_BLOCK_THRESHOLD = 0.3f
        private const val FEED_THUMBNAIL_WEIGHT = 0.4f
        private const val FEED_CHANNEL_WEIGHT = 0.3f
    }

    /**
     * Checks the complete content snapshot for blocks.
     *
     * For Chrome packages:
     *   - URL is PRIMARY (domain matching, keyword matching on URL string)
     *   - Query is NOT used (Chrome queries come from URL params)
     *   - Title is NOT used for blocking
     *
     * For Google app:
     *   - URL is checked first
     *   - Query is checked second
     *   - Title is NOT used for blocking
     */
    fun check(snapshot: ContentSnapshot, packageName: String = snapshot.packageName): MatchResult {
        // ── INCOGNITO MODE: block ALL Chrome browsing ─────────────────
        // Permanent built-in protection (no user toggle): any strong incognito
        // signal inside a Chrome package is blocked immediately, regardless of
        // URL/content. This prevents bypassing the filter via private mode.
        if (snapshot.incognito && isChromePackage(packageName)) {
            Log.i(TAG, "INCOGNITO MODE DETECTED in $packageName — blocking all browsing")
            return MatchResult.Blocked("Incognito browsing", MatchType.INCOGNITO, MatchSource.NONE)
        }

        // ── CHROME PACKAGES: URL ONLY ──────────────────────────────────
        if (isChromePackage(packageName)) {
            return checkChrome(snapshot)
        }

        // ── GOOGLE APP: URL + QUERY ────────────────────────────────────
        if (packageName == com.example.url_blocker.extractor.ContentExtractor.GOOGLE_PACKAGE) {
            return checkGoogle(snapshot)
        }

        // ── YOUTUBE APP: TITLE + SIGNALS ───────────────────────────────
        // YouTube has no URL bar exposed via accessibility, so we rely on
        // the video title and detected signals (Shorts, hashtags).
        // Title-based blocking is safe for YouTube because the video title
        // IS the primary content identifier (unlike Chrome where page titles
        // are arbitrary text that can cause false positives).
        if (isYouTubePackage(packageName)) {
            return checkYouTube(snapshot)
        }

        // ── FALLBACK (other packages): check everything ─────────────────
        return checkGeneric(snapshot)
    }

    /**
     * Chrome-specific check: URL is the ONLY source of truth.
     * Domain matching is already handled inside checkString(isUrl=true).
     *
     * Websites never block the tab-restricted gender terms (woman, man, ...) —
     * those are reserved for Google's Images/Videos tabs. See
     * [BlockRepository.TAB_RESTRICTED_KEYWORDS].
     *
     * IMPORTANT: Google search is frequently performed inside Chrome, not only
     * in the Google app. So when the Chrome URL is a Google search on the
     * Images or Videos tab (tbm=isch / tbm=vid, or the newer udm layout codes
     * such as udm=2 for Images), the full strict keyword set — including the
     * tab-restricted gender terms — is used, exactly as in the Google app.
     */
    private fun checkChrome(snapshot: ContentSnapshot): MatchResult {
        // Detect whether this is a Google Images/Videos search inside Chrome.
        val tab = snapshot.googleTab ?: ContentExtractor.googleTabFromUrl(snapshot.url)
        val isImageVideoTab = tab == "Images" || tab == "Videos"
        val builtInKeywords = tabAwareBuiltInKeywords(tab)
        Log.d(TAG, "CHROME_GOOGLE_TAB=$tab imageVideoTab=$isImageVideoTab")

        // ── YOUTUBE SEARCH PAGES: cards only, never the page itself ──
        // A YouTube search page (m.youtube.com/results?...&search_query=X or
        // /search?...) carries the USER'S QUERY in the URL and in the page
        // title ("Coffee and cleavage - YouTube") — neither describes a single
        // video's content, so neither may block the whole page (searching a
        // blocked word is not itself explicit content). The user's flow:
        // analyze each result CARD individually instead (the FEED path
        // below). Watch/shorts pages are NOT search pages and keep the full
        // URL + title checks.
        val url = snapshot.url
        val isYouTubeSearchPage = url != null &&
            ContentExtractor.isYouTubeDomain(url) &&
            (url.contains("/results") || url.contains("/search"))
        if (isYouTubeSearchPage) {
            // A blocked DOMAIN rule still applies at the host level.
            val domainRes = checkDomains(url.lowercase(Locale.ROOT))
            if (domainRes != null) {
                val res = MatchResult.Blocked(domainRes, MatchType.DOMAIN, MatchSource.DOMAIN)
                Log.i(TAG, buildBlockLog("URL", url, res))
                return res
            }
            checkYouTubeFeedCards(snapshot)?.let { return it }
            return MatchResult.Allowed
        }

        // URL is the primary source — domain matching is done inside checkString(isUrl=true)
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true, builtInKeywords = builtInKeywords)
            if (urlRes is MatchResult.Blocked) {
                val source = if (urlRes.matchType == MatchType.DOMAIN) MatchSource.DOMAIN else MatchSource.URL
                val finalRes = urlRes.copy(matchSource = source)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // Google Images/Videos search inside Chrome: mobile Chrome's address-bar
        // URL often OMITS the q= parameter (e.g. Images URL is just
        // "...&udm=2&fbs=..." with no query text). Recover the query from the
        // URL's q= param when present, else from the structured Google-search
        // window title ("... - Google Search") — a Google signal, not arbitrary
        // page text. This runs ONLY on positively-identified Images/Videos tab
        // searches, so ordinary websites are unaffected.
        if (isImageVideoTab) {
            val chromeQuery = extractGoogleQuery(snapshot)
                ?: snapshot.title?.let(GoogleSignalParser::queryFromWindowTitle)
            if (!chromeQuery.isNullOrBlank()) {
                val queryRes = checkString(chromeQuery, isUrl = false, builtInKeywords = builtInKeywords)
                if (queryRes is MatchResult.Blocked) {
                    val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                    Log.i(TAG, buildBlockLog("GOOGLE_TAB_QUERY", chromeQuery, finalRes))
                    return finalRes
                }
            }
        }

        // ── YOUTUBE VIDEOS WATCHED INSIDE CHROME ─────────────────────
        // YouTube's mobile site (m.youtube.com) exposes only an opaque watch
        // URL — no keyword — and often drops the path from the address bar
        // entirely (url becomes the bare domain or null), so the URL/query
        // checks above can never block the video itself. That was the user's
        // observed "reopen bypass": the SEARCH blocks, but once the video is
        // playing nothing blocks it. The window title carries the video title
        // ("Chrome: <Title> - YouTube") — the same content identifier the
        // YouTube app blocks on. When Chrome shows a YouTube page, check the
        // video title against the full strict set, exactly like the YouTube
        // app path.
        val chromeYouTubeTitle = extractChromeYouTubeTitle(snapshot)
        if (chromeYouTubeTitle != null) {
            // A permanently blocked channel blocks every video from it.
            val channel = snapshot.channel
            if (channel != null && channel.isNotBlank() && channelBlocklist.isBlocked(channel)) {
                val channelRes = MatchResult.Blocked(
                    channel,
                    MatchType.CHANNEL,
                    MatchSource.TITLE
                )
                Log.i(TAG, buildBlockLog("YOUTUBE_CHANNEL_IN_CHROME", channel, channelRes))
                return channelRes
            }
            val titleRes = checkString(
                chromeYouTubeTitle,
                isUrl = false,
                builtInKeywords = repository.activeBuiltInKeywords
            )
            if (titleRes is MatchResult.Blocked) {
                val finalRes = titleRes.copy(matchSource = MatchSource.TITLE)
                Log.i(TAG, buildBlockLog("YOUTUBE_TITLE_IN_CHROME", chromeYouTubeTitle, finalRes))
                return finalRes
            }
        }

        // ── YOUTUBE FEED: pre-emptive title check ────────────────────
        // On feed/search pages the window title is just "Chrome: YouTube", so
        // the watch-title path above can't fire. ContentExtractor surfaces
        // candidate cards (title + bounds); find EVERY matching card and
        // return them all on the result so the service can draw a block card
        // over each one BEFORE the user opens any of them. Strict full
        // keyword set, consistent with the watch-title check.
        checkYouTubeFeedCards(snapshot)?.let { return it }

        // Log page text matches but don't block
        if (!snapshot.title.isNullOrBlank()) {
            logPageTextMatch(snapshot.title, builtInKeywords)
        }
        if (!snapshot.query.isNullOrBlank()) {
            logPageTextMatch(snapshot.query, builtInKeywords)
        }

        return MatchResult.Allowed
    }

    /**
     * Decide whether a feed card is blocked, using the user's requested
     * weighted risk-score model for DECEPTIVE-video detection plus HARD blocks
     * for the trustworthy signals.
     *
     * Hard blocks (any single one blocks, regardless of score):
     *   1. A permanently blocked channel (the user explicitly blocked it —
     *      every video from it is blocked even with a clean title/thumbnail).
     *   2. An explicit keyword in the TITLE. Titles directly describe the
     *      content, so a title match is a trustworthy signal — weakening it to
     *      a low score weight would let explicit videos through unmarked on
     *      the feed (the latency the user originally complained about).
     *
     * Weighted risk score (block when the sum crosses [FEED_BLOCK_THRESHOLD]):
     *   - Thumbnail content description +0.4 (revealing caption alone crosses)
     *   - Channel name +0.3 (suggestive channel alone also crosses)
     * These catch the "safe title, revealing thumbnail" case where no single
     * signal is trustworthy. The channel-name check uses the website keyword
     * set (gender terms EXCLUDED) so innocent compound names like
     * "Women's Health TV" can't false-positive in strict mode.
     *
     * Returns the Blocked result (matchedItem = the primary keyword/name shown
     * on the block card), or null when the card is clean.
     */
    private fun checkFeedCardSignals(card: FeedVideoCard): MatchResult.Blocked? {
        val channel = card.channel?.trim().orEmpty()
        // Hard block 1: permanent channel blocklist.
        if (channel.isNotEmpty() && channelBlocklist.isBlocked(channel)) {
            val res = MatchResult.Blocked(channel, MatchType.CHANNEL, MatchSource.FEED)
            Log.i(TAG, buildBlockLog("YOUTUBE_FEED_CHANNEL", channel, res))
            return res
        }

        // Hard block 2: explicit keyword in the title.
        val titleRes = checkString(card.title, isUrl = false, builtInKeywords = repository.activeBuiltInKeywords)
        if (titleRes is MatchResult.Blocked) {
            Log.i(TAG, buildBlockLog("YOUTUBE_FEED_TITLE", card.title, titleRes))
            return titleRes
        }

        // Weighted risk score for the deceptive-video signals.
        var score = 0f
        var primary: String? = null
        val reasons = mutableListOf<String>()

        // Thumbnail content description (+0.4) — the strongest soft signal; a
        // revealing caption alone crosses the threshold.
        val desc = card.contentDesc?.trim().orEmpty()
        if (desc.isNotEmpty()) {
            val descRes = checkString(desc, isUrl = false, builtInKeywords = repository.activeBuiltInKeywords)
            if (descRes is MatchResult.Blocked) {
                score += FEED_THUMBNAIL_WEIGHT
                primary = descRes.matchedItem
                reasons.add("THUMBNAIL: ${descRes.matchedItem}")
            }
        }

        // Channel name (+0.3) — suggestive channel alone crosses the
        // threshold. Uses the website set (no tab-restricted gender terms).
        if (channel.isNotEmpty()) {
            val chRes = checkString(channel, isUrl = false, builtInKeywords = websiteBuiltInKeywords())
            if (chRes is MatchResult.Blocked) {
                score += FEED_CHANNEL_WEIGHT
                if (primary == null) primary = chRes.matchedItem
                reasons.add("CHANNEL: ${chRes.matchedItem}")
            }
        }

        // On-device thumbnail image signal (+0.4): the NSFW classifier on the
        // cropped thumbnail. Runs only when the image pipeline was possible
        // (Android 11+ + model in assets + overlay permission) — otherwise
        // nsfwScore is null and this never fires. A confident NSFW thumbnail
        // alone crosses the threshold — the user's "revealing thumbnail" case.
        val nsfw = card.nsfwScore
        if (nsfw != null && nsfw >= com.example.url_blocker.vision.ThumbnailSafetyAnalyzer.NSFW_THRESHOLD) {
            score += FEED_THUMBNAIL_WEIGHT
            if (primary == null) primary = "thumbnail"
            reasons.add("THUMBNAIL IMAGE: ${(nsfw * 100).toInt()}%")
        }

        if (score >= FEED_BLOCK_THRESHOLD) {
            Log.i(TAG, "YOUTUBE_FEED_SCORE score=$score reasons=$reasons")
            return MatchResult.Blocked(
                primary ?: "Blocked video in feed",
                MatchType.BUILT_IN_KEYWORD,
                MatchSource.FEED
            )
        }
        return null
    }

    /**
     * Extract the video title when Chrome is showing a YouTube page.
     *
     * Requires a YouTube context — the URL is a YouTube domain OR the window
     * title carries YouTube's " - YouTube" suffix (the parser only returns a
     * title for that suffix, so a non-YouTube page can never match here). The
     * URL alone is not sufficient because m.youtube.com frequently exposes
     * only the bare domain or null in the address bar.
     */
    private fun extractChromeYouTubeTitle(snapshot: ContentSnapshot): String? {
        val title = snapshot.title ?: return null
        val parsed = ContentExtractor.youtubeTitleFromChromeWindowTitle(title) ?: run {
            // Diagnostic: Chrome often exposes "Web View" as the active window
            // title, leaving no video title to match. ContentExtractor now
            // falls back to the on-page tree title and synthesizes the standard
            // "Chrome: <Title> - YouTube" shape; if this log still appears, the
            // fallback found no page content to read on this device.
            Log.d(TAG, "CHROME_YOUTUBE_TITLE_SKIPPED title='$title' (no parseable video title)")
            return null
        }
        val urlIsYouTube = ContentExtractor.isYouTubeDomain(snapshot.url)
        val titleIsYouTube = title.lowercase(Locale.ROOT).endsWith(" - youtube")
        if (urlIsYouTube || titleIsYouTube) {
            Log.d(TAG, "CHROME_YOUTUBE_TITLE=${parsed} (urlIsYouTube=$urlIsYouTube, titleIsYouTube=$titleIsYouTube)")
            return parsed
        }
        return null
    }

    /**
     * Pre-emptive feed-card check: find EVERY card whose signals block
     * (explicit keyword title, permanently blocked channel, or the weighted
     * thumbnail/channel risk score) and return them all on the Blocked result
     * so the service can draw a block card over each one BEFORE the user opens
     * any of them. Null when no card is blocked.
     */
    private fun checkYouTubeFeedCards(snapshot: ContentSnapshot): MatchResult.Blocked? {
        if (snapshot.feedCards.isEmpty()) return null
        // Pair each blocked card with its check result so the first card's
        // result is computed exactly once (not once in the filter and again
        // for the returned Blocked). The check runs the weighted risk score
        // over ALL of the card's signals — title, thumbnail content
        // description, and channel — and each returned card carries the
        // keyword shown on its block card.
        val blockedCards = snapshot.feedCards.mapNotNull { card ->
            checkFeedCardSignals(card)?.let { card to it }
        }
        if (blockedCards.isEmpty()) return null
        val (firstCard, firstRes) = blockedCards.first()
        val finalRes = firstRes.copy(
            matchSource = MatchSource.FEED,
            feedCards = blockedCards.map { (card, res) ->
                card.copy(blockedKeyword = res.matchedItem)
            }
        )
        Log.i(TAG, buildBlockLog("YOUTUBE_FEED_SIGNAL", firstCard.title, finalRes))
        return finalRes
    }

    /**
     * Google-specific check: URL first, then search query.
     *
     * Tab-restricted gender terms (woman, man, ...) are blocked ONLY on the
     * Videos and Images tabs. On the All tab (and News/Shopping) they are
     * allowed, because those everyday words are common in ordinary searches.
     */
    private fun checkGoogle(snapshot: ContentSnapshot): MatchResult {
        val googleQuery = extractGoogleQuery(snapshot)

        // Detect which Google tab is active (Images, Videos, News, Shopping, ...).
        // Prefer the tree-detected tab (the Google app never exposes the search
        // URL, so URL parsing alone cannot see the tab there); fall back to URL.
        val tab = snapshot.googleTab ?: ContentExtractor.googleTabFromUrl(snapshot.url)
        val isImageVideoTab = tab == "Images" || tab == "Videos"
        // The Google app never exposes the active tab (chips carry no selected
        // state and chip taps produce empty events), so tab-aware gender blocking
        // cannot work there. When the user enables the Google-app toggle, the
        // full strict set — including the tab-restricted gender terms — is used
        // on ALL Google app tabs. Chrome keeps its URL-based tab-aware behavior.
        val builtInKeywords = if (repository.blockGenderTermsInGoogleApp) {
            repository.activeBuiltInKeywords
        } else {
            tabAwareBuiltInKeywords(tab)
        }
        Log.d(TAG, "GOOGLE_TAB=$tab imageVideoTab=$isImageVideoTab googleAllTabs=${repository.blockGenderTermsInGoogleApp}")

        // 1. Check URL if available
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true, builtInKeywords = builtInKeywords)
            if (urlRes is MatchResult.Blocked) {
                val finalRes = urlRes.copy(matchSource = MatchSource.URL)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // 2. Check search query
        if (!googleQuery.isNullOrBlank()) {
            val queryRes = checkString(googleQuery, isUrl = false, builtInKeywords = builtInKeywords)
            if (queryRes is MatchResult.Blocked) {
                val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                Log.i(TAG, buildBlockLog("QUERY", googleQuery, finalRes))
                return finalRes
            }
        }

        // Log page text matches but don't block
        if (!snapshot.title.isNullOrBlank()) {
            logPageTextMatch(snapshot.title, builtInKeywords)
        }

        return MatchResult.Allowed
    }

    /**
     * YouTube-specific check: title is the PRIMARY signal.
     *
     * The YouTube app does not expose a URL bar, so we cannot do
     * domain-based or URL-based blocking. Instead we check:
     * 1. Video title (populated as snapshot.title by ContentExtractor)
     * 2. Detected signals (Shorts indicator, hashtags — populated as snapshot.query)
     *
     * Title-based blocking is appropriate here because:
     * - The video title IS the content identifier
     * - Users navigate YouTube by searching/tapping videos
     * - Unlike arbitrary web page text, video titles directly describe content
     * - YouTube has no URL bar, so URL extraction is not an alternative
     */
    private fun checkYouTube(snapshot: ContentSnapshot): MatchResult {
        // 0. In-app browser URL (websites opened from inside the YouTube app).
        //    When a link is tapped in a video description or comment, YouTube
        //    renders the site in its own embedded browser — the package stays
        //    YouTube, so the URL extracted by ContentExtractor must be checked
        //    like any other website URL.
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true)
            if (urlRes is MatchResult.Blocked) {
                val finalRes = urlRes.copy(matchSource = MatchSource.URL)
                Log.i(TAG, buildBlockLog("YOUTUBE_INAPP_URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // 0.5 Permanently blocked channel: every video from it is blocked,
        //     regardless of title — checked BEFORE the title so a clean-titled
        //     video from a blocked channel is still caught.
        val blockedChannel = snapshot.channel?.takeIf { it.isNotBlank() && channelBlocklist.isBlocked(it) }
        if (blockedChannel != null) {
            val channelRes = MatchResult.Blocked(blockedChannel, MatchType.CHANNEL, MatchSource.TITLE)
            Log.i(TAG, buildBlockLog("YOUTUBE_CHANNEL", blockedChannel, channelRes))
            return channelRes
        }

        // 1. Check video title (primary signal)
        if (!snapshot.title.isNullOrBlank()) {
            val titleRes = checkString(snapshot.title, isUrl = false)
            if (titleRes is MatchResult.Blocked) {
                val finalRes = titleRes.copy(matchSource = MatchSource.TITLE)
                Log.i(TAG, buildBlockLog("YOUTUBE_TITLE", snapshot.title, finalRes))
                return finalRes
            }
        }

        // 2. Check video description (best-effort secondary signal). Only
        //    matches when ContentExtractor surfaced a description, so a page
        //    without an exposed description is unaffected.
        if (!snapshot.description.isNullOrBlank()) {
            val descRes = checkString(snapshot.description, isUrl = false)
            if (descRes is MatchResult.Blocked) {
                val finalRes = descRes.copy(matchSource = MatchSource.DESCRIPTION)
                Log.i(TAG, buildBlockLog("YOUTUBE_DESCRIPTION", snapshot.description, finalRes))
                return finalRes
            }
        }

        // 3. Check extracted signals (Shorts, hashtags)
        if (!snapshot.query.isNullOrBlank()) {
            val queryRes = checkString(snapshot.query, isUrl = false)
            if (queryRes is MatchResult.Blocked) {
                val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                Log.i(TAG, buildBlockLog("YOUTUBE_SIGNAL", snapshot.query, finalRes))
                return finalRes
            }
        }

        return MatchResult.Allowed
    }

    /**
     * Generic check for non-target packages (URL first, then query, then title logging).
     * Tab-restricted gender terms never block embedded websites either.
     */
    private fun checkGeneric(snapshot: ContentSnapshot): MatchResult {
        val builtInKeywords = websiteBuiltInKeywords()

        // 1. Check URL explicitly if available
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true, builtInKeywords = builtInKeywords)
            if (urlRes is MatchResult.Blocked) {
                val finalRes = urlRes.copy(matchSource = MatchSource.URL)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // 2. Check Query explicitly if available
        if (!snapshot.query.isNullOrBlank()) {
            val queryRes = checkString(snapshot.query, isUrl = false, builtInKeywords = builtInKeywords)
            if (queryRes is MatchResult.Blocked) {
                val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                Log.i(TAG, buildBlockLog("QUERY", snapshot.query, finalRes))
                return finalRes
            }
        }

        // 3. Page Text / Title - only log, do NOT block
        if (!snapshot.title.isNullOrBlank()) {
            logPageTextMatch(snapshot.title, builtInKeywords)
        }

        return MatchResult.Allowed
    }

    /**
     * Extract the current Google search query from the snapshot.
     * Checks: explicit query field (populated by ContentExtractor), then URL params.
     * Title-based extraction is handled by ContentExtractor.extractQueryFromGoogleTitle().
     */
    private fun extractGoogleQuery(snapshot: ContentSnapshot): String? {
        // 1. Direct query from extraction (most reliable - set by ContentExtractor)
        if (!snapshot.query.isNullOrBlank()) {
            return snapshot.query
        }

        // 2. Try to extract from URL params (fallback)
        //    NOTE: Chrome's address-bar URL has NO scheme ("google.com/search?..."),
        //    which makes android.net.Uri treat "google.com" as an opaque scheme and
        //    throw on getQueryParameter(). GoogleSignalParser.queryFromUrl normalizes
        //    the scheme and guards the whole extraction so this can never crash the
        //    evaluation (previously it aborted Chrome Images/Videos checks).
        if (!snapshot.url.isNullOrBlank() && snapshot.url.contains("google.com/search")) {
            val qParam = GoogleSignalParser.queryFromUrl(snapshot.url)
            if (!qParam.isNullOrBlank()) {
                Log.d(TAG, "Extracted Google query from URL: $qParam")
                return qParam
            }
        }

        return null
    }

    /**
     * Log a page text match found but not blocked. Uses the same keyword set
     * as the active block check, so logcat stays consistent with what would
     * actually be blocked in this context (e.g. tab-restricted gender words
     * are not logged as matches on websites).
     */
    private fun logPageTextMatch(
        text: String,
        builtInKeywords: Set<String> = repository.activeBuiltInKeywords
    ) {
        val lower = text.lowercase(Locale.ROOT)
        val builtIn = checkKeywords(lower, builtInKeywords, protectShortWords = true)
        if (builtIn != null) {
            Log.d(TAG, """
                PAGE TEXT MATCH FOUND
                Keyword = $builtIn
                Action = ALLOW
                Reason = Not current URL / query
            """.trimIndent())
        } else {
            val userKeyword = checkKeywords(lower, repository.getUserKeywords())
            if (userKeyword != null) {
                Log.d(TAG, """
                    PAGE TEXT MATCH FOUND
                    Keyword = $userKeyword
                    Action = ALLOW
                    Reason = Not current URL / query
                """.trimIndent())
            }
        }
    }

    /**
     * Build a detailed block decision log entry.
     */
    private fun buildBlockLog(source: String, content: String, result: MatchResult.Blocked): String {
        return """
            BLOCK DECISION
            Source = $source
            Content = $content
            Matched Type = ${result.matchType.name}
            Matched Keyword = ${result.matchedItem}
        """.trimIndent()
    }

    /**
     * Built-in keyword set for websites / non-tabbed Google surfaces:
     * the full strict set MINUS the tab-restricted gender terms, which only
     * block inside Google's Images/Videos tabs.
     */
    private fun websiteBuiltInKeywords(): Set<String> =
        repository.activeBuiltInKeywords - BlockRepository.TAB_RESTRICTED_KEYWORDS

    /**
     * Tab-aware keyword selection: the full strict set (including the
     * tab-restricted gender terms) on Google's Images/Videos tabs, otherwise
     * the website set (gender terms filtered out).
     */
    private fun tabAwareBuiltInKeywords(tab: String?): Set<String> =
        if (tab == "Images" || tab == "Videos") {
            repository.activeBuiltInKeywords
        } else {
            websiteBuiltInKeywords()
        }

    /**
     * Matches a domain against the blocked domains list.
     */
    private fun checkString(
        text: String,
        isUrl: Boolean,
        builtInKeywords: Set<String> = repository.activeBuiltInKeywords
    ): MatchResult {
        val lower = text.lowercase(Locale.ROOT)

        // Domain matching for URLs
        if (isUrl) {
            val matchedDomain = checkDomains(lower)
            if (matchedDomain != null) {
                return MatchResult.Blocked(matchedDomain, MatchType.DOMAIN, MatchSource.DOMAIN)
            }
        }

        val matchedBuiltIn = checkKeywords(lower, builtInKeywords, protectShortWords = true)
        if (matchedBuiltIn != null) {
            return MatchResult.Blocked(matchedBuiltIn, MatchType.BUILT_IN_KEYWORD, MatchSource.NONE)
        }

        val matchedUser = checkKeywords(lower, repository.getUserKeywords())
        if (matchedUser != null) {
            return MatchResult.Blocked(matchedUser, MatchType.USER_KEYWORD, MatchSource.NONE)
        }

        return MatchResult.Allowed
    }

    private fun checkDomains(urlText: String): String? {
        val allDomains = repository.getAllBlockedDomains()
        if (allDomains.isEmpty()) return null

        val host = extractHost(urlText) ?: return null

        for (domain in allDomains) {
            if (host == domain || host.endsWith(".$domain")) {
                return domain
            }
        }
        return null
    }

    private fun checkKeywords(
        lowercaseText: String,
        keywords: Set<String>,
        protectShortWords: Boolean = false
    ): String? {
        if (keywords.isEmpty()) return null
        for (keyword in keywords.sortedWith(compareByDescending<String> { it.length }.thenBy { it })) {
            if (containsKeyword(lowercaseText, keyword, protectShortWords)) {
                return keyword
            }
        }
        return null
    }

    private fun containsKeyword(text: String, keyword: String, protectShortWords: Boolean): Boolean {
        var start = text.indexOf(keyword)
        while (start >= 0) {
            val end = start + keyword.length
            val requiresBoundary = protectShortWords && keyword.length <= 3
            val beforeIsWord = start > 0 && text[start - 1].isLetterOrDigit()
            val afterIsWord = end < text.length && text[end].isLetterOrDigit()
            if (!requiresBoundary || (!beforeIsWord && !afterIsWord)) return true
            start = text.indexOf(keyword, start + 1)
        }
        return false
    }

    /**
     * Safely extract the host part from a URL-like string.
     */
    fun extractHost(text: String): String? {
        var s = text.trim().lowercase(Locale.ROOT)
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        return try {
            val uri = Uri.parse(s)
            val host = uri.host?.lowercase()
            host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    private fun isChromePackage(packageName: String): Boolean {
        return packageName in com.example.url_blocker.extractor.ContentExtractor.CHROME_PACKAGES
    }

    private fun isYouTubePackage(packageName: String): Boolean {
        return packageName in com.example.url_blocker.extractor.ContentExtractor.YOUTUBE_PACKAGES
    }
}
