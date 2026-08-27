package com.muddassir.clearview.todo.model

/**
 * A single Todo. Temporary todos exist only for their [startDateEpochDay] →
 * [endDateEpochDay] period (after which they leave the active list but keep
 * their completion history for statistics); permanent todos stay until
 * removed and can repeat on selected weekdays ([scheduledDays], 1 = Monday …
 * 7 = Sunday; null = every day).
 *
 * All dates are stored as epoch days (LocalDate.toEpochDay()) and times as
 * minutes-from-midnight, so they are timezone-safe and DST-safe. Completion
 * history ([completions], epochDay → completedAt epoch millis) is never
 * pruned — weekly statistics depend on it.
 */
data class TodoItem(
    val id: String,
    val title: String,
    val details: String = "",
    val type: TodoType = TodoType.TEMPORARY,
    /** First day this todo is active (epoch day). */
    val startDateEpochDay: Long,
    /** Last active day; null = no end (permanent). */
    val endDateEpochDay: Long? = null,
    /** Repeat weekdays for permanent todos (1=Mon..7=Sun); null = every day. */
    val scheduledDays: Set<Int>? = null,
    /** Optional scheduled time of day (minutes from midnight). */
    val timeMinutes: Int? = null,
    /**
     * Optional scheduled time RANGE (minutes from midnight), mutually exclusive
     * with [timeMinutes]: the todo has no single time but a window (e.g.
     * 9:00 AM – 8:00 PM) that its reminders are spread across.
     */
    val timeStartMinutes: Int? = null,
    /** End of the scheduled time range (minutes from midnight). */
    val timeEndMinutes: Int? = null,
    /**
     * Strict interval: when true (with [timeStartMinutes]/[timeEndMinutes]
     * set), the todo can ONLY be completed inside that time window on an
     * applicable day. When the window ends uncompleted, the day is locked as
     * MISSED — it can never be completed afterwards ("can't redo"). The
     * checkbox disables, the notification Complete action is rejected, and
     * the missed day appears in History + statistics immediately.
     */
    val strictInterval: Boolean = false,
    val reminder: ReminderConfig? = null,
    val priority: TodoPriority = TodoPriority.NORMAL,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    /** Completion history: epochDay → completedAt epoch millis (never pruned). */
    val completions: Map<Long, Long> = emptyMap(),
    /**
     * History-clear watermark for MISSED occurrences: epoch days strictly
     * BEFORE this are no longer shown in the History tab's Incomplete section
     * ("Clear" is a History-only cleanup — it never touches the todo or its
     * schedule). Null = nothing cleared. The watermark is exclusive, so a
     * clear on day X hides everything up to and including X (marker = X+1)
     * while days after it count normally.
     */
    val missedClearedBefore: Long? = null,
    /**
     * History-clear watermark for COMPLETED occurrences: epoch days strictly
     * BEFORE this are no longer shown in the History tab's Completed section.
     * Deliberately separate from the completion data itself — clearing the
     * History cards must NOT undo the completions, because today's count,
     * the progress bar, the daily target and the weekly score/statistics all
     * still count them (see TodoStats, which reads [completions] directly).
     * Null = nothing cleared.
     */
    val completedClearedBefore: Long? = null,
    /** Behavior of the Todo (normal checkbox, attempted state, or time tracking) */
    val behavior: TodoBehavior = TodoBehavior.NORMAL,
    /** For TIME behavior: the target duration in minutes */
    val targetDurationMinutes: Int? = null,
    /** Permanent history of all modifications and daily state changes */
    val events: List<TodoEvent> = emptyList(),
    /** True if the Todo was deleted but its history was preserved. */
    val isDeleted: Boolean = false
)

/** The task type/behavior, independent of its TEMPORARY/PERMANENT persistence. */
enum class TodoBehavior { NORMAL, ATTEMPTED, TIME }

/** Permanent historical events for a Todo. */
sealed class TodoEvent {
    abstract val timestampMillis: Long

    // -- Overarching Todo changes --

    data class Created(
        override val timestampMillis: Long,
        val title: String,
        val timeMinutes: Int?,
        val durationMinutes: Int?
    ) : TodoEvent()

    data class Edited(
        override val timestampMillis: Long,
        val oldTitle: String?,
        val newTitle: String?,
        val oldTimeMinutes: Int?,
        val newTimeMinutes: Int?,
        val oldDurationMinutes: Int?,
        val newDurationMinutes: Int?
    ) : TodoEvent()

    // -- Day-specific occurrence changes --

    data class Attempted(
        override val timestampMillis: Long,
        val epochDay: Long
    ) : TodoEvent()

    data class Completed(
        override val timestampMillis: Long,
        val epochDay: Long
    ) : TodoEvent()

    data class Uncompleted(
        override val timestampMillis: Long,
        val epochDay: Long
    ) : TodoEvent()

    data class TimeAdded(
        override val timestampMillis: Long,
        val epochDay: Long,
        val addedMinutes: Int
    ) : TodoEvent()
}

/** A temporary todo leaves the active list after its period; a permanent one stays. */
enum class TodoType { TEMPORARY, PERMANENT }

/**
 * Optional priority: [weight] sorts the list (higher = more important);
 * [scoreWeight] is the multiplier inside the weekly score's priority-weighted
 * Completion component (High 3×, Medium 2×, Low 1× — Low still counts, it is
 * never zero).
 */
enum class TodoPriority(val weight: Int) {
    LOW(0), NORMAL(1), HIGH(2);

    val scoreWeight: Int
        get() = when (this) {
            LOW -> 1
            NORMAL -> 2
            HIGH -> 3
        }
}

/**
 * Reminder configuration: one or more reminder times of day (minutes from
 * midnight). When [repeat] is true the reminders fire on every active day of
 * the todo (recurring/permanent); when false they fire only on the first
 * active day. [enabled] is the per-todo off switch — a reminder can keep its
 * times while its alarms are switched off. [asAlarm] chooses HOW the reminder
 * fires: a real system ALARM (AlarmManager.setAlarmClock — exact, full-screen
 * ring, shown in the Clock app) instead of the default notification.
 */
data class ReminderConfig(
    val timesMinutes: List<Int>,
    val repeat: Boolean,
    val enabled: Boolean = true,
    /** True = ring the system alarm clock; false = post a notification. */
    val asAlarm: Boolean = false
)

// Compat for PR a219822: old keys kind/attemptState/history map to new behavior/events
typealias TodoKind = TodoBehavior
typealias TodoHistoryEntry = TodoEvent
