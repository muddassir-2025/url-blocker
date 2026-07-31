package com.example.url_blocker.quran.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.url_blocker.R
import com.example.url_blocker.quran.data.QuranRepository
import com.example.url_blocker.quran.ui.QuranVerseActivity
import com.example.url_blocker.quran.worker.QuranWorkScheduler

/**
 * Home-screen widget that shows the current English Quran verse.
 *
 * Rendering is instant: it only reads the persisted current verse (no file
 * parsing, no network). The verse itself is refreshed every 6 hours by
 * [QuranWorkScheduler]; this provider just reflects the stored value.
 *
 * Tapping the widget opens [QuranVerseActivity] (full verse details).
 */
class QuranReminderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Ensure the download/refresh schedule exists the moment the widget is
        // added, even if the app was never opened.
        QuranWorkScheduler.ensureScheduled(context)
        appWidgetIds.forEach { widgetId ->
            renderWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onEnabled(context: Context) {
        QuranWorkScheduler.ensureScheduled(context)
    }

    companion object {
        /**
         * Re-renders every active widget instance. Called after a verse refresh
         * so the new verse shows up without the user touching the widget.
         */
        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, QuranReminderWidgetProvider::class.java)
            )
            ids.forEach { widgetId ->
                renderWidget(context, manager, widgetId)
            }
        }

        private fun renderWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quran_reminder)
            val verse = QuranRepository(context).getCurrentVerse()

            if (verse != null) {
                views.setTextViewText(R.id.widget_verse_text, verse.text)
                views.setTextViewText(
                    R.id.widget_verse_ref,
                    context.getString(R.string.quran_verse_reference, verse.surahNumber, verse.ayahNumber) +
                        " · " + verse.surahName
                )
            } else {
                // First run before the download finishes: gentle placeholder.
                views.setTextViewText(R.id.widget_verse_text, context.getString(R.string.widget_quran_loading))
                views.setTextViewText(R.id.widget_verse_ref, context.getString(R.string.widget_quran_tap_to_load))
            }

            // Tap → verse details screen.
            val intent = Intent(context, QuranVerseActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
