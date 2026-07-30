package com.example.url_blocker.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.Locale

/**
 * Local-only repository for storing user-added keywords and blocked domains.
 * Built-in protected keywords are defined as a constant and never exposed to the user.
 */
class BlockRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the Strict Mode keyword preset is enabled. */
    var isStrictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_MODE, value).apply()

    /**
     * Built-in protected keywords — always active, hidden from the user, not editable.
     * When Strict Mode is enabled, additional broad keywords are included.
     * These cover broad adult-content and explicit-content filtering across 8 categories.
     */
    val activeBuiltInKeywords: Set<String>
        get() {
            val base = ADULT_KEYWORDS_BY_CATEGORY.values.flatten().toMutableSet()
            if (isStrictMode) {
                base.addAll(STRICT_MODE_KEYWORDS)
            }
            return base
        }

    /**
     * Built-in adult domain preset — always active.
     * Known adult-content domains. This list CANNOT guarantee 100% coverage
     * — new adult websites appear every day. Combine with keyword blocking
     * for stronger protection.
     */
    val activeAdultDomains: Set<String>
        get() = ADULT_DOMAINS

    /** All domains to check (user + preset). */
    fun getAllBlockedDomains(): Set<String> {
        val all = getBlockedDomains().toMutableSet()
        all.addAll(activeAdultDomains)
        return all
    }

    // ── User keywords ──────────────────────────────────────────────

    fun getUserKeywords(): Set<String> =
        (prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

    fun addUserKeyword(keyword: String) {
        val trimmed = keyword.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return
        val current = getUserKeywords().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
    }

    fun removeUserKeyword(keyword: String) {
        val current = getUserKeywords().toMutableSet()
        current.remove(keyword.trim().lowercase(Locale.ROOT))
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
    }

    fun replaceUserKeyword(oldKeyword: String, newKeyword: String) {
        val replacement = newKeyword.trim().lowercase(Locale.ROOT)
        if (replacement.isEmpty()) return
        val current = getUserKeywords().toMutableSet()
        current.remove(oldKeyword.trim().lowercase(Locale.ROOT))
        current.add(replacement)
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
    }

    // ── Blocked domains / websites ─────────────────────────────────

    fun getBlockedDomains(): Set<String> =
        (prefs.getStringSet(KEY_BLOCKED_DOMAINS, emptySet()) ?: emptySet())
            .mapNotNull(::normalizeDomain)
            .toSet()

    fun addBlockedDomain(domain: String) {
        val trimmed = normalizeDomain(domain) ?: return
        val current = getBlockedDomains().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
    }

    fun removeBlockedDomain(domain: String) {
        val current = getBlockedDomains().toMutableSet()
        normalizeDomain(domain)?.let(current::remove)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
    }

    // ── App Password / Lock ────────────────────────────────────────

    /** Returns the SHA-256 hash of the stored password, or null if no password is set. */
    fun getPasswordHash(): String? = prefs.getString(KEY_APP_PASSWORD, null)

    /** Whether a password has been configured. */
    fun hasPassword(): Boolean = getPasswordHash() != null

    /** Set a new password. Stores SHA-256 hash. */
    fun setPassword(password: String) {
        val hash = password.toByteArray().let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it)
        }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_APP_PASSWORD, hash).apply()
    }

    /** Verify a password attempt. */
    fun verifyPassword(password: String): Boolean {
        val storedHash = getPasswordHash() ?: return false
        val attemptHash = password.toByteArray().let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it)
        }.joinToString("") { "%02x".format(it) }
        return storedHash.equals(attemptHash, ignoreCase = true)
    }

    /** Remove the password (disable lock). */
    fun clearPassword() {
        prefs.edit().remove(KEY_APP_PASSWORD).apply()
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
        private const val KEY_APP_PASSWORD = "app_password"
        private const val KEY_STRICT_MODE = "strict_mode"
        private const val MAX_LOG_ENTRIES = 100

        /** Returns a hostname-only rule, or null when the input is not a safe host. */
        fun normalizeDomain(input: String): String? {
            var candidate = input.trim().lowercase(Locale.ROOT)
            if (candidate.isEmpty()) return null
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                candidate = "https://$candidate"
            }

            val host = runCatching { Uri.parse(candidate).host?.lowercase(Locale.ROOT) }
                .getOrNull()
                ?.removePrefix("www.")
                ?.trimEnd('.')
                ?: return null

            if (host.isEmpty() || host.length > 253 ||
                !host.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) ||
                host.contains("..") || host.startsWith(".") || host.endsWith(".")) {
                return null
            }
            return host
        }

        // ── ADULT KEYWORD PRESETS (organised by category) ────────────

        val ADULT_KEYWORD_CATEGORIES: Set<String> = setOf(
            "Porn Sites", "Explicit Terms", "Nudity",
            "NSFW / Adult", "Adult Social / Dating", "Fetish / BDSM",
            "Slang", "Misspellings"
        )

        val ADULT_KEYWORDS_BY_CATEGORY: Map<String, Set<String>> = linkedMapOf(
            "Porn Sites" to setOf(
                "porn", "porno", "pornography", "pornographic", "pornhub",
                "xnxx", "xvideos", "xhamster", "redtube", "youporn",
                "porntube", "hentai", "yaoi", "yuri", "nhentai",
                "rule34", "playboy", "hustler", "penthouse",
                "onlyfans", "fansly", "chaturbate", "stripchat",
                "camgirl", "webcam", "livejasmin", "cam4"
            ),
            "Explicit Terms" to setOf(
                "sex", "sexy", "sexual", "sexting", "sexo",
                "fuck", "fucking", "fucker",
                "cock", "dick", "penis", "penile",
                "pussy", "vagina", "vaginal", "clit", "clitoris",
                "boobs", "breast", "breasts", "tits", "titty",
                "asshole", "buttocks", "arse",
                "blowjob", "handjob", "oral", "rimjob", "deepthroat",
                "cum", "semen", "ejaculate", "ejaculation", "cumshot",
                "orgasm", "orgy", "orgies",
                "anal", "anally",
                "boner", "erection", "horny",
                "masturbate", "masturbation", "masturbating",
                "prostitute", "prostitution", "escort", "escorts",
                "sugardaddy", "sugarbaby",
                "slut", "whore", "bitch", "hooker",
                "milf", "squirt", "squirting",
                "vibrator", "dildo", "buttplug",
                "pornstar", "nudes"
            ),
            "Nudity" to setOf(
                "nude", "naked", "nudity", "nudist", "nudists",
                "topless", "bottomless",
                "stripper", "stripclub", "striptease",
                "lingerie", "erotic", "erotica",
                "burlesque"
            ),
            "NSFW / Adult" to setOf(
                "nsfw", "nsfl", "adult", "xxx",
                "mature", "explicit",
                "gore", "guro",
                "beastiality", "zoophilia", "bestiality",
                "lolita",
                "incest",
                "rape", "noncon",
                "snuff"
            ),
            "Adult Social / Dating" to setOf(
                "tinder", "bumble", "hinge", "grindr",
                "chatroulette", "omegle",
                "hookup", "hookups",
                "adultfriendfinder", "ashleymadison",
                "cuckold", "cuckolding",
                "gangbang", "threesome",
                "shemale", "transsexual",
                "bbw"
            ),
            "Fetish / BDSM" to setOf(
                "bdsm", "kink", "kinky", "fetish", "fetishes",
                "bondage", "domination", "dominatrix",
                "submissive", "submission",
                "leather", "latex",
                "footfetish", "footjob",
                "vore",
                "chastity"
            ),
            "Slang" to setOf(
                "noods",
                "thirsttrap",
                "simp", "simping",
                "nsfwart",
                "lewds", "lewd",
                "pedo", "pedophile",
                "furry", "yiff",
                "p0rn"
            ),
            "Misspellings" to setOf(
                "xvedio", "xvidoes",
                "pornhubb", "pornhup",
                "xnxxx",
                "s3x", "secks", "sekz",
                "n00de", "nued",
                "fuk", "fukc", "phuck", "fuq",
                "dikk",
                "pussi", "pusy",
                "boobz", "boobes",
                "azz",
                "henti",
                "milff", "milfs",
                "esc0rt", "escourt"
            )
        )

        // ── ADULT WEBSITE DOMAIN PRESET ──────────────────────────────
        //
        // Known adult-content domains. This list CANNOT guarantee 100% coverage
        // — new adult websites appear every day. Combine with keyword blocking
        // for stronger protection. Domain matching uses exact host or subdomain
        // suffix comparison (e.g., "pornhub.com" matches "www.pornhub.com" and
        // "static.pornhub.com" but NOT "notpornhub.com").

        val ADULT_DOMAINS: Set<String> = setOf(
            // Major adult sites
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "xhamster.com",
            "redtube.com",
            "youporn.com",
            "tube8.com",
            "porntube.com",
            "eporner.com",

            // Live cams
            "chaturbate.com",
            "stripchat.com",
            "livejasmin.com",
            "cam4.com",
            "camsoda.com",
            "myfreecams.com",
            "bongacams.com",
            "streamate.com",
            "omegle.com",

            // Adult social / dating
            "adultfriendfinder.com",
            "ashleymadison.com",
            "fetlife.com",

            // Premium / studios
            "playboy.com",
            "hustler.com",
            "penthouse.com",
            "brazzers.com",
            "bangbros.com",
            "realitykings.com",
            "naughtyamerica.com",
            "onlyfans.com",
            "fansly.com",

            // Hentai / Japanese adult
            "nhentai.net",
            "hentaihaven.org",
            "e-hentai.org",

            // Furry / Yiff
            "e621.net",
            "e926.net",
            "furaffinity.net",

            // Erotic literature
            "literotica.com",
            "asstr.org",

            // Art / image hosting
            "rule34.xxx",
            "gelbooru.com",
            "danbooru.donmai.us",

            // Misc adult
            "motherless.com",
            "imagefap.com",
            "adultempire.com",

            // Image hosting (commonly used for explicit images)
            "imgur.com",
            "imgbox.com",
            "postimg.cc",
            "postimages.org",
            "image.xyz",
            "imagebam.com",
            "pimpandhost.com",
            "pixhost.to",
            "imgclick.net",
            "imagetwist.com",
            "imagevenue.com",
            "imagebunker.com",
            "picstate.com",
            "hotimage.com"
        )


    }

        // ── STRICT MODE KEYWORDS ────────────────────────────────────
        //
        // WARNING: These words commonly appear in legitimate content
        // (news articles, clothing stores, weather reports, health info).
        // Enabling Strict Mode WILL cause false positives.
        // These are OFF by default and must be manually enabled.
        val STRICT_MODE_KEYWORDS: Set<String> = setOf(
            // Broad terms that overlap with adult content
            "hot",
            "sexy",
            "horny",
            "babe",
            "babes",
            "hottie",
            "hotties",
            "thicc",
            "thick",
            "curvy",
            "busty",
            "voluptuous",

            // Gender/group terms commonly used in adult context
            // WARNING: These WILL cause false positives on legitimate content
            // (women's health, education, news, sports, etc.)
            "women",
            "girls",
            "female",

            // Fashion/swimwear that overlaps with adult content
            "swimwear",
            "bikini",
            "lingerie",

            // Dating/romance related
            "dating",
            "match",
            "flirt",
            "flirting",
            "romance",
            "passion",
            "passionate",
            "intimate",

            // Body parts that can appear in medical/educational contexts
            "thigh",
            "thighs",
            "hips",
            "waist",
            "belly",
            "bellybutton",
            "navel",

            // Sleepwear / loungewear
            "nighty",
            "nightie",
            "negligee",
            "teddy",
            "bodysuit",
            "corset"
        )
}
