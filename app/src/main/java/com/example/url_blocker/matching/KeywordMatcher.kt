package com.example.url_blocker.matching

import android.net.Uri
import android.util.Log
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
        val matchSource: MatchSource
    ) : MatchResult()
}

enum class MatchType {
    DOMAIN,
    BUILT_IN_KEYWORD,
    USER_KEYWORD
}

enum class MatchSource {
    URL,
    QUERY,
    DOMAIN,
    TITLE,
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
    val querySource: QuerySource = QuerySource.NONE
) {
    // Generate an identity for deduplication
    fun toIdentityString(): String {
        return "$packageName|${url ?: ""}|${query ?: ""}|${title ?: ""}"
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
class KeywordMatcher(private val repository: BlockRepository) {

    companion object {
        private const val TAG = "KeywordMatcher"
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
     */
    private fun checkChrome(snapshot: ContentSnapshot): MatchResult {
        // URL is the primary source — domain matching is done inside checkString(isUrl=true)
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true)
            if (urlRes is MatchResult.Blocked) {
                val source = if (urlRes.matchType == MatchType.DOMAIN) MatchSource.DOMAIN else MatchSource.URL
                val finalRes = urlRes.copy(matchSource = source)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // Log page text matches but don't block
        if (!snapshot.title.isNullOrBlank()) {
            logPageTextMatch(snapshot.title)
        }
        if (!snapshot.query.isNullOrBlank()) {
            logPageTextMatch(snapshot.query)
        }

        return MatchResult.Allowed
    }

    /**
     * Google-specific check: URL first, then search query.
     */
    private fun checkGoogle(snapshot: ContentSnapshot): MatchResult {
        val googleQuery = extractGoogleQuery(snapshot)

        // 1. Check URL if available
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true)
            if (urlRes is MatchResult.Blocked) {
                val finalRes = urlRes.copy(matchSource = MatchSource.URL)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // 2. Check search query
        if (!googleQuery.isNullOrBlank()) {
            val queryRes = checkString(googleQuery, isUrl = false)
            if (queryRes is MatchResult.Blocked) {
                val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                Log.i(TAG, buildBlockLog("QUERY", googleQuery, finalRes))
                return finalRes
            }
        }

        // Log page text matches but don't block
        if (!snapshot.title.isNullOrBlank()) {
            logPageTextMatch(snapshot.title)
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
        // 1. Check video title (primary signal)
        if (!snapshot.title.isNullOrBlank()) {
            val titleRes = checkString(snapshot.title, isUrl = false)
            if (titleRes is MatchResult.Blocked) {
                val finalRes = titleRes.copy(matchSource = MatchSource.TITLE)
                Log.i(TAG, buildBlockLog("YOUTUBE_TITLE", snapshot.title, finalRes))
                return finalRes
            }
        }

        // 2. Check extracted signals (Shorts, hashtags)
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
     */
    private fun checkGeneric(snapshot: ContentSnapshot): MatchResult {
        // 1. Check URL explicitly if available
        if (!snapshot.url.isNullOrBlank()) {
            val urlRes = checkString(snapshot.url, isUrl = true)
            if (urlRes is MatchResult.Blocked) {
                val finalRes = urlRes.copy(matchSource = MatchSource.URL)
                Log.i(TAG, buildBlockLog("URL", snapshot.url, finalRes))
                return finalRes
            }
        }

        // 2. Check Query explicitly if available
        if (!snapshot.query.isNullOrBlank()) {
            val queryRes = checkString(snapshot.query, isUrl = false)
            if (queryRes is MatchResult.Blocked) {
                val finalRes = queryRes.copy(matchSource = MatchSource.QUERY)
                Log.i(TAG, buildBlockLog("QUERY", snapshot.query, finalRes))
                return finalRes
            }
        }

        // 3. Page Text / Title - only log, do NOT block
        if (!snapshot.title.isNullOrBlank()) {
            logPageTextMatch(snapshot.title)
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
        if (!snapshot.url.isNullOrBlank() && snapshot.url.contains("google.com/search")) {
            val uri = try {
                Uri.parse(snapshot.url)
            } catch (e: Exception) { null }
            if (uri != null) {
                val qParam = uri.getQueryParameter("q")
                if (!qParam.isNullOrBlank()) {
                    Log.d(TAG, "Extracted Google query from URL: $qParam")
                    return qParam
                }
            }
        }

        return null
    }

    /**
     * Log a page text match found but not blocked.
     */
    private fun logPageTextMatch(text: String) {
        val lower = text.lowercase(Locale.ROOT)
        val builtIn = checkKeywords(lower, repository.activeBuiltInKeywords, protectShortWords = true)
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
     * Matches a domain against the blocked domains list.
     */
    private fun checkString(text: String, isUrl: Boolean): MatchResult {
        val lower = text.lowercase(Locale.ROOT)

        // Domain matching for URLs
        if (isUrl) {
            val matchedDomain = checkDomains(lower)
            if (matchedDomain != null) {
                return MatchResult.Blocked(matchedDomain, MatchType.DOMAIN, MatchSource.DOMAIN)
            }
        }

        val matchedBuiltIn = checkKeywords(lower, repository.activeBuiltInKeywords, protectShortWords = true)
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
