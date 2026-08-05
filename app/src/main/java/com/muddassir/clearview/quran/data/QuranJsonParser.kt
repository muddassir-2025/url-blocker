package com.muddassir.clearview.quran.data

import com.muddassir.clearview.quran.model.QuranVerse
import org.json.JSONObject

/**
 * Parses the fawazahmed0/quran-api edition JSON (The Clear Quran English and
 * the IndoPak Arabic script) into verses. Pure function (no Android
 * dependencies) so it is unit-testable on the JVM with the real org.json
 * artifact.
 *
 * Expected shape:
 * ```
 * { "quran": [ { "chapter": 1, "verse": 1, "text": "In the Name of Allah..." } ] }
 * ```
 * The flat list carries no surah names, so the canonical 114 surah names +
 * translations are resolved from [SURAH_NAMES] / [SURAH_TRANSLATIONS].
 */
object QuranJsonParser {

    /**
     * @throws org.json.JSONException if the payload is malformed (callers treat
     *         that as a failed download and retry later).
     */
    fun parse(raw: String): List<QuranVerse> {
        val root = JSONObject(raw)
        val quran = root.getJSONArray("quran")

        val verses = ArrayList<QuranVerse>(6236)
        for (i in 0 until quran.length()) {
            val ayah = quran.getJSONObject(i)
            val surahNumber = ayah.getInt("chapter")
            verses.add(
                QuranVerse(
                    surahNumber = surahNumber,
                    ayahNumber = ayah.getInt("verse"),
                    surahName = surahName(surahNumber),
                    surahTranslation = surahTranslation(surahNumber),
                    text = ayah.getString("text")
                )
            )
        }
        return verses
    }

    /**
     * Parses an Arabic-script edition (same flat shape) into a lookup map
     * keyed by (surahNumber, ayahNumber) → Arabic text.
     *
     * @throws org.json.JSONException if the payload is malformed.
     */
    fun parseArabicTexts(raw: String): Map<Pair<Int, Int>, String> {
        val root = JSONObject(raw)
        val quran = root.getJSONArray("quran")

        val texts = HashMap<Pair<Int, Int>, String>(6236)
        for (i in 0 until quran.length()) {
            val ayah = quran.getJSONObject(i)
            texts[Pair(ayah.getInt("chapter"), ayah.getInt("verse"))] = ayah.getString("text")
        }
        return texts
    }

    /** Canonical English name of surah [number] (1..114), or "Surah N". */
    fun surahName(number: Int): String =
        SURAH_NAMES.getOrNull(number - 1) ?: "Surah $number"

    /** English translation of the surah name (e.g. "The Opening"), or "". */
    fun surahTranslation(number: Int): String =
        SURAH_TRANSLATIONS.getOrNull(number - 1) ?: ""

    private val SURAH_NAMES = listOf(
        "Al-Faatiha", "Al-Baqara", "Aal-i-Imraan", "An-Nisaa", "Al-Maaida",
        "Al-An'aam", "Al-A'raaf", "Al-Anfaal", "At-Tawba", "Yunus",
        "Hud", "Yusuf", "Ar-Ra'd", "Ibrahim", "Al-Hijr",
        "An-Nahl", "Al-Israa", "Al-Kahf", "Maryam", "Taa-Haa",
        "Al-Anbiyaa", "Al-Hajj", "Al-Muminoon", "An-Noor", "Al-Furqaan",
        "Ash-Shu'araa", "An-Naml", "Al-Qasas", "Al-Ankaboot", "Ar-Room",
        "Luqman", "As-Sajda", "Al-Ahzaab", "Saba", "Faatir",
        "Yaseen", "As-Saaffaat", "Saad", "Az-Zumar", "Ghafir",
        "Fussilat", "Ash-Shura", "Az-Zukhruf", "Ad-Dukhaan", "Al-Jaathiya",
        "Al-Ahqaf", "Muhammad", "Al-Fath", "Al-Hujuraat", "Qaaf",
        "Adh-Dhaariyat", "At-Tur", "An-Najm", "Al-Qamar", "Ar-Rahmaan",
        "Al-Waaqia", "Al-Hadid", "Al-Mujaadila", "Al-Hashr", "Al-Mumtahana",
        "As-Saff", "Al-Jumu'a", "Al-Munaafiqoon", "At-Taghaabun", "At-Talaaq",
        "At-Tahrim", "Al-Mulk", "Al-Qalam", "Al-Haaqqa", "Al-Ma'aarij",
        "Nooh", "Al-Jinn", "Al-Muzzammil", "Al-Muddaththir", "Al-Qiyaama",
        "Al-Insaan", "Al-Mursalaat", "An-Naba", "An-Naazi'aat", "Abasa",
        "At-Takwir", "Al-Infitaar", "Al-Mutaffifin", "Al-Inshiqaaq", "Al-Burooj",
        "At-Taariq", "Al-A'laa", "Al-Ghaashiya", "Al-Fajr", "Al-Balad",
        "Ash-Shams", "Al-Lail", "Ad-Dhuhaa", "Ash-Sharh", "At-Tin",
        "Al-Alaq", "Al-Qadr", "Al-Bayyina", "Az-Zalzala", "Al-Aadiyaat",
        "Al-Qaari'a", "At-Takaathur", "Al-Asr", "Al-Humaza", "Al-Fil",
        "Quraish", "Al-Maa'un", "Al-Kawthar", "Al-Kaafiroon", "An-Nasr",
        "Al-Masad", "Al-Ikhlaas", "Al-Falaq", "An-Naas"
    )

    private val SURAH_TRANSLATIONS = listOf(
        "The Opening", "The Cow", "The Family of Imraan", "The Women", "The Table",
        "The Cattle", "The Heights", "The Spoils of War", "The Repentance", "Jonas",
        "Hud", "Joseph", "The Thunder", "Abraham", "The Rock",
        "The Bee", "The Night Journey", "The Cave", "Mary", "Taa-Haa",
        "The Prophets", "The Pilgrimage", "The Believers", "The Light", "The Criterion",
        "The Poets", "The Ant", "The Stories", "The Spider", "The Romans",
        "Luqman", "The Prostration", "The Clans", "Sheba", "The Originator",
        "Yaseen", "Those drawn up in Ranks", "The letter Saad", "The Groups", "The Forgiver",
        "Explained in detail", "Consultation", "Ornaments of gold", "The Smoke", "Crouching",
        "The Dunes", "Muhammad", "The Victory", "The Inner Apartments", "The letter Qaaf",
        "The Winnowing Winds", "The Mount", "The Star", "The Moon", "The Beneficent",
        "The Inevitable", "The Iron", "The Pleading Woman", "The Exile", "She that is to be examined",
        "The Ranks", "Friday", "The Hypocrites", "Mutual Disillusion", "Divorce",
        "The Prohibition", "The Sovereignty", "The Pen", "The Reality", "The Ascending Stairways",
        "Noah", "The Jinn", "The Enshrouded One", "The Cloaked One", "The Resurrection",
        "Man", "The Emissaries", "The Announcement", "Those who drag forth", "He frowned",
        "The Overthrowing", "The Cleaving", "Defrauding", "The Splitting Open", "The Constellations",
        "The Morning Star", "The Most High", "The Overwhelming", "The Dawn", "The City",
        "The Sun", "The Night", "The Morning Hours", "The Consolation", "The Fig",
        "The Clot", "The Power, Fate", "The Evidence", "The Earthquake", "The Chargers",
        "The Calamity", "Competition", "The Declining Day, Epoch", "The Traducer", "The Elephant",
        "Quraysh", "Almsgiving", "Abundance", "The Disbelievers", "Divine Support",
        "The Palm Fibre", "Sincerity", "The Dawn", "Mankind"
    )
}
