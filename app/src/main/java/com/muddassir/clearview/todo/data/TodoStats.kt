package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure weekly + monthly statistics for the Todo screen. No Android
 * dependencies — unit-testable. Every figure is computed from
 * [TodoItem.completions] + the applicable-date rules in [TodoCodec], so
 * archived temporary todos keep contributing to history.
 *
 * DATE RULE (the single most important invariant): only days that have
 * ARRIVED (≤ today) are "applicable". A future day — however many todos are
 * scheduled on it — is empty here: it creates no due/completed counts, no
 * bar, no score effect, no missed/overdue penalty. Future schedules are
 * surfaced separately by the calendar ([monthDayStats], which keeps them for
 * display only).
 *
 * SCORE (max 100, fully explainable — v2, every point must be EARNED):
 *   Completion  55  · priority-weighted: 55 × Σweight(completed) / Σweight(due)
 *   Consistency 20  · 20 × (active days / days with due todos)
 *   Streak      15  · 15 × (min(streak, 7) / 7) — 0 is earned, not excluded
 *   Timeliness  10  · 10 × (1 − overdue/missed ÷ closed items)
 *
 * Exclusion rule: a component only scores when there is something to measure.
 * If its denominator is 0 it is EXCLUDED and the remaining base weights are
 * rescaled by 100 / (sum of included weights) so the total still reaches 100.
 * Nothing defaults to full marks for being empty or unfailed — that was the
 * old bug (free High-Priority +5 and On-track +25 produced a 30/100 score for
 * zero completions). A week with no due todos gets no numeric score at all.
 */
object TodoStats {

    // ── v2 base weights (renormalized after exclusions) ────────────────
    private const val W_COMPLETION = 55
    private const val W_CONSISTENCY = 20
    private const val W_STREAK = 15
    private const val W_TIMELINESS = 10

    data class WeekDayStats(
        val date: LocalDate,
        val due: Int,
        val completed: Int
    ) {
        val rate: Float
            get() = if (due > 0) (completed.toFloat() / due).coerceIn(0f, 1f) else 0f
    }

    /**
     * The per-component breakdown behind the weekly score (v2). Each earned
     * value is the component's RENORMALIZED weight × its ratio; the Max is
     * that renormalized weight (excluded components have Max 0).
     */
    data class ScoreBreakdown(
        val completion: Int,          // earned, 0..completionMax
        val completionMax: Int,       // renormalized weight (e.g. 61 when Timeliness is excluded)
        val consistency: Int,
        val consistencyMax: Int,
        val streak: Int,
        val streakMax: Int,
        val streakDays: Int,          // raw streak (for the explanation)
        val timeliness: Int,          // 0 when nothing has closed yet
        val timelinessMax: Int,       // 0 when excluded
        val closedItems: Int,         // occurrences whose due day has passed this week (completed or not)
        val dueWeight: Int,           // Σ priority weights of all due occurrences
        val doneWeight: Int,          // Σ priority weights of completed occurrences
        val overdueCount: Int,        // closed & uncompleted, still-active
        val missedCount: Int,         // closed & uncompleted, expired
        val total: Int                // 0..100
    )

    data class WeekStats(
        val today: LocalDate,
        /** Monday..Sunday of the current week; days AFTER today are empty (due=0). */
        val days: List<WeekDayStats>,
        val completed: Int,
        val due: Int,
        /** 0..100; null when the week had no due todos (nothing to measure). */
        val score: Int?,
        /** The component breakdown behind [score]; null together with it. */
        val breakdown: ScoreBreakdown?,
        /** The best applicable day by completion rate; null when nothing completed. */
        val bestDay: WeekDayStats?,
        /** Consecutive days (ending today or yesterday) with at least one completion. */
        val streak: Int,
        /** Days (of 7) with at least one completion. */
        val activeDays: Int,
        /** Completion-rate percentage points vs the previous week; null when no previous-week data. */
        val improvementPoints: Int?,
        /** True when this is the first week with any todo history — no comparison exists. */
        val firstWeek: Boolean,
        /** Applicable todos due today that are not yet completed. */
        val remainingToday: Int,
        /** Past uncompleted days this week of still-active todos. */
        val overdueCount: Int,
        /** Past uncompleted days this week of expired (archived) todos. */
        val missedCount: Int,
        /** The most productive 2-hour window (startHour..startHour+2) this week, or null below 2 completions. */
        val mostProductiveWindow: Pair<Int, Int>?
    ) {
        val percent: Int get() = if (due > 0) (rate * 100).toInt() else 0
        val rate: Float get() = if (due > 0) (completed.toFloat() / due).coerceIn(0f, 1f) else 0f
    }

    /** One day of the calendar / month grid: raw due + completed counts. */
    data class MonthDayStats(
        val date: LocalDate,
        /** All scheduled occurrences that day (FUTURE days included — for the calendar display only). */
        val due: Int,
        val completed: Int,
        /** True when the day hasn't arrived yet — its schedule is info, never progress. */
        val isFuture: Boolean
    )

    data class MonthStats(
        val monthStart: LocalDate,
        /** Every day of the month (Monday→Sunday irrelevant here), raw counts. */
        val days: List<MonthDayStats>,
        /** Applicable (≤ today) due occurrences this month. */
        val due: Int,
        val completed: Int
    ) {
        val percent: Int get() = if (due > 0) (completed.toFloat() / due * 100).toInt() else 0
        /** Scheduled occurrences after today (calendar-only info, never progress). */
        val futureScheduled: Int get() = days.filter { it.isFuture }.sumOf { it.due }
    }

    /** The Monday of the week containing [today]. */
    fun mondayOf(today: LocalDate): LocalDate =
        today.with(DayOfWeek.MONDAY)

    /** The seven days of the current week, Monday first. */
    fun weekDays(today: LocalDate): List<LocalDate> =
        (0..6).map { mondayOf(today).plusDays(it.toLong()) }

    /** Due and completed counts for [items] on [day] (all items, incl. archived ones). */
    fun dayStats(items: List<TodoItem>, day: LocalDate): WeekDayStats {
        val due = items.count { TodoCodec.isActiveOn(it, day) }
        val completed = items.count { TodoCodec.completedOn(it, day) }
        return WeekDayStats(day, due, completed)
    }

    /** Raw per-day counts for [day] (calendar: keeps future scheduled info). */
    fun monthDayStats(items: List<TodoItem>, day: LocalDate, today: LocalDate = LocalDate.now()): MonthDayStats {
        val due = items.count { TodoCodec.isActiveOn(it, day) }
        val completed = items.count { TodoCodec.completedOn(it, day) }
        return MonthDayStats(day, due, completed, day.isAfter(today))
    }

    /**
     * Full weekly statistics for the week containing [today]. Days after
     * today are deliberately EMPTY (due=0) — future schedules never count as
     * progress, missed, or score. [nowMillis] lets strict-interval todos
     * whose window closed TODAY count as missed/overdue immediately (their
     * day has effectively passed for completion purposes).
     */
    fun weekStats(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): WeekStats {
        val rawDays = weekDays(today)
        val days = rawDays.map { day ->
            if (day > today) WeekDayStats(day, 0, 0)
            else dayStats(items, day)
        }
        val completed = days.sumOf { it.completed }
        val due = days.sumOf { it.due }
        val daysWithDue = days.count { it.due > 0 }
        val activeDays = days.count { it.completed > 0 }
        val bestDay = days.filter { it.due > 0 }.maxWithOrNull(
            compareBy<WeekDayStats>({ it.rate }, { it.completed })
        )

        // Previous week (full 7 days — it is entirely in the past, so every
        // day is applicable).
        val lastWeek = weekDays(today.minusWeeks(1)).map { dayStats(items, it) }
        val lastDue = lastWeek.sumOf { it.due }
        val lastCompleted = lastWeek.sumOf { it.completed }
        val improvement = if (due > 0 && lastDue > 0) {
            val thisRate = completed.toFloat() / due
            val lastRate = lastCompleted.toFloat() / lastDue
            ((thisRate - lastRate) * 100).toInt()
        } else null

        val streak = streak(items, today)
        // Remaining = still COMPLETABLE right now. A strict-interval todo
        // whose window closed today is already locked as missed — it can no
        // longer be completed, so it must not count as "remaining".
        val remainingToday = items.count {
            TodoCodec.canCompleteOn(it, today, nowMillis)
        }

        // Overdue vs missed (bounded to the applicable days of THIS week). A
        // normal todo's TODAY is still actionable (skipped); a strict-interval
        // todo's TODAY counts as soon as its window closes uncompleted.
        // `closedItems` counts every occurrence whose due day has PASSED this
        // week — completed or not — and is the Timeliness denominator.
        var overdue = 0
        var missed = 0
        var closedItems = 0
        days.filter { it.date <= today }.forEach { day ->
            items.forEach { item ->
                if (!TodoCodec.isActiveOn(item, day.date)) return@forEach
                val dayClosed = day.date < today || TodoCodec.intervalEnded(item, day.date, nowMillis)
                if (!dayClosed) return@forEach
                closedItems++
                if (!TodoCodec.completedOn(item, day.date)) {
                    if (TodoCodec.isArchived(item, today)) missed++ else overdue++
                }
            }
        }

        // Score + breakdown (only meaningful with at least one due todo). v2:
        // priority-weighted completion (a single bucket — no separate
        // High-Priority that can sit empty and auto-pass), and components are
        // EXCLUDED — with the remaining weights rescaled — when their
        // denominator is 0. Nothing ever earns points for being empty.
        val breakdown = if (due > 0) {
            var dueWeight = 0
            var doneWeight = 0
            days.filter { it.date <= today }.forEach { day ->
                items.forEach { item ->
                    if (TodoCodec.isActiveOn(item, day.date)) {
                        dueWeight += item.priority.scoreWeight
                        if (TodoCodec.completedOn(item, day.date)) {
                            doneWeight += item.priority.scoreWeight
                        }
                    }
                }
            }
            val completionRaw = W_COMPLETION * (doneWeight.toFloat() / dueWeight)
            val consistencyRaw = if (daysWithDue > 0) {
                W_CONSISTENCY * (activeDays.toFloat() / daysWithDue)
            } else 0f
            val streakRaw = W_STREAK * (streak.coerceAtMost(7).toFloat() / 7f)
            // Timeliness: only when something has actually reached its due
            // time this week — being "on track" on a not-yet-due todo is not
            // an achievement. Overdue/missed shrink it toward 0.
            val timelinessRaw = if (closedItems > 0) {
                W_TIMELINESS * (1f - (overdue + missed).toFloat() / closedItems)
            } else 0f
            val includedWeight = W_COMPLETION + W_CONSISTENCY + W_STREAK +
                (if (closedItems > 0) W_TIMELINESS else 0)
            val scale = 100f / includedWeight
            // Score = round(Σ component scores) — one rounding at the end (per
            // the spec). The breakdown rows round independently, so they may
            // sum to ±1 of this authoritative total.
            val total = (scale * (completionRaw + consistencyRaw + streakRaw + timelinessRaw))
                .roundToInt().coerceIn(0, 100)
            ScoreBreakdown(
                completion = (scale * completionRaw).roundToInt().coerceIn(0, 100),
                completionMax = (scale * W_COMPLETION).roundToInt().coerceIn(0, 100),
                consistency = (scale * consistencyRaw).roundToInt().coerceIn(0, 100),
                consistencyMax = (scale * W_CONSISTENCY).roundToInt().coerceIn(0, 100),
                streak = (scale * streakRaw).roundToInt().coerceIn(0, 100),
                streakMax = (scale * W_STREAK).roundToInt().coerceIn(0, 100),
                streakDays = streak,
                timeliness = if (closedItems > 0) {
                    (scale * timelinessRaw).roundToInt().coerceIn(0, 100)
                } else 0,
                timelinessMax = if (closedItems > 0) {
                    (scale * W_TIMELINESS).roundToInt().coerceIn(0, 100)
                } else 0,
                closedItems = closedItems,
                dueWeight = dueWeight,
                doneWeight = doneWeight,
                overdueCount = overdue,
                missedCount = missed,
                total = total
            )
        } else null

        return WeekStats(
            today = today,
            days = days,
            completed = completed,
            due = due,
            score = breakdown?.total,
            breakdown = breakdown,
            bestDay = bestDay?.takeIf { completed > 0 },
            streak = streak,
            activeDays = activeDays,
            improvementPoints = improvement,
            firstWeek = due > 0 && lastDue == 0,
            remainingToday = remainingToday,
            overdueCount = overdue,
            missedCount = missed,
            mostProductiveWindow = mostProductiveWindow(items, mondayOf(today).toEpochDay(), mondayOf(today).plusDays(7).toEpochDay())
        )
    }

    /** Raw monthly stats for [month] (calendar: keeps future scheduled info). */
    fun monthStats(items: List<TodoItem>, month: YearMonth, today: LocalDate): MonthStats {
        val start = month.atDay(1)
        val days = (0 until start.lengthOfMonth()).map { monthDayStats(items, start.plusDays(it.toLong()), today) }
        val applicable = days.filter { !it.isFuture }
        return MonthStats(
            monthStart = start,
            days = days,
            due = applicable.sumOf { it.due },
            completed = applicable.sumOf { it.completed }
        )
    }

    /** Raw monthly stats for the current month. */
    fun monthStats(items: List<TodoItem>, today: LocalDate): MonthStats =
        monthStats(items, YearMonth.from(today), today)

    /**
     * Consecutive days with at least one completion, ending today when today
     * already has one, otherwise ending yesterday (an unfinished current day
     * must not break the streak).
     */
    fun streak(items: List<TodoItem>, today: LocalDate): Int {
        var day = today
        if (items.none { TodoCodec.completedOn(it, day) }) day = day.minusDays(1)
        var count = 0
        while (items.any { TodoCodec.completedOn(it, day) }) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /**
     * The most productive 2-HOUR WINDOW (startHour..startHour+2) among the
     * completions in [fromEpoch, toEpoch): the window containing the most
     * completion timestamps (earliest wins ties). Null below 2 completions.
     */
    fun mostProductiveWindow(
        items: List<TodoItem>,
        fromEpoch: Long,
        toEpoch: Long
    ): Pair<Int, Int>? {
        val hours = items.asSequence()
            .flatMap { item -> item.completions.entries.asSequence() }
            .filter { it.key in fromEpoch until toEpoch }
            .mapNotNull { (_, at) ->
                Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour
            }
            .toList()
        if (hours.size < 2) return null
        var bestStart = 0
        var bestCount = -1
        for (start in 0..22) {
            val count = hours.count { it in start until start + 2 }
            if (count > bestCount) {
                bestCount = count
                bestStart = start
            }
        }
        return bestStart to bestStart + 2
    }
}
