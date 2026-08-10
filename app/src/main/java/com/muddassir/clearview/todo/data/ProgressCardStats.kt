package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoItem
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure, unit-testable computation behind the shareable Progress Card — the
 * range-based summary the card renders. No Android dependencies.
 *
 * A "range" is [from]..[to] inclusive, never extending beyond today. Every
 * occurrence (completed or missed) is counted per applicable day exactly like
 * the rest of the app ([TodoCodec.isActiveOn] / [TodoCodec.completedOn]), so
 * archived temporary todos keep contributing and future days never count as
 * progress or as missed.
 *
 * The generalized [CardStats.score] (0..100, null when the range has no data)
 * mirrors the v2 Weekly Score exactly: Completion 55 (priority-weighted),
 * Consistency 20, Streak 15 (capped at 7), Timeliness 10 — with the same
 * exclusion rule (a component whose denominator is 0 is dropped and the
 * remaining weights rescale to 100, so nothing ever earns free points for
 * being empty). The WEEK range additionally matches the on-screen Weekly
 * Score exactly.
 */
object ProgressCardStats {

    /** The time ranges the card can summarize. */
    enum class RangeKind { WEEK, MONTH, ALL, CUSTOM }

    /** One day of the mini calendar heatmap strip. */
    enum class Heat { DONE, MISSED, SCHEDULED }

    data class HeatDay(val date: LocalDate, val heat: Heat)

    /** Everything the card renders, computed for one range. */
    data class CardStats(
        val from: LocalDate,
        val to: LocalDate,
        val rangeKind: RangeKind,
        /** Todos first created inside the range. */
        val created: Int,
        /** Completed occurrences in the range. */
        val completed: Int,
        /**
         * Uncompleted occurrences in the range up to and including today —
         * the card's "Incomplete/Missed" figure (today's still-pending todos
         * are incomplete, not yet missed).
         */
        val missed: Int,
        /** Days in the range with at least one completion. */
        val activeDays: Int,
        /** Consecutive completed days ending at [to] (or the day before it). */
        val currentStreak: Int,
        /** Longest run of consecutive completed days within the range. */
        val bestStreak: Int,
        /** Generalized 0..100 score; null when the range has no occurrences. */
        val score: Int?,
        /** Distinct todo titles with ≥1 completion in the range (max 6). */
        val skills: List<String>,
        /** Up to 30 daily dots, oldest first, ending at [to]. */
        val heatmap: List<HeatDay>,
        /** True when the range has fewer than 7 days carrying any data. */
        val firstWeek: Boolean
    ) {
        val due: Int get() = completed + missed

        /** Completion rate as whole percent (0 when nothing was due). */
        val percent: Int get() = if (due > 0) (completed.toFloat() / due * 100).toInt() else 0
    }

    /**
     * The inclusive [from]..[to] bounds of [kind], never extending beyond
     * [today]. CUSTOM ranges are clamped to today and to a sane order (an
     * inverted range collapses to its end day).
     */
    fun resolveRange(
        kind: RangeKind,
        items: List<TodoItem>,
        today: LocalDate,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null
    ): Pair<LocalDate, LocalDate> = when (kind) {
        RangeKind.WEEK -> TodoStats.mondayOf(today) to today
        RangeKind.MONTH -> YearMonth.from(today).atDay(1) to today
        RangeKind.ALL -> {
            val earliest = items.minOfOrNull { it.startDateEpochDay }?.let(LocalDate::ofEpochDay)
            (earliest ?: today) to today
        }
        RangeKind.CUSTOM -> {
            val end = minOf(customTo ?: today, today)
            val from = customFrom ?: end
            if (from > end) end to end else from to end
        }
    }

    /** Full card statistics for [kind] over the resolved range. */
    fun compute(
        items: List<TodoItem>,
        kind: RangeKind,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis(),
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null
    ): CardStats {
        val (from, to) = resolveRange(kind, items, today, customFrom, customTo)
        val todayEpoch = today.toEpochDay()

        var completed = 0
        // The card's "Incomplete/Missed" figure: uncompleted occurrences up to
        // and including today (today's pending todos are incomplete).
        var missed = 0
        // Genuinely PASSED uncompleted days (yesterday and before, plus today
        // for strict-interval todos whose window closed) — the Timeliness
        // numerator and the red heatmap dots.
        var passedUncompleted = 0
        var dataDays = 0
        var activeDays = 0
        var daysWithDue = 0
        // Priority-weighted completion (v2): High 3×, Medium 2×, Low 1×.
        var dueWeight = 0
        var doneWeight = 0
        // Completions on days that have already passed (their due day is over
        // — completed on time, so a closed item that did NOT miss).
        var closedCompleted = 0
        val skillsCounts = HashMap<String, Int>()

        var day = from
        while (!day.isAfter(to)) {
            val epoch = day.toEpochDay()
            var dayCompleted = 0
            var dayDue = 0
            items.forEach { item ->
                if (TodoCodec.isActiveOn(item, day)) {
                    dayDue++
                    dueWeight += item.priority.scoreWeight
                    if (TodoCodec.completedOn(item, day)) {
                        dayCompleted++
                        completed++
                        doneWeight += item.priority.scoreWeight
                        if (epoch < todayEpoch) closedCompleted++
                        if (item.title.isNotBlank()) skillsCounts.merge(item.title, 1, Int::plus)
                    } else if (epoch <= todayEpoch) {
                        missed++
                        if (epoch < todayEpoch || TodoCodec.intervalEnded(item, day, nowMillis)) {
                            passedUncompleted++
                        }
                    }
                }
            }
            if (dayDue > 0) daysWithDue++
            if (dayCompleted > 0) activeDays++
            if (dayDue > 0 || dayCompleted > 0) dataDays++
            day = day.plusDays(1)
        }

        val created = items.count {
            val date = Instant.ofEpochMilli(it.createdAtEpochMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            !date.isBefore(from) && !date.isAfter(to)
        }

        val currentStreak = streakEndingAt(items, from, to)
        val bestStreak = bestStreakIn(items, from, to)

        val skills = skillsCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)
            .map { it.key }

        // v2 score, identical semantics to the on-screen Weekly Score: every
        // component must have something to measure, excluded components rescale
        // the rest to 100, and nothing earns free points for being empty. A
        // still-pending today simply contributes 0 — never a bonus.
        val closed = closedCompleted + passedUncompleted
        val score = if (completed + missed > 0) {
            val completionRaw = 55f * (doneWeight.toFloat() / dueWeight)
            val consistencyRaw = if (daysWithDue > 0) {
                20f * (activeDays.toFloat() / daysWithDue)
            } else 0f
            val streakRaw = 15f * (currentStreak.coerceAtMost(7).toFloat() / 7f)
            val timelinessRaw = if (closed > 0) {
                10f * (1f - passedUncompleted.toFloat() / closed)
            } else 0f
            val includedWeight = 55 + 20 + 15 + (if (closed > 0) 10 else 0)
            val scale = 100f / includedWeight
            (scale * (completionRaw + consistencyRaw + streakRaw + timelinessRaw))
                .roundToInt().coerceIn(0, 100)
        } else null

        val heatmap = buildHeatmap(items, kind, from, to, todayEpoch, nowMillis)

        return CardStats(
            from = from,
            to = to,
            rangeKind = kind,
            created = created,
            completed = completed,
            missed = missed,
            activeDays = activeDays,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            score = score,
            skills = skills,
            heatmap = heatmap,
            firstWeek = dataDays < 7
        )
    }

    /**
     * The day dots, oldest first: the FULL current week (Mon..Sun, future
     * days gray) for the week view; the trailing min(30, range length) days
     * for every other range.
     */
    private fun buildHeatmap(
        items: List<TodoItem>,
        kind: RangeKind,
        from: LocalDate,
        to: LocalDate,
        todayEpoch: Long,
        nowMillis: Long
    ): List<HeatDay> {
        val days = if (kind == RangeKind.WEEK) {
            val weekStart = TodoStats.mondayOf(to)
            (0L..6L).map { weekStart.plusDays(it) }
        } else {
            val n = 30
            val list = ArrayList<LocalDate>()
            var d = to
            while (list.size < n && !d.isBefore(from)) {
                list.add(d)
                d = d.minusDays(1)
            }
            list.reversed()
        }
        return days.map { date ->
            val epoch = date.toEpochDay()
            val anyCompleted = items.any { TodoCodec.completedOn(it, date) }
            val anyDue = items.any { TodoCodec.isActiveOn(it, date) }
            val heat = when {
                anyCompleted -> Heat.DONE
                anyDue && (epoch < todayEpoch ||
                    items.any { TodoCodec.intervalEnded(it, date, nowMillis) }) -> Heat.MISSED
                else -> Heat.SCHEDULED
            }
            HeatDay(date, heat)
        }
    }

    /**
     * Consecutive days with at least one completion, ending at [to] — or the
     * day before it when [to] itself has none yet (an unfinished day must not
     * break the streak, mirroring [TodoStats.streak]).
     */
    private fun streakEndingAt(items: List<TodoItem>, from: LocalDate, to: LocalDate): Int {
        var day = to
        if (items.none { TodoCodec.completedOn(it, day) }) day = day.minusDays(1)
        var count = 0
        while (!day.isBefore(from) && items.any { TodoCodec.completedOn(it, day) }) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** Longest run of consecutive days with at least one completion. */
    private fun bestStreakIn(items: List<TodoItem>, from: LocalDate, to: LocalDate): Int {
        var best = 0
        var run = 0
        var day = from
        while (!day.isAfter(to)) {
            run = if (items.any { TodoCodec.completedOn(it, day) }) run + 1 else 0
            if (run > best) best = run
            day = day.plusDays(1)
        }
        return best
    }
}
