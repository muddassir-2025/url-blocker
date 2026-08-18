package com.muddassir.clearview.media.data

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Drives the LAUNCHER app-icon badge (the small bubble on the home screen)
 * from the unread channel-update count, so it clears as soon as the user
 * dismisses the in-app "Latest Updates".
 *
 * Reflection into `NotificationManager.setNotificationBadgeCount` — a hidden/
 * `@SystemApi` method, so it's not in the SDK; reflection is the only way to
 * call it, and it works on stock Android without posting anything (nothing
 * appears in the shade and it works even when notifications are blocked).
 *
 * If the reflection call is rejected (some OEM ROMs) the badge count simply
 * isn't set — there is deliberately NO fallback notification. Older builds
 * posted a silent "N channels have updates" notification to drive the bubble,
 * which duplicated the per-channel update notifications in the shade; that was
 * removed. The launcher still shows the standard dot/count from the per-channel
 * update notifications themselves.
 */
object MediaBadge {

    // Id of the badge-fallback notification OLD builds posted — only ever
    // cancelled now, to clear a leftover from a previous version.
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
        // Always clear the badge notification a PREVIOUS build posted (the
        // fallback was removed) — upgrading must never leave the generic
        // "N channels have updates" shade entry behind.
        runCatching { manager.cancel(BADGE_NOTIFICATION_ID) }
        val c = count.coerceAtLeast(0)
        val method = badgeCountMethod
        if (method != null) {
            try {
                method.invoke(manager, c)
            } catch (e: Exception) {
                // Reflection blocked — the badge count just isn't available on
                // this ROM. No fallback notification: a visible "N channels
                // have updates" shade entry would duplicate the per-channel
                // update notifications.
            }
        }
    }
}
