package com.muddassir.clearview.todo.data

import android.content.Context
import com.muddassir.clearview.todo.model.TodoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Local persistence for the Todo feature: the whole [TodoItem] list as JSON
 * in one SharedPreferences file (the same pattern as DhikrStore). Reads are
 * instant, so the UI can save on every mutation (add / edit / complete /
 * delete) without any jank. The completion history lives inside the items, so
 * it survives app restarts and temporary-todo archiving.
 *
 * [itemsFlow] is a process-wide reactive mirror of the persisted list: every
 * [saveItems] (from the UI, the reminder receiver or the snooze activity)
 * publishes the new list, so an open Todo screen stays in lock-step with
 * notification actions that complete or reschedule todos in the background.
 */
class TodoStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every todo with its completion history; empty on first launch / corrupt data. */
    fun getItems(): List<TodoItem> =
        TodoCodec.decode(prefs.getString(KEY_ITEMS, null))

    /** Persists the full todo list and publishes it to every open screen. */
    fun saveItems(items: List<TodoItem>) {
        prefs.edit().putString(KEY_ITEMS, TodoCodec.encode(items)).apply()
        TodoStore.itemsFlow.value = items
    }

    /** Reactive view of the persisted list (null until the first [saveItems]). */
    val items: StateFlow<List<TodoItem>?> get() = TodoStore.itemsFlow

    /** The user's daily completion target (default 5; 1..50). */
    fun getDailyTarget(): Int =
        prefs.getInt(KEY_DAILY_TARGET, DEFAULT_DAILY_TARGET).coerceIn(1, 50)

    /** Persists the daily completion target. */
    fun setDailyTarget(target: Int) {
        prefs.edit().putInt(KEY_DAILY_TARGET, target.coerceIn(1, 50)).apply()
    }

    /**
     * Whether Todo reminders post notifications (the global toggle, default
     * ON, surfaced as a settings row next to the media / Quran toggles).
     */
    fun getTodoNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_TODO_NOTIFICATIONS, true)

    /** Persists the global Todo-reminders toggle. */
    fun setTodoNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TODO_NOTIFICATIONS, enabled).apply()
    }

    /** The name the user last entered in the Progress Card generator ("" = never). */
    fun getProgressCardName(): String =
        prefs.getString(KEY_PROGRESS_CARD_NAME, "") ?: ""

    /** Caches the Progress Card name so returning users don't retype it. */
    fun setProgressCardName(name: String) {
        prefs.edit().putString(KEY_PROGRESS_CARD_NAME, name.trim()).apply()
    }

    // ── Scheduled-alarm tracking ──────────────────────────────────
    //
    // Android 12+ limits how many PendingIntents an app may create (roughly
    // 10,000). Brute-forcing a PendingIntent for every (todo × index × day)
    // combination — as a cancel-and-reschedule sweep does — blows through that
    // quota in one save and alarms silently stop being created. Instead, we
    // record EXACTLY which alarms are scheduled ("todoId#index" → epochDay) and
    // only create/cancel PendingIntents for those. A todo holds 1-3 alarms, not
    // hundreds.

    /** The currently-scheduled alarms: "todoId#index" → the epoch day of the occurrence. */
    fun getScheduledAlarms(): Map<String, Long> {
        val raw = prefs.getString(KEY_SCHEDULED_ALARMS, null)
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { key ->
                val v = o.optLong(key, -1L)
                if (v >= 0) key to v else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Records that [todoId]'s [index]-th reminder is scheduled for [epochDay]. */
    fun setScheduledAlarm(todoId: String, index: Int, epochDay: Long) {
        val map = getScheduledAlarms().toMutableMap()
        map["$todoId#$index"] = epochDay
        saveScheduledAlarms(map)
    }

    /** Removes the record for [todoId]'s [index]-th reminder (it fired / was cancelled). */
    fun clearScheduledAlarm(todoId: String, index: Int) {
        val map = getScheduledAlarms().toMutableMap()
        if (map.remove("$todoId#$index") != null) saveScheduledAlarms(map)
    }

    /** Removes every recorded alarm belonging to [todoId] (delete / full cancel). */
    fun clearScheduledAlarmsFor(todoId: String) {
        val map = getScheduledAlarms().toMutableMap()
        val sizeBefore = map.size
        map.keys.removeAll { it.startsWith("$todoId#") }
        if (map.size != sizeBefore) saveScheduledAlarms(map)
    }

    private fun saveScheduledAlarms(map: Map<String, Long>) {
        val o = JSONObject()
        map.forEach { (key, epochDay) -> o.put(key, epochDay) }
        prefs.edit().putString(KEY_SCHEDULED_ALARMS, o.toString()).apply()
    }

    // ── Snoozed-reminder tracking ──────────────────────────────────
    //
    // A snooze re-schedules an occurrence to now+delay with the SAME request
    // code. The snooze record keeps (fire time, occurrence day) so that
    // [TodoScheduler.scheduleItems] can re-arm a still-pending snooze after
    // [TodoScheduler.rescheduleAll] — snoozes survive app restarts and
    // unrelated todo edits instead of silently reverting to the normal
    // schedule. The UI also reads it to show "Today: 1:58 → 2:08" on the card.

    /** One pending snooze: when it fires, and which occurrence (day) it belongs to. */
    data class SnoozedReminder(val fireAtMillis: Long, val epochDay: Long)

    /** The pending snoozes: "todoId#index" → its new fire time + occurrence day. */
    fun getSnoozedReminders(): Map<String, SnoozedReminder> {
        val raw = prefs.getString(KEY_SNOOZED_REMINDERS, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { key ->
                val v = o.optJSONObject(key) ?: return@mapNotNull null
                SnoozedReminder(
                    fireAtMillis = v.optLong("at", -1L),
                    epochDay = v.optLong("day", -1L)
                ).takeIf { it.fireAtMillis >= 0 && it.epochDay >= 0 }?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Records that [todoId]'s [index]-th reminder is snoozed until [fireAtMillis]. */
    fun setSnoozedReminder(todoId: String, index: Int, fireAtMillis: Long, epochDay: Long) {
        val map = getSnoozedReminders().toMutableMap()
        map["$todoId#$index"] = SnoozedReminder(fireAtMillis, epochDay)
        saveSnoozedReminders(map)
    }

    /** Removes the snooze record for [todoId]'s [index]-th reminder (it fired / was cancelled). */
    fun clearSnoozedReminder(todoId: String, index: Int) {
        val map = getSnoozedReminders().toMutableMap()
        if (map.remove("$todoId#$index") != null) saveSnoozedReminders(map)
    }

    /** Removes every snooze record belonging to [todoId] (delete / full cancel). */
    fun clearSnoozedRemindersFor(todoId: String) {
        val map = getSnoozedReminders().toMutableMap()
        val sizeBefore = map.size
        map.keys.removeAll { it.startsWith("$todoId#") }
        if (map.size != sizeBefore) saveSnoozedReminders(map)
    }

    private fun saveSnoozedReminders(map: Map<String, SnoozedReminder>) {
        val o = JSONObject()
        map.forEach { (key, s) ->
            o.put(key, JSONObject().put("at", s.fireAtMillis).put("day", s.epochDay))
        }
        prefs.edit().putString(KEY_SNOOZED_REMINDERS, o.toString()).apply()
    }

    companion object {
        /** Process-wide mirror of the persisted list (see class doc). */
        val itemsFlow = MutableStateFlow<List<TodoItem>?>(null)

        const val DEFAULT_DAILY_TARGET = 5

        private const val PREFS_NAME = "todo_store"
        private const val KEY_ITEMS = "items"
        private const val KEY_TODO_NOTIFICATIONS = "todo_notifications_enabled"
        private const val KEY_DAILY_TARGET = "daily_target"
        private const val KEY_SCHEDULED_ALARMS = "scheduled_alarms"
        private const val KEY_SNOOZED_REMINDERS = "snoozed_reminders"
        private const val KEY_PROGRESS_CARD_NAME = "progress_card_name"
    }
}
