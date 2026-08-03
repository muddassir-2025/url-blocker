package com.muddassir.clearview.extractor

import java.net.URLDecoder

object GoogleSignalParser {

    private const val MIN_QUERY_LENGTH = 2

    fun queryFromWindowTitle(title: String): String? {
        val lowerTitle = title.lowercase()
        val suffixes = listOf(" - google search", " - google", " - google chrome")
        for (suffix in suffixes) {
            if (lowerTitle.endsWith(suffix)) {
                val query = title.substring(0, title.length - suffix.length).trim()
                if (query.length >= MIN_QUERY_LENGTH) return query
            }
        }
        return null
    }

    /**
     * Extract the `q` query parameter from a Google search URL, or null when
     * absent/unparseable.
     *
     * Chrome exposes the address-bar URL WITHOUT a scheme (e.g.
     * "google.com/search?client=...&q=women"). android.net.Uri would then treat
     * "google.com" as an opaque scheme and getQueryParameter() would throw
     * "This isn't a hierarchical URI" — which, unguarded, aborted the whole
     * block evaluation (the user's observed crash on the Images tab).
     *
     * The query string is parsed directly instead of via android.net.Uri, so
     * malformed/scheme-less URLs can never crash evaluation AND this stays
     * fully unit-testable on the JVM (where android.net.Uri is a stub that
     * returns null from every method).
     */
    fun queryFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return null
            val query = url.substring(queryStart + 1)
            for (param in query.split('&')) {
                val eq = param.indexOf('=')
                if (eq <= 0) continue
                if (param.substring(0, eq) != "q") continue
                val value = param.substring(eq + 1)
                if (value.isBlank()) return null
                return URLDecoder.decode(value, "UTF-8").takeIf { it.isNotBlank() }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun cleanQuery(text: String): String {
        return text.trim()
            .replace(
                Regex(
                    "^(search query|current query|search for|search)\\s*[:：-]?\\s*",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }

    fun isExplicitUrl(text: String): Boolean {
        val lowerText = text.trim().lowercase()
        return lowerText.startsWith("http://") ||
                lowerText.startsWith("https://") ||
                lowerText.startsWith("www.")
    }
}
