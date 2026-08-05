package com.muddassir.clearview.quran.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranJsonParserTest {

    private val sample = """
        {
          "quran": [
            { "chapter": 65, "verse": 2, "text": "And whoever fears Allah—He will make a way out for them." },
            { "chapter": 65, "verse": 3, "text": "and provide for them from sources they could never imagine." },
            { "chapter": 66, "verse": 1, "text": "O Prophet! Why do you forbid what Allah has made lawful to you?" }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesFlatListIntoVersesWithSurahMetadata() {
        val verses = QuranJsonParser.parse(sample)

        assertEquals(3, verses.size)

        val first = verses[0]
        assertEquals(65, first.surahNumber)
        assertEquals(2, first.ayahNumber)
        // Surah name/translation come from the embedded canonical list.
        assertEquals("At-Talaaq", first.surahName)
        assertEquals("Divorce", first.surahTranslation)
        assertEquals("And whoever fears Allah—He will make a way out for them.", first.text)

        val second = verses[1]
        assertEquals(65, second.surahNumber)
        assertEquals(3, second.ayahNumber)

        // Second surah correctly assigned to its own verses.
        val third = verses[2]
        assertEquals(66, third.surahNumber)
        assertEquals(1, third.ayahNumber)
        assertEquals("At-Tahrim", third.surahName)
        assertEquals("The Prohibition", third.surahTranslation)
    }

    @Test
    fun handlesEmptyVerseList() {
        val empty = """{"quran":[]}"""
        assertTrue(QuranJsonParser.parse(empty).isEmpty())
    }

    @Test(expected = JSONException::class)
    fun throwsOnMalformedJson() {
        QuranJsonParser.parse("this is not json")
    }

    @Test(expected = JSONException::class)
    fun throwsWhenQuranSectionMissing() {
        QuranJsonParser.parse("""{"code":404}""")
    }

    @Test
    fun parsesArabicEditionIntoLookupMap() {
        // The Arabic edition uses the same flat shape; parseArabicTexts turns it
        // into a (surah, ayah) → text lookup.
        val arabic = """
            {
              "quran": [
                { "chapter": 2, "verse": 255, "text": "اللّٰهُ لَا إِلٰهَ إِلَّا هُوَ" },
                { "chapter": 2, "verse": 256, "text": "لَا إِكۡرَاهَ فِي الدِّيۡنِ" }
              ]
            }
        """.trimIndent()

        val map = QuranJsonParser.parseArabicTexts(arabic)

        assertEquals(2, map.size)
        assertEquals("اللّٰهُ لَا إِلٰهَ إِلَّا هُوَ", map[Pair(2, 255)])
        assertEquals("لَا إِكۡرَاهَ فِي الدِّيۡنِ", map[Pair(2, 256)])
        assertTrue(map[Pair(2, 999)] == null)
    }

    @Test
    fun resolvesSurahMetadataForAll114Surahs() {
        // Every canonical surah name/translation must resolve (1..114).
        assertEquals("Al-Faatiha", QuranJsonParser.surahName(1))
        assertEquals("The Opening", QuranJsonParser.surahTranslation(1))
        assertEquals("An-Naas", QuranJsonParser.surahName(114))
        assertEquals("Mankind", QuranJsonParser.surahTranslation(114))
        // Guard against a transposed/mistyped entry in either hand-maintained
        // list silently mislabelling every verse of a surah.
        for (i in 1..114) {
            assertTrue("surahName($i) must not be blank", QuranJsonParser.surahName(i).isNotBlank())
            assertTrue("surahTranslation($i) must not be blank", QuranJsonParser.surahTranslation(i).isNotBlank())
        }
        // Out-of-range numbers fall back gracefully instead of crashing.
        assertEquals("Surah 115", QuranJsonParser.surahName(115))
        assertEquals("", QuranJsonParser.surahTranslation(0))
    }
}
