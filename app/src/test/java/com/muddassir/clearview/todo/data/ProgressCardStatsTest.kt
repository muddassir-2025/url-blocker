package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoPriority
import com.muddassir.clearview.todo.model.TodoType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCardStatsTest {

    // 2026-08-12 is a Wednesday → the current week runs Mon 2026-08-10 …
    // Sun 08-16, and the applicable days are 08-10 (Mon), 08-11 (Tue), 08-12 (Wed).
    private val today: LocalDate = LocalDate.of(2026, 8, 12)
    private val monday: LocalDate = TodoStats.mondayOf(today) // 2026-08-10

    private fun millis() = 1L

    private fun item(
        id: String,
        title: String = "Todo",
        start: LocalDate = monday,
        end: LocalDate? = null,
        permanent: Boolean = true,
        completions: Map<LocalDate, Long> = emptyMap(),
        priority: TodoPriority = TodoPriority.NORMAL,
        createdAt: LocalDate = start
    ): TodoItem = TodoItem(
        id = id,
        title = title,
        type = if (permanent) TodoType.PERMANENT else TodoType.TEMPORARY,
        startDateEpochDay = start.toEpochDay(),
        endDateEpochDay = end?.toEpochDay(),
        scheduledDays = null,
        completions = completions.entries.associate { it.key.toEpochDay() to it.value },
        priority = priority,
        createdAtEpochMillis = createdAt.atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    )

    // ── Ranges ──────────────────────────────────────────────────────

    @Test
    fun `resolveRange week is monday through today`() {
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.WEEK, emptyList(), today
        )
        assertEquals(monday, from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange month is the first of the month through today`() {
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.MONTH, emptyList(), today
        )
        assertEquals(LocalDate.of(2026, 8, 1), from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange all time starts at the earliest todo`() {
        val items = listOf(
            item("a", start = LocalDate.of(2026, 5, 2)),
            item("b", start = LocalDate.of(2026, 1, 20))
        )
        val (from, to) = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.ALL, items, today
        )
        assertEquals(LocalDate.of(2026, 1, 20), from)
        assertEquals(today, to)
    }

    @Test
    fun `resolveRange custom clamps to today and collapses inverted ranges`() {
        val future = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.CUSTOM, emptyList(), today,
            customFrom = LocalDate.of(2026, 7, 1), customTo = LocalDate.of(2026, 12, 31)
        )
        assertEquals(LocalDate.of(2026, 7, 1), future.first)
        assertEquals(today, future.second)

        val inverted = ProgressCardStats.resolveRange(
            ProgressCardStats.RangeKind.CUSTOM, emptyList(), today,
            customFrom = LocalDate.of(2026, 8, 11), customTo = LocalDate.of(2026, 8, 1)
        )
        assertEquals(LocalDate.of(2026, 8, 1), inverted.first)
        assertEquals(LocalDate.of(2026, 8, 1), inverted.second)
    }

    // ── Counts & percentages ────────────────────────────────────────

    @Test
    fun `week counts completed and missed occurrences`() {
        val items = listOf(
            item("q", "Read Qur'an", completions = mapOf(
                monday to millis(),
                monday.plusDays(1) to millis()
            ))
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(2, stats.completed)
        assertEquals(1, stats.missed) // today still uncompleted
        assertEquals(3, stats.due)
        assertEquals(66, stats.percent)
        assertEquals(2, stats.activeDays)
    }

    @Test
    fun `created counts todos created inside the range only`() {
        val items = listOf(
            item("a", createdAt = monday),               // in range
            item("b", createdAt = today),                // in range
            item("c", createdAt = monday.minusDays(3))   // previous week → out of range
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(2, stats.created)
    }

    // ── Streaks ─────────────────────────────────────────────────────

    @Test
    fun `current streak ends at to and skips an unfinished final day`() {
        val completions = mapOf(
            monday to millis(),
            monday.plusDays(1) to millis()
        )
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.WEEK, today
        )
        // Today (Wed) has no completion → the streak ends at Tue.
        assertEquals(2, stats.currentStreak)
        assertEquals(2, stats.bestStreak)
    }

    @Test
    fun `current streak survives a completed today`() {
        val completions = mapOf(
            monday to millis(),
            monday.plusDays(1) to millis(),
            today to millis()
        )
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `best streak finds the longest run while current streak ends at today`() {
        // Custom range Aug 1..12. Completed Aug 1-3 (run of 3), then a long
        // gap, then Aug 10-11 (run of 2). Today (Aug 12) is uncompleted, so
        // the CURRENT streak is 2 but the BEST is 3.
        val from = LocalDate.of(2026, 8, 1)
        val completions = mapOf(
            from to millis(),
            from.plusDays(1) to millis(),
            from.plusDays(2) to millis(),
            from.plusDays(9) to millis(),  // Aug 10
            from.plusDays(10) to millis()  // Aug 11
        )
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = from, customTo = today
        )
        assertEquals(3, stats.bestStreak)
        assertEquals(2, stats.currentStreak)
    }

    // ── Skills ──────────────────────────────────────────────────────

    @Test
    fun `skills are distinct titles with completions, most frequent first`() {
        val items = listOf(
            item("a", title = "Read Qur'an", completions = mapOf(monday to millis(), today to millis())),
            item("b", title = "Fajr Salah", completions = mapOf(monday.plusDays(1) to millis())),
            item("c", title = "Leetcode DSA", completions = emptyMap())
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(listOf("Read Qur'an", "Fajr Salah"), stats.skills)
    }

    @Test
    fun `skills are capped at six`() {
        val items = (1..8).map { i ->
            item("$i", title = "Habit $i", completions = mapOf(monday to millis()))
        }
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(6, stats.skills.size)
    }

    // ── Heatmap ─────────────────────────────────────────────────────

    @Test
    fun `heatmap week is 7 days with done missed and scheduled dots`() {
        val items = listOf(
            item("q", completions = mapOf(monday to millis()))
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(7, stats.heatmap.size)
        assertEquals(ProgressCardStats.Heat.DONE, stats.heatmap.first().heat)
        // 2026-08-13 (Thu) is still future in the week range → scheduled.
        val future = stats.heatmap.last()
        assertTrue(future.date.isAfter(today))
        assertEquals(ProgressCardStats.Heat.SCHEDULED, future.heat)
    }

    @Test
    fun `heatmap long ranges are capped at 30 days`() {
        // A todo that started 45 days ago makes the ALL range longer than 30
        // days, so the strip shows the trailing 30 days.
        val items = listOf(
            item("q", start = today.minusDays(45), permanent = true, completions = emptyMap())
        )
        val stats = ProgressCardStats.compute(
            items, ProgressCardStats.RangeKind.ALL, today
        )
        assertEquals(30, stats.heatmap.size)
        assertEquals(today.minusDays(29), stats.heatmap.first().date)
        assertEquals(today, stats.heatmap.last().date)
    }

    @Test
    fun `heatmap marks missed days red for passed uncompleted occurrences`() {
        // Permanent daily todo with NO completions at all — every past day in
        // the week was missed, today is still actionable (scheduled).
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = emptyMap())),
            ProgressCardStats.RangeKind.WEEK, today
        )
        val past = stats.heatmap.filter { it.date.isBefore(today) }
        assertTrue(past.isNotEmpty())
        assertTrue(past.all { it.heat == ProgressCardStats.Heat.MISSED })
        val todayDot = stats.heatmap.first { it.date == today }
        assertEquals(ProgressCardStats.Heat.SCHEDULED, todayDot.heat)
    }

    // ── First week & score ──────────────────────────────────────────

    @Test
    fun `firstWeek is true with fewer than seven data days`() {
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = mapOf(monday to millis()))),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertTrue(stats.firstWeek)
    }

    @Test
    fun `score is null without any occurrences and full when everything done`() {
        val none = ProgressCardStats.compute(emptyList(), ProgressCardStats.RangeKind.WEEK, today)
        assertNull(none.score)

        // A 12-day custom range with EVERY day completed gives a streak of 7
        // (the cap) → Completion 55 + Consistency 20 + Streak 15 + Timeliness
        // 10 (11 closed items, none missed) = 100.
        val from = LocalDate.of(2026, 8, 1)
        val completions = (0L..11L).associate { from.plusDays(it) to millis() }
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.CUSTOM, today,
            customFrom = from, customTo = today
        )
        assertEquals(100, stats.score)
    }

    @Test
    fun `zero completions with due todos scores zero not a free 30`() {
        // The v2 core rule: a component only scores when there is something to
        // measure. 1 due Medium todo, not yet overdue, 0 completed → every
        // included component is 0. The OLD algorithm gifted 30 for doing
        // nothing (5 for having no high-priority todos + 25 for nothing being
        // overdue) — that inflated score was the reported bug.
        val stats = ProgressCardStats.compute(
            listOf(item("q", "Read Qur'an")),
            ProgressCardStats.RangeKind.WEEK, today
        )
        assertEquals(0, stats.score)
    }

    @Test
    fun `completing a high priority todo earns more than a low one`() {
        // Two daily todos (one Low, one High), only Monday completed. Priority
        // is a multiplier INSIDE the single weighted Completion bucket, so the
        // same amount of work earns more when the completed todo was High.
        fun weekWith(priority: TodoPriority, completions: Map<LocalDate, Long>) =
            ProgressCardStats.compute(
                listOf(
                    item("lo", priority = TodoPriority.LOW),
                    item("hi", priority = TodoPriority.HIGH, completions = completions)
                ),
                ProgressCardStats.RangeKind.WEEK, today
            )
        val highDone = weekWith(TodoPriority.HIGH, mapOf(monday to millis()))
        val highScore = highDone.score!!
        // Same shape but the completion went to the LOW todo instead.
        val lowDone = ProgressCardStats.compute(
            listOf(
                item("lo", priority = TodoPriority.LOW, completions = mapOf(monday to millis())),
                item("hi", priority = TodoPriority.HIGH)
            ),
            ProgressCardStats.RangeKind.WEEK, today
        ).score!!
        assertTrue(
            "high-done ($highScore) should outscore low-done ($lowDone)",
            highScore > lowDone
        )
    }

    @Test
    fun `score drops when occurrences were missed`() {
        // 1 done Monday; Tuesday passed uncompleted (a genuine miss); today is
        // still pending (costs nothing — the miss is reflected inside
        // Completion and Timeliness).
        val completions = mapOf(monday to millis())
        val stats = ProgressCardStats.compute(
            listOf(item("q", completions = completions)),
            ProgressCardStats.RangeKind.WEEK, today
        )
        // The incomplete figure counts Tuesday (missed) AND today (pending).
        assertEquals(2, stats.missed)
        val score = stats.score!!
        assertTrue("score should be well below 100 (was $score)", score < 100)
        assertTrue(score > 0)
    }
}
