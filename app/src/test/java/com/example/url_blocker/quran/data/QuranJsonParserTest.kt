package com.example.url_blocker.quran.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranJsonParserTest {

    private val sample = """
        {
          "code": 200,
          "status": "OK",
          "data": {
            "surahs": [
              {
                "number": 65,
                "name": "سُورَةُ الطَّلَاقِ",
                "englishName": "At-Talaaq",
                "englishNameTranslation": "Divorce",
                "revelationType": "Medinan",
                "numberOfAyahs": 12,
                "ayahs": [
                  {
                    "number": 5200,
                    "text": "And whoever fears Allah - He will make for him a way out.",
                    "numberInSurah": 2,
                    "juz": 28,
                    "manzil": 6,
                    "page": 558,
                    "ruku": 1,
                    "hizbQuarter": 112,
                    "sajda": false
                  },
                  {
                    "number": 5201,
                    "text": "And will provide for him from where he does not expect.",
                    "numberInSurah": 3,
                    "juz": 28,
                    "manzil": 6,
                    "page": 558,
                    "ruku": 1,
                    "hizbQuarter": 112,
                    "sajda": false
                  }
                ]
              },
              {
                "number": 66,
                "name": "سُورَةُ التَّحْرِيمِ",
                "englishName": "At-Tahrim",
                "englishNameTranslation": "The Prohibition",
                "revelationType": "Medinan",
                "numberOfAyahs": 12,
                "ayahs": [
                  {
                    "number": 5202,
                    "text": "O Prophet, why do you prohibit [yourself from] what Allah has made lawful for you?",
                    "numberInSurah": 1,
                    "juz": 28,
                    "manzil": 6,
                    "page": 560,
                    "ruku": 1,
                    "hizbQuarter": 112,
                    "sajda": false
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun parsesSurahsAndAyahsIntoFlatVerseList() {
        val verses = QuranJsonParser.parse(sample)

        assertEquals(3, verses.size)

        val first = verses[0]
        assertEquals(65, first.surahNumber)
        assertEquals(2, first.ayahNumber)
        assertEquals("At-Talaaq", first.surahName)
        assertEquals("Divorce", first.surahTranslation)
        assertEquals("And whoever fears Allah - He will make for him a way out.", first.text)

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
    fun handlesEmptySurahList() {
        val empty = """{"code":200,"status":"OK","data":{"surahs":[]}}"""
        assertTrue(QuranJsonParser.parse(empty).isEmpty())
    }

    @Test(expected = JSONException::class)
    fun throwsOnMalformedJson() {
        QuranJsonParser.parse("this is not json")
    }

    @Test(expected = JSONException::class)
    fun throwsWhenDataSectionMissing() {
        QuranJsonParser.parse("""{"code":404}""")
    }

    @Test
    fun parsesArabicEditionIntoLookupMap() {
        // The Arabic edition uses the same JSON shape; parseArabicTexts turns it
        // into a (surah, ayah) → text lookup.
        val arabic = """
            {
              "data": {
                "surahs": [
                  {
                    "number": 2,
                    "ayahs": [
                      { "numberInSurah": 255, "text": "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ" },
                      { "numberInSurah": 256, "text": "لَا إِكْرَاهَ فِي الدِّينِ" }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val map = QuranJsonParser.parseArabicTexts(arabic)

        assertEquals(2, map.size)
        assertEquals("اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ", map[Pair(2, 255)])
        assertEquals("لَا إِكْرَاهَ فِي الدِّينِ", map[Pair(2, 256)])
        assertTrue(map[Pair(2, 999)] == null)
    }

    @Test
    fun ignoresUnknownExtraFields() {
        // Extra/missing metadata fields in the payload must not break parsing;
        // only the fields the reminder uses are read.
        val minimal = """
            {
              "data": {
                "surahs": [
                  {
                    "number": 1,
                    "englishName": "Al-Faatiha",
                    "ayahs": [
                      { "numberInSurah": 1, "text": "In the name of Allah" }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val verses = QuranJsonParser.parse(minimal)
        assertEquals(1, verses.size)
        assertEquals(1, verses[0].surahNumber)
        assertEquals(1, verses[0].ayahNumber)
        assertEquals("Al-Faatiha", verses[0].surahName)
        // englishNameTranslation is optional and defaults to empty.
        assertEquals("", verses[0].surahTranslation)
    }
}
