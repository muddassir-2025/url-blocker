package com.muddassir.clearview.youtubetest

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Completely separate, test-only keyword storage for the YouTube-in-Chrome
 * Shorts experiment.
 *
 * These keywords are deliberately NOT stored in the normal [BlockRepository]
 * prefs (url_blocker_prefs / user_keywords) and are never fed into the normal
 * blocking matcher. Matching a YouTube test keyword pauses the video instead
 * of showing the ClearView block overlay.
 *
 * Storage: SharedPreferences under its own prefs file and key
 * ("youtube_test_keywords"), mirroring the project's existing persistence
 * approach. No cache is kept (the set is tiny and the service must always see
 * edits made from the app UI — both run in the same process).
 */
class YoutubeTestKeywordRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Test keywords, lowercased, trimmed, de-duplicated. */
    fun getKeywords(): Set<String> =
        (prefs.getStringSet(KEY, emptySet()) ?: emptySet())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

    fun addKeyword(keyword: String) {
        val trimmed = keyword.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return
        val current = getKeywords().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun removeKeyword(keyword: String) {
        val current = getKeywords().toMutableSet()
        current.remove(keyword.trim().lowercase(Locale.ROOT))
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun clearKeywords() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "yt_chrome_test_prefs"
        private const val KEY = "youtube_test_keywords"
    }
}
