package com.muddassir.clearview.matching

import android.net.Uri
import android.util.Log
import com.muddassir.clearview.extractor.ContentExtractor
import com.muddassir.clearview.extractor.GoogleSignalParser
import com.muddassir.clearview.repository.BlockRepository
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
         * titles matched. Empty for all other sources. Feed blocks now trigger
         * the full block overlay like every other match.
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
 *    ("Title by Channel • views • ago"), used as a soft risk signal.
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
    val blockedKeyword: String? = null
)

enum class MatchType {
    DOMAIN,
    BUILT_IN_KEYWORD,
    USER_KEYWORD,
    INCOGNITO
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
        return "$packageName|${url ?: ""}|${query ?: ""}|${title ?: ""}|incognito=$incognito|tab=${googleTab ?: ""}|desc=${description?.length ?: 0}|feed=${feedCards.joinToString("|") { "${it.title.hashCode()}:${it.contentDesc?.hashCode()}:${it.channel?.hashCode()}" }.take(240)}"
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
    private val repository: BlockRepository
) {

    companion object {
        private const val TAG = "KeywordMatcher"

        // ── Precompiled match data (hot path) ─────────────────────────
        // checkKeywords used to RE-SORT the whole keyword set on every call
        // (a ~700-element sort, several times per accessibility event). The
        // sorted list is cached per keyword set; the per-keyword indexOf scan
        // itself is fast (~13µs for the full set — verified by benchmark) and
        // stays as-is. BlockRepository returns STABLE set instances (it caches
        // them), so keying by set identity is safe — an edited/rebuild set is
        // a new key that builds a fresh entry. Weak keys so replaced sets can
        // be collected. Thread-safe: checks run on the main thread and on
        // Dispatchers.Default (thumbnail re-analysis).
        private class KeywordCache(
            // (original, normalized, needsBoundary): original is what a match
            // reports (logs / block card); normalized is what's compared against
            // the normalized scan text; needsBoundary is the whole-word verdict
            // for the NORMALIZED form. All precomputed once per set so the hot
            // path never normalizes or re-classifies keywords per event/poll.
            val entries: List<KeywordEntry>
        ) {
            class KeywordEntry(
                val original: String,
                val normalized: String,
                val needsBoundary: Boolean
            )
        }

        private val keywordCacheLock = Any()
        private val keywordCache = java.util.WeakHashMap<Set<String>, KeywordCache>()

        private fun cacheFor(keywords: Set<String>): KeywordCache {
            synchronized(keywordCacheLock) {
                keywordCache[keywords]?.let { return it }
            }
            // Build outside the lock (the sort of ~700 strings is ~tens of µs;
            // a concurrent duplicate build is benign — the put below keeps one).
            val cache = KeywordCache(
                entries = keywords.sortedWith(
                    compareByDescending<String> { it.length }.thenBy { it }
                ).map { kw ->
                    val normalized = normalizeForMatching(kw)
                    KeywordCache.KeywordEntry(kw, normalized, isPlainWordKeyword(normalized))
                }
            )
            synchronized(keywordCacheLock) {
                keywordCache.putIfAbsent(keywords, cache)?.let { return it }
                return cache
            }
        }

        // Compiled once, not per event (was constructed inside checkShortsBlock).
        private val SHORTS_SIGNAL_REGEX = Regex("\\bshorts\\b")

        // ── Whole-word matching (hot path) ──────────────────────────
        // Plain-word keywords (letters + spaces only, every space-separated
        // token a real word of >= 2 letters) match ONLY as whole words: the
        // keyword must never be glued to a neighboring letter/digit. The
        // boundary test uses Char.isLetterOrDigit(), which is Unicode-aware —
        // unlike Java regex \b, it treats non-Latin letters as letters, so
        // "cock" can never hide inside "cocktail"/"peacock", "ass" inside
        // "class"/"grass", "anal" inside "analysis", "bra" inside
        // "library", "man"/"men" inside "management"/"German", "hot"
        // inside "hotel", "sex" inside "Middlesex"/"sextant", "erect"
        // inside "erector", and the phrase "sex tape" inside "sex tapeworm".
        //
        // Obfuscation / leetspeak / misspelling keywords — anything containing
        // digits, punctuation, underscores or emoji ("p0rn", "s3x", "f*ck",
        // "p.o.r.n", "f_u_c_k", "18+", "x-rated", "🔞"), and spaced
        // single-letter forms ("n u d e", "x n x x") — keep plain substring
        // matching. They are intentionally punctuation-embedded, so their only
        // realistic occurrences ARE standalone, and inflected variants
        // ("f*cked", "p0rnhub", "s3xual") must keep matching.
        internal fun isPlainWordKeyword(keyword: String): Boolean {
            var sawLetter = false
            for (ch in keyword) {
                if (ch == ' ') continue
                if (!ch.isLetter()) return false
                sawLetter = true
            }
            if (!sawLetter) return false
            // Spaced single-letter obfuscations ("n u d e", "x n x x") are
            // NOT plain words — keep substring matching for them.
            for (token in keyword.split(' ')) {
                if (token.length < 2) return false
            }
            return true
        }

        /**
         * True when a Google-app query represents a SUBMITTED search (results
         * page showing), not live text the user is still typing in the search
         * box.
         *
         * The Google app exposes the search-box text as the query WHILE the
         * user types (SEARCH_BAR / EDITABLE_FIELD / ACCESSIBILITY_EVENT
         * sources), so a query check without this gate blocks the moment a
         * partial word matches (e.g. "ass" as the first letters of
         * "assignment") — before any search runs. Committed-search signals:
         *  - querySource == WINDOW_TITLE (the "keyword - Google Search"
         *    window title only exists on the results page);
         *  - googleTab != null (the All/Images/Videos tab chips only exist on
         *    the results page);
         *  - the window title parses as a Google search title (defense in
         *    depth when the chip scan misses);
         *  - a google.com/search URL is exposed (Chrome; the Google app
         *    rarely exposes it, but when it does the search is committed).
         *
         * While typing on the Google home screen none of these are true, so
         * the typed text is never treated as a search. Chrome is unaffected
         * by design: its query comes from the URL q= param or the
         * results-page window title, which only exist after submission.
         */
        internal fun isGoogleSearchCommitted(snapshot: ContentSnapshot): Boolean =
            snapshot.querySource == QuerySource.WINDOW_TITLE ||
                snapshot.googleTab != null ||
                GoogleSignalParser.queryFromWindowTitle(snapshot.title ?: "") != null ||
                snapshot.url?.contains("google.com/search") == true

        /**
         * Whole-word aware keyword matching. [needsBoundary] is
         * [isPlainWordKeyword]'s verdict for [keyword] (precomputed per set by
         * the cache); pass false to get a plain substring search for the
         * obfuscation/leetspeak forms. The text must already be lowercased.
         */
        internal fun containsKeyword(text: String, keyword: String, needsBoundary: Boolean): Boolean {
            if (!needsBoundary) return text.contains(keyword)
            var start = text.indexOf(keyword)
            while (start >= 0) {
                val end = start + keyword.length
                val beforeIsWord = start > 0 && text[start - 1].isLetterOrDigit()
                val afterIsWord = end < text.length && text[end].isLetterOrDigit()
                if (!beforeIsWord && !afterIsWord) return true
                start = text.indexOf(keyword, start + 1)
            }
            return false
        }

        // ── Text normalization (pre-keyword matching) ──────────────────
        // Runs BEFORE every keyword comparison so disguised spellings are
        // caught generically instead of by hardcoded evasion entries: Unicode
        // NFKC + homoglyph folding + leetspeak substitution + single-letter
        // separator collapsing. O(n) over the text length using precomputed
        // lookup maps only (no regex compiled per call, no live Unicode
        // confusables database). Applied to BOTH the scanned text and the
        // keywords (via the cache), so existing literal entries keep matching
        // their raw forms and redundant evasions simply become harmless. The
        // result feeds the whole-word boundary check in containsKeyword —
        // normalization never bypasses it ("p.o.r.n" → "porn", and "porn"
        // still needs word boundaries, so "pornstar" stays unmatched by it).

        /** Visually-similar non-Latin letters folded to their Latin look-alikes. */
        private val HOMOGLYPH_MAP: Map<Char, Char> = mapOf(
            // Cyrillic look-alikes.
            'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y',
            'х' to 'x', 'в' to 'b', 'н' to 'h', 'м' to 'm', 'т' to 't', 'к' to 'k',
            'і' to 'i', 'ѕ' to 's', 'г' to 'r',
            // Greek look-alikes (practical subset).
            'α' to 'a', 'ε' to 'e', 'ο' to 'o', 'ρ' to 'p', 'σ' to 's', 'ς' to 's',
            'ι' to 'i', 'ν' to 'v', 'χ' to 'x', 'κ' to 'k', 'μ' to 'm', 'τ' to 't'
        )

        /** Digit/symbol → letter leetspeak stand-ins (word-like tokens only). */
        private val LEET_MAP: Map<Char, Char> = mapOf(
            '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's',
            '7' to 't', '@' to 'a', '$' to 's', '!' to 'i'
        )

        /**
         * Canonical form used for ALL keyword comparisons. Steps:
         *  1. lowercase + NFKC (collapses fullwidth/compatibility variants:
         *     "ＰＯＲＮ" / "ｐｏｒｎ" → "porn");
         *  2. homoglyph folding (Cyrillic/Greek → Latin look-alikes);
         *  3. per whitespace token: intra-token separator collapsing
         *     ("p.o.r.n" → "porn"), then whitespace single-letter-run
         *     collapsing ("n u d e" → "nude", "p o r n site" → "porn site");
         *  4. leetspeak substitution on word-like tokens ("p0rn" → "porn",
         *     "v1brator" → "vibrator"), never on pure-numeric tokens or
         *     serials like "4x4" (fewer than 2 real letters).
         * Ordinary text — "the score was 100 to 0", "class of 2025",
         * "watch porn now" — passes through unchanged.
         */
        internal fun normalizeForMatching(text: String): String {
            // 1. Lowercase, then NFKC.
            val base = java.text.Normalizer.normalize(
                text.lowercase(Locale.ROOT),
                java.text.Normalizer.Form.NFKC
            )
            // 2. Homoglyph folding (only when the text contains any).
            val folded = if (base.any { it in HOMOGLYPH_MAP }) {
                buildString(base.length) {
                    for (ch in base) append(HOMOGLYPH_MAP[ch] ?: ch)
                }
            } else {
                base
            }
            // 3. Per-token separator collapsing, then single-letter-run
            //    collapsing across whitespace, then leetspeak.
            val tokens = folded.split(' ').filter { it.isNotEmpty() }
            val collapsed = ArrayList<String>(tokens.size)
            for (token in tokens) collapsed.add(collapseSingleLetterSeparators(token))
            val runs = collapseSingleLetterRuns(collapsed)
            val out = ArrayList<String>(runs.size)
            for (token in runs) out.add(applyLeet(token))
            return out.joinToString(" ")
        }

        /** Pure numeric tokens ("100", "0", "3.14", "1,000", "2025") are never leeted. */
        private fun isPureNumericToken(token: String): Boolean {
            if (token.isEmpty()) return false
            for (ch in token) {
                if (!ch.isDigit() && ch != '.' && ch != ',') return false
            }
            return true
        }

        /**
         * Leetspeak substitution. Applied only to word-like tokens (at least
         * two real letters) so serials like "4x4", "2fast" or "1st" are not
         * mangled, while "p0rn", "s3x", "v1brator" are. Pure-numeric tokens
         * ("100", "2025", "3.14") are returned untouched.
         */
        private fun applyLeet(token: String): String {
            if (isPureNumericToken(token)) return token
            var letters = 0
            for (ch in token) if (ch.isLetter()) letters++
            if (letters < 2) return token
            var changed = false
            for (ch in token) {
                if (ch in LEET_MAP) { changed = true; break }
            }
            if (!changed) return token
            return buildString(token.length) {
                for (ch in token) append(LEET_MAP[ch] ?: ch)
            }
        }

        /**
         * Collapse punctuation/underscore separators between SINGLE characters
         * inside a token: "p.o.r.n" → "porn", "p_o_r_n" → "porn",
         * "p..o..r..n" → "porn", "p/o/r/n" → "porn", "n_u_d_3" → "nud3"
         * (leeted to "nude" later). Digits are LEETSPEAK LETTERS, not
         * separators, so "s3x", "p0rn" and "4x4" are never split. Tokens
         * with a multi-character run ("x-videos", "18+") or no content
         * ("🔞") are returned unchanged.
         */
        private fun collapseSingleLetterSeparators(token: String): String {
            if (token.length <= 1) return token
            var i = 0
            var anySeparator = false
            val content = StringBuilder()
            while (i < token.length) {
                if (token[i].isLetter() || token[i].isDigit()) {
                    var j = i
                    while (j < token.length && (token[j].isLetter() || token[j].isDigit())) j++
                    if (j - i > 1) return token // multi-char run → not the pattern
                    content.append(token[i])
                    i = j
                } else {
                    anySeparator = true
                    i++
                }
            }
            return if (anySeparator && content.isNotEmpty()) content.toString() else token
        }

        /**
         * Join consecutive single-character tokens: "n u d e" → "nude",
         * "p o r n site" → "porn site". Longer tokens break the run, so
         * normal multi-word text ("watch porn now") is never joined.
         */
        private fun collapseSingleLetterRuns(tokens: List<String>): List<String> {
            if (tokens.size < 2) return tokens
            val out = ArrayList<String>(tokens.size)
            var i = 0
            while (i < tokens.size) {
                if (tokens[i].length == 1) {
                    val runStart = i
                    while (i < tokens.size && tokens[i].length == 1) i++
                    if (i - runStart >= 2) {
                        val joined = buildString(i - runStart) {
                            for (k in runStart until i) append(tokens[k])
                        }
                        out.add(joined)
                    } else {
                        out.add(tokens[runStart])
                    }
                } else {
                    out.add(tokens[i])
                    i++
                }
            }
            return out
        }

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

        // ── BLOCK SHORTS (Block tab toggle) ──────────────────────────
        // When enabled, YouTube Shorts are blocked in every monitored package
        // (Chrome, the Google app's in-app browser, the YouTube app, ...): any
        // YouTube /shorts URL and the Shorts content signal inside the YouTube
        // app. This replaces the old workaround of adding "shorts" as a user
        // keyword — which only matched URLs and over-blocked innocent words
        // (shortsleeves, shortstop, ...). Long-form videos and /watch URLs are
        // never affected.
        if (repository.blockShorts) {
            checkShortsBlock(snapshot, packageName)?.let { return it }
        }

        // ── CHROME PACKAGES: URL ONLY ──────────────────────────────────
        if (isChromePackage(packageName)) {
            return checkChrome(snapshot)
        }

        // ── GOOGLE APP: URL + QUERY ────────────────────────────────────
        if (packageName == com.muddassir.clearview.extractor.ContentExtractor.GOOGLE_PACKAGE) {
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
     * Block decision for the "Block Shorts" toggle. Matches only when the
     * snapshot is actually a YouTube Short:
     *  - a YouTube /shorts URL in ANY monitored package (Chrome, the Google
     *    app's in-app browser, ...) — a /shorts path is always a Short, or
     *  - the Shorts content signal inside the YOUTUBE APP — the extractor
     *    normalizes the Shorts indicator / #shorts hashtag to the signal
     *    "shorts" in [ContentSnapshot.query] (signals are space-joined, so a
     *    word-boundary match is used: "shorts #fyp" hits, "shortsy" doesn't).
     *
     * The signal part is deliberately limited to YouTube packages: Chrome's /
     * the Google app's search QUERY is the user's own search text, so a search
     * for the plain word "shorts" (clothing, weather, ...) must never be
     * blocked by this toggle — only actual /shorts URLs are blocked there.
     * Returns null when neither applies, so ordinary videos, pages and
     * searches are never blocked by this toggle.
     */
    private fun checkShortsBlock(
        snapshot: ContentSnapshot,
        packageName: String
    ): MatchResult.Blocked? {
        val url = snapshot.url.orEmpty().lowercase(Locale.ROOT)
        val isShortsUrl = ContentExtractor.isYouTubeDomain(url) && url.contains("/shorts")
        val shortsSignal = !isShortsUrl && isYouTubePackage(packageName) &&
            snapshot.query?.let { q ->
                SHORTS_SIGNAL_REGEX.containsMatchIn(q.lowercase(Locale.ROOT))
            } == true
        if (!isShortsUrl && !shortsSignal) return null
        val res = MatchResult.Blocked(
            "YouTube Shorts",
            MatchType.BUILT_IN_KEYWORD,
            if (isShortsUrl) MatchSource.URL else MatchSource.QUERY
        )
        Log.i(TAG, buildBlockLog("SHORTS", snapshot.url ?: snapshot.query ?: "", res))
        return res
    }

    /**
     * Content-only check for LONG (non-Short) YouTube videos watched inside
     * Chrome. Matches the extracted video title and description/text against
     * the SAME active rules the YouTube-app path uses (active built-in +
     * user keywords, plus the context-combination tier) — the authoritative
     * check the long-video coordinator runs on the actual watch page after
     * the user opens the video.
     *
     * URL/query are deliberately NOT consulted: the long-video flow decides
     * on real video content only (a blocked DOMAIN rule is still applied by
     * the normal Chrome pipeline, unaffected by this method). Returns
     * Blocked with MatchSource.TITLE / .DESCRIPTION naming the exact keyword,
     * or Allowed.
     */
    fun checkLongVideoContent(title: String?, description: String?): MatchResult {
        if (!title.isNullOrBlank()) {
            val titleRes = checkString(title, isUrl = false)
            if (titleRes is MatchResult.Blocked) {
                return titleRes.copy(matchSource = MatchSource.TITLE)
            }
        }
        if (!description.isNullOrBlank()) {
            val descRes = checkString(description, isUrl = false)
            if (descRes is MatchResult.Blocked) {
                return descRes.copy(matchSource = MatchSource.DESCRIPTION)
            }
        }
        return MatchResult.Allowed
    }

    /**
     * Chrome-specific check: URL is the ONLY source of truth.
     * Domain matching is already handled inside checkString(isUrl=true).
     *
     * Gender/people terms (woman, girl, ...) NEVER block standalone — the
     * audit moved them out of Strict Mode into the combination generic halves
     * ([BlockRepository.COMBINATION_GENERIC_TERMS]); they only block paired
     * with a risky half (woman + bikini), in every mode.
     *
     * IMPORTANT: Google search is frequently performed inside Chrome, not only
     * in the Google app. So when the Chrome URL is a Google search on the
     * Images or Videos tab (tbm=isch / tbm=vid, or the newer udm layout codes
     * such as udm=2 for Images), the full strict keyword set is used, exactly
     * as in the Google app.
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

            // Check the USER'S YOUTUBE SEARCH QUERY from the URL.
            // The YouTube search results page (m.youtube.com/results?search_query=X)
            // carries the user's search query in the URL. Even though the user
            // searched for a keyword (not a video title), the app blocks the
            // search itself — consistent with how Google Search blocking works.
            // Uses the website keyword set (no tab-restricted gender terms) so
            // innocent searches like "women's health" are not blocked on the
            // All tab.
            val searchQuery = extractYouTubeSearchQuery(url)
            if (!searchQuery.isNullOrBlank()) {
                val queryRes = checkString(searchQuery, isUrl = false, builtInKeywords = websiteBuiltInKeywords())
                if (queryRes is MatchResult.Blocked) {
                    val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                    Log.i(TAG, buildBlockLog("YOUTUBE_SEARCH_QUERY", searchQuery, finalRes))
                    return finalRes
                }
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

        // Google search inside Chrome: block the USER'S SEARCH QUERY on EVERY
        // tab (All, Images, Videos, News, Shopping, ...) — the keyword + pattern
        // (context-combination) blocking must work everywhere, not only on
        // Images/Videos. Mobile Chrome's address-bar URL often OMITS the q=
        // parameter (e.g. Images URL is just "...&udm=2&fbs=..." with no query
        // text), so the query is recovered from the URL's q= param when present,
        // else from the structured Google-search window title ("... - Google
        // Search") — a Google signal, not arbitrary page text. Gated on
        // positively identifying a Google SEARCH page, so ordinary websites are
        // unaffected.
        val isGoogleSearchPage = snapshot.url?.contains("google.com/search") == true ||
            snapshot.title?.let(GoogleSignalParser::queryFromWindowTitle) != null
        if (isGoogleSearchPage) {
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
     *   An explicit keyword in the TITLE. Titles directly describe the
     *   content, so a title match is a trustworthy signal — weakening it to
     *   a low score weight would let explicit videos through unmarked on
     *   the feed (the latency the user originally complained about).
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

        // Hard block: explicit keyword in the title.
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
     * Extract the YouTube search query from a YouTube search/results URL.
     * Handles both `search_query=X` and `?q=X` parameter names.
     */
    private fun extractYouTubeSearchQuery(url: String): String? {
        try {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return null
            val query = url.substring(queryStart + 1)
            for (param in query.split('&')) {
                val eq = param.indexOf('=')
                if (eq <= 0) continue
                val name = param.substring(0, eq).lowercase(Locale.ROOT)
                if (name != "search_query" && name != "q") continue
                val value = param.substring(eq + 1)
                if (value.isBlank()) return null
                return java.net.URLDecoder.decode(value, "UTF-8").takeIf { it.isNotBlank() }
            }
            return null
        } catch (e: Exception) {
            return null
        }
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
     * (explicit keyword title, or the weighted thumbnail/channel risk score)
     * and return them all on the Blocked result — the service then triggers
     * the full block overlay, catching the video BEFORE the user opens it.
     * Null when no card is blocked.
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
     * The gender terms the tab system used to gate (woman, girl, ...) were
     * removed from the keyword sets entirely — they never block standalone
     * anywhere (they only participate as context-combination halves). The
     * Images/Videos tab uses the same keyword set as every other surface
     * ([TAB_RESTRICTED_KEYWORDS] is empty), so the check is uniform.
     */
    private fun checkGoogle(snapshot: ContentSnapshot): MatchResult {
        val googleQuery = extractGoogleQuery(snapshot)

        // Detect which Google tab is active (Images, Videos, News, Shopping, ...).
        // Prefer the tree-detected tab (the Google app never exposes the search
        // URL, so URL parsing alone cannot see the tab there); fall back to URL.
        val tab = snapshot.googleTab ?: ContentExtractor.googleTabFromUrl(snapshot.url)
        val isImageVideoTab = tab == "Images" || tab == "Videos"
        val builtInKeywords = tabAwareBuiltInKeywords(tab)
        Log.d(TAG, "GOOGLE_TAB=$tab imageVideoTab=$isImageVideoTab")

        // 1. Check URL if available
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true, builtInKeywords = builtInKeywords)
            if (urlRes is MatchResult.Blocked) {
                val finalRes = urlRes.copy(matchSource = MatchSource.URL)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // 2. Check search query — ONLY for committed (submitted) searches.
        //    The Google app exposes the live search-box text as the query
        //    while the user types (SEARCH_BAR / EDITABLE_FIELD /
        //    ACCESSIBILITY_EVENT sources), so without the gate a partial
        //    typed word (e.g. "ass" while typing "assignment") blocks before
        //    any search runs — the reported "blocks as soon as I type". The
        //    gate ([isGoogleSearchCommitted]) requires a results-page signal:
        //    the query came from the window title, the tab chips are visible,
        //    the window title parses as "... - Google Search", or a
        //    google.com/search URL is exposed. Chrome is unaffected (its
        //    query only exists post-submission).
        if (!googleQuery.isNullOrBlank()) {
            if (isGoogleSearchCommitted(snapshot)) {
                val queryRes = checkString(googleQuery, isUrl = false, builtInKeywords = builtInKeywords)
                if (queryRes is MatchResult.Blocked) {
                    val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                    Log.i(TAG, buildBlockLog("QUERY", googleQuery, finalRes))
                    return finalRes
                }
            } else {
                Log.d(TAG, "GOOGLE_QUERY_TYPING_SKIPPED query='$googleQuery' (search not submitted)")
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
        val normalized = normalizeForMatching(text)
        val builtIn = checkKeywords(normalized, builtInKeywords)
        if (builtIn != null) {
            Log.d(TAG, """
                PAGE TEXT MATCH FOUND
                Keyword = $builtIn
                Action = ALLOW
                Reason = Not current URL / query
            """.trimIndent())
        } else {
            val userKeyword = checkKeywords(normalized, repository.getUserKeywords())
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
        // Cached in the repository — no per-call set subtraction.
        repository.activeWebsiteKeywords

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
        // Text normalization runs BEFORE all matching (NFKC + homoglyphs +
        // leetspeak + separator collapsing — see normalizeForMatching); the
        // whole-word boundary check in containsKeyword then applies to the
        // normalized text and the cache's normalized keyword forms.
        val normalized = normalizeForMatching(text)

        // Domain matching for URLs
        if (isUrl) {
            val matchedDomain = checkDomains(normalized)
            if (matchedDomain != null) {
                return MatchResult.Blocked(matchedDomain, MatchType.DOMAIN, MatchSource.DOMAIN)
            }
        }

        val matchedBuiltIn = checkKeywords(normalized, builtInKeywords)
        if (matchedBuiltIn != null) {
            return MatchResult.Blocked(matchedBuiltIn, MatchType.BUILT_IN_KEYWORD, MatchSource.NONE)
        }

        val matchedUser = checkKeywords(normalized, repository.getUserKeywords())
        if (matchedUser != null) {
            return MatchResult.Blocked(matchedUser, MatchType.USER_KEYWORD, MatchSource.NONE)
        }

        // TIER 3 — context-combination (normal-mode discovery catch):
        // "woman + bikini", "girl + pics". Only for query/title/description
        // text — raw URLs are exempt so a legitimate page path like
        // /photos/beach.html can never trip it.
        if (!isUrl) {
            val matchedCombo = checkCombinationTerms(normalized)
            if (matchedCombo != null) {
                return MatchResult.Blocked(matchedCombo, MatchType.BUILT_IN_KEYWORD, MatchSource.NONE)
            }
        }

        return MatchResult.Allowed
    }

    /**
     * TIER 3 — context-combination matching. Blocks when the text contains BOTH
     * a generic discovery half (woman, girl, beach, pool, ...) AND a risky half
     * (bikini, lingerie, pics, ...): "woman bikini", "girl pics". This catches
     * searches clearly heading toward sexualized content in NORMAL mode without
     * blocking the halves individually; in Strict Mode the individual terms
     * already block. Reuses the same whole-word rule as checkKeywords, so
     * "hottest" never counts as "hot", "brass" never counts as "bra", and
     * "button" never counts as "butt".
     */
    private fun checkCombinationTerms(lowercaseText: String): String? {
        val generic = BlockRepository.COMBINATION_GENERIC_TERMS
        val risky = BlockRepository.COMBINATION_RISKY_TERMS
        if (generic.isEmpty() || risky.isEmpty()) return null
        var genericHit: String? = null
        for (term in generic) {
            if (containsKeyword(lowercaseText, term, isPlainWordKeyword(term))) {
                genericHit = term
                // Some terms are BOTH a generic and a risky half ("beach"): a
                // term must never self-combine, so a bare "beach" stays
                // innocent — it only blocks together with a DIFFERENT generic
                // ("women beach") or risky ("beach bikini") half. Prefer a
                // generic half that is NOT also risky ("women" over "beach"
                // in "women beach"), so the risky copy stays available to
                // combine with it.
                if (term !in risky) break
            }
        }
        if (genericHit == null) return null
        for (term in risky) {
            // Self-combination guard: a bare "beach" must never block.
            if (term == genericHit) continue
            if (containsKeyword(lowercaseText, term, isPlainWordKeyword(term))) {
                return "$genericHit + $term"
            }
        }
        return null
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
        normalizedText: String,
        keywords: Set<String>
    ): String? {
        if (keywords.isEmpty()) return null
        // The per-keyword scan runs in the original longest-first order; each
        // entry carries its precomputed normalized form + boundary flag, so
        // there is no per-call normalization, sort, or classification.
        for (entry in cacheFor(keywords).entries) {
            if (containsKeyword(normalizedText, entry.normalized, entry.needsBoundary)) {
                return entry.original
            }
        }
        return null
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
        return packageName in com.muddassir.clearview.extractor.ContentExtractor.CHROME_PACKAGES
    }

    private fun isYouTubePackage(packageName: String): Boolean {
        return packageName in com.muddassir.clearview.extractor.ContentExtractor.YOUTUBE_PACKAGES
    }
}
