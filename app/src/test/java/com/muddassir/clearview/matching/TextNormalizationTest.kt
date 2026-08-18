package com.muddassir.clearview.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the text-normalization layer ([KeywordMatcher.normalizeForMatching])
 * that runs BEFORE every keyword comparison: Unicode NFKC + homoglyph folding
 * (Cyrillic/Greek → Latin) + leetspeak substitution + single-letter separator
 * collapsing. Disguised spellings are caught generically instead of by
 * hardcoded evasion entries, while ordinary text passes through unchanged.
 */
class TextNormalizationTest {

    private fun norm(text: String): String = KeywordMatcher.normalizeForMatching(text)

    /**
     * Mirrors the production path: both the scanned text AND the keyword are
     * normalized, then the whole-word boundary check applies to the normalized
     * forms (exactly what checkString / the keyword cache do).
     */
    private fun variantMatchesKeyword(text: String, keyword: String): Boolean {
        val normText = KeywordMatcher.normalizeForMatching(text)
        val normKw = KeywordMatcher.normalizeForMatching(keyword)
        return KeywordMatcher.containsKeyword(
            normText,
            normKw,
            KeywordMatcher.isPlainWordKeyword(normKw)
        )
    }

    // ── Catches variants NOT in the hardcoded list ─────────────────

    @Test
    fun `cyrillic homoglyphs are caught`() {
        assertEquals("porn", norm("pоrn"))            // Cyrillic о — NOT in the list
        assertEquals("porn", norm("рогn"))            // Cyrillic р + о
        assertEquals("porn", norm("РОRN"))            // Cyrillic Р + О, uppercased input
        assertEquals("hentai", norm("hentaі"))        // Cyrillic і
        assertEquals("hentai", norm("hеntai"))        // Cyrillic е
        assertTrue(variantMatchesKeyword("watch pоrn videos", "porn"))
        assertTrue(variantMatchesKeyword("hеntai site", "hentai"))
    }

    @Test
    fun `leetspeak substitution catches variants`() {
        assertEquals("porn", norm("p0rn"))
        assertEquals("sex", norm("s3x"))
        assertEquals("sexual", norm("s3xuаl"))        // leet + Cyrillic а — not in the list
        assertEquals("vibrator", norm("v1brator"))
        assertEquals("nude", norm("n_u_d_3"))         // not in the list (list has n_u_d_e / nud3)
        assertEquals("hot", norm("h0t"))
        assertEquals("ass", norm("@ss"))
        assertEquals("boobs", norm("b00bs"))
        assertEquals("sexy", norm("5exy"))
        assertTrue(variantMatchesKeyword("watch v1brator videos", "vibrator"))
        assertTrue(variantMatchesKeyword("n_u_d_3", "nude"))
    }

    @Test
    fun `separator obfuscations are caught`() {
        assertEquals("porn", norm("p.o.r.n"))
        assertEquals("porn", norm("p o r n"))
        assertEquals("porn", norm("p_o_r_n"))
        assertEquals("porn", norm("p-o-r-n"))
        assertEquals("porn", norm("p/o/r/n"))
        assertEquals("porn", norm("p..o..r..n"))
        assertEquals("nude", norm("n u d e"))
        assertEquals("xvideos", norm("x v i d e o s"))
        assertEquals("porn site", norm("p o r n site"))
        assertTrue(variantMatchesKeyword("go to p.o.r.n", "porn"))
        assertTrue(variantMatchesKeyword("n u d e photos", "nude"))
    }

    @Test
    fun `fullwidth and mixed variants are caught`() {
        assertEquals("porn", norm("ＰＯＲＮ"))           // fullwidth
        assertEquals("porn", norm("ｐｏｒｎ"))           // fullwidth lowercase
        assertEquals("porn", norm("pоrn"))            // mixed Latin + Cyrillic
    }

    // ── Normal output for the user's example variants ─────────────

    @Test
    fun `user example variants normalize as specified`() {
        // "!" maps to "i" per the leetspeak spec, so p!rn → pirn (a different
        // letter than "o" — flagged in the report; it only matches if a
        // keyword normalizes to "pirn").
        assertEquals("pirn", norm("p!rn"))
        // "s3xxx" → "sexxx" (no repeated-character collapsing — judgment call,
        // left out by default; see report).
        assertEquals("sexxx", norm("s3xxx"))
        assertEquals("nude", norm("n_u_d_3"))
    }

    // ── Legitimate text is NOT mangled ────────────────────────────

    @Test
    fun `legitimate text passes through unchanged`() {
        assertEquals("the score was 100 to 0", norm("the score was 100 to 0"))
        assertEquals("i need a 4x4 vehicle", norm("I need a 4x4 vehicle"))
        assertEquals("class of 2025", norm("class of 2025"))
        assertEquals("pi is 3.14", norm("pi is 3.14"))
        assertEquals("1,000 people", norm("1,000 people"))
        assertEquals("watch porn now", norm("watch porn now"))
        assertEquals("cocktail party", norm("cocktail party"))
        assertEquals("analysis of german documents", norm("analysis of German documents"))
        assertEquals("the assignment is due", norm("the assignment is due"))
        assertEquals("library books", norm("library books"))
    }

    // ── Normalization feeds whole-word boundary matching ──────────

    @Test
    fun `normalization feeds the whole-word boundary check`() {
        // A normalized "cocktail" is still one word — "cock" must not match.
        assertEquals("cocktail", norm("cocktail"))
        assertFalse(KeywordMatcher.containsKeyword(norm("cocktail"), "cock", true))
        // Normalized evasions still respect word boundaries.
        assertTrue(KeywordMatcher.containsKeyword(norm("watch p0rn"), "porn", true))
        assertFalse(KeywordMatcher.containsKeyword(norm("pornstar"), "porn", true))
        assertTrue(KeywordMatcher.containsKeyword(norm("pornstar videos"), "pornstar", true))
        assertFalse(KeywordMatcher.containsKeyword(norm("erector set"), "erect", true))
    }

    // ── Performance smoke test (hot path: every event + 500 ms poll) ──

    @Test
    fun `normalization is fast on representative text`() {
        // Representative on-screen chunk (titles, queries, descriptions with
        // sprinkled evasions), ~220 KB per pass; 10 passes ≈ 2.2 MB total.
        val chunk = buildString {
            repeat(2000) {
                append("watch p0rn videos now the score was 100 to 0 ")
                append("bikini girl photos n_u_d_3 class of 2025 ")
                append("i need a 4x4 vehicle hеntai рогn s3x v1brator ")
            }
        }
        // Warm up the NFKC/JIT paths first.
        repeat(3) { KeywordMatcher.normalizeForMatching(chunk) }
        val start = System.nanoTime()
        var checksum = 0
        repeat(10) { checksum += KeywordMatcher.normalizeForMatching(chunk).length }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        val totalKiB = chunk.length * 10 / 1024
        println("normalizeForMatching benchmark: $totalKiB KiB in ${"%.1f".format(elapsedMs)} ms " +
            "(${totalKiB / (elapsedMs / 1000.0)} KiB/s)")
        // Extremely generous CI bound: a real screen holds ~2 KB and the poll
        // budget is 500 ms per scan; 2.2 MB must process in well under a second.
        assertTrue("normalization too slow: ${"%.1f".format(elapsedMs)} ms", elapsedMs < 2000.0)
        assertTrue(checksum > 0)
    }
}
