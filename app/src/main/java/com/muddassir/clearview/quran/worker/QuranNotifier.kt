package com.muddassir.clearview.quran.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.quran.data.QuranRepository
import com.muddassir.clearview.quran.model.QuranVerse

/**
 * Posts the OS notification for a freshly chosen Quran verse (the background
 * refresh). Gated by the Quran-notifications toggle AND the OS permission —
 * silently skips when either is off. Tapping the notification opens the app
 * on the home (Quran) tab.
 */
object QuranNotifier {

    const val CHANNEL_ID = "quran_reminders"
    private const val NOTIFICATION_ID = 0x51A7

    /** Idempotent — creates the notification channel on first use. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.quran_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.quran_notification_channel_desc)
                }
            )
        }
    }

    /** Posts the \"new verse\" notification (no-op unless enabled + permitted). */
    @SuppressLint("MissingPermission")
    fun notifyNewVerse(context: Context, verse: QuranVerse) {
        if (!QuranRepository(context).getQuranNotificationsEnabled()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val quoted = "\u201C${verse.text}\u201D"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(
                context.getString(R.string.quran_new_verse_notification_title, verse.surahName)
            )
            .setContentText(quoted)
            .setStyle(NotificationCompat.BigTextStyle().bigText(quoted))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
