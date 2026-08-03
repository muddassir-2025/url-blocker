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
     * Whether the tab-restricted gender terms (woman, man, girl, ...) also block
     * on ALL Google app search tabs (not just Images/Videos).
     *
     * The Google app never exposes the active search tab to accessibility (the
     * tab chips carry no selected state and chip taps produce empty events), so
     * tab-aware blocking cannot work there. When enabled, these words block on
     * every Google app search tab. Chrome is unaffected — it keeps its URL-based
     * tab detection (Images/Videos only).
     */
    var blockGenderTermsInGoogleApp: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_GENDER_GOOGLE_APP, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_GENDER_GOOGLE_APP, value).apply()

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
                // Tab-restricted gender terms are part of Strict Mode but only
                // block inside Google's Images/Videos tabs (see KeywordMatcher).
                base.addAll(TAB_RESTRICTED_KEYWORDS)
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
        private const val KEY_BLOCK_GENDER_GOOGLE_APP = "block_gender_google_app"
        private const val MAX_LOG_ENTRIES = 100

        /**
         * Normalize a user-entered domain into a hostname.
         *
         * Examples:
         *   https://www.example.com/path -> example.com
         *   example.com                  -> example.com
         */
        fun normalizeDomain(input: String): String? {
            var candidate = input.trim().lowercase(Locale.ROOT)

            if (candidate.isEmpty()) return null

            if (!candidate.startsWith("http://") &&
                !candidate.startsWith("https://")
            ) {
                candidate = "https://$candidate"
            }

            val host = runCatching {
                Uri.parse(candidate)
                    .host
                    ?.lowercase(Locale.ROOT)
            }
                .getOrNull()
                ?.removePrefix("www.")
                ?.trimEnd('.')
                ?: return null

            if (
                host.isEmpty() ||
                host.length > 253 ||
                !host.matches(
                    Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                ) ||
                host.contains("..") ||
                host.startsWith(".") ||
                host.endsWith(".")
            ) {
                return null
            }

            return host
        }


        // ═══════════════════════════════════════════════════════════════════════
        // ADULT KEYWORD CATEGORIES
        // ═══════════════════════════════════════════════════════════════════════

        val ADULT_KEYWORD_CATEGORIES: Set<String> = linkedSetOf(

            "Porn Sites",

            "Pornography",

            "Explicit Sexual Terms",

            "Sexual Acts",

            "Sexual Anatomy",

            "Nudity",

            "NSFW / Adult",

            "Adult Social / Dating",

            "Cam / Webcam",

            "Fetish / BDSM",

            "Sexual Slang",

            "Erotic Content",

            "Adult Entertainment",

            "Hentai / Anime Adult",

            "Furry / Yiff",

            "Adult Content Codes",

            "Misspellings",

            "Leetspeak",

            "Obfuscations",

            "Search Phrases"
        )


        // ═══════════════════════════════════════════════════════════════════════
        // ADULT KEYWORDS
        // ═══════════════════════════════════════════════════════════════════════

        val ADULT_KEYWORDS_BY_CATEGORY: Map<String, Set<String>> =
            linkedMapOf(

                // ────────────────────────────────────────────────────────────────
                // PORN SITES / BRAND TERMS
                // ────────────────────────────────────────────────────────────────

                "Porn Sites" to linkedSetOf(

                    "porn",

                    "porno",

                    "pornography",

                    "pornographic",

                    "pornhub",

                    "xvideos",

                    "xnxx",

                    "xhamster",

                    "redtube",

                    "youporn",

                    "porntube",

                    "tube8",

                    "spankbang",

                    "eporner",

                    "beeg",

                    "tnaflix",

                    "drtuber",

                    "hqporner",

                    "pornhd",

                    "pornone",

                    "porn300",

                    "porn300",

                    "pornpics",

                    "pornstar",

                    "pornstars",

                    "pornstar video",

                    "porn video",

                    "sex video",

                    "sexvideos",

                    "xxx video",

                    "xxx videos",

                    "adult video",

                    "adult videos",

                    "free porn",

                    "free porno",

                    "free xxx",

                    "watch porn",

                    "watch xxx",

                    "porn tube",

                    "sex tube",

                    "xxx tube",

                    "hentai",

                    "hentай",

                    "nhentai",

                    "rule34",

                    "rule 34",

                    "r34",

                    "e621",

                    "e926",

                    "booru",

                    "gelbooru",

                    "danbooru",

                    "porn comics",

                    "porn comic",

                    "adult comics",

                    "adult comic",

                    "doujin",

                    "doujinshi",

                    "yaoi",

                    "yuri",

                    "ecchi",

                    "lewd anime",

                    "adult anime",

                    "nsfw anime",

                    "porn anime",

                    "hentai anime"
                ),


                // ────────────────────────────────────────────────────────────────
                // EXPLICIT SEXUAL TERMS
                // ────────────────────────────────────────────────────────────────

                "Explicit Sexual Terms" to linkedSetOf(

                    "sex",

                    "sexy",

                    "sexual",

                    "sexuality",

                    "sexual content",

                    "sexually explicit",

                    "explicit sexual",

                    "sexting",

                    "sex chat",

                    "sexcam",

                    "sex cams",

                    "sex cam",

                    "sex show",

                    "sex tape",

                    "sex tapes",

                    "sex video",

                    "sex videos",

                    "fuck",

                    "fucking",

                    "fucked",

                    "fucker",

                    "fuckers",

                    "fucks",

                    "fucked up",

                    "motherfucker",

                    "motherfuck",

                    "cock",

                    "cocks",

                    "dick",

                    "dicks",

                    "penis",

                    "penile",

                    "shaft",

                    "pussy",

                    "pussies",

                    "vagina",

                    "vaginal",

                    "vulva",

                    "clit",

                    "clitoris",

                    "clitoral",

                    "boob",

                    "boobs",

                    "breast",

                    "breasts",

                    "tits",

                    "titty",

                    "titties",

                    "nipple",

                    "nipples",

                    "asshole",

                    "assholes",

                    "arsehole",

                    "buttocks",

                    "buttock",

                    "blowjob",

                    "blow job",

                    "blowjobs",

                    "handjob",

                    "hand job",

                    "handjobs",

                    "oral sex",

                    "oral",

                    "rimjob",

                    "rim job",

                    "deepthroat",

                    "deep throat",

                    "cum",

                    "cumming",

                    "cumshot",

                    "cumshots",

                    "semen",

                    "ejaculate",

                    "ejaculation",

                    "orgasm",

                    "orgasms",

                    "orgy",

                    "orgies",

                    "anal",

                    "anally",

                    "anal sex",

                    "anal intercourse",

                    "boner",

                    "erection",

                    "erect",

                    "horny",

                    "masturbate",

                    "masturbation",

                    "masturbating",

                    "masturbates",

                    "masturbator",

                    "prostitute",

                    "prostitution",

                    "escort",

                    "escorts",

                    "escort service",

                    "sugardaddy",

                    "sugar daddy",

                    "sugarbaby",

                    "sugar baby",

                    "slut",

                    "sluts",

                    "whore",

                    "whores",

                    "hooker",

                    "hookers",

                    "milf",

                    "milfs",

                    "dilf",

                    "dilfs",

                    "squirt",

                    "squirting",

                    "vibrator",

                    "vibrators",

                    "dildo",

                    "dildos",

                    "buttplug",

                    "butt plug",

                    "anal plug",

                    "nudes",

                    "nude pics",

                    "naked pics",

                    "nude photos",

                    "naked photos",

                    "dirty pics",

                    "dirty pictures",

                    "explicit pics",

                    "explicit photos",

                    "pornstar",

                    "pornstars"
                ),


                // ────────────────────────────────────────────────────────────────
                // SEXUAL ACTS
                // ────────────────────────────────────────────────────────────────

                "Sexual Acts" to linkedSetOf(

                    "sexual intercourse",

                    "intercourse",

                    "having sex",

                    "have sex",

                    "sex position",

                    "sex positions",

                    "sex positions",

                    "making love",

                    "oral sex",

                    "anal sex",

                    "group sex",

                    "rough sex",

                    "safe sex",

                    "unprotected sex",

                    "casual sex",

                    "phone sex",

                    "cybersex",

                    "virtual sex",

                    "webcam sex",

                    "phone sex",

                    "threesome",

                    "threesomes",

                    "foursome",

                    "gangbang",

                    "gang bang",

                    "orgy",

                    "orgies",

                    "double penetration",

                    "dp",

                    "facial",

                    "creampie",

                    "cream pie",

                    "cumshot",

                    "money shot",

                    "bukkake",

                    "masturbation",

                    "mutual masturbation",

                    "jerk off",

                    "jerking off",

                    "jack off",

                    "jacking off",

                    "fingering",

                    "titfuck",

                    "tit fuck",

                    "footjob",

                    "foot job",

                    "blow job",

                    "hand job",

                    "rim job",

                    "deep throat"
                ),


                // ────────────────────────────────────────────────────────────────
                // NUDITY
                // ────────────────────────────────────────────────────────────────

                "Nudity" to linkedSetOf(

                    "nude",

                    "nudes",

                    "naked",

                    "nudity",

                    "nudist",

                    "nudists",

                    "topless",

                    "bottomless",

                    "fully nude",

                    "full nude",

                    "full nudity",

                    "nude photo",

                    "nude photos",

                    "nude picture",

                    "nude pictures",

                    "naked photo",

                    "naked photos",

                    "naked picture",

                    "naked pictures",

                    "explicit photo",

                    "explicit photos",

                    "explicit image",

                    "explicit images",

                    "stripper",

                    "strippers",

                    "strip club",

                    "stripclub",

                    "strip clubs",

                    "stripclubs",

                    "striptease",

                    "strip tease",

                    "pole dancer",

                    "pole dancing",

                    "lingerie",

                    "erotic",

                    "erotica",

                    "burlesque",

                    "see through",

                    "see-through",

                    "sheer clothing",

                    "sheer clothes",

                    "revealing clothing",

                    "revealing clothes",

                    "bare breasts",

                    "bare chest",

                    "bare body"
                ),


                // ────────────────────────────────────────────────────────────────
                // NSFW / ADULT
                // ────────────────────────────────────────────────────────────────

                "NSFW / Adult" to linkedSetOf(

                    "nsfw",

                    "nsfl",

                    "not safe for work",

                    "not safe for life",

                    "adult content",

                    "adult only",

                    "18+",

                    "18 plus",

                    "18plus",

                    "21+",

                    "xxx",

                    "xxxx",

                    "mature content",

                    "mature",

                    "explicit",

                    "explicit content",

                    "adult material",

                    "adult entertainment",

                    "adult media",

                    "adult site",

                    "adult website",

                    "adult websites",

                    "restricted content",

                    "age restricted",

                    "age-restricted",

                    "graphic sexual",

                    "sexually explicit",

                    "pornographic content",

                    "obscene content",

                    "gore",

                    "guro",

                    "snuff",

                    "bestiality",

                    "beastiality",

                    "zoophilia",

                    "incest",

                    "noncon",

                    "non-con",

                    "non consensual",

                    "non-consensual"
                ),


                // ────────────────────────────────────────────────────────────────
                // ADULT SOCIAL / DATING
                // ────────────────────────────────────────────────────────────────

                "Adult Social / Dating" to linkedSetOf(

                    "tinder",

                    "bumble",

                    "hinge",

                    "grindr",

                    "adultfriendfinder",

                    "adult friend finder",

                    "ashleymadison",

                    "ashley madison",

                    "fetlife",

                    "chatroulette",

                    "omegle",

                    "hookup",

                    "hookups",

                    "hook up",

                    "hook ups",

                    "casual dating",

                    "casual hookup",

                    "one night stand",

                    "one-night stand",

                    "friends with benefits",

                    "friends-with-benefits",

                    "fwb",

                    "swinger",

                    "swingers",

                    "swinging",

                    "cuckold",

                    "cuckolding",

                    "cuck",

                    "threesome",

                    "threesomes",

                    "gangbang",

                    "gang bang",

                    "bbw",

                    "big beautiful woman",

                    "adult dating",

                    "sex dating",

                    "sex partner",

                    "sex partners",

                    "find sex",

                    "find a hookup",

                    "meet singles"
                ),


                // ────────────────────────────────────────────────────────────────
                // CAM / WEBCAM
                // ────────────────────────────────────────────────────────────────

                "Cam / Webcam" to linkedSetOf(

                    "camgirl",

                    "cam girls",

                    "cam girl",

                    "camgirls",

                    "camboy",

                    "cam boy",

                    "camboys",

                    "webcam",

                    "webcams",

                    "web cam",

                    "web cams",

                    "live cam",

                    "live cams",

                    "live webcam",

                    "live webcams",

                    "adult cam",

                    "adult cams",

                    "sex cam",

                    "sex cams",

                    "cam show",

                    "cam shows",

                    "private cam",

                    "private show",

                    "strip cam",

                    "strip cams",

                    "chaturbate",

                    "stripchat",

                    "livejasmin",

                    "cam4",

                    "camsoda",

                    "myfreecams",

                    "bongacams",

                    "streamate"
                ),


                // ────────────────────────────────────────────────────────────────
                // FETISH / BDSM
                // ────────────────────────────────────────────────────────────────

                "Fetish / BDSM" to linkedSetOf(

                    "bdsm",

                    "kink",

                    "kinky",

                    "fetish",

                    "fetishes",

                    "bondage",

                    "domination",

                    "dominatrix",

                    "dominant",

                    "submissive",

                    "submission",

                    "dominance",

                    "dom sub",

                    "dom/sub",

                    "master",

                    "mistress",

                    "slave",

                    "collar",

                    "leash",

                    "gag",

                    "spanking",

                    "whipping",

                    "flogging",

                    "chastity",

                    "chastity cage",

                    "latex fetish",

                    "leather fetish",

                    "foot fetish",

                    "footfetish",

                    "footjob",

                    "feet fetish",

                    "vore",

                    "furry",

                    "yiff",

                    "voyeur",

                    "voyeurism",

                    "exhibitionism",

                    "roleplay",

                    "role play",

                    "sexual roleplay",

                    "humiliation",

                    "public nudity"
                ),


                // ────────────────────────────────────────────────────────────────
                // SEXUAL SLANG
                // ────────────────────────────────────────────────────────────────

                "Sexual Slang" to linkedSetOf(

                    "noods",

                    "noodz",

                    "nudes",

                    "lewd",

                    "lewds",

                    "lewdz",

                    "smut",

                    "smutty",

                    "thirst trap",

                    "thirsttrap",

                    "thirsty",

                    "simp",

                    "simping",

                    "fap",

                    "fapping",

                    "jerk off",

                    "jack off",

                    "wank",

                    "wanking",

                    "horny",

                    "turned on",

                    "turn me on",

                    "dirty talk",

                    "dirty chat",

                    "dirty pics",

                    "dirty video",

                    "sexy pics",

                    "sexy photos",

                    "sexy video",

                    "hot pics",

                    "hot videos",

                    "adult pics",

                    "adult videos",

                    "pedo",

                    "pedophile",

                    "pedophilia",

                    "child porn",

                    "cp",

                    "loli",

                    "lolicon",

                    "shotacon",

                    "p0rn",

                    "pr0n",

                    "nsfwart"
                ),


                // ────────────────────────────────────────────────────────────────
                // ADULT ENTERTAINMENT
                // ────────────────────────────────────────────────────────────────

                "Adult Entertainment" to linkedSetOf(

                    "playboy",

                    "hustler",

                    "penthouse",

                    "brazzers",

                    "bangbros",

                    "realitykings",

                    "naughtyamerica",

                    "onlyfans",

                    "fansly",

                    "manyvids",

                    "pornstar",

                    "porn stars",

                    "adult star",

                    "adult stars",

                    "sex worker",

                    "sex workers",

                    "escort",

                    "escorts",

                    "escort service",

                    "prostitute",

                    "prostitution",

                    "brothel",

                    "red light district"
                ),


                // ────────────────────────────────────────────────────────────────
                // HENTAI / ANIME ADULT
                // ────────────────────────────────────────────────────────────────

                "Hentai / Anime Adult" to linkedSetOf(

                    "hentai",

                    "hentай",

                    "hentaі",

                    "nhentai",

                    "hentaihaven",

                    "e-hentai",

                    "ehentai",

                    "hentai manga",

                    "hentai manhwa",

                    "hentai doujin",

                    "adult anime",

                    "nsfw anime",

                    "ecchi",

                    "lewd anime",

                    "lewd manga",

                    "yaoi",

                    "yuri",

                    "lolicon",

                    "loli",

                    "shotacon",

                    "shota",

                    "doujin",

                    "doujinshi",

                    "rule34",

                    "rule 34",

                    "r34"
                ),


                // ────────────────────────────────────────────────────────────────
                // ADULT CONTENT CODES
                // ────────────────────────────────────────────────────────────────

                "Adult Content Codes" to linkedSetOf(

                    "nsfw",

                    "nsfl",

                    "r18",

                    "r-18",

                    "18+",

                    "18plus",

                    "xxx",

                    "x-rated",

                    "x rated",

                    "xxx rated",

                    "adult only",

                    "mature only",

                    "explicit only",

                    "🔞"
                ),


                // ────────────────────────────────────────────────────────────────
                // COMMON MISSPELLINGS
                // ────────────────────────────────────────────────────────────────

                "Misspellings" to linkedSetOf(

                    "xvedio",

                    "xvedios",

                    "xvidio",

                    "xvidios",

                    "xvidoes",

                    "xvideo",

                    "xnxx",

                    "xnxxx",

                    "xnx",

                    "pornhubb",

                    "pornhup",

                    "pornhubz",

                    "pornhubbb",

                    "porhnub",

                    "porhn",

                    "poron",

                    "p0rn",

                    "pr0n",

                    "pron",

                    "p0rno",

                    "prono",

                    "s3x",

                    "secks",

                    "sekz",

                    "sexx",

                    "sxe",

                    "fuk",

                    "fukc",

                    "phuck",

                    "fuq",

                    "fck",

                    "fuking",

                    "fuckingg",

                    "dikk",

                    "dik",

                    "dic",

                    "d1ck",

                    "pussi",

                    "pusy",

                    "pussy",

                    "pussyy",

                    "boobz",

                    "boobes",

                    "boobies",

                    "tittz",

                    "titz",

                    "tittiez",

                    "azz",

                    "a55",

                    "asss",

                    "henti",

                    "hentia",

                    "hentay",

                    "milff",

                    "milfs",

                    "m1lf",

                    "esc0rt",

                    "escourt",

                    "esc0rts",

                    "n00de",

                    "n00dz",

                    "n00ds",

                    "nued",

                    "nud3",

                    "n4ked",

                    "nak3d"
                ),


                // ────────────────────────────────────────────────────────────────
                // LEETSPEAK / CHARACTER SUBSTITUTIONS
                // ────────────────────────────────────────────────────────────────

                "Leetspeak" to linkedSetOf(

                    "p0rn",

                    "pr0n",

                    "p0rno",

                    "s3x",

                    "s3xy",

                    "s3xual",

                    "n00de",

                    "n00des",

                    "n00ds",

                    "n00dz",

                    "n4ked",

                    "n4k3d",

                    "nud3",

                    "f4p",

                    "f4pping",

                    "fuk",

                    "fck",

                    "f*ck",

                    "phuck",

                    "d1ck",

                    "d!ck",

                    "p*ssy",

                    "p@ssy",

                    "p#ssy",

                    "b00bs",

                    "b00bz",

                    "t1ts",

                    "t!ts",

                    "h0rny",

                    "h0t"
                ),


                // ────────────────────────────────────────────────────────────────
                // OBFUSCATIONS
                // ────────────────────────────────────────────────────────────────

                "Obfuscations" to linkedSetOf(

                    "p.o.r.n",

                    "p o r n",

                    "p-o-r-n",

                    "p_o_r_n",

                    "p/o/r/n",

                    "s.e.x",

                    "s e x",

                    "s-e-x",

                    "s_e_x",

                    "s/e/x",

                    "n.u.d.e",

                    "n u d e",

                    "n-u-d-e",

                    "n_u_d_e",

                    "f.u.c.k",

                    "f u c k",

                    "f-u-c-k",

                    "f_u_c_k",

                    "p.u.s.s.y",

                    "p u s s y",

                    "p-u-s-s-y",

                    "d.i.c.k",

                    "d i c k",

                    "d-i-c-k",

                    "b.o.o.b.s",

                    "b o o b s",

                    "t.i.t.s",

                    "t i t s",

                    "x v i d e o s",

                    "x-videos",

                    "x videos",

                    "x_videos",

                    "x.videos",

                    "xn xx",

                    "x n x x"
                ),


                // ────────────────────────────────────────────────────────────────
                // COMMON SEARCH PHRASES
                // ────────────────────────────────────────────────────────────────

                "Search Phrases" to linkedSetOf(

                    "porn",

                    "free porn",

                    "free xxx",

                    "free sex videos",

                    "sex videos",

                    "xxx videos",

                    "adult videos",

                    "nude videos",

                    "naked videos",

                    "nude pics",

                    "naked pics",

                    "nudes",

                    "hot nudes",

                    "free nudes",

                    "send nudes",

                    "leaked nudes",

                    "celebrity nudes",

                    "private video",

                    "private videos",

                    "leaked video",

                    "leaked videos",

                    "sex tape",

                    "sex tapes",

                    "onlyfans leak",

                    "onlyfans leaks",

                    "porn leak",

                    "porn leaks",

                    "xxx leak",

                    "xxx leaks",

                    "adult content",

                    "nsfw content",

                    "nsfw videos",

                    "nsfw images",

                    "explicit videos",

                    "explicit images",

                    "explicit photos",

                    "watch porn",

                    "watch xxx",

                    "watch sex videos",

                    "porn search",

                    "porn videos online",

                    "free adult videos",

                    "free adult content"
                )
            )


        // ═══════════════════════════════════════════════════════════════════════
        // ADULT WEBSITE DOMAIN PRESET
        // ═══════════════════════════════════════════════════════════════════════

        val ADULT_DOMAINS: Set<String> = linkedSetOf(

            // ────────────────────────────────────────────────────────────────────
            // Major adult video platforms
            // ────────────────────────────────────────────────────────────────────

            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "xhamster.com",
            "redtube.com",
            "youporn.com",
            "tube8.com",
            "porntube.com",
            "eporner.com",
            "spankbang.com",
            "beeg.com",
            "tnaflix.com",
            "drtuber.com",
            "hqporner.com",
            "pornhd.com",
            "pornone.com",
            "porn300.com",
            "spankwire.com",
            "keezmovies.com",
            "sunporno.com",
            "thumbzilla.com",
            "vporn.com",
            "xhamsterlive.com",
            "porn.com",

            // ────────────────────────────────────────────────────────────────────
            // Live cams
            // ────────────────────────────────────────────────────────────────────

            "chaturbate.com",
            "stripchat.com",
            "livejasmin.com",
            "cam4.com",
            "camsoda.com",
            "myfreecams.com",
            "bongacams.com",
            "streamate.com",
            "flirt4free.com",
            "imlive.com",
            "livecam.com",
            "camcontacts.com",

            // ────────────────────────────────────────────────────────────────────
            // Adult social / dating
            // ────────────────────────────────────────────────────────────────────

            "adultfriendfinder.com",
            "ashleymadison.com",
            "fetlife.com",
            "adultspace.com",
            "swapfinder.com",
            "alt.com",
            "swingtowns.com",
            "doublelist.com",

            // ────────────────────────────────────────────────────────────────────
            // Premium / studios
            // ────────────────────────────────────────────────────────────────────

            "playboy.com",
            "hustler.com",
            "penthouse.com",
            "brazzers.com",
            "bangbros.com",
            "realitykings.com",
            "naughtyamerica.com",
            "onlyfans.com",
            "fansly.com",
            "manyvids.com",

            // ────────────────────────────────────────────────────────────────────
            // Hentai / Japanese adult
            // ────────────────────────────────────────────────────────────────────

            "nhentai.net",
            "hentaihaven.org",
            "e-hentai.org",
            "ehentai.org",
            "hitomi.la",
            "hentaimama.com",
            "hanime.tv",
            "hanime1.me",

            // ────────────────────────────────────────────────────────────────────
            // Rule34 / adult artwork
            // ────────────────────────────────────────────────────────────────────

            "rule34.xxx",
            "gelbooru.com",
            "danbooru.donmai.us",
            "e621.net",
            "e926.net",
            "tbib.org",

            // ────────────────────────────────────────────────────────────────────
            // Furry
            // ────────────────────────────────────────────────────────────────────

            "furaffinity.net",

            // ────────────────────────────────────────────────────────────────────
            // Erotic literature
            // ────────────────────────────────────────────────────────────────────

            "literotica.com",
            "asstr.org",
            "mcstories.com",

            // ────────────────────────────────────────────────────────────────────
            // Adult image / gallery sites
            // ────────────────────────────────────────────────────────────────────

            "motherless.com",
            "imagefap.com",
            "adultempire.com",
            "imagebam.com",
            "pimpandhost.com",
            "pixhost.to",
            "imgclick.net",
            "imagetwist.com",
            "imagevenue.com",
            "imagebunker.com",
            "picstate.com",
            "hotimage.com",

            // ────────────────────────────────────────────────────────────────────
            // Adult entertainment
            // ────────────────────────────────────────────────────────────────────

            "sex.com",
            "pornpics.com",
            "pornmd.com",
            "pornoxo.com",
            "pornrabbit.com",
            "pornhat.com",
            "pornkai.com",
            "pornflip.com"
        )


        // ═══════════════════════════════════════════════════════════════════════
        // MAXIMUM SAFETY STRICT MODE
        //
        // These are broad terms.
        //
        // WARNING:
        // Because the user requested maximum blocking, these may block legitimate
        // searches involving health, fashion, education, news, sports, etc.
        // ═══════════════════════════════════════════════════════════════════════

        val STRICT_MODE_KEYWORDS: Set<String> = linkedSetOf(

            // NOTE: the broad gender / people terms (woman, man, girl, ...) live
            // in TAB_RESTRICTED_KEYWORDS, not here. They are part of Strict Mode
            // but only block inside Google's Images/Videos tabs.

            // ────────────────────────────────────────────────────────────────────
            // Appearance
            // ────────────────────────────────────────────────────────────────────

            "hot",
            "sexy",
            "sexiest",
            "horny",
            "babe",
            "babes",
            "baby",
            "hottie",
            "hotties",
            "beautiful",
            "gorgeous",
            "attractive",
            "seductive",
            "seduction",
            "tempting",
            "sensual",
            "provocative",

            "thicc",
            "thick",
            "curvy",
            "curves",
            "busty",
            "voluptuous",
            "bust",
            "cleavage",

            // ────────────────────────────────────────────────────────────────────
            // Clothing
            // ────────────────────────────────────────────────────────────────────

            "bra",
            "bras",
            "bralette",
            "panty",
            "panties",
            "underwear",
            "underclothes",
            "lingerie",
            "bikini",
            "bikinis",
            "swimwear",
            "swimsuit",
            "swimsuits",
            "monokini",
            "thong",
            "thongs",
            "briefs",
            "negligee",
            "nighty",
            "nightie",
            "sleepwear",
            "bodysuit",
            "bodysuits",
            "corset",
            "corsets",
            "stockings",
            "fishnets",
            "sheer",
            "see through",
            "see-through",

            // ────────────────────────────────────────────────────────────────────
            // Fashion / modeling
            // ────────────────────────────────────────────────────────────────────

            "fashion show",
            "fashion model",
            "fashion models",
            "model photos",
            "modeling",
            "photoshoot",
            "photo shoot",
            "glamour model",
            "glamour modeling",
            "glamour photos",
            "try on",
            "try-on",
            "clothing haul",
            "fashion haul",
            "bikini haul",
            "lingerie haul",

            // ────────────────────────────────────────────────────────────────────
            // Dating / romance
            // ────────────────────────────────────────────────────────────────────

            // NOTE: bare "date"/"dates" were removed (observed on-device: a
            // clean video titled "First dates" was blocked). containsKeyword
            // only word-boundary-protects keywords <= 3 chars, so "date" also
            // matched inside "dates" — both had to go. "dating" stays (the
            // user's own log shows "Dating Tips" blocking correctly).
            "dating",
            "dating app",
            "dating apps",
            "match",
            "matches",
            "flirt",
            "flirting",
            "flirty",
            "romance",
            "romantic",
            "passion",
            "passionate",
            "intimate",
            "intimacy",
            "relationship",
            "relationships",
            "kiss",
            "kissing",
            "makeout",
            "making out",

            // ────────────────────────────────────────────────────────────────────
            // Body terms
            // ────────────────────────────────────────────────────────────────────

            "body",
            "bodies",
            "body type",
            "body types",
            "thigh",
            "thighs",
            "hips",
            "hip",
            "waist",
            "belly",
            "bellybutton",
            "navel",
            "stomach",
            "abs",
            "chest",
            "back",
            "butt",
            "butts",
            "ass",
            "asses",

            // ────────────────────────────────────────────────────────────────────
            // Sleepwear / outfits
            // ────────────────────────────────────────────────────────────────────

            "pajama",
            "pajamas",
            "pyjamas",
            "nightwear",
            "loungewear",
            "teddy",
            "costume",
            "schoolgirl",
            "schoolboy",
            "uniform",

            // ────────────────────────────────────────────────────────────────────
            // Generic adult terms
            // ────────────────────────────────────────────────────────────────────

            "adult",
            "adults",
            "mature",
            "maturity",
            "explicit",
            "nsfw",
            "xxx",
            "erotic",
            "erotica",
            "sensual",
            "nude",
            "nudes",
            "naked",
            "nudity",
            "strip",
            "stripper",
            "stripping",

            // ────────────────────────────────────────────────────────────────────
            // Generic sexual search language
            // ────────────────────────────────────────────────────────────────────

            "sex",
            "sexual",
            "sexuality",
            "sexualized",
            "sexualized content",
            "sexually explicit",
            "adult content",
            "explicit content",
            "mature content",
            "private content",
            "private photos",
            "private pictures",
            "private video",
            "private videos",
            "leaked",
            "leak",
            "leaks",
            "leaked photos",
            "leaked pictures",
            "leaked videos"
        )

        /**
         * Broad gender / people terms from [STRICT_MODE_KEYWORDS] that are
         * CONTEXT-RESTRICTED.
         *
         * KeywordMatcher keeps these active ONLY inside Google's Videos and
         * Images tabs. They are filtered out everywhere else — Google's All /
         * News / Shopping tabs, Chrome websites, and embedded in-app browsers —
         * so ordinary searches and web pages using these everyday words are not
         * blocked. YouTube keeps blocking them (video titles are matched with
         * the full strict set).
         */
        val TAB_RESTRICTED_KEYWORDS: Set<String> = linkedSetOf(

            "woman",

            "women",

            "girl",

            "girls",

            "female",

            "females",

            "lady",

            "ladies",

            "man",

            "men",

            "boy",

            "boys",

            "male",

            "males"
        )


        // ═══════════════════════════════════════════════════════════════════════
        // COMMON OBFUSCATION NORMALIZATION
        // ═══════════════════════════════════════════════════════════════════════

        val NORMALIZED_VARIANTS: Set<String> = linkedSetOf(

            "p o r n",
            "p.o.r.n",
            "p-o-r-n",
            "p_o_r_n",

            "p0rn",
            "pr0n",
            "p0rno",

            "s e x",
            "s.e.x",
            "s-e-x",
            "s_e_x",

            "s3x",
            "s3xy",

            "n u d e",
            "n.u.d.e",
            "n-u-d-e",
            "n_u_d_e",

            "nud3",
            "n00de",
            "n00ds",
            "n00dz",
            "n4ked",
            "n4k3d",

            "f*ck",
            "f**k",
            "f#ck",
            "f@ck",
            "f.u.c.k",
            "f u c k",

            "p*ssy",
            "p@ssy",
            "p#ssy",

            "d*ck",
            "d!ck",

            "b*obs",
            "b00bs",
            "b00bz",

            "t*ts",
            "t1ts",
            "t!ts",

            "h0rny",

            "x-videos",
            "x videos",
            "x_videos",
            "x.videos"
        )
    }

}