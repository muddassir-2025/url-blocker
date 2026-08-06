package com.muddassir.clearview.media.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.media.worker.MediaNotifier

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
 * count without disturbing the user — and cancelled at count 0. It reuses the
 * app's single "Channel updates" channel ([MediaNotifier.CHANNEL_ID]) with
 * [NotificationCompat.Builder.setSilent], so no extra channel is ever
 * registered (a second "Channel updates" entry used to appear in system
 * notification settings via the old "media_badge" channel).
 */
object MediaBadge {

    private const val TAG = "MediaBadge"
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
            runCatching { manager.cancel(BADGE_NOTIFICATION_ID) }
        } else {
            // Single creation path for the "Channel updates" channel — the
            // badge fallback posts silently on the same channel the update
            // notifications use, keeping the channel list clean.
            MediaNotifier.ensureChannel(context)
            val intent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, MediaNotifier.CHANNEL_ID)
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
            // Raw NotificationManager.notify() throws SecurityException on 13+
            // when POST_NOTIFICATIONS is denied — the badge is best-effort, so
            // never let it crash a background worker over a launcher bubble.
            runCatching { manager.notify(BADGE_NOTIFICATION_ID, notification) }
                .onFailure { Log.w(TAG, "Badge notification blocked: ${it.message}") }
        }
    }
}
