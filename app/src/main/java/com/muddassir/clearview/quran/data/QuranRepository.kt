package com.muddassir.clearview.quran.data

import android.content.Context
import com.muddassir.clearview.quran.model.QuranVerse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for Quran verse data.
 *
 * Responsibilities:
 *  - Download the full translation once and cache it locally.
 *  - Select a random verse from the cache and persist it as the current verse.
 *  - Read the current verse instantly for widget/detail-screen rendering.
 *
 * All file/network IO happens on [Dispatchers.IO]; only [getCurrentVerse]
 * (SharedPreferences read) is safe to call from the main thread.
 */
class QuranRepository(context: Context) {

    private val store = QuranStore(context.applicationContext)

    /** True when the full translation is already cached locally. */
    suspend fun isCached(): Boolean = withContext(Dispatchers.IO) { store.isCached() }

    /**
     * Downloads + caches the full translation. No-op when a VALID cache exists
     * (valid = the cached JSON actually parses, so a truncated/corrupt file is
     * re-downloaded instead of blocking the widget on a forever-loading state).
     * @return true when a usable cache is present afterwards.
     */
    suspend fun downloadIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val cached = store.loadVerses()
        if (cached != null && cached.isNotEmpty()) {
            true
        } else {
            val json = QuranApi.download()
            if (json != null) {
                store.saveJson(json)
                true
            } else {
                false
            }
        }
    }

    /**
     * Ensures BOTH editions are cached: English (required — the verse text
     * depends on it) and Arabic Uthmani (supplementary — the verse screen shows
     * Arabic when available, but English-only still works offline). The Arabic
     * fetch is best-effort: a failure must not fail the worker, since the
     * reminder remains fully functional in English.
     * @return true when the English cache is usable afterwards.
     */
    suspend fun ensureEnglishAndArabic(): Boolean = withContext(Dispatchers.IO) {
        val englishOk = downloadIfNeeded()
        if (englishOk && !store.isArabicCached()) {
            val json = QuranApi.downloadArabic()
            if (json != null) store.saveArabicJson(json)
        }
        englishOk
    }

    /**
     * Process-wide cache of the parsed Arabic lookup so repeated verse picks
     * in the same process don't re-parse the ~8MB file on every repository
     * instance. Stamped against the cache file so a later (re)download of the
     * Arabic edition is picked up. Only ever accessed from background threads.
     */
    private fun arabicTexts(): Map<Pair<Int, Int>, String>? {
        val stamp = store.arabicCacheStamp()
        if (arabicTextsStamp != stamp) {
            ArabicTextsCache = store.loadArabicTexts()
            arabicTextsStamp = stamp
        }
        return ArabicTextsCache
    }

    /**
     * Process-wide cache of the parsed English verses (the flat Quran-ordered
     * list), so repeated picks AND prev/next navigation don't re-parse the
     * cache file on every call. Stamped against the cache file so a later
     * (re)download is picked up. Only ever accessed from background threads.
     */
    private fun englishVerses(): List<QuranVerse>? {
        val stamp = store.cacheStamp()
        if (versesStamp != stamp) {
            EnglishVersesCache = store.loadVerses()
            versesStamp = stamp
        }
        return EnglishVersesCache
    }

    /**
     * Picks a random verse from the cached translation, persists it as the
     * current verse and returns it. Avoids repeating the verse that is
     * currently displayed (when there is more than one choice). Returns null
     * only when no data is available (offline first run).
     */
    suspend fun pickRandomVerse(): QuranVerse? = withContext(Dispatchers.IO) {
        val verses = englishVerses() ?: return@withContext null
        if (verses.isEmpty()) return@withContext null

        val current = store.readCurrentVerse()
        var verse = verses.random()
        var attempts = 0
        // Don't show the exact same verse twice in a row when there is choice.
        while (verse == current && attempts < 5 && verses.size > 1) {
            verse = verses.random()
            attempts++
        }

        // Attach the Arabic text when the Arabic edition is cached (best-effort;
        // stays empty on English-only installs).
        val enriched = if (verse.arabicText.isBlank()) {
            verse.copy(arabicText = arabicTexts()?.get(Pair(verse.surahNumber, verse.ayahNumber)) ?: "")
        } else {
            verse
        }

        store.saveCurrentVerse(enriched)
        enriched
    }

    /** The currently displayed verse (instant; null before first download). */
    fun getCurrentVerse(): QuranVerse? = store.readCurrentVerse()

    /** Persists [verse] as the currently displayed verse (instant). */
    fun saveCurrentVerse(verse: QuranVerse) = store.saveCurrentVerse(verse)

    /**
     * Returns the verse [step] positions away (+1 = next ayah, -1 = previous
     * ayah) from (surahNumber, ayahNumber). The flat cache is Quran-ordered,
     * so surah boundaries wrap naturally: previous of 2:1 is 1:286, next of
     * 1:7 is 2:1, etc. The result is enriched with Arabic text when available
     * and persisted as the current verse. Null at the very first/last verse of
     * the Quran, or when no data is cached yet.
     */
    suspend fun getAdjacentVerse(surahNumber: Int, ayahNumber: Int, step: Int): QuranVerse? =
        withContext(Dispatchers.IO) {
            val verses = englishVerses() ?: return@withContext null
            if (verses.isEmpty()) return@withContext null
            val index = verses.indexOfFirst {
                it.surahNumber == surahNumber && it.ayahNumber == ayahNumber
            }
            if (index < 0) return@withContext null
            val targetIndex = index + step
            if (targetIndex !in verses.indices) return@withContext null

            val raw = verses[targetIndex]
            val enriched = if (raw.arabicText.isBlank()) {
                raw.copy(
                    arabicText = arabicTexts()?.get(Pair(raw.surahNumber, raw.ayahNumber)) ?: ""
                )
            } else {
                raw
            }
            store.saveCurrentVerse(enriched)
            enriched
        }

    /**
     * Whether a verse exists [step] positions away (+1 / -1) from
     * (surahNumber, ayahNumber) — used to disable the Previous/Next buttons at
     * the start and end of the Quran.
     */
    suspend fun hasAdjacentVerse(surahNumber: Int, ayahNumber: Int, step: Int): Boolean =
        withContext(Dispatchers.IO) {
            val verses = englishVerses() ?: return@withContext false
            val index = verses.indexOfFirst {
                it.surahNumber == surahNumber && it.ayahNumber == ayahNumber
            }
            index >= 0 && (index + step) in verses.indices
        }

    /** User-chosen interval (hours) between automatic new-verse refreshes. */
    fun getRefreshIntervalHours(): Int = store.getRefreshIntervalHours()

    /** Sets + persists the interval (hours) between automatic new-verse refreshes. */
    fun setRefreshIntervalHours(hours: Int) = store.setRefreshIntervalHours(hours)

    /** Whether the app posts an OS notification when a new verse is chosen. */
    fun getQuranNotificationsEnabled(): Boolean = store.getQuranNotificationsEnabled()

    /** Persists the Quran-verse notification toggle. */
    fun setQuranNotificationsEnabled(enabled: Boolean) = store.setQuranNotificationsEnabled(enabled)

    // ── Search ───────────────────────────────────────────────────────

    /**
     * Searches the cached English translation for [query] (case-insensitive,
     * substring match on the translation text, surah name and reference).
     *
     * The query can also be a reference:
     *  - "2:255" / "2 255" / "2.255"  → that exact surah:ayah
     *  - "255" (a plain number)        → every verse numbered 255 (any surah)
     *
     * Results are enriched with Arabic text when the Arabic edition is cached.
     * Returns at most [limit] verses (the flat list is Quran-ordered, so the
     * matches come back in order). Empty when nothing is cached yet.
     */
    suspend fun searchVerses(query: String, limit: Int = 200): List<QuranVerse> =
        withContext(Dispatchers.IO) {
            val verses = englishVerses() ?: return@withContext emptyList()
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            val lower = q.lowercase()
            // "2:255", "2 255" or "2.255" → exact reference.
            val ref = REFERENCE_REGEX.find(q)
            val refSurah = ref?.groupValues?.get(1)?.toIntOrNull()
            val refAyah = ref?.groupValues?.get(2)?.toIntOrNull()
            val plainNumber = q.toIntOrNull()

            val matches = ArrayList<QuranVerse>(minOf(limit, 64))
            for (v in verses) {
                if (matches.size >= limit) break
                val hit = when {
                    refSurah != null && refAyah != null ->
                        v.surahNumber == refSurah && v.ayahNumber == refAyah
                    plainNumber != null ->
                        v.surahNumber == plainNumber || v.ayahNumber == plainNumber
                    else ->
                        v.text.lowercase().contains(lower) ||
                            v.surahName.lowercase().contains(lower) ||
                            "${v.surahNumber}:${v.ayahNumber}".contains(lower)
                }
                if (hit) matches.add(enrich(v))
            }
            matches
        }

    // ── Bookmarks ────────────────────────────────────────────────────

    /** Set of "surah:ayah" strings the user has bookmarked. */
    fun getBookmarks(): Set<String> = store.getBookmarks()

    fun isBookmarked(surahNumber: Int, ayahNumber: Int): Boolean =
        store.isBookmarked(surahNumber, ayahNumber)

    /** Toggles the bookmark for a verse. Returns true when now bookmarked. */
    fun toggleBookmark(surahNumber: Int, ayahNumber: Int): Boolean =
        store.toggleBookmark(surahNumber, ayahNumber)

    /** Removes the bookmark for a verse. */
    fun removeBookmark(surahNumber: Int, ayahNumber: Int) =
        store.removeBookmark(surahNumber, ayahNumber)

    /**
     * Resolves every saved bookmark into its full verse (enriched with Arabic
     * when cached), in Quran order (surah, then ayah) — deterministic regardless
     * of the underlying prefs set. Empty when none are bookmarked or the
     * translation isn't cached yet.
     */
    suspend fun getBookmarkedVerses(): List<QuranVerse> = withContext(Dispatchers.IO) {
        val verses = englishVerses() ?: return@withContext emptyList()
        val byRef = HashMap<Pair<Int, Int>, QuranVerse>(verses.size)
        for (v in verses) byRef[Pair(v.surahNumber, v.ayahNumber)] = v

        store.getBookmarks().mapNotNull { key ->
            val parts = key.split(":")
            val surah = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val ayah = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            byRef[Pair(surah, ayah)]?.let { enrich(it) }
        }.sortedWith(compareBy<QuranVerse> { it.surahNumber }.thenBy { it.ayahNumber })
    }

    /** Enriches [v] with Arabic text when the Arabic edition is cached. */
    private fun enrich(v: QuranVerse): QuranVerse =
        if (v.arabicText.isBlank()) {
            v.copy(arabicText = arabicTexts()?.get(Pair(v.surahNumber, v.ayahNumber)) ?: "")
        } else {
            v
        }

    private companion object {
        @Volatile
        private var ArabicTextsCache: Map<Pair<Int, Int>, String>? = null
        private var arabicTextsStamp = -1L

        @Volatile
        private var EnglishVersesCache: List<QuranVerse>? = null
        private var versesStamp = -1L

        // "2:255", "2 255" or "2.255" → exact surah:ayah reference.
        private val REFERENCE_REGEX =
            Regex("""^\s*(\d+)\s*[:.\s]\s*(\d+)\s*$""")
    }
}
