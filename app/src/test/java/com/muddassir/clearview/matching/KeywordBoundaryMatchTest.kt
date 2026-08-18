package com.muddassir.clearview.matching

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Locks in the whole-word boundary matching used for every keyword lookup
 * (built-in lists, user keywords, and the TIER-3 combination halves).
 *
 * Plain-word keywords (letters + spaces, all tokens real words) match ONLY as
 * whole words — never glued to a neighboring letter/digit. Punctuation-embedded
 * obfuscation / leetspeak forms (digits, dots, underscores, ...) keep substring
 * matching so they cannot break.
 */
class KeywordBoundaryMatchTest {

    /** Mirror of the production call: lowercase text, boundary verdict from the keyword itself. */
    private fun match(text: String, keyword: String): Boolean =
        KeywordMatcher.containsKeyword(
            text.lowercase(Locale.ROOT),
            keyword,
            KeywordMatcher.isPlainWordKeyword(keyword)
        )

    // ── False positives: keyword glued inside an innocent word ────

    @Test
    fun `short keywords never match inside innocent words`() {
        assertFalse(match("cocktail party", "cock"))
        assertFalse(match("cockpit", "cock"))
        assertFalse(match("peacock", "cock"))
        assertFalse(match("shuttlecock", "cock"))

        assertFalse(match("assignment", "ass"))
        assertFalse(match("assist", "ass"))
        assertFalse(match("assess", "ass"))
        assertFalse(match("class", "ass"))
        assertFalse(match("glass", "ass"))
        assertFalse(match("pass", "ass"))
        assertFalse(match("grass", "ass"))

        assertFalse(match("analysis", "anal"))
        assertFalse(match("analyst", "anal"))
        assertFalse(match("analogy", "anal"))
        assertFalse(match("analog", "anal"))

        assertFalse(match("predict", "dic"))
        assertFalse(match("indicate", "dic"))
        assertFalse(match("medic", "dic"))
        assertFalse(match("judicial", "dic"))

        assertFalse(match("library", "bra"))
        assertFalse(match("celebration", "bra"))
        assertFalse(match("zebra", "bra"))
        assertFalse(match("algebra", "bra"))
        assertFalse(match("vibrant", "bra"))
        assertFalse(match("embrace", "bra"))

        assertFalse(match("management", "man"))
        assertFalse(match("mention", "men"))
        assertFalse(match("German", "man"))
        assertFalse(match("document", "men"))
        assertFalse(match("comment", "men"))

        assertFalse(match("hotel", "hot"))
        assertFalse(match("hotdog", "hot"))
        assertFalse(match("Hotmail", "hot"))

        assertFalse(match("erector set", "erect"))

        assertFalse(match("Middlesex", "sex"))
        assertFalse(match("sextant", "sex"))
        assertFalse(match("sextuplet", "sex"))

        assertFalse(match("sexy", "sex"))
        assertFalse(match("button", "butt"))
        assertFalse(match("brass", "bra"))
        assertFalse(match("hottest", "hot"))
        assertFalse(match("breastfeeding", "breast"))
        assertFalse(match("breaststroke", "breast"))
        assertFalse(match("transmission", "trans"))
        assertFalse(match("transport", "trans"))
    }

    // ── True positives: the same keywords as standalone words ─────

    @Test
    fun `keywords still match as standalone words`() {
        assertTrue(match("cock", "cock"))
        assertTrue(match("watch cock videos", "cock"))
        assertTrue(match("cock now", "cock"))
        assertTrue(match("cock.", "cock"))
        assertTrue(match("cock!", "cock"))
        assertTrue(match("cock,", "cock"))
        assertTrue(match("\ncock\n", "cock"))

        assertTrue(match("nice ass", "ass"))
        assertTrue(match("ass", "ass"))
        assertTrue(match("ass is", "ass"))
        assertTrue(match("an ass.", "ass"))

        assertTrue(match("anal sex", "anal"))
        assertTrue(match("anal.", "anal"))

        assertTrue(match("erect", "erect"))
        assertTrue(match("erect penis", "erect"))
        assertTrue(match("an erect.", "erect"))

        assertTrue(match("have sex", "sex"))
        assertTrue(match("sex.", "sex"))
        assertTrue(match("sex, drugs", "sex"))

        // At string start and end.
        assertTrue(match("cock", "cock"))
        assertTrue(match("porn", "porn"))
        assertTrue(match("nude", "nude"))
    }

    // ── Multi-word phrases ─────────────────────────────────────────

    @Test
    fun `multi-word phrases match only as contiguous whole words`() {
        // True positives.
        assertTrue(match("sex tape", "sex tape"))
        assertTrue(match("watch sex tape now", "sex tape"))
        assertTrue(match("sex tape.", "sex tape"))
        assertTrue(match("hand job", "hand job"))
        assertTrue(match("he needs a hand job", "hand job"))
        assertTrue(match("blow job", "blow job"))
        assertTrue(match("anal sex", "anal sex"))
        assertTrue(match("teen porn", "teen porn"))
        assertTrue(match("see through dress", "see through"))

        // False positives: phrase glued to a neighboring word (never a
        // substring across word boundaries).
        assertFalse(match("sex tapeworm", "sex tape"))
        assertFalse(match("sex tapes", "sex tape"))
        assertFalse(match("xsex tape", "sex tape"))
        assertFalse(match("sex tapex", "sex tape"))
        assertFalse(match("hand jobber", "hand job"))
        assertFalse(match("blow jobber", "blow job"))
        assertFalse(match("anal sexy", "anal sex"))
        // Phrase split across unrelated words is NOT a match either.
        assertFalse(match("sex and tape", "sex tape"))
    }

    // ── Obfuscation / leetspeak / misspelling forms keep substring matching ──

    @Test
    fun `obfuscation and leetspeak forms are preserved`() {
        // Not plain words → substring matching.
        assertFalse(KeywordMatcher.isPlainWordKeyword("p.o.r.n"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("f_u_c_k"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("p0rn"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("s3x"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("f*ck"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("18+"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("n u d e"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("x n x x"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("a55"))

        // Still matched as substrings.
        assertTrue(match("watch p.o.r.n", "p.o.r.n"))
        assertTrue(match("p.o.r.n", "p.o.r.n"))
        assertTrue(match("f_u_c_k", "f_u_c_k"))
        assertTrue(match("p0rnhub", "p0rn"))          // inflected leet domain keeps matching
        assertTrue(match("f*cked", "f*ck"))           // inflected form keeps matching
        assertTrue(match("s3xual", "s3x"))
        assertTrue(match("n u d e", "n u d e"))
        assertTrue(match("x n x x video", "x n x x"))
        assertTrue(match("a55", "a55"))
    }

    // ── Plain-word classification ─────────────────────────────────

    @Test
    fun `plain word classification`() {
        assertTrue(KeywordMatcher.isPlainWordKeyword("sex"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("cock"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("erect"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("pornographic"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("sex tape"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("red light district"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("see through"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("breastfeeding"))
        assertTrue(KeywordMatcher.isPlainWordKeyword("hentай")) // Unicode letters
        assertFalse(KeywordMatcher.isPlainWordKeyword("see-through"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("e-hentai"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("x-rated"))
        assertFalse(KeywordMatcher.isPlainWordKeyword("🔞"))
    }

    // ── Unicode boundaries ────────────────────────────────────────

    @Test
    fun `unicode letters count as word characters on both sides`() {
        // A Cyrillic letter glued to the keyword is still a word boundary.
        assertFalse(match("cockаб", "cock"))
        assertFalse(match("абcock", "cock"))
        // A standalone keyword next to Cyrillic text still matches.
        assertTrue(match("аб cock бв", "cock"))
    }
}
