package com.muddassir.clearview.phonelimit

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.Calendar
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.receiver.DeviceAdminReceiver
import java.util.Locale

/**
 * The single source of truth for the Phone Limit feature, shared by every
 * surface — the foreground service (live countdown), the AlarmManager
 * backstop, the home-screen widget and the in-app sheet — so they can never
 * drift apart.
 *
 * The timer is an ABSOLUTE end timestamp (start + duration), never a
 * decrementing counter, so backgrounding, doze or process death can't make
 * the countdown drift: `remaining = endTime - now`. The end timestamp is
 * persisted in SharedPreferences and survives restarts and reboots.
 *
 * Expiry is defended twice:
 *  1. [PhoneLimitService] — the foreground service notices the end and locks.
 *  2. An exact [AlarmManager] alarm ([scheduleExpiryAlarm]) fires at the end
 *     timestamp even when the process is dead or the device is dozing.
 *
 * Locking uses [DevicePolicyManager.lockNow] — the official device-lock API.
 * It is only available to Device Admin apps; when the app is not an admin the
 * expiry posts an explanatory notification instead of faking anything.
 * Android does NOT let a normal app press the power button or power the
 * device off, so this feature never claims to.
 */
object PhoneLimitCoordinator {

    private const val TAG = "PhoneLimitCoordinator"

    /** Notification channel for the ongoing countdown + the expiry notice. */
    const val CHANNEL_ID = "phone_limit"

    /** Broadcast actions delivered to [PhoneLimitReceiver]. */
    const val ACTION_EXPIRE = "com.muddassir.clearview.phonelimit.EXPIRE"
    const val ACTION_START = "com.muddassir.clearview.phonelimit.START"

    /** Service extra carrying the requested countdown length in millis. */
    const val EXTRA_DURATION_MILLIS = "duration_millis"

    /**
     * Launcher-activity extra that opens the app straight into the Phone
     * Limit sheet (the "timer window") — used as the widget's fallback when
     * the typed duration can't be read from the widget, and by the expiry
     * notification. Mirrors the Todo flow's EXTRA_OPEN_TODO.
     */
    const val EXTRA_OPEN_PHONE_LIMIT = "open_phone_limit"

    /** Key under which a widget's EditText text arrives with a click intent. */
    const val EXTRA_TEXT_INPUT = Intent.EXTRA_TEXT

    /** Notification id used for both the ongoing countdown and the expiry notice. */
    const val NOTIF_ID = 0x701

    private const val PREFS_NAME = "phone_limit_prefs"
    private const val KEY_END_TIME = "end_time"
    private const val KEY_TOTAL_MILLIS = "total_millis"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── State ──────────────────────────────────────────────────────

    /** The absolute end timestamp when a limit is running, else null. */
    fun activeEndTime(context: Context): Long? {
        val end = prefs(context).getLong(KEY_END_TIME, 0L)
        return if (end > System.currentTimeMillis()) end else null
    }

    fun isActive(context: Context): Boolean = activeEndTime(context) != null

    /** Time left in millis (0 when idle or expired). */
    fun remainingMillis(context: Context): Long {
        val end = activeEndTime(context) ?: return 0L
        return (end - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** The originally requested duration (for progress displays). */
    fun totalMillis(context: Context): Long =
        prefs(context).getLong(KEY_TOTAL_MILLIS, 0L)

    private fun persist(context: Context, durationMillis: Long) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_END_TIME, now + durationMillis)
            .putLong(KEY_TOTAL_MILLIS, durationMillis)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_END_TIME).remove(KEY_TOTAL_MILLIS).apply()
    }

    // ── Starting / expiry ──────────────────────────────────────────

    /**
     * Starts a phone limit of [durationMillis]: persists the absolute end
     * timestamp, arms the expiry alarm, creates the channel and re-renders
     * the widget. Does NOT start the foreground service — callers do that
     * (they must pick foreground-vs-plain start for the API level).
     */
    fun begin(context: Context, durationMillis: Long) {
        persist(context, durationMillis)
        ensureChannel(context)
        scheduleExpiryAlarm(context)
        PhoneLimitWidgetProvider.refreshAllWidgets(context)
        Log.i(TAG, "Phone limit started for ${durationMillis / 1000}s")
    }

    /**
     * The countdown reached zero (called by the service tick OR the alarm
     * backstop — whichever fires first; both are idempotent). Clears the
     * state, locks the phone when the app is a Device Admin, posts the expiry
     * notice and re-renders the widget.
     */
    fun handleExpiry(context: Context) {
        clear(context)
        cancelExpiryAlarm(context)
        val locked = lockNowIfAdmin(context)
        postExpiredNotification(context, locked)
        PhoneLimitWidgetProvider.refreshAllWidgets(context)
        Log.i(TAG, "Phone limit expired (locked=$locked)")
    }

    // ── Device lock ────────────────────────────────────────────────

    /** Whether the app is an ACTIVATED Device Admin at all. */
    fun isAdminActive(context: Context): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(context, DeviceAdminReceiver::class.java))
    } catch (e: Exception) {
        false
    }

    /**
     * Whether the app can actually LOCK the screen. Beyond being an active
     * admin it must hold the force-lock policy ([DeviceAdminInfo.USES_POLICY_FORCE_LOCK])
     * — when an app update adds a NEW admin policy, Android keeps the old
     * policy set until the user re-accepts the admin change, so the admin can
     * be active while lockNow() still throws "No active admin ... for policy
     * #3". This explicit check turns that opaque error into actionable UI.
     */
    fun canLockDevice(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = ComponentName(context, DeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(component)) return false
            val granted = grantedAdminInfo(dpm, component)
            // The system-side DeviceAdminInfo (what the user actually granted).
            if (granted != null) return granted.usesPolicy(DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
            // getAdminInfo is no longer in the public API (SDK 37) — fall back
            // to the policies DECLARED in device_admin_policies.xml.
            declaredForceLock(context, component)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The system-granted [DeviceAdminInfo] for [component] via reflection:
     * DevicePolicyManager.getAdminInfo() was removed from the public SDK
     * stubs (deprecated since API 21, dropped on newer SDKs) but still exists
     * on the platform, and only it reports which policies the user actually
     * granted (vs. merely declared). Returns null when unavailable.
     */
    private fun grantedAdminInfo(
        dpm: DevicePolicyManager,
        component: ComponentName
    ): DeviceAdminInfo? = try {
        DevicePolicyManager::class.java
            .getMethod("getAdminInfo", ComponentName::class.java)
            .invoke(dpm, component) as? DeviceAdminInfo
    } catch (e: Exception) {
        null
    }

    /** Reads the force-lock policy from the receiver's declared meta-data. */
    private fun declaredForceLock(context: Context, component: ComponentName): Boolean = try {
        val activityInfo = context.packageManager.getReceiverInfo(
            component, PackageManager.GET_META_DATA
        )
        val info = DeviceAdminInfo(
            context,
            ResolveInfo().apply { this.activityInfo = activityInfo }
        )
        info.usesPolicy(DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
    } catch (e: Exception) {
        false
    }

    /** The precise state of the device-lock capability (for the setup UI). */
    enum class LockCapability { NOT_ADMIN, POLICY_PENDING, READY }

    fun lockCapability(context: Context): LockCapability = when {
        !isAdminActive(context) -> LockCapability.NOT_ADMIN
        canLockDevice(context) -> LockCapability.READY
        else -> LockCapability.POLICY_PENDING
    }

    /**
     * Locks the phone via [DevicePolicyManager.lockNow] — the official
     * device-lock API, only callable by Device Admin apps. Never powers the
     * device off and never simulates a power-button press. Returns false (the
     * expiry then only posts a notice) when the app is not a Device Admin.
     */
    fun lockNowIfAdmin(context: Context): Boolean {
        if (!canLockDevice(context)) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            true
        } catch (e: Exception) {
            Log.e(TAG, "lockNow failed: ${e.message}")
            false
        }
    }

    // ── Alarm backstop ─────────────────────────────────────────────

    /**
     * Arms an exact alarm at the end timestamp — the reliable expiry trigger
     * even when the process is dead or the device is dozing. Falls back to an
     * inexact alarm when the exact-alarm permission is missing (API 31+),
     * exactly like the todo scheduler does.
     */
    fun scheduleExpiryAlarm(context: Context) {
        val end = activeEndTime(context) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = expiryPending(context)
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not permitted; using inexact: ${e.message}")
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        }
    }

    fun cancelExpiryAlarm(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(expiryPending(context))
    }

    /** True when exact alarms may be used (API < 31, or the permission is granted). */
    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarm.canScheduleExactAlarms()
    }

    private fun expiryPending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0x7011,
            Intent(context, PhoneLimitReceiver::class.java).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ── Notification ───────────────────────────────────────────────

    /** Idempotent — creates the quiet countdown channel on first use. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.phone_limit_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.phone_limit_notification_channel_desc)
                }
            )
        }
    }

    /**
     * The ongoing countdown notification (rebuilt every second by the
     * service). Ongoing + no actions: the user cannot cancel it from the
     * shade — by design, the limit runs until it expires.
     */
    fun buildCountdownNotification(context: Context, remainingMillis: Long): android.app.Notification {
        val openApp = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(context.getString(R.string.phone_limit_active))
            .setContentText(
                context.getString(R.string.phone_limit_remaining, format(remainingMillis))
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0x7012,
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    /**
     * Posted when the countdown ends. Tapping it opens the app; it auto-cancels.
     * When the app is not a Device Admin the text explains why the phone did
     * not lock (Android only lets Device Admin apps lock the screen).
     */
    fun postExpiredNotification(context: Context, locked: Boolean) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            Log.w(TAG, "Expiry notification skipped: notifications disabled")
            return
        }
        val openApp = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val text = if (locked) {
            context.getString(R.string.phone_limit_expired_text)
        } else {
            context.getString(R.string.phone_limit_expired_no_admin_text)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(context.getString(R.string.phone_limit_expired_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0x7013,
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Expiry notification suppressed: ${e.message}")
        }
    }

    // ── Duration parsing / formatting ──────────────────────────────

    /**
     * Parses a user-entered duration string into millis. Accepts the unit
     * forms — `1h`, `20m`, `30s`, `1h:20m:10s`, `1h 20m 10s`, `45m 30s`,
     * `90s`, `2h` — and plain colon-separated numbers (`1:20:10` = h:m:s,
     * `45:30` = m:s, `30` = minutes). Returns null when nothing parseable or
     * the total is zero.
     */
    fun parseDuration(input: String): Long? {
        val text = input.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null

        val colonParts = text.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        val hasUnits = text.any { it == 'h' || it == 'm' || it == 's' }
        var millis = 0L

        if (hasUnits || colonParts.size > 1) {
            // Unit form: tokenize digits followed by h/m/s (surrounding
            // whitespace, colons or nothing).
            val regex = Regex("(\\d+)\\s*([hms])")
            var found = false
            regex.findAll(text).forEach { m ->
                found = true
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "h" -> millis += n * 3_600_000L
                    "m" -> millis += n * 60_000L
                    "s" -> millis += n * 1_000L
                }
            }
            if (!found) {
                // Pure colon form (no unit letters anywhere).
                when (colonParts.size) {
                    3 -> {
                        millis = colonParts[0].toLongOrNull()?.let { it * 3_600_000L } ?: 0L
                        millis += colonParts[1].toLongOrNull()?.let { it * 60_000L } ?: 0L
                        millis += colonParts[2].toLongOrNull()?.let { it * 1_000L } ?: 0L
                    }
                    2 -> {
                        millis = colonParts[0].toLongOrNull()?.let { it * 60_000L } ?: 0L
                        millis += colonParts[1].toLongOrNull()?.let { it * 1_000L } ?: 0L
                    }
                    1 -> millis = colonParts[0].toLongOrNull()?.let { it * 60_000L } ?: 0L
                    else -> return null
                }
            }
        } else {
            // A plain number means minutes.
            millis = text.toLongOrNull()?.let { it * 60_000L } ?: return null
        }
        return if (millis > 0L) millis else null
    }

    /** Formats remaining millis as H:MM:SS (e.g. `1:20:10`, `45:30`). */
    fun format(remainingMillis: Long): String {
        val totalSec = (remainingMillis / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
        } else {
            "%02d:%02d".format(Locale.ROOT, m, s)
        }
    }

    // ── Today's screen time ───────────────────────────────────────

    /**
     * Whether the user granted Usage Access (the system setting behind the
     * protected PACKAGE_USAGE_STATS permission) — required to read real
     * screen time via [UsageStatsManager]. Without it the widget/sheet fall
     * back to their static hints.
     */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Today's total screen-on time in millis, measured the same way Digital
     * Wellbeing does — from the raw usage EVENT stream, not the aggregated
     * daily buckets. `totalTimeInForeground` in an INTERVAL_DAILY bucket is
     * not reliably "today" (Android controls when buckets roll, and the value
     * can include time outside the queried range), which is what made the old
     * figure inflated. Building the state machine from [UsageEvents] sums the
     * actual visible sessions since midnight and matches the system's own
     * wellbeing figure. 0 when Usage Access is missing or the query fails.
     */
    fun screenTimeToday(context: Context): Long {
        if (!hasUsageAccess(context)) return 0L
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDay = calendar.timeInMillis
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(startOfDay, now)
            val event = UsageEvents.Event()
            var sessionStart = -1L // -1 = nothing on screen right now
            var total = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    // Ways a visible session can begin. Android emits BOTH the
                    // legacy MOVE_TO_* and the modern ACTIVITY_* families for
                    // one transition — the -1 guard makes duplicate starts
                    // no-ops, so nothing is double-counted.
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.KEYGUARD_HIDDEN,
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        if (sessionStart == -1L) sessionStart = event.timeStamp
                    }
                    // Any way the visible session can end closes the interval
                    // (duplicate ends are no-ops too). The keyguard/screen-off
                    // cases are what keep lock-screen and AOD time out of the
                    // total, matching Digital Wellbeing.
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (sessionStart != -1L) {
                            total += (event.timeStamp - sessionStart).coerceAtLeast(0L)
                            sessionStart = -1L
                        }
                    }
                }
            }
            // The screen is still on right now — count the open session's tail.
            if (sessionStart != -1L) total += (now - sessionStart).coerceAtLeast(0L)
            total
        } catch (e: Exception) {
            Log.w(TAG, "screenTimeToday failed: ${e.message}")
            0L
        }
    }

    /** Formats screen time compactly as `3h 55m` / `45m` / `0m`. */
    fun formatScreenTime(millis: Long): String {
        val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0L -> "${h}h ${m}m"
            m > 0L -> "${m}m"
            else -> "0m"
        }
    }
}
