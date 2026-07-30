package com.example.url_blocker.extractor

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
