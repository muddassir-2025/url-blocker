package com.muddassir.clearview.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the two-list keyword policy:
 *  - [BlockRepository.ALWAYS_BLOCK_KEYWORDS]: clearly adult terms that block
 *    in EVERY mode (normal + Strict). Only unmistakably adult/sexual terms.
 *  - [BlockRepository.STRICT_MODE_KEYWORDS]: a small, curated set of innocent-
 *    but-risky discovery terms that block ONLY while Strict Mode is on.
 *  - TIER 3 safety net: context-combination halves
 *    ([BlockRepository.COMBINATION_GENERIC_TERMS] x
 *    [BlockRepository.COMBINATION_RISKY_TERMS]) catch paired discovery
 *    searches (woman + bikini) in normal mode.
 *  - Ambiguous everyday words (body, model, oral, master, mature, ...) are
 *    not blocked anywhere.
 */
class BlockRepositoryKeywordsTest {

    private val alwaysOn: Set<String> = BlockRepository.ALWAYS_BLOCK_KEYWORDS
    private val strictMode: Set<String> = BlockRepository.STRICT_MODE_KEYWORDS
    private val comboGeneric: Set<String> = BlockRepository.COMBINATION_GENERIC_TERMS
    private val comboRisky: Set<String> = BlockRepository.COMBINATION_RISKY_TERMS

    // ── ALWAYS: clearly adult terms ────────────────────────────────

    @Test
    fun `clear adult words are always-on`() {
        val clear = listOf(
            // User's core always-block list.
            "porn", "porno", "pornography", "sex", "sexual", "sexy",
            "nude", "naked", "nudity", "boob", "boobs", "breast", "breasts",
            "nipple", "nipples", "vagina", "vaginal", "penis", "penile",
            "anal", "ass", "butt", "buttocks", "butts", "genitals", "genital",
            "orgasm", "masturbation", "masturbate", "erect", "erection",
            "hentai", "xxx", "nsfw",
            // Clearly-adult extras kept from the previous sheet.
            "pornhub", "xvideos", "blowjob", "nudes", "onlyfans", "sex tape",
            "dildo", "vibrator", "camgirl", "cum", "cock", "dick", "pussy",
            "bestiality", "child porn", "pedo", "erotic", "erotica",
            "escort", "escorts", "stripping", "stripper", "horny",
            "masturbating", "busty", "thicc", "cream pie", "squirt", "kinky",
            "femboy", "non consensual", "non-con", "non-consensual", "noncon",
            "leaked photos", "leaked pictures", "sexiest", "sexualized",
            "sexualized content", "adult content", "explicit content",
            "mature content", "shemale", "futanari", "ahegao", "teen porn",
            "underage porn", "barely legal", "sex doll", "dick pic",
            "camming", "deepfake porn"
        )
        clear.forEach { assertTrue("'$it' must stay always-on", it in alwaysOn) }
    }

    @Test
    fun `ambiguous and female-discovery words are not always-on`() {
        val notAlways = listOf(
            // Moved to Strict Mode (discovery terms).
            "beach", "bikini", "bikinis", "swimsuit", "swimwear", "swimming",
            "lingerie", "panty", "panties", "bra", "bras", "bralette",
            "cleavage", "seductive", "seduction", "sensual", "provocative",
            "revealing", "skimpy", "hottie", "hotties", "thong", "thongs",
            "see through", "see-through", "breastfeeding", "breast feeding",
            // Ambiguous everyday words — not blocked anywhere.
            "body", "model", "models", "modeling", "glamour", "photoshoot",
            "webcam", "webcams", "oral", "facial", "master", "slave",
            "collar", "leash", "gag", "spanking", "whipping", "flogging",
            "chastity", "kink", "roleplay", "role play", "humiliation",
            "submission", "submissive", "dominance", "dominant", "domination",
            "mature", "explicit", "adult", "furry", "grindr", "swinging",
            "burlesque", "schoolboy", "schoolgirl", "babe", "babes",
            "leaked", "private photos", "private pictures", "private content",
            "uncensored", "dp", "gore", "snuff", "shaft", "strip",
            // Gender/people terms — combination halves only.
            "woman", "women", "girl", "girls", "female", "females",
            "lady", "ladies",
            // Dating apps / fashion / sleepwear / body parts — removed.
            "tinder", "bumble", "hinge", "fashion show", "pajama",
            "chest", "hip", "waist", "navel", "abs", "thigh", "thighs",
            "buttock", "curves", "curvy"
        )
        notAlways.forEach { assertFalse("'$it' must NOT be always-on", it in alwaysOn) }
    }

    // ── STRICT: the curated 27 discovery terms ─────────────────────

    @Test
    fun `strict mode covers exactly the curated discovery list`() {
        val strict = listOf(
            "beach", "bikini", "bikinis", "swimsuit", "swimwear", "swimming",
            "lingerie", "panty", "panties", "bra", "bras", "bralette",
            "cleavage", "seductive", "seduction", "sensual", "provocative",
            "revealing", "skimpy", "hottie", "hotties", "thong", "thongs",
            "see through", "see-through", "breastfeeding", "breast feeding"
        )
        strict.forEach { assertTrue("'$it' must be in Strict Mode", it in strictMode) }
        // Nothing else may be in Strict Mode — the list is intentionally small.
        assertTrue("Strict list must contain exactly 27 curated terms", strictMode.size == 27)
    }

    @Test
    fun `ambiguous everyday words are not in strict mode`() {
        val notStrict = listOf(
            "body", "model", "models", "modeling", "glamour", "photoshoot",
            "photo shoot", "webcam", "webcams", "web cam", "web cams",
            "live cam", "live cams", "live webcam", "live webcams",
            "oral", "facial", "master", "slave", "collar", "leash", "gag",
            "spanking", "whipping", "flogging", "chastity", "kink",
            "roleplay", "role play", "humiliation", "submission",
            "submissive", "dominance", "dominant", "domination",
            "mature", "explicit", "adult", "furry", "grindr", "swinging",
            "burlesque", "schoolboy", "schoolgirl", "babe", "babes",
            "leaked", "private photos", "private pictures", "private content",
            "uncensored", "dp", "gore", "snuff", "shaft", "strip",
            "underwear", "corset", "fishnets", "stockings", "bodysuit",
            "negligee", "nightie", "nighty", "teddy", "sheer", "underclothes",
            "monokini", "beachwear", "bikini haul", "lingerie haul",
            "glamour model", "glamour modeling", "model photos",
            "restricted content", "age restricted", "age-restricted",
            // Gender/people terms — combination halves only.
            "woman", "women", "girl", "girls", "female", "females",
            "lady", "ladies"
        )
        notStrict.forEach { assertFalse("'$it' must NOT be in Strict Mode", it in strictMode) }
    }

    @Test
    fun `independently adult terms are not in strict mode`() {
        val promoted = listOf(
            // Now always-on (or already always-on); never toggle-gated.
            "sex", "sexy", "sexual", "porn", "porno", "pornography",
            "nude", "naked", "nudity",            "nipple", "nipples",
            "breast", "breasts", "butt", "buttocks", "butts", "genitals", "genital",
            "ass", "asses", "escort", "escorts", "horny", "erotic",
            "erotica", "stripper", "stripping", "busty", "thicc",
            "cream pie", "erect", "squirt", "kinky", "femboy",
            "non consensual", "non-con", "non-consensual", "noncon",
            "leaked photos", "leaked pictures", "sexiest", "sexualized",
            "sexualized content", "adult content", "explicit content",
            "mature content", "blowjob", "nudes", "pornhub", "onlyfans"
        )
        promoted.forEach {
            assertTrue("'$it' must be always-on", it in alwaysOn)
            assertFalse("'$it' must not be in Strict Mode", it in strictMode)
        }
    }

    // ── No overlap between the two lists ────────────────────────────

    @Test
    fun `always-block and strict-mode lists are disjoint`() {
        val overlap = alwaysOn intersect strictMode
        assertTrue("No term may be in both lists: $overlap", overlap.isEmpty())
    }

    // ── TIER 3: combination halves (safety net) ────────────────────

    @Test
    fun `combination halves are wired correctly`() {
        // Generic discovery halves (female gender terms live ONLY here).
        listOf("woman", "women", "girl", "girls", "female", "females",
            "lady", "ladies", "beach", "pool", "poolside").forEach {
            assertTrue("'$it' must be a generic combination half", it in comboGeneric)
        }
        // Female gender terms must NOT be standalone strict keywords.
        listOf("woman", "women", "girl", "girls", "female", "females",
            "lady", "ladies").forEach {
            assertFalse("'$it' must not be in strict mode (context-combination only)", it in strictMode)
        }
        // Risky halves.
        listOf("bikini", "bikinis", "swimsuit", "swimsuits", "swimwear", "beachwear",
            "lingerie", "underwear", "panties", "thong", "bra", "cleavage",
            "skimpy", "revealing", "pics", "pictures", "photos", "images",
            "wallpaper").forEach {
            assertTrue("'$it' must be a risky combination half", it in comboRisky)
        }
        // Always-block words must not be duplicated as risky halves.
        assertFalse(comboRisky.contains("nude"))
        assertFalse(comboRisky.contains("sexy"))
        assertFalse(comboRisky.contains("topless"))
    }

    // ── Fully innocent / over-broad words: not blocked anywhere ────

    @Test
    fun `fully innocent and over-broad words are not blocked anywhere`() {
        // NOTE: "photos"/"pics"/"pictures" are risky COMBINATION halves by
        // design (search-intent) — they only block together with a generic
        // half, never alone.
        listOf("back", "hair", "water", "school", "love", "health", "education",
            "family", "fashion", "clothing", "sport", "fitness", "anatomy",
            "relationship", "costume", "matches", "videos", "button",
            "brass", "analysis", "dominance", "master", "oral", "facial",
            "mature", "model").forEach {
            assertFalse("'$it' must not be always-on", it in alwaysOn)
            assertFalse("'$it' must not be in strict mode", it in strictMode)
            assertFalse("'$it' must not be a generic combo half", it in comboGeneric)
            assertFalse("'$it' must not be a risky combo half", it in comboRisky)
        }
    }
}
