package com.muddassir.clearview.phonelimit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R

/**
 * Compact wide-strip (2x1) home-screen widget for the Phone Limit feature.
 *
 *  - IDLE: a field-styled label showing TODAY'S SCREEN TIME ("Screen time:
 *    3h 55m"; "Screen time: —" when the user hasn't granted Usage Access)
 *    and a START button. Refreshed every 30 min (the Android minimum for
 *    widgets) via updatePeriodMillis.
 *  - ACTIVE: the remaining time, kept in sync by [PhoneLimitService].
 *
 * Typing INSIDE the widget is deliberately not offered: a RemoteViews layout
 * containing an EditText fails to inflate on some launchers ("Problem loading
 * widget"), and widget text input isn't supported everywhere. START therefore
 * deep-links into the app's Phone Limit sheet (the timer window) via
 * [PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT] — the user-approved
 * fallback for launchers where direct widget input isn't possible.
 *
 * There is no cancel/stop control — the limit runs until it expires.
 */
class PhoneLimitWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            render(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        private const val TAG = "PhoneLimitWidgetProvider"

        /** Re-renders every active widget instance (state changed / ticking). */
        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PhoneLimitWidgetProvider::class.java)
            )
            ids.forEach { widgetId ->
                render(context, manager, widgetId)
            }
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_phone_limit)
            val active = PhoneLimitCoordinator.remainingMillis(context) > 0L

            // One block per state: the card keeps its size either way.
            views.setViewVisibility(
                R.id.widget_phone_remaining_block,
                if (active) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.widget_phone_controls,
                if (active) View.GONE else View.VISIBLE
            )
            if (active) {
                views.setTextViewText(
                    R.id.widget_phone_remaining,
                    PhoneLimitCoordinator.format(PhoneLimitCoordinator.remainingMillis(context))
                )
            } else {
                // Idle: today's real screen time (Usage Access granted), else
                // an em-dash placeholder — the label ALWAYS reads "Screen
                // time", never the old input-format example.
                views.setTextViewText(
                    R.id.widget_phone_input,
                    if (PhoneLimitCoordinator.hasUsageAccess(context)) {
                        context.getString(
                            R.string.widget_phone_screen_time,
                            PhoneLimitCoordinator.formatScreenTime(
                                PhoneLimitCoordinator.screenTimeToday(context)
                            )
                        )
                    } else {
                        context.getString(R.string.widget_phone_screen_time_na)
                    }
                )
            }
            wireActions(context, views, widgetId)
            manager.updateAppWidget(widgetId, views)
        }

        private fun wireActions(context: Context, views: RemoteViews, widgetId: Int) {
            // START: open the app straight into the Phone Limit sheet — the
            // timer window, not just the Quran tab.
            val open = Intent(context, LauncherActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT, true)
            }
            views.setOnClickPendingIntent(
                R.id.widget_phone_start,
                PendingIntent.getActivity(
                    context,
                    0x7040 + widgetId,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }
}
