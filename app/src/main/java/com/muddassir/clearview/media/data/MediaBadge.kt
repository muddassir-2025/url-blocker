package com.muddassir.clearview.media.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R

/**
 * Drives the LAUNCHER app-icon badge (the small bubble on the home screen)
 * from the unread channel-update count, so it clears as soon as the user
 * dismisses the in-app "Latest Updates".
 *
 * Primary path (API 26+): reflection into `NotificationManager.
 * setNotificationBadgeCount` — a hidden/`@SystemApi` method, so it's not in
 * the SDK; reflection is the only way to call it, and it works on stock
 * Android without posting anything (nothing appears in the shade and it works
 * even when notifications are blocked).
 *
 * Fallback: if the reflection call is rejected (OEM ROMs), a single SILENT
 * badge notification is posted instead — it drives the launcher bubble with a
 * count without disturbing the user — and cancelled at count 0.
 */
object MediaBadge {

    private const val BADGE_CHANNEL_ID = "media_badge"
    private const val BADGE_NOTIFICATION_ID = 0xBAD6E

    private val badgeCountMethod by lazy {
        runCatching {
            NotificationManager::class.java.getMethod(
                "setNotificationBadgeCount",
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    /** Sets the launcher badge to [count]; 0 clears it. */
    fun setBadge(context: Context, count: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val c = count.coerceAtLeast(0)
        val method = badgeCountMethod
        if (method != null) {
            try {
                method.invoke(manager, c)
                return
            } catch (e: Exception) {
                // Reflection blocked — fall through to the notification path.
            }
        }
        if (c == 0) {
            manager.cancel(BADGE_NOTIFICATION_ID)
        } else {
            ensureChannel(context, manager)
            val intent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget_copy)
                .setContentTitle(
                    context.resources.getQuantityString(
                        R.plurals.media_channels_updated, c, c
                    )
                )
                .setContentText(context.getString(R.string.media_latest_updates))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setNumber(c)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            manager.notify(BADGE_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(BADGE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    BADGE_CHANNEL_ID,
                    context.getString(R.string.media_notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = context.getString(R.string.media_notification_channel_desc)
                    // IMPORTANCE_MIN channels show badges by default.
                }
            )
        }
    }
}
