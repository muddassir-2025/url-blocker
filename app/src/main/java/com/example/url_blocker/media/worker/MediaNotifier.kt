package com.example.url_blocker.media.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.url_blocker.LauncherActivity
import com.example.url_blocker.R
import com.example.url_blocker.media.model.MediaChannelUpdate

/**
 * Posts the media (channel-update) notifications: one "… has an update"
 * notification per channel that uploaded something new, grouped into a single
 * summary so a burst of uploads never spams the shade. Tapping one opens the
 * app (the home page shows the Latest Updates feed with the same channels).
 */
object MediaNotifier {

    const val CHANNEL_ID = "media_updates"
    private const val GROUP_KEY = "media_updates_group"
    private const val SUMMARY_ID = 0
    // Cap the per-run notifications so a channel that uploaded a backlog of
    // videos can't flood the user; the summary still counts everything.
    private const val MAX_SHOWN = 4

    /** Idempotent — creates the notification channel on first use. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.media_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.media_notification_channel_desc)
                }
            )
        }
    }

    /**
     * Posts one notification per channel in [updates] plus a group summary.
     * Returns the number of channels notified (0 when notifications are
     * disabled by the system — e.g. the user denied the permission).
     */
    fun notifyUpdates(context: Context, updates: List<MediaChannelUpdate>): Int {
        if (updates.isEmpty()) return 0
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        // The worker checks our own toggle, but the OS-level permission (13+)
        // can still be off — silently skip instead of throwing.
        if (!manager.areNotificationsEnabled()) return 0

        updates.take(MAX_SHOWN).forEachIndexed { index, update ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget_copy)
                .setContentTitle(
                    context.getString(R.string.media_has_update, update.channelName)
                )
                .setContentText(update.latestVideoTitle)
                .setContentIntent(openAppIntent(context, update.channelId))
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .build()
            manager.notify(channelNotificationId(update.channelId, index), notification)
        }

        // Group summary ("3 channels have updates") so the notifications collapse.
        if (updates.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget_copy)
                .setContentTitle(
                    context.resources.getQuantityString(
                        R.plurals.media_channels_updated,
                        updates.size,
                        updates.size
                    )
                )
                .setContentIntent(openAppIntent(context, updates.first().channelId))
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .build()
            manager.notify(SUMMARY_ID, summary)
        }
        return updates.size
    }

    /** Opens the app from a notification; a fresh task if it isn't running. */
    private fun openAppIntent(context: Context, channelId: String): PendingIntent {
        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // Per-channel request codes keep the PendingIntents distinct even
        // though they all just open the app.
        return PendingIntent.getActivity(
            context,
            channelId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Stable per-channel notification id (positive, won't collide with the summary). */
    private fun channelNotificationId(channelId: String, index: Int): Int =
        (channelId.hashCode() and 0x7FFFFFFF) % 100_000 + index + 1
}
