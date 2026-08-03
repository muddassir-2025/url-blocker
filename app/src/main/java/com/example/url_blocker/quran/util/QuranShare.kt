package com.example.url_blocker.quran.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.url_blocker.R
import com.example.url_blocker.quran.model.QuranVerse

/**
 * Shared clipboard helpers for the Quran reminder. Used by BOTH the widget
 * ([com.example.url_blocker.quran.widget.QuranReminderWidgetProvider]) and the
 * full verse screen ([com.example.url_blocker.quran.ui.QuranVerseActivity]) so
 * every surface copies the exact same text.
 */

/**
 * Formats [verse] for sharing/copying: "Quran S:V · Surah Name", then the
 * Arabic verse (when cached) and the English translation.
 */
fun formatVerseForSharing(context: Context, verse: QuranVerse): String {
    val ref = context.getString(R.string.quran_verse_reference, verse.surahNumber, verse.ayahNumber) +
        " · " + verse.surahName
    return if (verse.arabicText.isNotBlank()) {
        "$ref\n\n${verse.arabicText}\n\n${verse.text}"
    } else {
        "$ref\n\n${verse.text}"
    }
}

/** Puts [verse] on the clipboard (formatted by [formatVerseForSharing]). */
fun copyVerseToClipboard(context: Context, verse: QuranVerse) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            context.getString(R.string.widget_quran_reminder_title),
            formatVerseForSharing(context, verse)
        )
    )
}
