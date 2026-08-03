package com.example.url_blocker.quran.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.example.url_blocker.R
import com.example.url_blocker.quran.data.QuranRepository
import com.example.url_blocker.quran.ui.QuranVerseActivity
import com.example.url_blocker.quran.util.copyVerseToClipboard
import com.example.url_blocker.quran.util.formatVerseForSharing
import com.example.url_blocker.quran.worker.QuranWorkScheduler

/**
 * Home-screen widget that shows the current English Quran verse.
 *
 * Rendering is instant: it only reads the persisted current verse (no file
 * parsing, no network). The verse itself is refreshed on the user-chosen
 * schedule (default 6 hours) by [QuranWorkScheduler]; this provider just
 * reflects the stored value.
 *
 * Tapping the widget body opens [QuranVerseActivity] (full verse details).
 * The header carries two actions:
 *  - Copy: copies the current verse (reference + text) to the clipboard.
 *  - Refresh: enqueues an immediate offline verse pick (the same MODE_REFRESH
 *    path the scheduled refresh uses).
 * Both are delivered back to this provider via explicit broadcasts.
 */
class QuranReminderWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY_VERSE -> handleCopy(context)
            ACTION_REFRESH_VERSE -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                handleRefresh(context, widgetId)
            }
            else -> super.onReceive(context, intent)
        }
    }

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
        /** Explicit broadcast actions handled by this provider (button taps). */
        private const val ACTION_COPY_VERSE = "com.example.url_blocker.quran.widget.COPY_VERSE"
        private const val ACTION_REFRESH_VERSE = "com.example.url_blocker.quran.widget.REFRESH_VERSE"

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

            // Icon buttons (Copy / Refresh). The src is set here rather than in
            // the layout so the refresh flash (handleRefresh) can swap images
            // explicitly — RemoteViews treats the layout's android:src as the
            // default, and re-renders would otherwise reset the icon.
            views.setImageViewResource(R.id.widget_copy_button, R.drawable.ic_widget_copy)
            views.setImageViewResource(R.id.widget_refresh_button, R.drawable.ic_widget_refresh)

            wireActions(context, views, widgetId)
            manager.updateAppWidget(widgetId, views)
        }

        /**
         * Wires every click action onto [views] for [widgetId]: the body opens
         * the verse details screen, and the Copy/Refresh chips deliver explicit
         * broadcasts back to this provider. Shared by the normal render AND the
         * refresh loading-flash so the buttons stay live even mid-flash.
         */
        private fun wireActions(
            context: Context,
            views: RemoteViews,
            widgetId: Int
        ) {
            // Tap → verse details screen.
            val intent = Intent(context, QuranVerseActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // Copy button → this provider's broadcast receiver. No widget id
            // needed: copy reads the global current verse. Request codes are
            // unique per widget (extras don't participate in PendingIntent
            // identity).
            views.setOnClickPendingIntent(
                R.id.widget_copy_button,
                PendingIntent.getBroadcast(
                    context,
                    widgetId * 2,
                    Intent(context, QuranReminderWidgetProvider::class.java)
                        .setAction(ACTION_COPY_VERSE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // Refresh button → this provider's broadcast receiver.
            views.setOnClickPendingIntent(
                R.id.widget_refresh_button,
                PendingIntent.getBroadcast(
                    context,
                    widgetId * 2 + 1,
                    Intent(context, QuranReminderWidgetProvider::class.java)
                        .setAction(ACTION_REFRESH_VERSE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        private fun handleCopy(context: Context) {
            val verse = QuranRepository(context).getCurrentVerse()
            if (verse == null) {
                // No verse yet (first run before the download finished): don't
                // copy a placeholder or claim success — just tell the user.
                Toast.makeText(
                    context,
                    context.getString(R.string.widget_quran_loading),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            copyVerseToClipboard(context, verse)
            Toast.makeText(
                context,
                context.getString(R.string.widget_quran_copied),
                Toast.LENGTH_SHORT
            ).show()
        }

        private fun handleRefresh(context: Context, widgetId: Int) {
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // Immediate feedback: swap in the loading placeholder right away;
                // the (fast, offline) refresh worker re-renders the new verse.
                // wireActions keeps Copy/Refresh/body clickable during the flash.
                val views = RemoteViews(context.packageName, R.layout.widget_quran_reminder)
                views.setTextViewText(R.id.widget_verse_text, context.getString(R.string.widget_quran_refreshing))
                views.setTextViewText(R.id.widget_verse_ref, "")
                views.setImageViewResource(R.id.widget_copy_button, R.drawable.ic_widget_copy)
                views.setImageViewResource(R.id.widget_refresh_button, R.drawable.ic_widget_refresh)
                wireActions(context, views, widgetId)
                AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
            }
            QuranWorkScheduler.refreshNow(context)
        }
    }
}
