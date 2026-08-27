package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoEvent
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoPriority
import com.muddassir.clearview.todo.model.TodoType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Pure Todo logic: JSON (de)serialization, every mutation of a [TodoItem]
 * list, active-day/archive rules, the list filters and sort orders, the
 * history (completed vs missed occurrences) and the human-readable schedule
 * labels. No Android dependencies, so the whole lifecycle is unit-testable on
 * the JVM (mirrors the DhikrCodec / UserPlaylists pattern). All mutations are
 * functional: list in → list out.
 *
 * DATE MODEL: a todo is only ever "applicable" on its own required days —
 * for a temporary todo every day of its [TodoItem.startDateEpochDay] →
 * [TodoItem.endDateEpochDay] period, for a permanent one every selected
 * weekday (or every day when [TodoItem.scheduledDays] is null). A future
 * todo is NOT applicable today: it cannot be completed, must not count as
 * missed, and must not affect today's score, progress or statistics.
 */
object TodoCodec {

    private val SCHEDULE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun newId(): String = UUID.randomUUID().toString()

    // ── Persistence ─────────────────────────────────────────────────

    /** JSON-encodes the list (order preserved as stored). */
    fun encode(items: List<TodoItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            val reminder = item.reminder?.let {
                JSONObject()
                    .put("times", JSONArray(it.timesMinutes))
                    .put("repeat", it.repeat)
                    .put("enabled", it.enabled)
                    .put("asAlarm", it.asAlarm)
            }
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("details", item.details)
                    .put("type", item.type.name)
                    .put("start", item.startDateEpochDay)
                    .put("end", item.endDateEpochDay ?: JSONObject.NULL)
                    .put("days", item.scheduledDays?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
                    .put("time", item.timeMinutes ?: JSONObject.NULL)
                    .put("timeStart", item.timeStartMinutes ?: JSONObject.NULL)
                    .put("timeEnd", item.timeEndMinutes ?: JSONObject.NULL)
                    .put("reminder", reminder ?: JSONObject.NULL)
                    .put("priority", item.priority.name)
                    .put("interval", item.strictInterval)
                    .put("createdAt", item.createdAtEpochMillis)
                    .put("updatedAt", item.updatedAtEpochMillis)
                    .put("completions", JSONObject().apply {
                        item.completions.forEach { (day, at) -> put(day.toString(), at) }
                    })
                    .put("missedCleared", item.missedClearedBefore ?: JSONObject.NULL)
                    .put("completedCleared", item.completedClearedBefore ?: JSONObject.NULL)
                    .put("behavior", item.behavior.name)
                    .put("targetDurationMinutes", item.targetDurationMinutes ?: JSONObject.NULL)
                    .put("events", encodeEvents(item.events))
                    .put("isDeleted", item.isDeleted)
            )
        }
        return arr.toString()
    }

    private fun encodeEvents(events: List<TodoEvent>): JSONArray {
        val arr = JSONArray()
        for (e in events) {
            val obj = JSONObject().put("timestampMillis", e.timestampMillis)
            when (e) {
                is TodoEvent.Created -> {
                    obj.put("type", "Created")
                    obj.put("title", e.title)
                    obj.put("timeMinutes", e.timeMinutes ?: JSONObject.NULL)
                    obj.put("durationMinutes", e.durationMinutes ?: JSONObject.NULL)
                }
                is TodoEvent.Edited -> {
                    obj.put("type", "Edited")
                    obj.put("oldTitle", e.oldTitle ?: JSONObject.NULL)
                    obj.put("newTitle", e.newTitle ?: JSONObject.NULL)
                    obj.put("oldTimeMinutes", e.oldTimeMinutes ?: JSONObject.NULL)
                    obj.put("newTimeMinutes", e.newTimeMinutes ?: JSONObject.NULL)
                    obj.put("oldDurationMinutes", e.oldDurationMinutes ?: JSONObject.NULL)
                    obj.put("newDurationMinutes", e.newDurationMinutes ?: JSONObject.NULL)
                }
                is TodoEvent.Attempted -> {
                    obj.put("type", "Attempted")
                    obj.put("epochDay", e.epochDay)
                }
                is TodoEvent.Completed -> {
                    obj.put("type", "Completed")
                    obj.put("epochDay", e.epochDay)
                }
                is TodoEvent.Uncompleted -> {
                    obj.put("type", "Uncompleted")
                    obj.put("epochDay", e.epochDay)
                }
                is TodoEvent.TimeAdded -> {
                    obj.put("type", "TimeAdded")
                    obj.put("epochDay", e.epochDay)
                    obj.put("addedMinutes", e.addedMinutes)
                }
            }
            arr.put(obj)
        }
        return arr
    }

    /** Decodes persisted JSON; empty list on blank/corrupt input. */
    fun decode(json: String?): List<TodoItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id", "")
                    if (id.isBlank()) null else decodeItem(id, o)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Builds one [TodoItem] from its JSON and HEALS stale completion data: a
     * completion recorded on a day the todo is not active on (e.g. a tomorrow
     * todo that carries a "completed today" entry from before it was moved)
     * is dropped at load time. The UI already refuses to render such
     * completions, but healing the data itself guarantees they can never
     * strike a card through or leak into statistics — today, and after any
     * future change.
     */
    private fun decodeItem(id: String, o: JSONObject): TodoItem {
        val item = TodoItem(
            id = id,
            title = o.optString("title", ""),
            details = o.optString("details", ""),
            type = runCatching { TodoType.valueOf(o.optString("type", "TEMPORARY")) }
                .getOrDefault(TodoType.TEMPORARY),
            startDateEpochDay = o.optLong("start", LocalDate.now().toEpochDay()),
            endDateEpochDay = if (o.isNull("end")) null else o.optLong("end"),
            scheduledDays = if (o.isNull("days")) null
            else (0 until o.getJSONArray("days").length())
                .map { o.getJSONArray("days").getInt(it) }
                .filter { it in 1..7 }
                .toSet()
                .takeIf { it.isNotEmpty() },
            // Times are validated on decode: minutes-from-midnight outside
            // 0..1439 (corrupt data, an older buggy build) are dropped instead
            // of ever reaching LocalTime.of — where 1440 ("24:00") or a
            // negative value would crash with a DateTimeException.
            timeMinutes = if (o.isNull("time")) null else o.optInt("time").takeIf { it in 0..1439 },
            timeStartMinutes = if (o.isNull("timeStart")) null else o.optInt("timeStart").takeIf { it in 0..1439 },
            timeEndMinutes = if (o.isNull("timeEnd")) null else o.optInt("timeEnd").takeIf { it in 0..1439 },
            reminder = if (o.isNull("reminder")) null else {
                val r = o.getJSONObject("reminder")
                ReminderConfig(
                    timesMinutes = (0 until r.getJSONArray("times").length())
                        .map { r.getJSONArray("times").getInt(it) }
                        .filter { it in 0..1439 }
                        .distinct()
                        .takeIf { it.isNotEmpty() } ?: listOf(20 * 60),
                    repeat = r.optBoolean("repeat", false),
                    enabled = r.optBoolean("enabled", true),
                    asAlarm = r.optBoolean("asAlarm", false)
                )
            },
            priority = runCatching { TodoPriority.valueOf(o.optString("priority", "NORMAL")) }
                .getOrDefault(TodoPriority.NORMAL),
            strictInterval = o.optBoolean("interval", false),
            createdAtEpochMillis = o.optLong("createdAt", 0L),
            updatedAtEpochMillis = o.optLong("updatedAt", 0L),
            completions = runCatching {
                val c = o.getJSONObject("completions")
                c.keys().asSequence().mapNotNull { key ->
                    key.toLongOrNull()?.let { it to c.optLong(key) }
                }.toMap()
            }.getOrDefault(emptyMap()),
            missedClearedBefore = if (o.isNull("missedCleared")) null
            else o.optLong("missedCleared"),
            completedClearedBefore = if (o.isNull("completedCleared")) null
            else o.optLong("completedCleared"),
            behavior = runCatching { TodoBehavior.valueOf(o.optString("behavior", "NORMAL")) }
                .getOrDefault(TodoBehavior.NORMAL),
            targetDurationMinutes = if (o.isNull("targetDurationMinutes")) null else o.optInt("targetDurationMinutes"),
            events = decodeEvents(o.optJSONArray("events")),
            isDeleted = o.optBoolean("isDeleted", false)
        )
        
        val migratedEvents = if (item.events.isEmpty() && item.completions.isNotEmpty()) {
            item.completions.map { (day, at) -> TodoEvent.Completed(at, day) }.sortedBy { it.timestampMillis }
        } else {
            item.events
        }

        return item.copy(
            events = migratedEvents,
            completions = item.completions.filterKeys { day ->
                isActiveOn(item, LocalDate.ofEpochDay(day))
            }
        )
    }

    private fun decodeEvents(arr: JSONArray?): List<TodoEvent> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val obj = arr.getJSONObject(i)
                val ts = obj.getLong("timestampMillis")
                when (obj.getString("type")) {
                    "Created" -> TodoEvent.Created(
                        ts,
                        obj.getString("title"),
                        if (obj.isNull("timeMinutes")) null else obj.getInt("timeMinutes"),
                        if (obj.isNull("durationMinutes")) null else obj.getInt("durationMinutes")
                    )
                    "Edited" -> TodoEvent.Edited(
                        ts,
                        if (obj.isNull("oldTitle")) null else obj.getString("oldTitle"),
                        if (obj.isNull("newTitle")) null else obj.getString("newTitle"),
                        if (obj.isNull("oldTimeMinutes")) null else obj.getInt("oldTimeMinutes"),
                        if (obj.isNull("newTimeMinutes")) null else obj.getInt("newTimeMinutes"),
                        if (obj.isNull("oldDurationMinutes")) null else obj.getInt("oldDurationMinutes"),
                        if (obj.isNull("newDurationMinutes")) null else obj.getInt("newDurationMinutes")
                    )
                    "Attempted" -> TodoEvent.Attempted(ts, obj.getLong("epochDay"))
                    "Completed" -> TodoEvent.Completed(ts, obj.getLong("epochDay"))
                    "Uncompleted" -> TodoEvent.Uncompleted(ts, obj.getLong("epochDay"))
                    "TimeAdded" -> TodoEvent.TimeAdded(ts, obj.getLong("epochDay"), obj.getInt("addedMinutes"))
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** True when [item] is due on [day] (within its period, and a repeat day for permanent todos). */
    fun isActiveOn(item: TodoItem, day: LocalDate): Boolean {
        val epoch = day.toEpochDay()
        if (epoch < item.startDateEpochDay) return false
        if (item.endDateEpochDay != null && epoch > item.endDateEpochDay) return false
        if (item.type == TodoType.PERMANENT && item.scheduledDays != null) {
            if (day.dayOfWeek.value !in item.scheduledDays) return false
        }
        return true
    }

    /**
     * True when a todo is deleted or a temporary todo's period has ended
     * (it leaves the active list but its history stays for statistics).
     */
    fun isArchived(item: TodoItem, today: LocalDate): Boolean =
        item.isDeleted || (item.type == TodoType.TEMPORARY &&
            item.endDateEpochDay != null &&
            today.toEpochDay() > item.endDateEpochDay)

    /** Non-archived todos (the working set for the active list UI). */
    fun visibleItems(items: List<TodoItem>, today: LocalDate): List<TodoItem> =
        items.filterNot { isArchived(it, today) }

    /** True when [item] has a completion recorded for [day]. */
    fun completedOn(item: TodoItem, day: LocalDate): Boolean =
        item.completions.containsKey(day.toEpochDay())

    /** True when [item] has been marked ATTEMPTED on [day] (and is not yet completed). */
    fun isAttemptedOn(item: TodoItem, day: LocalDate): Boolean {
        if (completedOn(item, day)) return false
        return item.events.filterIsInstance<TodoEvent.Attempted>().any { it.epochDay == day.toEpochDay() }
    }

    /** Returns total minutes added to [item] on [day]. */
    fun timeSpentOn(item: TodoItem, day: LocalDate): Int =
        item.events.filterIsInstance<TodoEvent.TimeAdded>()
            .filter { it.epochDay == day.toEpochDay() }
            .sumOf { it.addedMinutes }

    /** True when [item] is due on [today] (the only day it can be completed). */
    fun isDueToday(item: TodoItem, today: LocalDate): Boolean = isActiveOn(item, today)

    // ── Strict interval (deadline window) ───────────────────────────
    //
    // A strict-interval todo (strictInterval + timeStart/timeEnd) can ONLY be
    // completed inside its window on an applicable day. When the window ends
    // uncompleted, that day is LOCKED as missed — the checkbox disables, the
    // notification Complete action is rejected, and the day shows up in
    // History + stats immediately. "Can't redo": once the window closes, the
    // day can never be completed afterwards.

    /** Epoch millis of [day] at [minutes] past midnight (system zone). */
    fun dayTimeMillis(day: LocalDate, minutes: Int): Long {
        val m = normalizedMinutes(minutes)
        return LocalDateTime.of(day, LocalTime.of(m / 60, m % 60))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** True when [item] has a strict completion window. */
    fun hasStrictInterval(item: TodoItem): Boolean =
        item.strictInterval && item.timeStartMinutes != null && item.timeEndMinutes != null

    /** True when [item]'s strict window on [day] has CLOSED (now > end) — locked as missed. */
    fun intervalEnded(item: TodoItem, day: LocalDate, nowMillis: Long): Boolean {
        if (!hasStrictInterval(item)) return false
        return nowMillis > dayTimeMillis(day, item.timeEndMinutes!!)
    }

    /** True when [item]'s strict window on [day] is currently OPEN (completion allowed). */
    fun intervalOpen(item: TodoItem, day: LocalDate, nowMillis: Long): Boolean {
        if (!hasStrictInterval(item)) return true
        val start = dayTimeMillis(day, item.timeStartMinutes!!)
        val end = dayTimeMillis(day, item.timeEndMinutes!!)
        return nowMillis in start..end
    }

    /**
     * Whether [item] can be completed on [day] right now: it must be
     * applicable, not already completed, and — for strict-interval todos — the
     * window must be OPEN. A closed window means the day is locked as missed
     * ("can't redo") and completion is refused everywhere (checkbox, day
     * dialog, notification Complete action).
     */
    fun canCompleteOn(item: TodoItem, day: LocalDate, nowMillis: Long): Boolean =
        isActiveOn(item, day) && !completedOn(item, day) && intervalOpen(item, day, nowMillis)

    /**
     * The next date strictly after [today] on which [item] is active (its
     * next applicable day), or null when it never applies again. Used by the
     * Upcoming list grouping and the "upcoming" card marker.
     */
    fun nextActiveDate(item: TodoItem, today: LocalDate): LocalDate? {
        if (item.endDateEpochDay != null && item.endDateEpochDay <= today.toEpochDay()) return null
        var day = today.plusDays(1)
        val last = item.endDateEpochDay?.let(LocalDate::ofEpochDay) ?: today.plusDays(400)
        while (!day.isAfter(last)) {
            if (isActiveOn(item, day)) return day
            day = day.plusDays(1)
        }
        return null
    }

    // ── Mutations (functional) ──────────────────────────────────────

    /** Appends a new todo (id always fresh; completions start empty). */
    fun added(items: List<TodoItem>, item: TodoItem): List<TodoItem> {
        val now = System.currentTimeMillis()
        val createdEvent = TodoEvent.Created(now, item.title, item.timeMinutes, item.targetDurationMinutes)
        return items + item.copy(id = item.id.ifBlank { newId() }, completions = emptyMap(), events = listOf(createdEvent))
    }

    /**
     * Replaces the todo with the same id. Completion history and created-at
     * are preserved (the editor only edits the plan, never the record of
     * what was done); updated-at is refreshed.
     */
    fun updated(items: List<TodoItem>, item: TodoItem): List<TodoItem> =
        updateWithCompletions(items, item, preserveCompletions = true)

    /**
     * Edit that treats a completed / missed todo as a NEW todo: the edited
     * plan is applied, but the completion record is cleared — the todo leaves
     * Completed history and becomes actionable again ("it becomes new"). Used
     * when editing a completed card, where the user changed the plan.
     */
    fun editedAsNew(items: List<TodoItem>, item: TodoItem): List<TodoItem> =
        updateWithCompletions(items, item, preserveCompletions = false)

    private fun updateWithCompletions(
        items: List<TodoItem>,
        item: TodoItem,
        preserveCompletions: Boolean
    ): List<TodoItem> =
        items.map { existing ->
            if (existing.id == item.id) {
                existing.copy(
                    title = item.title,
                    details = item.details,
                    type = item.type,
                    startDateEpochDay = item.startDateEpochDay,
                    endDateEpochDay = item.endDateEpochDay,
                    scheduledDays = item.scheduledDays,
                    timeMinutes = item.timeMinutes,
                    timeStartMinutes = item.timeStartMinutes,
                    timeEndMinutes = item.timeEndMinutes,
                    reminder = item.reminder,
                    priority = item.priority,
                    // Preserved completions are kept ONLY on days the new plan
                    // is still active on — a completion on a day the edit made
                    // inapplicable (e.g. completed today, then moved to
                    // tomorrow) is stale data and is dropped, so the todo can
                    // never show as struck-through in the wrong tab.
                    completions = if (preserveCompletions) {
                        existing.completions.filterKeys { day ->
                            isActiveOn(item, LocalDate.ofEpochDay(day))
                        }
                    } else {
                        emptyMap()
                    },
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    events = existing.events + TodoEvent.Edited(
                        System.currentTimeMillis(),
                        if (existing.title != item.title) existing.title else null,
                        if (existing.title != item.title) item.title else null,
                        if (existing.timeMinutes != item.timeMinutes) existing.timeMinutes else null,
                        if (existing.timeMinutes != item.timeMinutes) item.timeMinutes else null,
                        if (existing.targetDurationMinutes != item.targetDurationMinutes) existing.targetDurationMinutes else null,
                        if (existing.targetDurationMinutes != item.targetDurationMinutes) item.targetDurationMinutes else null
                    )
                )
            } else {
                existing
            }
        }

    /** Removes the todo entirely (its history is gone too). */
    fun removed(items: List<TodoItem>, id: String): List<TodoItem> =
        items.filterNot { it.id == id }

    /** Archives the todo by marking it deleted (preserves history). */
    fun deletedOnly(items: List<TodoItem>, id: String): List<TodoItem> =
        items.map { if (it.id == id) it.copy(isDeleted = true, updatedAtEpochMillis = System.currentTimeMillis()) else it }

    /**
     * Marks [id] completed on [day] — STRICT completion, never a toggle: a
     * day already completed stays completed. Returns the new list. Callers
     * are responsible for the date rule (a todo can only be completed on a
     * day it is applicable on, never a future day).
     */
    fun completed(items: List<TodoItem>, id: String, day: LocalDate, at: Long): List<TodoItem> =
        items.map { item ->
            if (item.id != id) item
            else if (item.completions.containsKey(day.toEpochDay())) item
            else item.copy(
                completions = item.completions + (day.toEpochDay() to at),
                events = item.events + TodoEvent.Completed(at, day.toEpochDay()),
                updatedAtEpochMillis = at
            )
        }

    /**
     * Adds (or removes) the completion of [id] on [day] — the interactive
     * toggle used by the Today checkboxes. Returns the new list AND whether
     * the todo is now completed on that day, so callers can react (e.g.
     * cancel that day's reminders). Never prunes older history.
     */
    fun toggled(items: List<TodoItem>, id: String, day: LocalDate, at: Long): Pair<List<TodoItem>, Boolean> {
        val epoch = day.toEpochDay()
        var nowCompleted = false
        val updated = items.map { item ->
            if (item.id != id) item
            else {
                val completions = item.completions.toMutableMap()
                val newEvents = item.events.toMutableList()
                if (completions.remove(epoch) != null) {
                    nowCompleted = false
                    newEvents.add(TodoEvent.Uncompleted(at, epoch))
                } else {
                    completions[epoch] = at
                    nowCompleted = true
                    newEvents.add(TodoEvent.Completed(at, epoch))
                }
                item.copy(completions = completions, events = newEvents, updatedAtEpochMillis = at)
            }
        }
        return updated to nowCompleted
    }

    /** Marks [id] as attempted on [day] (for ATTEMPTED behavior). */
    fun attempted(items: List<TodoItem>, id: String, day: LocalDate, at: Long): List<TodoItem> =
        items.map { item ->
            if (item.id != id) item
            else item.copy(
                events = item.events + TodoEvent.Attempted(at, day.toEpochDay()),
                updatedAtEpochMillis = at
            )
        }

    /** Adds time to [id] on [day] (for TIME behavior). */
    fun timeAdded(items: List<TodoItem>, id: String, day: LocalDate, at: Long, addedMinutes: Int): List<TodoItem> =
        items.map { item ->
            if (item.id != id) item
            else item.copy(
                events = item.events + TodoEvent.TimeAdded(at, day.toEpochDay(), addedMinutes),
                updatedAtEpochMillis = at
            )
        }

    // ── Filters ─────────────────────────────────────────────────────

    fun filter(
        items: List<TodoItem>,
        filter: TodoFilter,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TodoItem> =
        when (filter) {
            // History includes ARCHIVED todos — it is the record of the past,
            // not a view of the active list.
            TodoFilter.HISTORY -> items.filter { hasHistory(it, today, nowMillis) }
            else -> {
                val visible = visibleItems(items, today)
                when (filter) {
                    TodoFilter.ALL -> visible
                    TodoFilter.TODAY -> visible.filter { isActiveOn(it, today) }
                    // Upcoming = NOT due today, but due on a future day — so
                    // Today and Upcoming are disjoint, never duplicated.
                    TodoFilter.UPCOMING -> visible.filter {
                        !isActiveOn(it, today) && nextActiveDate(it, today) != null
                    }
                    TodoFilter.TEMPORARY -> visible.filter { it.type == TodoType.TEMPORARY }
                    TodoFilter.PERMANENT -> visible.filter { it.type == TodoType.PERMANENT }
                    else -> emptyList()
                }
            }
        }

    fun sorted(items: List<TodoItem>, sort: TodoSort, today: LocalDate): List<TodoItem> =
        when (sort) {
            // Default: today's incomplete first, then by time, priority, creation.
            TodoSort.SMART -> items.sortedWith(
                compareBy(
                    { !(isActiveOn(it, today) && !completedOn(it, today)) },
                    { it.timeMinutes ?: Int.MAX_VALUE },
                    { -it.priority.weight },
                    { it.createdAtEpochMillis }
                )
            )
            TodoSort.TIME -> items.sortedWith(compareBy({ it.timeMinutes ?: Int.MAX_VALUE }, { it.title.lowercase() }))
            TodoSort.PRIORITY -> items.sortedWith(
                compareBy({ -it.priority.weight }, { it.timeMinutes ?: Int.MAX_VALUE })
            )
            TodoSort.CREATED -> items.sortedBy { it.createdAtEpochMillis }
            TodoSort.STATUS -> items.sortedWith(
                compareBy(
                    { completedOn(it, today) },
                    { it.timeMinutes ?: Int.MAX_VALUE },
                    { -it.priority.weight }
                )
            )
        }

    /** History entries sorted by most-recent occurrence first. */
    fun historySorted(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<HistoryEntry> =
        items.filter { hasHistory(it, today, nowMillis) }
            .map { entry(it, today, nowMillis) }
            .sortedByDescending { it.lastOccurrence }

    // ── History (past + today's occurrences) ────────────────────────

    /**
     * The completed / missed counts of [item]'s occurrences so far — every
     * day up to and including [today] on which the todo was applicable.
     *
     *  - Completed counts a completion on its required day (INCLUDING today:
     *    completing a todo today must appear in History immediately).
     *  - Missed counts applicable days left uncompleted whose day has PASSED
     *    (strictly before today), plus TODAY for strict-interval todos whose
     *    window has already closed uncompleted — that day is locked as missed
     *    the moment the window ends ("can't redo"). A normal todo today is
     *    still actionable, so it is never counted as missed.
     */
    fun occurrenceCounts(
        item: TodoItem,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Int, Int> {
        var completed = 0
        var missed = 0
        val todayEpoch = today.toEpochDay()
        val first = item.startDateEpochDay
        val last = (item.endDateEpochDay ?: todayEpoch).coerceAtMost(todayEpoch)
        var day = first
        while (day <= last) {
            val date = LocalDate.ofEpochDay(day)
            if (isActiveOn(item, date)) {
                if (completedOn(item, date)) {
                    // The completed-clear watermark hides the day from the
                    // History view ONLY — the completion itself stays in
                    // [TodoItem.completions] so today's count / progress /
                    // statistics keep counting it. A completed day never
                    // falls through to missed either.
                    if (item.completedClearedBefore == null || day >= item.completedClearedBefore!!) {
                        completed++
                    }
                } else if (day < todayEpoch || intervalEnded(item, date, nowMillis)) {
                    // Days before the missed-clear watermark are not shown in
                    // the Incomplete section anymore ("clearing history" only
                    // hides the records — the todo itself stays).
                    if (item.missedClearedBefore == null || day >= item.missedClearedBefore!!) {
                        missed++
                    }
                }
            }
            day++
        }
        return completed to missed
    }

    /**
     * True when [item] has any history: a completion on an applicable day up
     * to and including [today], OR an applicable day strictly before [today]
     * (a missed occurrence), OR — for a strict-interval todo — today's window
     * has already closed uncompleted (locked as missed immediately). A future
     * todo never has history. Fast path: short-circuits on completions and
     * expired temporaries, and only scans a bounded window (at most one week)
     * for the remaining case — a permanent todo whose start weekday isn't in
     * its schedule.
     */
    fun hasHistory(
        item: TodoItem,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val todayEpoch = today.toEpochDay()
        // Any completion on an applicable day up to and including today proves
        // history (a todo completed today must show up immediately) — unless
        // that completion was cleared from the History view (the watermark
        // hides it there, while stats still count it).
        if (item.completions.keys.any { day ->
                day <= todayEpoch && isActiveOn(item, LocalDate.ofEpochDay(day)) &&
                    (item.completedClearedBefore == null || day >= item.completedClearedBefore!!)
            }
        ) return true
        // A strict-interval todo whose window closed today uncompleted is
        // already a missed occurrence — it appears in History immediately
        // (unless its history was cleared: the watermark hides it).
        if (intervalEnded(item, today, nowMillis) &&
            isActiveOn(item, today) && !completedOn(item, today) &&
            (item.missedClearedBefore == null || todayEpoch >= item.missedClearedBefore)
        ) return true
        if (item.startDateEpochDay >= todayEpoch) return false
        // An expired temporary always had past occurrences (unless cleared).
        if (item.type == TodoType.TEMPORARY &&
            item.endDateEpochDay != null && item.endDateEpochDay < todayEpoch &&
            (item.missedClearedBefore == null || item.endDateEpochDay >= item.missedClearedBefore)
        ) return true
        // Scan for the first past active day (bounded: consecutive scheduled
        // weekdays are at most 6 days apart), honoring the clear watermark.
        val last = (item.endDateEpochDay ?: todayEpoch - 1).coerceAtMost(todayEpoch - 1)
        var day = item.startDateEpochDay
        while (day <= last) {
            if (isActiveOn(item, LocalDate.ofEpochDay(day)) &&
                (item.missedClearedBefore == null || day >= item.missedClearedBefore)
            ) return true
            day++
        }
        return false
    }

    /** One history row: an item with its past completed / missed counts. */
    data class HistoryEntry(
        val item: TodoItem,
        val completedCount: Int,
        val missedCount: Int,
        val lastOccurrence: LocalDate
    )

    fun entry(
        item: TodoItem,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): HistoryEntry {
        val (completed, missed) = occurrenceCounts(item, today, nowMillis)
        return HistoryEntry(
            item = item,
            completedCount = completed,
            missedCount = missed,
            lastOccurrence = lastOccurrence(item, today) ?: today.minusDays(1)
        )
    }

    /** The most recent occurrence of [item] (up to and including [today]), or null. */
    fun lastOccurrence(item: TodoItem, today: LocalDate): LocalDate? {
        var day = (item.endDateEpochDay ?: today.toEpochDay()).coerceAtMost(today.toEpochDay())
        while (day >= item.startDateEpochDay) {
            val date = LocalDate.ofEpochDay(day)
            if (isActiveOn(item, date)) return date
            day--
        }
        return null
    }

    /** History entries with at least one completion on a required date. */
    fun historyCompleted(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<HistoryEntry> =
        historySorted(items, today, nowMillis).filter { it.completedCount > 0 }

    /** History entries with at least one required date that passed uncompleted. */
    fun historyMissed(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<HistoryEntry> =
        historySorted(items, today, nowMillis).filter { it.missedCount > 0 }

    /**
     * "Clear" (Completed section): HIDES the completed history cards from the
     * History view only. The completion records are deliberately KEPT — today's
     * completed count, the progress bar, the daily target and the weekly
     * score/statistics all still count them (TodoStats reads the completions
     * directly and never consults this watermark). A cleared day can never
     * resurface as "missed" either — the completed-day branch never falls
     * through to the missed counter.
     */
    fun removeCompletedHistory(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TodoItem> {
        val ids = historyCompleted(items, today, nowMillis).map { it.item.id }.toSet()
        val clearedBefore = today.toEpochDay() + 1
        return items.map {
            if (it.id in ids) it.copy(completedClearedBefore = clearedBefore) else it
        }
    }

    /**
     * "Clear" (Incomplete section): HIDES the missed history cards from the
     * History view only — the todos and their schedules are untouched, and
     * today's completion/progress calculations are unaffected (this watermark
     * only hides past missed occurrences from the Incomplete section). Days
     * after the clear count normally.
     */
    fun removeMissedHistory(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TodoItem> {
        val ids = historyMissed(items, today, nowMillis).map { it.item.id }.toSet()
        val clearedBefore = today.toEpochDay() + 1
        return items.map { if (it.id in ids) it.copy(missedClearedBefore = clearedBefore) else it }
    }

    /**
     * "Clear All" (bottom): HIDES every History card (completed AND missed)
     * from the History view — a pure History-cleanup action. It never deletes
     * todos and never touches the completion data: today's completed count,
     * the progress bar, the daily target, the weekly progress and the
     * score/statistics all keep working exactly as before. The History tab
     * can appear empty while the app still correctly remembers everything
     * for calculations.
     */
    fun clearHistory(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TodoItem> {
        val ids = items.filter { hasHistory(it, today, nowMillis) }.map { it.id }.toSet()
        val clearedBefore = today.toEpochDay() + 1
        return items.map {
            if (it.id in ids) {
                it.copy(completedClearedBefore = clearedBefore, missedClearedBefore = clearedBefore)
            } else {
                it
            }
        }
    }

    /**
     * "Reset" (bottom): the DESTRUCTIVE counterpart of the history-only
     * clears. This genuinely resets the progress / history / statistics data:
     * every completion record is wiped — so today's completed count becomes
     * 0, the progress bar 0%, the daily target progress 0 and the weekly
     * score/statistics recompute to nothing — and BOTH history watermarks are
     * set to hide every past completed AND missed occurrence, so the History
     * tab starts empty too. The todos themselves — their plans, schedules,
     * reminders and types — are untouched; only the record of what was done
     * is reset. Days after the reset count normally (a fresh start). Always
     * gated behind a typed confirmation in the UI.
     */
    fun resetHistory(
        items: List<TodoItem>,
        today: LocalDate
    ): List<TodoItem> {
        val clearedBefore = today.toEpochDay() + 1
        return items.map {
            it.copy(
                completions = emptyMap(),
                completedClearedBefore = clearedBefore,
                missedClearedBefore = clearedBefore
            )
        }
    }

    // ── Human-readable labels (pure, for the UI) ────────────────────

    /** "Today", "Tomorrow", "Mon, Aug 10", "Every day", "Mon • Wed • Fri", or a range. */
    fun scheduleLabel(item: TodoItem, today: LocalDate): String {
        val start = LocalDate.ofEpochDay(item.startDateEpochDay)
        val startText = when (start) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> "${start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}, ${SCHEDULE.format(start)}"
        }
        if (item.type == TodoType.PERMANENT) {
            if (item.scheduledDays != null && item.scheduledDays.size < 7) {
                val names = item.scheduledDays.sorted().joinToString(" • ") { DAY_NAMES[it] }
                return names
            }
            return startText
        }
        val end = item.endDateEpochDay?.let { LocalDate.ofEpochDay(it) } ?: return startText
        return if (end == start) startText
        else "$startText – ${SCHEDULE.format(end)}"
    }

    /** "8:00 PM" / "9:00 AM · 1:00 PM · 8:00 PM" for the reminder times. */
    fun reminderLabel(item: TodoItem): String? =
        item.reminder?.timesMinutes?.joinToString(" · ") { timeLabel(it) }

    /** "10:00 PM" for a minutes-of-day value (never throws — see [normalizedMinutes]). */
    fun timeLabel(minutes: Int): String {
        val m = normalizedMinutes(minutes)
        return TIME.format(java.time.LocalTime.of(m / 60, m % 60))
    }

    /** "2:08 PM" for an epoch-millis instant (snoozed reminder fire times). */
    fun timeLabelFromMillis(millis: Long): String =
        TIME.format(
            java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalTime()
        )

    /**
     * "8:00 PM" for a single time, "9:00 AM – 8:00 PM" for a time range, or
     * null when the todo has no scheduled time.
     */
    fun scheduledTimeLabel(item: TodoItem): String? {
        item.timeMinutes?.let { return timeLabel(it) }
        val start = item.timeStartMinutes ?: return null
        val end = item.timeEndMinutes ?: return null
        return "${timeLabel(start)} – ${timeLabel(end)}"
    }

    /**
     * [count] times spread evenly across [startMinutes]..[endMinutes] inclusive,
     * rounded to whole minutes. The endpoints are always included when
     * count > 1; duplicates collapse, so a short range yields fewer distinct
     * times.
     */
    fun rangeTimes(startMinutes: Int, endMinutes: Int, count: Int): List<Int> {
        val n = count.coerceAtLeast(1)
        val span = (endMinutes - startMinutes).coerceAtLeast(0)
        if (n == 1 || span == 0) return listOf(startMinutes)
        return (0 until n).map { i ->
            startMinutes + Math.round(i * span.toDouble() / (n - 1)).toInt()
        }.map { it.coerceIn(0, 1439) }.distinct()
    }

    /**
     * Maps any minutes-of-day value into the valid 0..1439 range so a time
     * can never throw: 1440 ("24:00" — e.g. the exclusive end of a 22:00–24:00
     * productive window) wraps to 0 (midnight), and negative / huge corrupt
     * values wrap the same way (java.time mod semantics: -1 → 23:59).
     */
    private fun normalizedMinutes(minutes: Int): Int {
        if (minutes in 0..1439) return minutes
        return ((minutes % 1440) + 1440) % 1440
    }

    private val DAY_NAMES = arrayOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}

/** The list filters offered by the Todo screen (in the exact UI order). */
enum class TodoFilter {
    TODAY, UPCOMING, HISTORY, ALL, TEMPORARY, PERMANENT
}

/** The list sort orders offered by the Todo screen. */
enum class TodoSort {
    SMART, TIME, PRIORITY, CREATED, STATUS
}
