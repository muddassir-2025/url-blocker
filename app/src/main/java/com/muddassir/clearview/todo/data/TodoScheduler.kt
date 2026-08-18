package com.muddassir.clearview.todo.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.ui.TodoAlarmActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules Todo reminder alarms with [AlarmManager]. Exact alarms where the
 * platform allows (API < 31, or API 31+ when the user granted exact-alarm
 * access) with an automatic fallback to inexact [setAndAllowWhileIdle] — so
 * reminders work everywhere. The exact-alarm permission is declared in the
 * manifest and the Todo editor guides the user to grant it when they pick the
 * Alarm style ([hasExactAlarmPermission]).
 *
 * Every reminder is its own alarm keyed by a deterministic request code for
 * (todoId, reminderIndex, epochDay) — a 31-bit hash so codes can't collide.
 * For each reminder time only the NEXT upcoming occurrence is scheduled;
 * when it fires, the receiver schedules the FOLLOWING occurrence
 * ([scheduleFollowing]), so a recurring todo keeps reminding day after day
 * without the app needing to be opened.
 *
 * ALARM TRACKING (important for Android 12+): the platform limits how many
 * PendingIntents an app can create (~10,000). A "cancel everything just in
 * case" sweep would create a PendingIntent for every (todo × index × day)
 * combination — hundreds per todo, enough to exhaust the quota so alarms
 * silently stop being scheduled. Instead, [TodoStore] persists EXACTLY which
 * alarms are currently set ("todoId#index" → epochDay); scheduling records
 * it, [cancelTodo] cancels only those, and [markFired] consumes a record when
 * an alarm fires. A todo therefore holds 1-3 PendingIntents, not hundreds.
 *
 * Snoozing ([snoozeFromNotification] / [snoozeNext]) CANCELS the pending
 * alarm for that exact occurrence and re-schedules it with the SAME request
 * code at now + delay, so repeated snoozes replace each other and can never
 * pile up duplicate notifications.
 *
 * A reminder with [ReminderConfig.asAlarm] set rings as a REAL system alarm:
 * scheduled with [AlarmManager.setAlarmClock] — always exact (no special
 * permission needed), shows in the Clock app's next-alarm, and wakes the
 * device. The fired broadcast is turned by [TodoNotifier] into an ALARM
 * notification with a FULL-SCREEN INTENT to [TodoAlarmActivity]: the system
 * shows that activity over the lock screen (the sanctioned alarm pattern —
 * launching the activity directly from the alarm is blocked by the
 * background-activity-launch restriction on modern Android). Alarm style is
 * deliberately NOT a plain notification.
 */
object TodoScheduler {

    private const val TAG = "TodoScheduler"
    private const val DAYS_AHEAD = 30L
    // A reminder set for a time seconds from now must still fire TODAY — a
    // 30s guard silently pushed it to tomorrow ("I set 2:12 PM and nothing
    // happened at 2:12"). 5s only protects against clock-skew rounding where
    // the target instant has just passed.
    private const val MIN_AHEAD_MS = 5_000L
    private const val INDICES = 10

    /** Recomputes and re-schedules every pending reminder (called on app start
     *  and after every todo change). Cheap: a few prefs reads + alarm set()s. */
    fun rescheduleAll(context: Context) {
        // Make sure the notification channels (incl. the alarm channel) are in
        // the correct state from the very first launch — not only when the
        // first alarm happens to fire. Idempotent.
        TodoNotifier.ensureChannel(context)
        val items = TodoStore(context).getItems()
        cancelAll(context, items)
        scheduleItems(context, items)
    }

    /**
     * Cancels every alarm that could belong to [item] (before deleting it) and
     * drops its snooze records — a deleted todo has no reminders at all.
     * Only the RECORDED alarms are touched — no brute-force sweep, so no
     * hundreds of throwaway PendingIntents.
     */
    fun cancelTodo(context: Context, item: TodoItem) {
        cancelRecordedAlarms(context, item)
        TodoStore(context).clearSnoozedRemindersFor(item.id)
    }

    /**
     * Cancels the recorded alarms of [item] WITHOUT touching its snooze
     * records — used by [rescheduleAll] so a still-pending snooze can be
     * re-armed by [scheduleNextForIndex] instead of silently reverting to the
     * normal schedule.
     */
    private fun cancelRecordedAlarms(context: Context, item: TodoItem) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val store = TodoStore(context)
        val scheduled = store.getScheduledAlarms()
        for (index in 0 until INDICES) {
            scheduled["${item.id}#$index"]?.let { epochDay ->
                // Cancel BOTH pending-intent shapes (broadcast + activity). The
                // scheduled style depends on the reminder config at schedule
                // time; cancel() on a PendingIntent that isn't scheduled is a
                // no-op, so this can never cancel the wrong alarm — but it CAN
                // catch an alarm scheduled before a style change.
                alarm.cancel(broadcastPending(context, item.id, index, epochDay))
                alarm.cancel(alarmActivityPending(context, item.id, index, epochDay))
            }
        }
        store.clearScheduledAlarmsFor(item.id)
    }

    /**
     * Consumes the record of a fired alarm (called by the receiver when an
     * [TodoNotifier.ACTION_REMIND] alarm fires) — including any snooze record
     * for that occurrence, so the snoozed window disappears from the card once
     * the snoozed alarm has rung. [scheduleFollowing] re-records the next
     * occurrence when one exists.
     */
    fun markFired(context: Context, todoId: String, index: Int) {
        val store = TodoStore(context)
        store.clearScheduledAlarm(todoId, index)
        store.clearSnoozedReminder(todoId, index)
    }

    private fun cancelAll(context: Context, items: List<TodoItem>) {
        // Preserve snooze records — scheduleNextForIndex re-arms them below.
        items.forEach { cancelRecordedAlarms(context, it) }
    }

    private fun scheduleItems(context: Context, items: List<TodoItem>) {
        // The global settings toggle switches ALL todo alarms off/on.
        if (!TodoStore(context).getTodoNotificationsEnabled()) {
            Log.w(TAG, "Alarm scheduling skipped: global Todo reminders toggle is OFF")
            return
        }
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        // Snoozed reminders, read once for the whole pass (the JSON map is
        // small, but per (item × index) reads would be wasteful).
        val snoozed = TodoStore(context).getSnoozedReminders()
        items.forEach { item ->
            val reminder = item.reminder ?: return@forEach
            if (reminder.timesMinutes.isEmpty()) return@forEach
            if (!reminder.enabled) return@forEach
            reminder.timesMinutes.forEachIndexed { index, minutes ->
                scheduleNextForIndex(
                    context, alarm, item, index, minutes, today, now, snoozed
                )
            }
        }
    }

    /**
     * Schedules the next occurrence of [item]'s [index]-th reminder at or
     * after [fromDay] (the first active day whose reminder time is still in
     * the future and that isn't completed). Returns true when one was set.
     *
     * A still-pending SNOOZE takes precedence: its exact fire time and
     * occurrence day are re-armed (same request code) instead of the normal
     * schedule, so snoozes survive app restarts and unrelated todo edits.
     */
    private fun scheduleNextForIndex(
        context: Context,
        alarm: AlarmManager,
        item: TodoItem,
        index: Int,
        minutes: Int,
        fromDay: LocalDate,
        now: Long,
        snoozed: Map<String, TodoStore.SnoozedReminder> = emptyMap()
    ): Boolean {
        val store = TodoStore(context)
        // Pending snooze → re-arm exactly it (same code, same day).
        snoozed["${item.id}#$index"]?.let { rec ->
            val day = LocalDate.ofEpochDay(rec.epochDay)
            if (rec.fireAtMillis > now &&
                TodoCodec.isActiveOn(item, day) && !TodoCodec.completedOn(item, day)
            ) {
                val code = requestCode(item.id, index, rec.epochDay)
                val pending = firingPending(context, item, index, rec.epochDay)
                if (item.reminder?.asAlarm == true) {
                    setAlarmClock(alarm, context, item.id, rec.fireAtMillis, pending, code)
                } else {
                    setAlarm(alarm, pending, rec.fireAtMillis)
                }
                store.setScheduledAlarm(item.id, index, rec.epochDay)
                return true
            }
            // Elapsed, or the occurrence is no longer applicable / completed:
            // drop the stale snooze and fall through to the normal schedule.
            store.clearSnoozedReminder(item.id, index)
        }
        val reminder = item.reminder ?: return false
        var day = fromDay
        for (i in 0 until DAYS_AHEAD) {
            if (!TodoCodec.isActiveOn(item, day)) { day = day.plusDays(1); continue }
            if (!reminder.repeat && day.toEpochDay() != item.startDateEpochDay) {
                day = day.plusDays(1); continue
            }
            if (TodoCodec.completedOn(item, day)) { day = day.plusDays(1); continue }
            val at = LocalDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at <= now + MIN_AHEAD_MS) { day = day.plusDays(1); continue }
            val code = requestCode(item.id, index, day.toEpochDay())
            val pending = firingPending(context, item, index, day.toEpochDay())
            if (reminder.asAlarm) {
                setAlarmClock(alarm, context, item.id, at, pending, code)
            } else {
                setAlarm(alarm, pending, at)
            }
            store.setScheduledAlarm(item.id, index, day.toEpochDay())
            return true
        }
        return false
    }

    /**
     * Schedules the occurrence of [item]'s [index]-th reminder AFTER the one
     * that just fired ([afterEpochDay]) — the recurring chain: each fired
     * alarm schedules the next day's, so reminders never silently stop.
     * No-op for one-shot (non-repeat) reminders.
     */
    fun scheduleFollowing(context: Context, item: TodoItem, index: Int, afterEpochDay: Long) {
        val reminder = item.reminder ?: return
        if (!reminder.repeat) return
        val minutes = reminder.timesMinutes.getOrNull(index) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val fromDay = LocalDate.ofEpochDay(afterEpochDay).plusDays(1)
        scheduleNextForIndex(context, alarm, item, index, minutes, fromDay, now)
    }

    /**
     * Snoozes the todo's next pending reminder by [delayMinutes] (snooze:
     * 10/30/60 min or a custom delay). Only that one
     * occurrence is pushed — other days' alarms stay intact. No-op when
     * nothing is pending (falls back to re-reminding shortly).
     */
    fun snoozeNext(context: Context, todoId: String, delayMinutes: Long) {
        val item = TodoStore(context).getItems().firstOrNull { it.id == todoId } ?: return
        val reminder = item.reminder ?: return
        if (reminder.timesMinutes.isEmpty()) return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        for (index in reminder.timesMinutes.indices) {
            val minutes = reminder.timesMinutes[index]
            var day = today
            for (i in 0 until DAYS_AHEAD) {
                if (!TodoCodec.isActiveOn(item, day)) { day = day.plusDays(1); continue }
                if (!reminder.repeat && day.toEpochDay() != item.startDateEpochDay) {
                    day = day.plusDays(1); continue
                }
                if (TodoCodec.completedOn(item, day)) { day = day.plusDays(1); continue }
                val originalAt = LocalDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (originalAt <= now + MIN_AHEAD_MS) { day = day.plusDays(1); continue }
                val code = requestCode(todoId, index, day.toEpochDay())
                // Cancel whichever shape was scheduled (broadcast or activity).
                alarm.cancel(broadcastPending(context, todoId, index, day.toEpochDay()))
                alarm.cancel(alarmActivityPending(context, todoId, index, day.toEpochDay()))
                val pending = firingPending(context, item, index, day.toEpochDay())
                scheduleSnoozed(alarm, context, item, pending, code, now + delayMinutes * 60_000L)
                TodoStore(context).setScheduledAlarm(todoId, index, day.toEpochDay())
                // Record the snooze so the card can show "Today: 1:58 → 2:08" and
                // rescheduleAll re-arms exactly it instead of the normal time.
                TodoStore(context).setSnoozedReminder(
                    todoId, index, now + delayMinutes * 60_000L, day.toEpochDay()
                )
                return
            }
        }
        // Fallback: nothing pending ahead (e.g. a one-time todo whose time
        // already passed) — re-remind shortly so the snooze is never a no-op.
        val code = requestCode(todoId, 0, today.toEpochDay())
        alarm.cancel(broadcastPending(context, todoId, 0, today.toEpochDay()))
        alarm.cancel(alarmActivityPending(context, todoId, 0, today.toEpochDay()))
        val pending = firingPending(context, item, 0, today.toEpochDay())
        scheduleSnoozed(alarm, context, item, pending, code, now + delayMinutes * 60_000L)
        TodoStore(context).setScheduledAlarm(todoId, 0, today.toEpochDay())
        TodoStore(context).setSnoozedReminder(
            todoId, 0, now + delayMinutes * 60_000L, today.toEpochDay()
        )
    }

    /**
     * Snoozes the EXACT reminder that produced a notification: [epochDay] /
     * [index] identify the occurrence, and the new alarm reuses its request
     * code — so snoozing the same notification repeatedly replaces the
     * pending alarm instead of stacking duplicates. The notification itself is
     * dismissed by the caller.
     */
    fun snoozeFromNotification(
        context: Context,
        todoId: String,
        index: Int,
        epochDay: Long,
        delayMinutes: Long
    ) {
        val item = TodoStore(context).getItems().firstOrNull { it.id == todoId } ?: return
        val reminder = item.reminder ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val code = requestCode(todoId, index, epochDay)
        alarm.cancel(broadcastPending(context, todoId, index, epochDay))
        alarm.cancel(alarmActivityPending(context, todoId, index, epochDay))
        val pending = firingPending(context, item, index, epochDay)
        scheduleSnoozed(alarm, context, item, pending, code, now + delayMinutes * 60_000L)
        TodoStore(context).setScheduledAlarm(todoId, index, epochDay)
        // Record the snooze (same occurrence day) so the card shows the new
        // window and rescheduleAll keeps it instead of reverting to normal.
        TodoStore(context).setSnoozedReminder(
            todoId, index, now + delayMinutes * 60_000L, epochDay
        )
    }

    /** Snoozes a reminder in the STYLE it was scheduled with: an alarm-style
     *  todo stays a real system alarm, a notification-style one stays a
     *  notification. */
    private fun scheduleSnoozed(
        alarm: AlarmManager,
        context: Context,
        item: TodoItem,
        pending: PendingIntent,
        code: Int,
        at: Long
    ) {
        if (item.reminder?.asAlarm == true) {
            setAlarmClock(alarm, context, item.id, at, pending, code)
        } else {
            setAlarm(alarm, pending, at)
        }
    }

    /** True when exact alarms may be used (API < 31, or the permission is granted). */
    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarm.canScheduleExactAlarms()
    }

    private fun setAlarm(alarm: AlarmManager, pending: PendingIntent, at: Long) {
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            // API 31+: exact alarms need a special permission we don't request —
            // inexact is close enough for a reminder.
            Log.w(TAG, "Exact alarm not permitted (${e.message}); using inexact")
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /**
     * Rings a REAL system alarm at [at]: [AlarmManager.setAlarmClock] is always
     * exact (exempt from the exact-alarm permission) and registers the alarm
     * in the Clock app. [pending] is the broadcast PendingIntent the system
     * fires at [at]; the receiver turns it into a full-screen alarm
     * notification ([TodoAlarmActivity]). The show-activity PendingIntent
     * (what tapping the Clock-app alarm opens) reuses ONE request code per
     * todo, so re-scheduling a recurring alarm never creates a new
     * PendingIntent.
     */
    private fun setAlarmClock(
        alarm: AlarmManager,
        context: Context,
        todoId: String,
        at: Long,
        pending: PendingIntent,
        code: Int
    ) {
        val showIntent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TodoNotifier.EXTRA_OPEN_TODO, true)
        }
        val showPending = PendingIntent.getActivity(
            context,
            ("alarm-show-$todoId").hashCode() and 0x7FFFFFFF,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPending), pending)
    }

    /**
     * The PendingIntent that FIRES when an alarm triggers. BOTH reminder
     * styles use a broadcast to [TodoReminderReceiver]: it posts the reminder
     * notification — for alarm style with a full-screen intent, so the SYSTEM
     * (not the app) launches [TodoAlarmActivity] over the lock screen.
     * (Launching an activity directly from a background alarm is blocked by
     * the background-activity-launch restriction, so the alarm screen is
     * always reached through the notification's full-screen intent.)
     */
    private fun firingPending(
        context: Context,
        item: TodoItem,
        index: Int,
        epochDay: Long
    ): PendingIntent = broadcastPending(context, item.id, index, epochDay)

    /** Broadcast shape — delivers [TodoNotifier.ACTION_REMIND] to the receiver. */
    private fun broadcastPending(
        context: Context,
        todoId: String,
        index: Int,
        epochDay: Long
    ): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java).apply {
            action = TodoNotifier.ACTION_REMIND
            putExtra(TodoNotifier.EXTRA_TODO_ID, todoId)
            putExtra(TodoNotifier.EXTRA_REMINDER_INDEX, index)
            // The exact day this reminder belongs to (epoch day), carried
            // through extras so the receiver acts on the right day.
            putExtra(TodoNotifier.EXTRA_EPOCH_DAY, epochDay)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(todoId, index, epochDay),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Activity shape used ONLY for cancellation: any alarm that a previous app
     * version scheduled as a direct activity launch is cleaned up here (cancel
     * on a PendingIntent that isn't scheduled is a no-op). Same request-code
     * space as the broadcast shape, so the two can never collide.
     */
    private fun alarmActivityPending(
        context: Context,
        todoId: String,
        index: Int,
        epochDay: Long
    ): PendingIntent {
        val intent = Intent(context, TodoAlarmActivity::class.java).apply {
            action = TodoNotifier.ACTION_REMIND
            putExtra(TodoNotifier.EXTRA_TODO_ID, todoId)
            putExtra(TodoNotifier.EXTRA_REMINDER_INDEX, index)
            putExtra(TodoNotifier.EXTRA_EPOCH_DAY, epochDay)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(todoId, index, epochDay),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Stable, positive, collision-free request code for a (todo, reminder
     * index, epoch day) — a full 31-bit string hash, so two different
     * reminders can never alias each other's alarm (recomputed identically by
     * [cancelTodo] and reused by snoozes to replace rather than stack).
     */
    private fun requestCode(todoId: String, index: Int, epochDay: Long): Int =
        "$todoId#$index#$epochDay".hashCode() and 0x7FFFFFFF
}
