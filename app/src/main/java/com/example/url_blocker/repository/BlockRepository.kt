package com.example.url_blocker.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Local-only repository for storing user-added keywords and blocked domains.
 * Built-in protected keywords are defined as a constant and never exposed to the user.
 */
class BlockRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Built-in protected keywords — hidden from the user, not editable, not deletable.
     * These cover broad adult-content and explicit-content filtering.
     */
    val builtInKeywords: Set<String>
        get() = BUILT_IN_KEYWORDS

    // ── User keywords ──────────────────────────────────────────────

    fun getUserKeywords(): Set<String> =
        prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet()

    fun addUserKeyword(keyword: String) {
        val trimmed = keyword.trim().lowercase()
        if (trimmed.isEmpty()) return
        val current = getUserKeywords().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
    }

    fun removeUserKeyword(keyword: String) {
        val current = getUserKeywords().toMutableSet()
        current.remove(keyword.trim().lowercase())
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
    }

    // ── Blocked domains / websites ─────────────────────────────────

    fun getBlockedDomains(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_DOMAINS, emptySet()) ?: emptySet()

    fun addBlockedDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
        if (trimmed.isEmpty()) return
        val current = getBlockedDomains().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
    }

    fun removeBlockedDomain(domain: String) {
        val current = getBlockedDomains().toMutableSet()
        current.remove(domain.trim().lowercase())
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
    }

    // ── Event log (in-memory, session only) ────────────────────────

    private val eventLog = mutableListOf<String>()

    fun addLogEntry(entry: String) {
        synchronized(eventLog) {
            eventLog.add(entry)
            if (eventLog.size > MAX_LOG_ENTRIES) {
                eventLog.removeAt(0)
            }
        }
    }

    fun getLogEntries(): List<String> = synchronized(eventLog) { eventLog.toList() }

    fun clearLog() {
        synchronized(eventLog) { eventLog.clear() }
    }

    // ── SharedPreferences keys ─────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "url_blocker_prefs"
        private const val KEY_USER_KEYWORDS = "user_keywords"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
        private const val MAX_LOG_ENTRIES = 100

        /**
         * Broad built-in adult-content and explicit-content keyword set.
         * Includes common terms, variants, plurals, and alternative spellings.
         * Completely hidden from the user interface.
         */
        val BUILT_IN_KEYWORDS: Set<String> = setOf(
            // Core adult terms
            "porn", "porno", "pornography", "pornographic", "pornhub",
            "xnxx", "xvideos", "xhamster", "redtube", "youporn",
            "sex", "sexy", "sexual", "sexting", "sexo",
            "nude", "naked", "nudity", "nudist",
            "boobs", "breast", "breasts", "boob",
            "ass", "butt", "buttocks", "asshole",
            "xxx", "adult", "erotic", "erotica", "nsfw",
            "playboy", "hustler", "penthouse",
            "escort", "escorts",
            "hentai", "yaoi", "yuri", "doujin",
            "bdsm", "kink", "fetish",
            "camgirl", "webcam", "chaturbate",
            "milf", "squirt",
            "orgasm", "orgy", "orgies",
            "vibrator", "dildo",
            "lolita",
            "fuck", "fucking", "fucker",
            "cock", "dick", "penis", "penis",
            "pussy", "vagina", "clit", "clitoris",
            "slut", "whore", "bitch",
            "blowjob", "oral", "handjob",
            "cum", "semen", "ejaculate", "ejaculation",
            "anal",
            "bondage", "dominatrix", "domination",
            "stripper", "stripclub", "striptease",
            "onlyfans", "fansly",
            "tinder", "bumble",
            "chatroulette", "omegle",
            "stripchat", "livejasmin",
            "masturbate", "masturbation", "masturbating",
            "prostitute", "prostitution", "prostituto",
            "sugardaddy", "sugarbaby",
            "cuckold",
            "gangbang",
            "threesome",
            "shemale", "transsexual",
            "bbw", "bigcock",
            "teenporn",
            "beastiality", "zoophilia",
            "rule34", "nhentai",
            "e621", "e926",
            "gore",
            "gambling", "casino", "betting",
            "warez", "cracked",
            "hacked", "hacking",
            "phishing",
            "tinder", "bumble",  // dating apps already covered above
        )
    }
}
