package com.muddassir.clearview.repository

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

    /**
     * Caches for the expensive derived sets (keywords + domains). The matching
     * hot path — every accessibility event and the 500ms poll — reads these
     * sets many times per scan. Without caching, each access rebuilt them from
     * scratch: flattening ~700 built-in keywords into a new set, re-reading
     * SharedPreferences, and re-normalizing every stored domain (Uri.parse +
     * regex) on every single call. The caches are invalidated only when the
     * underlying prefs actually change (Strict Mode toggle, user keyword /
     * domain edits), so the hot path reuses stable immutable sets. Volatile
     * because the service reads them from several threads.
     */
    @Volatile private var builtInKeywordsCache: Set<String>? = null
    @Volatile private var websiteKeywordsCache: Set<String>? = null
    @Volatile private var userKeywordsCache: Set<String>? = null
    @Volatile private var blockedDomainsCache: Set<String>? = null
    @Volatile private var allBlockedDomainsCache: Set<String>? = null

    /** Whether the Strict Mode keyword preset is enabled. */
    var isStrictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_STRICT_MODE, value).apply()
            // Strict Mode changes the built-in keyword sets below.
            builtInKeywordsCache = null
            websiteKeywordsCache = null
        }

    /**
     * Whether YouTube Shorts are blocked while browsing (Block tab toggle).
     * Default OFF. When enabled, any YouTube /shorts URL (in Chrome, the
     * Google app's in-app browser, ...) and the Shorts signal inside the
     * YouTube app are blocked — so users don't have to add "shorts" as a
     * keyword (which would over-block innocent words like "shortsleeves"
     * or "shortstop").
     */
    var blockShorts: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTS, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_SHORTS, value).apply()

    /**
     * Built-in protected keywords — always active, hidden from the user, not editable.
     *
     * TIER 1: clear adult keywords ([ALWAYS_BLOCK_KEYWORDS]) block in every
     * mode. TIER 2: risky-but-innocent words ([STRICT_MODE_KEYWORDS]) block
     * only while Strict Mode is on. TIER 3: context-combination terms
     * ([COMBINATION_GENERIC_TERMS] x [COMBINATION_RISKY_TERMS]) block in
     * normal mode only when BOTH a generic and a risky half appear.
     */
    val activeBuiltInKeywords: Set<String>
        get() {
            builtInKeywordsCache?.let { return it }
            val base = ALWAYS_BLOCK_KEYWORDS.toMutableSet()
            if (isStrictMode) {
                base.addAll(STRICT_MODE_KEYWORDS)
            }
            // Immutable copy: the same instance is handed to every caller and
            // cached, so a caller mutating it must never corrupt the cache.
            // Return the LOCAL, not the field — a concurrent invalidation (the
            // isStrictMode setter) could null the field between store and load.
            val result = base.toSet()
            builtInKeywordsCache = result
            return result
        }

    /**
     * Built-in keywords for websites / non-tabbed Google surfaces.
     *
     * Historically this was the full strict set MINUS the tab-restricted gender
     * terms. The audit removed gender terms from the keyword sets entirely, and
     * [TAB_RESTRICTED_KEYWORDS] is empty, so this equals [activeBuiltInKeywords].
     * Kept as a separate cached accessor for KeywordMatcher's website paths.
     */
    val activeWebsiteKeywords: Set<String>
        get() {
            websiteKeywordsCache?.let { return it }
            val set = activeBuiltInKeywords
            websiteKeywordsCache = set
            return set
        }

    /**
     * Built-in adult domain preset — always active.
     * Known adult-content domains. This list CANNOT guarantee 100% coverage
     * — new adult websites appear every day. Combine with keyword blocking
     * for stronger protection.
     */
    val activeAdultDomains: Set<String>
        get() = ADULT_DOMAINS

    /** All domains to check (user + preset). Cached; invalidated on domain edits. */
    fun getAllBlockedDomains(): Set<String> {
        allBlockedDomainsCache?.let { return it }
        val all = getBlockedDomains().toMutableSet()
        all.addAll(activeAdultDomains)
        // Immutable copy — see activeBuiltInKeywords. Return the local, not
        // the field (a concurrent domain edit could null it between store/load).
        val result = all.toSet()
        allBlockedDomainsCache = result
        return result
    }

    // ── User keywords ──────────────────────────────────────────────

    fun getUserKeywords(): Set<String> {
        userKeywordsCache?.let { return it }
        val keywords = (prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
        userKeywordsCache = keywords
        return keywords
    }

    fun addUserKeyword(keyword: String) {
        val trimmed = keyword.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return
        val current = getUserKeywords().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
        userKeywordsCache = null
    }

    fun removeUserKeyword(keyword: String) {
        val current = getUserKeywords().toMutableSet()
        current.remove(keyword.trim().lowercase(Locale.ROOT))
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
        userKeywordsCache = null
    }

    fun replaceUserKeyword(oldKeyword: String, newKeyword: String) {
        val replacement = newKeyword.trim().lowercase(Locale.ROOT)
        if (replacement.isEmpty()) return
        val current = getUserKeywords().toMutableSet()
        current.remove(oldKeyword.trim().lowercase(Locale.ROOT))
        current.add(replacement)
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, current).apply()
        userKeywordsCache = null
    }

    // ── Blocked domains / websites ─────────────────────────────────

    fun getBlockedDomains(): Set<String> {
        blockedDomainsCache?.let { return it }
        val domains = (prefs.getStringSet(KEY_BLOCKED_DOMAINS, emptySet()) ?: emptySet())
            .mapNotNull(::normalizeDomain)
            .toSet()
        blockedDomainsCache = domains
        return domains
    }

    fun addBlockedDomain(domain: String) {
        val trimmed = normalizeDomain(domain) ?: return
        val current = getBlockedDomains().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
        blockedDomainsCache = null
        allBlockedDomainsCache = null
    }

    fun removeBlockedDomain(domain: String) {
        val current = getBlockedDomains().toMutableSet()
        normalizeDomain(domain)?.let(current::remove)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
        blockedDomainsCache = null
        allBlockedDomainsCache = null
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

    // ── SharedPreferences keys ─────────────────────────────────────

    companion object {

        private const val PREFS_NAME = "url_blocker_prefs"
        private const val KEY_USER_KEYWORDS = "user_keywords"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
        private const val KEY_APP_PASSWORD = "app_password"
        private const val KEY_STRICT_MODE = "strict_mode"
        private const val KEY_BLOCK_SHORTS = "block_shorts"

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
        // TIER 1 — ALWAYS-BLOCK / CLEAR ADULT KEYWORDS
        //
        // Blocked in EVERY mode (normal + Strict) in every monitored package.
        // Only terms strongly associated with pornography, explicit sexual
        // content, nudity, sexual acts, explicit sexual anatomy, adult services /
        // sites, or unmistakable adult search intent belong here. Ordinary words
        // that merely CAN occur in adult content do not — those live in Strict
        // Mode or the combination lists below.
        // ═══════════════════════════════════════════════════════════════════════

        val ALWAYS_BLOCK_KEYWORDS: Set<String> = linkedSetOf(
            // ── Porn Sites ────────────────────────────────────
            "adult anime",
            "adult comic",
            "adult comics",
            "adult video",
            "adult videos",
            "beeg",
            "booru",
            "danbooru",
            "doujin",
            "doujinshi",
            "drtuber",
            "e621",
            "e926",
            "ecchi",
            "eporner",
            "free porn",
            "free porno",
            "free xxx",
            "gelbooru",
            "hentai",
            "hentai anime",
            "hentай",
            "hqporner",
            "lewd anime",
            "nhentai",
            "nsfw anime",
            "porn",
            "porn anime",
            "porn comic",
            "porn comics",
            "porn tube",
            "porn video",
            "porn300",
            "porn300",
            "pornhd",
            "pornhub",
            "porno",
            "pornographic",
            "pornography",
            "pornone",
            "pornpics",
            "pornstar",
            "pornstar video",
            "pornstars",
            "porntube",
            "r34",
            "redtube",
            "rule 34",
            "rule34",
            "sex tube",
            "sex video",
            "sexvideos",
            "spankbang",
            "tnaflix",
            "tube8",
            "watch porn",
            "watch xxx",
            "xhamster",
            "xnxx",
            "xvideos",
            "xxx tube",
            "xxx video",
            "xxx videos",
            "yaoi",
            "youporn",
            "yuri",
            // ── Explicit Sexual Terms ────────────────────────────────────
            "anal",
            "anal intercourse",
            "anal plug",
            "anal sex",
            "anally",
            "arsehole",
            "asshole",
            "assholes",
            "blow job",
            "blowjob",
            "blowjobs",
            "boner",
            "boob",
            "boobs",
            "butt plug",
            "buttplug",
            "clit",
            "clitoral",
            "clitoris",
            "cock",
            "cocks",
            "cum",
            "cumming",
            "cumshot",
            "cumshots",
            "deep throat",
            "deepthroat",
            "dick",
            "dicks",
            "dildo",
            "dildos",
            "dilf",
            "dilfs",
            "dirty pics",
            "dirty pictures",
            "ejaculate",
            "ejaculation",
            "erection",
            "escort service",
            "explicit photos",
            "explicit pics",
            "explicit sexual",
            "fuck",
            "fucked",
            "fucked up",
            "fucker",
            "fuckers",
            "fucking",
            "fucks",
            "hand job",
            "handjob",
            "handjobs",
            "hooker",
            "hookers",
            "horny",
            "masturbate",
            "masturbates",
            "masturbating",
            "masturbation",
            "masturbator",
            "milf",
            "milfs",
            "motherfuck",
            "motherfucker",
            "naked photos",
            "naked pics",
            "nude photos",
            "nude pics",
            "nudes",
            "oral sex",
            "orgasm",
            "orgasms",
            "orgies",
            "orgy",
            "penile",
            "penis",
            "pornstar",
            "pornstars",
            "prostitute",
            "prostitution",
            "pussies",
            "pussy",
            "rim job",
            "rimjob",
            "semen",
            "sex",
            "sex cam",
            "sex cams",
            "sex chat",
            "sex show",
            "sex tape",
            "sex tapes",
            "sex video",
            "sex videos",
            "sexcam",
            "sexting",
            "sexual",
            "sexual content",
            "sexuality",
            "sexually explicit",
            "sexy",
            "slut",
            "sluts",
            "squirting",
            "sugar baby",
            "sugar daddy",
            "sugarbaby",
            "sugardaddy",
            "tits",
            "titties",
            "titty",
            "vagina",
            "vaginal",
            "vibrator",
            "vibrators",
            "vulva",
            "whore",
            "whores",
            // ── Sexual Acts ────────────────────────────────────
            "anal sex",
            "blow job",
            "bukkake",
            "casual sex",
            "creampie",
            "cumshot",
            "cybersex",
            "deep throat",
            "double penetration",
            "fingering",
            "foot job",
            "footjob",
            "foursome",
            "gang bang",
            "gangbang",
            "group sex",
            "hand job",
            "have sex",
            "having sex",
            "intercourse",
            "jack off",
            "jacking off",
            "jerk off",
            "jerking off",
            "making love",
            "masturbation",
            "money shot",
            "mutual masturbation",
            "oral sex",
            "orgies",
            "orgy",
            "phone sex",
            "phone sex",
            "rim job",
            "rough sex",
            "safe sex",
            "sex position",
            "sex positions",
            "sex positions",
            "sexual intercourse",
            "threesome",
            "threesomes",
            "tit fuck",
            "titfuck",
            "unprotected sex",
            "virtual sex",
            "webcam sex",
            // ── Nudity ────────────────────────────────────
            "bare body",
            "bare breasts",
            "bare chest",
            "bottomless",
            "erotic",
            "erotica",
            "explicit image",
            "explicit images",
            "explicit photo",
            "explicit photos",
            "full nude",
            "full nudity",
            "fully nude",
            "naked",
            "naked photo",
            "naked photos",
            "naked picture",
            "naked pictures",
            "nude",
            "nude photo",
            "nude photos",
            "nude picture",
            "nude pictures",
            "nudes",
            "nudist",
            "nudists",
            "nudity",
            "pole dancer",
            "pole dancing",
            "strip club",
            "strip clubs",
            "strip tease",
            "stripclub",
            "stripclubs",
            "stripper",
            "strippers",
            "striptease",
            "topless",
            // ── NSFW / Adult ────────────────────────────────────
            "18 plus",
            "18+",
            "18plus",
            "21+",
            "adult content",
            "adult entertainment",
            "adult material",
            "adult media",
            "adult only",
            "adult site",
            "adult website",
            "adult websites",
            "beastiality",
            "bestiality",
            "explicit content",
            "graphic sexual",
            "guro",
            "incest",
            "mature content",
            "not safe for life",
            "not safe for work",
            "nsfl",
            "nsfw",
            "obscene content",
            "pornographic content",
            "sexually explicit",
            "xxx",
            "xxxx",
            "zoophilia",
            // ── Adult Social / Dating ────────────────────────────────────
            "adult dating",
            "adult friend finder",
            "adultfriendfinder",
            "ashley madison",
            "ashleymadison",
            "bbw",
            "big beautiful woman",
            "casual dating",
            "casual hookup",
            "chatroulette",
            "cuck",
            "cuckold",
            "cuckolding",
            "fetlife",
            "find a hookup",
            "find sex",
            "friends with benefits",
            "friends-with-benefits",
            "fwb",
            "gang bang",
            "gangbang",
            "hook up",
            "hook ups",
            "hookup",
            "hookups",
            "meet singles",
            "omegle",
            "one night stand",
            "one-night stand",
            "sex dating",
            "sex partner",
            "sex partners",
            "swinger",
            "swingers",
            "threesome",
            "threesomes",
            // ── Cam / Webcam ────────────────────────────────────
            "adult cam",
            "adult cams",
            "bongacams",
            "cam boy",
            "cam girl",
            "cam girls",
            "cam show",
            "cam shows",
            "cam4",
            "camboy",
            "camboys",
            "camgirl",
            "camgirls",
            "camsoda",
            "chaturbate",
            "livejasmin",
            "myfreecams",
            "private cam",
            "private show",
            "sex cam",
            "sex cams",
            "streamate",
            "strip cam",
            "strip cams",
            "stripchat",
            // ── Fetish / BDSM ────────────────────────────────────
            "bdsm",
            "bondage",
            "chastity cage",
            "dom sub",
            "dom/sub",
            "dominatrix",
            "exhibitionism",
            "feet fetish",
            "fetish",
            "fetishes",
            "foot fetish",
            "footfetish",
            "footjob",
            "latex fetish",
            "leather fetish",
            "mistress",
            "public nudity",
            "sexual roleplay",
            "vore",
            "voyeur",
            "voyeurism",
            "yiff",
            // ── Sexual Slang ────────────────────────────────────
            "adult pics",
            "adult videos",
            "child porn",
            "cp",
            "dirty chat",
            "dirty pics",
            "dirty talk",
            "dirty video",
            "fap",
            "fapping",
            "horny",
            "hot pics",
            "hot videos",
            "jack off",
            "jerk off",
            "lewd",
            "lewds",
            "lewdz",
            "loli",
            "lolicon",
            "noods",
            "noodz",
            "nsfwart",
            "nudes",
            "p0rn",
            "pedo",
            "pedophile",
            "pedophilia",
            "pr0n",
            "sexy photos",
            "sexy pics",
            "sexy video",
            "shotacon",
            "smut",
            "smutty",
            "thirst trap",
            "thirsttrap",
            "turn me on",
            "wank",
            "wanking",
            // ── Adult Entertainment ────────────────────────────────────
            "adult star",
            "adult stars",
            "bangbros",
            "brazzers",
            "brothel",
            "escort service",
            "fansly",
            "hustler",
            "manyvids",
            "naughtyamerica",
            "onlyfans",
            "penthouse",
            "playboy",
            "porn stars",
            "pornstar",
            "prostitute",
            "prostitution",
            "realitykings",
            "red light district",
            "sex worker",
            "sex workers",
            // ── Hentai / Anime Adult ────────────────────────────────────
            "adult anime",
            "doujin",
            "doujinshi",
            "e-hentai",
            "ecchi",
            "ehentai",
            "hentai",
            "hentai doujin",
            "hentai manga",
            "hentai manhwa",
            "hentaihaven",
            "hentaі",
            "hentай",
            "lewd anime",
            "lewd manga",
            "loli",
            "lolicon",
            "nhentai",
            "nsfw anime",
            "r34",
            "rule 34",
            "rule34",
            "shota",
            "shotacon",
            "yaoi",
            "yuri",
            // ── Adult Content Codes ────────────────────────────────────
            "18+",
            "18plus",
            "adult only",
            "explicit only",
            "mature only",
            "nsfl",
            "nsfw",
            "r-18",
            "r18",
            "x rated",
            "x-rated",
            "xxx",
            "xxx rated",
            "🔞",
            // ── Misspellings ────────────────────────────────────
            "a55",
            "asss",
            "azz",
            "boobes",
            "boobies",
            "boobz",
            "d1ck",
            "dic",
            "dik",
            "dikk",
            "esc0rt",
            "esc0rts",
            "escourt",
            "fck",
            "fuckingg",
            "fuk",
            "fukc",
            "fuking",
            "fuq",
            "hentay",
            "henti",
            "hentia",
            "m1lf",
            "milff",
            "milfs",
            "n00de",
            "n00ds",
            "n00dz",
            "n4ked",
            "nak3d",
            "nud3",
            "nued",
            "p0rn",
            "p0rno",
            "phuck",
            "porhn",
            "porhnub",
            "pornhubb",
            "pornhubbb",
            "pornhubz",
            "pornhup",
            "poron",
            "pr0n",
            "pron",
            "prono",
            "pussi",
            "pussy",
            "pussyy",
            "pusy",
            "s3x",
            "secks",
            "sekz",
            "sexx",
            "sxe",
            "tittiez",
            "tittz",
            "titz",
            "xnx",
            "xnxx",
            "xnxxx",
            "xvedio",
            "xvedios",
            "xvideo",
            "xvidio",
            "xvidios",
            "xvidoes",
            // ── Leetspeak ────────────────────────────────────
            "b00bs",
            "b00bz",
            "d!ck",
            "d1ck",
            "f*ck",
            "f4p",
            "f4pping",
            "fck",
            "fuk",
            "h0rny",
            "h0t",
            "n00de",
            "n00des",
            "n00ds",
            "n00dz",
            "n4k3d",
            "n4ked",
            "nud3",
            "p#ssy",
            "p*ssy",
            "p0rn",
            "p0rno",
            "p@ssy",
            "phuck",
            "pr0n",
            "s3x",
            "s3xual",
            "s3xy",
            "t!ts",
            "t1ts",
            // ── Obfuscations ────────────────────────────────────
            "b o o b s",
            "b.o.o.b.s",
            "d i c k",
            "d-i-c-k",
            "d.i.c.k",
            "f u c k",
            "f-u-c-k",
            "f.u.c.k",
            "f_u_c_k",
            "n u d e",
            "n-u-d-e",
            "n.u.d.e",
            "n_u_d_e",
            "p o r n",
            "p u s s y",
            "p-o-r-n",
            "p-u-s-s-y",
            "p.o.r.n",
            "p.u.s.s.y",
            "p/o/r/n",
            "p_o_r_n",
            "s e x",
            "s-e-x",
            "s.e.x",
            "s/e/x",
            "s_e_x",
            "t i t s",
            "t.i.t.s",
            "x n x x",
            "x v i d e o s",
            "x videos",
            "x-videos",
            "x.videos",
            "x_videos",
            "xn xx",
            // ── Search Phrases ────────────────────────────────────
            "adult content",
            "adult videos",
            "celebrity nudes",
            "explicit images",
            "explicit photos",
            "explicit videos",
            "free adult content",
            "free adult videos",
            "free nudes",
            "free porn",
            "free sex videos",
            "free xxx",
            "hot nudes",
            "leaked nudes",
            "leaked video",
            "leaked videos",
            "naked pics",
            "naked videos",
            "nsfw content",
            "nsfw images",
            "nsfw videos",
            "nude pics",
            "nude videos",
            "nudes",
            "onlyfans leak",
            "onlyfans leaks",
            "porn",
            "porn leak",
            "porn leaks",
            "porn search",
            "porn videos online",
            "private video",
            "private videos",
            "send nudes",
            "sex tape",
            "sex tapes",
            "sex videos",
            "watch porn",
            "watch sex videos",
            "watch xxx",
            "xxx leak",
            "xxx leaks",
            "xxx videos",
            // ── New additions (audit) ─────────────────────
            "adult game",
            "adult games",
            "adult stories",
            "adult streaming",
            "ahegao",
            "ai porn",
            "barely legal",
            "boob pic",
            "boob pics",
            "camming",
            "cunnilingus",
            "deepfake porn",
            "dick pic",
            "dick pics",
            "discord nsfw",
            "erotic stories",
            "fisting",
            "free onlyfans",
            "futanari",
            "hentai game",
            "hentai games",
            "minor porn",
            "naked leak",
            "naked leaks",
            "naked selfie",
            "naked selfies",
            "naked teen",
            "naked webcam",
            "nsfw discord",
            "nsfw reddit",
            "nsfw telegram",
            "nude leak",
            "nude leaks",
            "nude selfie",
            "nude selfies",
            "nude teen",
            "nude webcam",
            "onlyfans nude",
            "onlyfans nudes",
            "oppai",
            "paizuri",
            "pegging",
            "porn game",
            "porn games",
            "porn site",
            "porn sites",
            "porn streaming",
            "reddit nsfw",
            "rimming",
            "sex doll",
            "sex game",
            "sex games",
            "sex site",
            "sex sites",
            "sex stories",
            "sexdoll",
            "shemale",
            "snuff film",
            "snuff films",
            "teen nude",
            "teen nudes",
            "teen porn",
            "teen sex",
            "teens porn",
            "telegram nsfw",
            "tit pic",
            "tit pics",
            "underage porn",
            "webcam nude",

            // ── Promoted from STRICT_MODE (independently adult) ──
            "ass",
            "asses",
            "escort",
            "escorts",
            "nipple",
            "nipples",
            "sexiest",
            "sexualized",
            "sexualized content",
            "stripping",

            // ── Promoted (2nd audit pass) — explicit alone ──
            "busty",
            "cream pie",
            "erect",
            "femboy",
            "kinky",
            "leaked photos",
            "leaked pictures",
            "non consensual",
            "non-con",
            "non-consensual",
            "noncon",
            "squirt",
            "thicc",
            "voluptuous",

            // ── User core anatomy terms (always on) ──
            "breast",
            "breasts",
            "butt",
            "buttocks",
            "butts",
            "genital",
            "genitals",
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
        val STRICT_MODE_KEYWORDS: Set<String> = linkedSetOf(
            // Innocent-alone, risky-when-searched discovery terms. Active only
            // while Strict Mode is on. Independently adult words live in
            // ALWAYS_BLOCK_KEYWORDS; gender/people terms live in the
            // combination generic halves (never blocked standalone).
            // ── Common sexualized / revealing-content discovery ──
            "beach",
            "bikini",
            "bikinis",
            "swimsuit",
            "swimwear",
            "swimming",
            "lingerie",
            "panty",
            "panties",
            "bra",
            "bras",
            "bralette",
            "cleavage",
            "seductive",
            "seduction",
            "sensual",
            "provocative",
            "revealing",
            "skimpy",
            "hottie",
            "hotties",
            "thong",
            "thongs",
            "see through",
            "see-through",
            "breastfeeding",
            "breast feeding",
        )

        // ═══════════════════════════════════════════════════════════════════════
        // TIER 3 — CONTEXT-COMBINATION TERMS
        //
        // For searches that are innocent alone but clearly heading toward
        // adult/sexualized content when combined: "woman + bikini",
        // "girl + swimsuit", "beach + bikini", "pool + pics". A text blocks
        // via this tier when it contains BOTH a generic discovery half AND a
        // risky half (active in normal mode; in Strict Mode the individual
        // terms already block). See KeywordMatcher.checkCombinationTerms().
        // ═══════════════════════════════════════════════════════════════════════

        /** Generic discovery halves: innocent alone, sexualized in combination. */
        val COMBINATION_GENERIC_TERMS: Set<String> = linkedSetOf(
            "beach",
            "female",
            "females",
            "girl",
            "girls",
            "ladies",
            "lady",
            "pool",
            "poolside",
            "woman",
            "women",
        )

        /** Risky halves: the term that sexualizes a generic discovery half. */
        val COMBINATION_RISKY_TERMS: Set<String> = linkedSetOf(
            "beachwear",
            "bikini",
            "bikinis",
            "bra",
            "bralette",
            "bras",
            "busty",
            "cleavage",
            "curves",
            "curvy",
            "glamour",
            "images",
            "lingerie",
            "panties",
            "panty",
            "photos",
            "photoshoot",
            "pics",
            "pictures",
            "provocative",
            "revealing",
            "seductive",
            "see through",
            "see-through",
            "skimpy",
            "swimsuit",
            "swimsuits",
            "swimwear",
            "thong",
            "thongs",
            "underwear",
            "voluptuous",
            "wallpaper",
        )

        /**
         * DEPRECATED — kept empty so older subtraction/reference code keeps
         * compiling. The gender terms it used to hold (woman, girl, ...) were
         * removed from standalone blocking by the audit; they now only
         * participate as context-combination halves
         * ([COMBINATION_GENERIC_TERMS] x [COMBINATION_RISKY_TERMS]).
         */
        val TAB_RESTRICTED_KEYWORDS: Set<String> = emptySet()
    }

}