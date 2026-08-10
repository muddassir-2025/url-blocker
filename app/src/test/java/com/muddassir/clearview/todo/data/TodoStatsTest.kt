package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Monday, 2026-08-10 — "today" for every stats assertion. */
private val TODAY: LocalDate = LocalDate.of(2026, 8, 10)

/** Epoch millis for [date] at [hour]:00 in the system zone. */
private fun at(date: LocalDate, hour: Int): Long =
    date.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()

private fun item(
    id: String,
    start: LocalDate,
    end: LocalDate? = null,
    days: Set<Int>? = null,
    type: TodoType = TodoType.TEMPORARY,
    strictInterval: Boolean = false,
    timeStart: Int? = null,
    timeEnd: Int? = null,
    completions: Map<Long, Long> = emptyMap()
): TodoItem = TodoItem(
    id = id,
    title = id,
    type = type,
    startDateEpochDay = start.toEpochDay(),
    endDateEpochDay = end?.toEpochDay(),
    scheduledDays = days,
    strictInterval = strictInterval,
    timeStartMinutes = timeStart,
    timeEndMinutes = timeEnd,
    completions = completions
)

/** Epoch millis for [date] at [minutes] past midnight (system zone). */
private fun atMinutes(date: LocalDate, minutes: Int): Long =
    date.atTime(minutes / 60, minutes % 60)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

class TodoStatsTest {

    private val mon = TODAY
    private val tue = TODAY.plusDays(1)
    private val wed = TODAY.plusDays(2)
    private val sat = TODAY.plusDays(5)
    private val sun = TODAY.plusDays(6)

    private val items = listOf(
        item("t1", start = mon, end = mon, completions = mapOf(mon.toEpochDay() to at(mon, 8))),
        item(
            "t2", type = TodoType.PERMANENT, start = mon, days = setOf(1, 3, 6, 7),
            completions = mapOf(
                mon.toEpochDay() to at(mon, 9),
                wed.toEpochDay() to at(wed, 20),
                sat.toEpochDay() to at(sat, 23)
            )
        ),
        item("t3", type = TodoType.PERMANENT, start = mon, days = setOf(2, 4)),
        // Archived (last week) but still contributes history.
        item("t4", start = mon.minusDays(6), end = mon.minusDays(4),
            completions = mapOf(mon.minusDays(5).toEpochDay() to at(mon.minusDays(5), 10)))
    )

    @Test
    fun `weekly due and completed counts only include applicable days`() {
        val stats = TodoStats.weekStats(items, TODAY)
        assertEquals(7, stats.days.size)
        assertEquals(mon, stats.days.first().date)
        assertEquals(sun, stats.days.last().date)

        assertEquals(2, stats.days[0].due)      // Mon: t1 + t2
        assertEquals(2, stats.days[0].completed)
        // Days after today are EMPTY — a future schedule is never a bar.
        assertEquals(0, stats.days[1].due)      // Tue (future)
        assertEquals(0, stats.days[2].due)      // Wed (future)
        assertEquals(0, stats.days[4].due)      // Fri (future)
        assertEquals(2, stats.due)
        assertEquals(2, stats.completed)
    }

    @Test
    fun `score is null without due todos and positive otherwise`() {
        assertNull(TodoStats.weekStats(emptyList(), TODAY).score)
        val stats = TodoStats.weekStats(items, TODAY)
        assertNotNull(stats.score)
        assertTrue(stats.score!! in 1..100)
        // v2: Completion 55 + Consistency 20 + Streak 15×(1/7) — Timeliness is
        // EXCLUDED (nothing closed yet this week), so the 90 base rescales:
        // 100/90 × (55 + 20 + 2.14) ≈ 86.
        assertEquals(86, stats.score)
    }

    @Test
    fun `score breakdown is explainable and sums to the total`() {
        val stats = TodoStats.weekStats(items, TODAY)
        val b = stats.breakdown!!
        // Timeliness excluded (nothing closed yet) → 90 rescales to 100.
        assertEquals(61, b.completion)     // 100/90 × 55 × 2/2
        assertEquals(22, b.consistency)    // 100/90 × 20 × 1/1
        assertEquals(2, b.streak)          // 100/90 × 15 × 1/7
        assertEquals(0, b.timeliness)      // excluded → no points, no max
        assertEquals(0, b.timelinessMax)
        assertEquals(0, b.closedItems)
        assertEquals(61, b.completionMax)
        assertEquals(22, b.consistencyMax)
        assertEquals(17, b.streakMax)
        assertEquals(0, b.overdueCount)
        assertEquals(0, b.missedCount)
        assertEquals(86, b.total)
    }

    @Test
    fun `overdue days reduce the score via completion and timeliness`() {
        // Permanent daily todo due Mon..Wed; completed only Monday.
        val items = listOf(
            item("p", type = TodoType.PERMANENT, start = mon,
                completions = mapOf(mon.toEpochDay() to at(mon, 8)))
        )
        val today = wed // Wednesday
        val stats = TodoStats.weekStats(items, today)
        val b = stats.breakdown!!
        assertEquals(1, b.overdueCount)   // Tuesday passed uncompleted
        assertEquals(0, b.missedCount)
        // v2: the missed occurrence is reflected INSIDE the components — no
        // separate penalty lines. Closed items = Mon (done) + Tue (missed);
        // Timeliness = 10 × (1 − 1/2) = 5.
        assertEquals(2, b.closedItems)
        assertEquals(5, b.timeliness)
        // Completion 55×(2/6)=18.33 + Consistency 20×(1/3)=6.67 + Streak 0
        // (yesterday had no completion — the streak must end today or
        // yesterday) + Timeliness 5 = 30.
        assertEquals(
            "completion=${b.completion}/${b.completionMax} consistency=${b.consistency}/${b.consistencyMax} " +
                "streak=${b.streak}/${b.streakMax} streakDays=${b.streakDays} timeliness=${b.timeliness}/${b.timelinessMax} " +
                "dueW=${b.dueWeight} doneW=${b.doneWeight} score=${stats.score}",
            30, stats.score
        )
    }

    @Test
    fun `expired temporaries count as missed not overdue`() {
        // A temp todo that ended Tuesday is archived by Wednesday — its
        // uncompleted days are MISSED, never overdue.
        val items = listOf(
            item("t", start = mon, end = tue)
        )
        val today = wed
        val stats = TodoStats.weekStats(items, today)
        val b = stats.breakdown!!
        assertEquals(0, b.overdueCount)
        assertEquals(2, b.missedCount)
        // Both closed items missed → Timeliness = 10 × (1 − 2/2) = 0.
        assertEquals(2, b.closedItems)
        assertEquals(0, b.timeliness)
        // Nothing was completed → every component is 0; no free points.
        assertEquals(0, stats.score)
    }

    @Test
    fun `best day picks the highest completion rate with count tiebreak`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // Only Mon is applicable (2/2) — the best (and only) measurable day.
        assertEquals(mon, stats.bestDay?.date)
        assertEquals(1.0f, stats.bestDay!!.rate)
    }

    @Test
    fun `improvement compares against last week and first week has none`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // This week 2/2 = 100%; last week t4 completed 1 of 3 ≈ 33% → +66 points.
        assertEquals(66, stats.improvementPoints)
        assertFalse(stats.firstWeek)

        // A single-week user has no previous data → first-week message instead.
        val fresh = listOf(
            item("a", start = mon, end = mon, completions = mapOf(mon.toEpochDay() to 1L))
        )
        val first = TodoStats.weekStats(fresh, TODAY)
        assertTrue(first.firstWeek)
        assertNull(first.improvementPoints)
    }

    @Test
    fun `most productive time is a two hour window`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // Mon 8am + Mon 9am → the 8-10 AM window wins.
        assertEquals(8 to 10, stats.mostProductiveWindow)
    }

    @Test
    fun `streak counts consecutive completed days ending today or yesterday`() {
        // Completions on Mon, Wed, Sat this week (+ last Wed). Today (Mon) has
        // one → streak = 1 (Mon), since Sunday has none.
        assertEquals(1, TodoStats.streak(items, TODAY))
    }

    @Test
    fun `streak continues when today already has a completion`() {
        val done = listOf(
            item("a", start = TODAY.minusDays(2), end = TODAY, completions = mapOf(
                TODAY.minusDays(2).toEpochDay() to 1L,
                TODAY.minusDays(1).toEpochDay() to 2L,
                TODAY.toEpochDay() to 3L
            ))
        )
        assertEquals(3, TodoStats.streak(done, TODAY))
    }

    @Test
    fun `active days counts only days with completions`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // Only Monday is applicable and completed this week.
        assertEquals(1, stats.activeDays)
    }

    @Test
    fun `future todos never affect this weeks applicable stats`() {
        val future = listOf(
            item("f", start = TODAY.plusDays(1), end = TODAY.plusDays(1)),  // due tomorrow
            item("p", type = TodoType.PERMANENT, start = TODAY, days = setOf(2)) // due Tue
        )
        val stats = TodoStats.weekStats(future, TODAY)
        assertEquals(0, stats.due)
        assertEquals(0, stats.completed)
        assertNull(stats.score)
        assertNull(stats.bestDay)
        assertEquals(0, stats.remainingToday)

        // The calendar still SHOWS the future schedule (info, not progress):
        // 1 occurrence tomorrow + 3 future Tuesdays this month.
        val month = TodoStats.monthStats(future, TODAY)
        assertEquals(4, month.futureScheduled)
        assertEquals(0, month.due)
        assertEquals(0, month.percent)
    }

    @Test
    fun `closed strict window today counts as overdue and not remaining`() {
        // A strict-interval todo with a window that ended at 11:00 — and it is
        // now 11:01. The day is due and cannot be redone, so it counts as
        // overdue (not remaining) the moment the window closes.
        val strict = item(
            "s", start = mon,
            strictInterval = true, timeStart = 9 * 60, timeEnd = 11 * 60
        )
        val after = atMinutes(mon, 11 * 60 + 1)
        val stats = TodoStats.weekStats(listOf(strict), TODAY, after)
        assertEquals(1, stats.due)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.remainingToday)
        assertEquals(1, stats.overdueCount)
        assertEquals(0, stats.missedCount)
        val b = stats.breakdown!!
        // The closed window is one closed item that was missed → Timeliness 0.
        assertEquals(1, b.closedItems)
        assertEquals(0, b.timeliness)
        // 1 due, 0 done, 0 active days, 0 streak → every component 0.
        assertEquals(0, stats.score)

        // While the window is still OPEN, the same todo is actionable — and
        // Timeliness is excluded (nothing has reached its due time yet).
        val open = TodoStats.weekStats(listOf(strict), TODAY, atMinutes(mon, 10 * 60))
        assertEquals(1, open.remainingToday)
        assertEquals(0, open.overdueCount)
        assertEquals(0, open.breakdown!!.timelinessMax)
        assertEquals(0, open.score) // still pending → 0/100, not a free score
    }

    @Test
    fun `month stats count only applicable days`() {
        val items = listOf(
            item("a", start = TODAY, end = TODAY),
            item("b", start = TODAY.plusDays(5), end = TODAY.plusDays(5)) // future
        )
        val month = TodoStats.monthStats(items, TODAY)
        assertEquals(1, month.due)
        assertEquals(0, month.completed)
        assertEquals(1, month.futureScheduled)
        assertEquals(0, month.percent)
    }
}
