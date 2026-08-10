package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoPriority
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Monday, 2026-08-10 — used as "today" for all date-relative assertions. */
private val TODAY: LocalDate = LocalDate.of(2026, 8, 10)

private fun item(
    id: String,
    type: TodoType = TodoType.TEMPORARY,
    start: LocalDate = TODAY,
    end: LocalDate? = null,
    days: Set<Int>? = null,
    time: Int? = null,
    timeStart: Int? = null,
    timeEnd: Int? = null,
    reminder: ReminderConfig? = null,
    priority: TodoPriority = TodoPriority.NORMAL,
    createdAt: Long = 0L,
    strictInterval: Boolean = false,
    completions: Map<Long, Long> = emptyMap()
): TodoItem = TodoItem(
    id = id,
    title = id,
    type = type,
    startDateEpochDay = start.toEpochDay(),
    endDateEpochDay = end?.toEpochDay(),
    scheduledDays = days,
    timeMinutes = time,
    timeStartMinutes = timeStart,
    timeEndMinutes = timeEnd,
    reminder = reminder,
    priority = priority,
    createdAtEpochMillis = createdAt,
    strictInterval = strictInterval,
    completions = completions
)

/** Epoch millis for [date] at [minutes] past midnight (system zone). */
private fun atMinutes(date: LocalDate, minutes: Int): Long =
    date.atTime(minutes / 60, minutes % 60)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

class TodoCodecTest {

    @Test
    fun `encode decode round-trips every field`() {
        val original = listOf(
            item(
                id = "t1",
                type = TodoType.PERMANENT,
                start = TODAY,
                days = setOf(1, 3, 5),
                time = 20 * 60,
                reminder = ReminderConfig(listOf(9 * 60, 13 * 60, 20 * 60), repeat = true),
                priority = TodoPriority.HIGH,
                completions = mapOf(TODAY.toEpochDay() to 1_700_000_000_000L)
            ),
            item(id = "t2", start = TODAY, end = TODAY.plusDays(2))
        )
        val decoded = TodoCodec.decode(TodoCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `reminder enabled flag round-trips`() {
        val off = item(
            id = "t1",
            start = TODAY,
            reminder = ReminderConfig(listOf(9 * 60), repeat = true, enabled = false)
        )
        val decoded = TodoCodec.decode(TodoCodec.encode(listOf(off))).first()
        assertFalse(decoded.reminder!!.enabled)
        assertEquals(listOf(9 * 60), decoded.reminder!!.timesMinutes)
        assertTrue(decoded.reminder!!.repeat)
    }

    @Test
    fun `reminder alarm style flag round-trips`() {
        val alarm = item(
            id = "t1",
            start = TODAY,
            reminder = ReminderConfig(listOf(9 * 60), repeat = true, enabled = true, asAlarm = true)
        )
        val decodedAlarm = TodoCodec.decode(TodoCodec.encode(listOf(alarm))).first()
        assertTrue(decodedAlarm.reminder!!.asAlarm)
        assertTrue(decodedAlarm.reminder!!.enabled)

        // The default (notification style) stays false — and old JSON without
        // the key decodes to the same default.
        val note = item(
            id = "t2",
            start = TODAY,
            reminder = ReminderConfig(listOf(20 * 60), repeat = true)
        )
        val decodedNote = TodoCodec.decode(TodoCodec.encode(listOf(note))).first()
        assertFalse(decodedNote.reminder!!.asAlarm)
    }

    @Test
    fun `decode tolerates blank and corrupt input`() {
        assertTrue(TodoCodec.decode(null).isEmpty())
        assertTrue(TodoCodec.decode("").isEmpty())
        assertTrue(TodoCodec.decode("not json").isEmpty())
    }

    @Test
    fun `temporary todo is active only within its period`() {
        val oneDay = item("a", start = TODAY, end = TODAY)
        assertTrue(TodoCodec.isActiveOn(oneDay, TODAY))
        assertFalse(TodoCodec.isActiveOn(oneDay, TODAY.minusDays(1)))
        assertFalse(TodoCodec.isActiveOn(oneDay, TODAY.plusDays(1)))

        val week = item("b", start = TODAY, end = TODAY.plusDays(6))
        assertTrue(TodoCodec.isActiveOn(week, TODAY.plusDays(6)))
        assertFalse(TodoCodec.isActiveOn(week, TODAY.plusDays(7)))
    }

    @Test
    fun `permanent todo honors scheduled weekdays and never archives`() {
        val mwf = item("c", type = TodoType.PERMANENT, start = TODAY, days = setOf(1, 3, 5))
        assertTrue(TodoCodec.isActiveOn(mwf, TODAY))                 // Monday
        assertTrue(TodoCodec.isActiveOn(mwf, TODAY.plusDays(2)))     // Wednesday
        assertFalse(TodoCodec.isActiveOn(mwf, TODAY.plusDays(1)))    // Tuesday
        assertFalse(TodoCodec.isArchived(mwf, TODAY.plusDays(400)))
    }

    @Test
    fun `temporary todo archives after its period ends`() {
        val past = item("d", start = TODAY.minusDays(5), end = TODAY.minusDays(3))
        assertTrue(TodoCodec.isArchived(past, TODAY))
        assertFalse(TodoCodec.isArchived(past, TODAY.minusDays(4)))
    }

    @Test
    fun `toggling adds then removes a completion for that day`() {
        val items = listOf(item("a", start = TODAY, end = TODAY))
        val at = 1_700_000_000_000L

        val (added, nowCompleted) = TodoCodec.toggled(items, "a", TODAY, at)
        assertTrue(nowCompleted)
        assertTrue(TodoCodec.completedOn(added.first(), TODAY))

        val (removed, stillCompleted) = TodoCodec.toggled(added, "a", TODAY, at + 1)
        assertFalse(stillCompleted)
        assertFalse(TodoCodec.completedOn(removed.first(), TODAY))
    }

    @Test
    fun `toggling preserves history from other days`() {
        val items = listOf(
            item("a", start = TODAY.minusDays(1), end = TODAY, completions = mapOf(TODAY.minusDays(1).toEpochDay() to 1L))
        )
        val (updated, _) = TodoCodec.toggled(items, "a", TODAY, 2L)
        assertEquals(2, updated.first().completions.size)
    }

    @Test
    fun `updated keeps applicable completions and createdAt but drops stale ones`() {
        val items = listOf(
            item("a", start = TODAY, end = TODAY, completions = mapOf(TODAY.toEpochDay() to 1L))
        )
        // An edit where today is STILL an applicable day keeps the completion.
        val stillToday = item("a", type = TodoType.PERMANENT, start = TODAY, days = setOf(1))
        val kept = TodoCodec.updated(items, stillToday).first()
        assertEquals(setOf(1), kept.scheduledDays)
        assertEquals(TodoType.PERMANENT, kept.type)
        assertEquals(1L, kept.completions[TODAY.toEpochDay()])
        assertEquals(items.first().createdAtEpochMillis, kept.createdAtEpochMillis)

        // Moving the todo to tomorrow makes today's completion stale — it is
        // dropped, so the todo can never appear struck-through in another tab.
        val moved = TodoCodec.updated(items, item("a", start = TODAY.plusDays(1), end = TODAY.plusDays(1)))
            .first()
        assertTrue(moved.completions.isEmpty())
    }

    @Test
    fun `decode drops completions recorded on days the todo is not active on`() {
        // The user's exact scenario: a temporary todo set for TOMORROW that
        // carries a stale "completed today" entry (e.g. left behind after the
        // plan was moved). It must never survive a load, or the card would
        // render struck-through in All / Temporary.
        val tomorrowStale = item(
            id = "t",
            start = TODAY.plusDays(1),
            end = TODAY.plusDays(1),
            completions = mapOf(TODAY.toEpochDay() to 1L)
        )
        val healed = TodoCodec.decode(TodoCodec.encode(listOf(tomorrowStale))).first()
        assertTrue(healed.completions.isEmpty())

        // A legit completion on an ACTIVE day is never touched.
        val today = item(
            id = "ok",
            start = TODAY,
            end = TODAY,
            completions = mapOf(TODAY.toEpochDay() to 2L)
        )
        val kept = TodoCodec.decode(TodoCodec.encode(listOf(today))).first()
        assertEquals(1, kept.completions.size)

        // Same for a permanent todo: a completion on a day outside its
        // scheduled weekdays is stale and dropped.
        val mwf = item(
            id = "p",
            type = TodoType.PERMANENT,
            start = TODAY,
            days = setOf(1, 3, 5),
            completions = mapOf(TODAY.plusDays(1).toEpochDay() to 3L) // Tuesday
        )
        val healedPerm = TodoCodec.decode(TodoCodec.encode(listOf(mwf))).first()
        assertTrue(healedPerm.completions.isEmpty())
    }

    @Test
    fun `filters select the right working sets`() {
        val completedAt = 1_700_000_000_000L
        val items = listOf(
            item("a", start = TODAY, end = TODAY),                                       // temp today, open
            item("b", start = TODAY, end = TODAY, completions = mapOf(TODAY.toEpochDay() to completedAt)), // temp today, done
            item("c", type = TodoType.PERMANENT, start = TODAY, days = setOf(1, 3, 5)),  // due today (Mon)
            item("d", type = TodoType.PERMANENT, start = TODAY, days = setOf(4)),        // due Thursday
            item("e", start = TODAY, end = TODAY.plusDays(2), completions = mapOf(TODAY.plusDays(1).toEpochDay() to completedAt)), // active, done later
            item("f", start = TODAY.minusDays(6), end = TODAY.minusDays(4)),             // archived, missed
            item("g", start = TODAY.minusDays(2), end = TODAY.minusDays(1),
                completions = mapOf(TODAY.minusDays(1).toEpochDay() to completedAt))      // archived, completed once
        )

        assertEquals(listOf("a", "b", "c", "d", "e"), TodoCodec.filter(items, TodoFilter.ALL, TODAY).map { it.id })
        assertEquals(listOf("a", "b", "c", "e"), TodoCodec.filter(items, TodoFilter.TODAY, TODAY).map { it.id })
        assertEquals(listOf("d"), TodoCodec.filter(items, TodoFilter.UPCOMING, TODAY).map { it.id })
        // History includes ARCHIVED items with any occurrence, AND today's
        // completions ("b" is completed today — it must show up immediately).
        // "e"'s completion is on a FUTURE day, so it never counts.
        assertEquals(listOf("b", "f", "g"), TodoCodec.filter(items, TodoFilter.HISTORY, TODAY).map { it.id })
        assertEquals(listOf("a", "b", "e"), TodoCodec.filter(items, TodoFilter.TEMPORARY, TODAY).map { it.id })
        assertEquals(listOf("c", "d"), TodoCodec.filter(items, TodoFilter.PERMANENT, TODAY).map { it.id })
    }

    @Test
    fun `a todo completed today appears in history immediately`() {
        val at = 1_700_000_000_000L
        // Completed today → Completed history right away.
        val doneToday = item(
            "doneToday", start = TODAY, end = TODAY,
            completions = mapOf(TODAY.toEpochDay() to at)
        )
        // Due today but NOT yet completed → still actionable, never missed.
        val openToday = item("openToday", start = TODAY, end = TODAY)
        // A todo whose due date PASSED uncompleted → appears as missed.
        val missedYesterday = item("missedYesterday", start = TODAY.minusDays(1), end = TODAY.minusDays(1))

        val items = listOf(doneToday, openToday, missedYesterday)
        assertEquals(
            listOf("doneToday", "missedYesterday"),
            TodoCodec.filter(items, TodoFilter.HISTORY, TODAY).map { it.id }
        )
        assertEquals(
            listOf("doneToday"),
            TodoCodec.historyCompleted(items, TODAY).map { it.item.id }
        )
        assertEquals(
            listOf("missedYesterday"),
            TodoCodec.historyMissed(items, TODAY).map { it.item.id }
        )
        assertEquals(1 to 0, TodoCodec.occurrenceCounts(doneToday, TODAY))
        assertEquals(0 to 0, TodoCodec.occurrenceCounts(openToday, TODAY))
        assertEquals(0 to 1, TodoCodec.occurrenceCounts(missedYesterday, TODAY))
    }

    @Test
    fun `history separates completed and missed past occurrences`() {
        val at = 1_700_000_000_000L
        val items = listOf(
            // 3-day temp: completed only on its last day → 1 completed, 2 missed.
            item("mixed", start = TODAY.minusDays(3), end = TODAY.minusDays(1),
                completions = mapOf(TODAY.minusDays(1).toEpochDay() to at)),
            // Single-day temp completed on its only day → fully completed.
            item("done", start = TODAY.minusDays(2), end = TODAY.minusDays(2),
                completions = mapOf(TODAY.minusDays(2).toEpochDay() to at)),
            // Due tomorrow → never in history.
            item("future", start = TODAY.plusDays(1), end = TODAY.plusDays(1))
        )
        assertEquals(listOf("mixed", "done"), TodoCodec.filter(items, TodoFilter.HISTORY, TODAY).map { it.id })
        assertEquals(
            listOf("mixed", "done"),
            TodoCodec.historyCompleted(items, TODAY).map { it.item.id }
        )
        assertEquals(listOf("mixed"), TodoCodec.historyMissed(items, TODAY).map { it.item.id })
        assertEquals(1 to 2, TodoCodec.occurrenceCounts(items[0], TODAY))
        assertEquals(1 to 0, TodoCodec.occurrenceCounts(items[1], TODAY))
    }

    @Test
    fun `clear only hides history cards and never touches completion data`() {
        val at = 1_700_000_000_000L
        val items = listOf(
            item("done", start = TODAY.minusDays(1), end = TODAY.minusDays(1),
                completions = mapOf(TODAY.minusDays(1).toEpochDay() to at)),
            item("doneToday", start = TODAY, end = TODAY,
                completions = mapOf(TODAY.toEpochDay() to at)),
            item("missed", start = TODAY.minusDays(1), end = TODAY.minusDays(1)),
            item("active", start = TODAY, end = TODAY)
        )

        // "Clear" (Completed) HIDES the completed cards — every todo AND every
        // completion stays, so today's count / progress / stats keep working.
        val clearedCompleted = TodoCodec.removeCompletedHistory(items, TODAY)
        assertEquals(listOf("done", "doneToday", "missed", "active"), clearedCompleted.map { it.id })
        assertTrue(TodoCodec.historyCompleted(clearedCompleted, TODAY).isEmpty())
        // The KEY invariant: completion data is untouched.
        assertTrue(TodoCodec.completedOn(clearedCompleted.first { it.id == "doneToday" }, TODAY))
        assertEquals(at, clearedCompleted.first { it.id == "done" }.completions[TODAY.minusDays(1).toEpochDay()])
        // Today's progress still counts the cleared completion (1 of the 2
        // todos due today is completed) — same as the progress bar.
        assertEquals(1, TodoStats.dayStats(clearedCompleted, TODAY).completed)
        assertEquals(2, TodoStats.dayStats(clearedCompleted, TODAY).due)
        // The "missed" item is untouched (still in the Incomplete section) and
        // the cleared completed days never resurface as missed — the day counts
        // as NEITHER completed nor missed in the History view.
        assertEquals(listOf("missed"), TodoCodec.historyMissed(clearedCompleted, TODAY).map { it.item.id })
        assertEquals(0 to 0, TodoCodec.occurrenceCounts(clearedCompleted.first { it.id == "done" }, TODAY))

        // "Clear" (Incomplete) HIDES the missed cards — todos and completions
        // stay untouched; only the missed records are hidden.
        val clearedMissed = TodoCodec.removeMissedHistory(items, TODAY)
        assertEquals(listOf("done", "doneToday", "missed", "active"), clearedMissed.map { it.id })
        assertTrue(TodoCodec.historyMissed(clearedMissed, TODAY).isEmpty())
        assertEquals(
            listOf("doneToday", "done"),
            TodoCodec.historyCompleted(clearedMissed, TODAY).map { it.item.id }
        )
        assertEquals(1, TodoStats.dayStats(clearedMissed, TODAY).completed)
        // The clear watermark survives persistence.
        val roundTrip = TodoCodec.decode(TodoCodec.encode(clearedMissed))
        assertEquals(TODAY.toEpochDay() + 1, roundTrip.first { it.id == "missed" }.missedClearedBefore)

        // "Clear All" hides BOTH sections — every todo AND every completion
        // stays; only the History view empties.
        val clearedAll = TodoCodec.clearHistory(items, TODAY)
        assertEquals(listOf("done", "doneToday", "missed", "active"), clearedAll.map { it.id })
        assertTrue(TodoCodec.historySorted(clearedAll, TODAY).isEmpty())
        assertEquals(at, clearedAll.first { it.id == "doneToday" }.completions[TODAY.toEpochDay()])
        assertEquals(1, TodoStats.dayStats(clearedAll, TODAY).completed)
        // Active todos keep working after a clear (still completable today).
        assertTrue(TodoCodec.canCompleteOn(clearedAll.first { it.id == "active" }, TODAY, atMinutes(TODAY, 12 * 60)))
    }

    @Test
    fun `reset wipes the progress and history data but keeps every todo`() {
        val at = 1_700_000_000_000L
        val items = listOf(
            item("done", start = TODAY.minusDays(1), end = TODAY.minusDays(1),
                completions = mapOf(TODAY.minusDays(1).toEpochDay() to at)),
            item("doneToday", start = TODAY, end = TODAY,
                completions = mapOf(TODAY.toEpochDay() to at)),
            item("missed", start = TODAY.minusDays(1), end = TODAY.minusDays(1)),
            item("active", start = TODAY, end = TODAY)
        )

        // Reset is DESTRUCTIVE: the completion records are wiped (today's
        // count -> 0, progress -> 0%, score/stats reset) AND both history
        // watermarks hide every past occurrence — but the todos stay.
        val reset = TodoCodec.resetHistory(items, TODAY)
        assertEquals(listOf("done", "doneToday", "missed", "active"), reset.map { it.id })
        assertTrue(reset.all { it.completions.isEmpty() })
        assertFalse(TodoCodec.completedOn(reset.first { it.id == "doneToday" }, TODAY))
        assertEquals(0, TodoStats.dayStats(reset, TODAY).completed)
        assertTrue(TodoCodec.historySorted(reset, TODAY).isEmpty())
        // Both watermarks hide everything up to today (the History view is
        // empty) while days after the reset count normally.
        assertTrue(
            reset.all {
                it.completedClearedBefore == TODAY.toEpochDay() + 1 &&
                    it.missedClearedBefore == TODAY.toEpochDay() + 1
            }
        )
        // The todos themselves are still fully intact and actionable.
        assertEquals(
            TODAY.minusDays(1).toEpochDay(),
            reset.first { it.id == "done" }.startDateEpochDay
        )
        assertTrue(TodoCodec.canCompleteOn(reset.first { it.id == "active" }, TODAY, atMinutes(TODAY, 12 * 60)))
    }

    @Test
    fun `expired temporary todos leave the active lists whether completed or not`() {
        val at = 1_700_000_000_000L
        // Both expired (ended yesterday): one completed on its only day, one missed.
        val expiredDone = item(
            "expiredDone", start = TODAY.minusDays(1), end = TODAY.minusDays(1),
            completions = mapOf(TODAY.minusDays(1).toEpochDay() to at)
        )
        val expiredMissed = item("expiredMissed", start = TODAY.minusDays(1), end = TODAY.minusDays(1))
        // Still active today.
        val active = item("active", start = TODAY, end = TODAY)
        val items = listOf(expiredDone, expiredMissed, active)

        // Both expired temps are gone from every active list...
        assertEquals(listOf("active"), TodoCodec.filter(items, TodoFilter.ALL, TODAY).map { it.id })
        assertEquals(listOf("active"), TodoCodec.filter(items, TodoFilter.TEMPORARY, TODAY).map { it.id })
        // ...but their history stays for the History tab.
        assertEquals(
            listOf("expiredDone", "expiredMissed"),
            TodoCodec.filter(items, TodoFilter.HISTORY, TODAY).map { it.id }
        )
        assertEquals(1 to 0, TodoCodec.occurrenceCounts(expiredDone, TODAY))
        assertEquals(0 to 1, TodoCodec.occurrenceCounts(expiredMissed, TODAY))
    }

    @Test
    fun `completed strictly adds and never toggles off`() {
        val items = listOf(item("a", start = TODAY, end = TODAY))
        val once = TodoCodec.completed(items, "a", TODAY, 1L)
        assertTrue(TodoCodec.completedOn(once.first(), TODAY))
        val twice = TodoCodec.completed(once, "a", TODAY, 2L)
        assertTrue(TodoCodec.completedOn(twice.first(), TODAY))
        assertEquals(1, twice.first().completions.size)
    }

    @Test
    fun `nextActiveDate finds the next applicable day only`() {
        val tue = item("t", start = TODAY, end = TODAY.plusDays(2))
        assertEquals(TODAY.plusDays(1), TodoCodec.nextActiveDate(tue, TODAY))
        // Permanent Wed+Fri only → the next one is Wednesday.
        val mwf = item("m", type = TodoType.PERMANENT, start = TODAY, days = setOf(3, 5))
        assertEquals(TODAY.plusDays(2), TodoCodec.nextActiveDate(mwf, TODAY))
        // Already expired → never applicable again.
        val ended = item("e", start = TODAY.minusDays(2), end = TODAY.minusDays(1))
        assertNull(TodoCodec.nextActiveDate(ended, TODAY))
    }

    @Test
    fun `smart sort puts today's incomplete todos first`() {
        val completedAt = 1_700_000_000_000L
        val items = listOf(
            item("a", start = TODAY, end = TODAY, time = 400),                                                      // today, open
            item("b", start = TODAY, end = TODAY, time = 600, completions = mapOf(TODAY.toEpochDay() to completedAt)), // today, done
            item("c", type = TodoType.PERMANENT, start = TODAY, days = setOf(4)),                                    // upcoming
            item("d", start = TODAY.plusDays(1), end = TODAY.plusDays(1), time = 900)                                // upcoming
        )
        assertEquals(
            listOf("a", "b", "d", "c"),
            TodoCodec.sorted(items, TodoSort.SMART, TODAY).map { it.id }
        )
    }

    @Test
    fun `sort orders follow their contract`() {
        val items = listOf(
            item("a", start = TODAY, end = TODAY, time = 400, priority = TodoPriority.LOW, createdAt = 3),
            item("b", start = TODAY, end = TODAY, time = 600, priority = TodoPriority.HIGH, createdAt = 1),
            item("c", start = TODAY, end = TODAY, priority = TodoPriority.NORMAL, createdAt = 2)
        )
        assertEquals(listOf("a", "b", "c"), TodoCodec.sorted(items, TodoSort.TIME, TODAY).map { it.id })
        assertEquals(listOf("b", "c", "a"), TodoCodec.sorted(items, TodoSort.PRIORITY, TODAY).map { it.id })
        assertEquals(listOf("b", "c", "a"), TodoCodec.sorted(items, TodoSort.CREATED, TODAY).map { it.id })
    }

    @Test
    fun `schedule labels are human friendly`() {
        assertEquals("Today", TodoCodec.scheduleLabel(item("a", start = TODAY, end = TODAY), TODAY))
        assertEquals("Tomorrow", TodoCodec.scheduleLabel(item("a", start = TODAY.plusDays(1), end = TODAY.plusDays(1)), TODAY))
        assertEquals(
            "Mon, Aug 10",
            TodoCodec.scheduleLabel(item("a", start = TODAY, end = TODAY), TODAY.minusDays(7))
        )
        assertEquals(
            "Mon • Wed • Fri",
            TodoCodec.scheduleLabel(item("a", type = TodoType.PERMANENT, start = TODAY, days = setOf(1, 3, 5)), TODAY)
        )
        assertEquals("Today – Aug 16", TodoCodec.scheduleLabel(item("a", start = TODAY, end = TODAY.plusDays(6)), TODAY))
    }

    @Test
    fun `time range round-trips and labels as a range`() {
        val original = item(
            id = "t1",
            start = TODAY,
            timeStart = 9 * 60,
            timeEnd = 21 * 60,
            reminder = ReminderConfig(listOf(9 * 60, 15 * 60, 21 * 60), repeat = true)
        )
        val decoded = TodoCodec.decode(TodoCodec.encode(listOf(original))).first()
        assertEquals(original, decoded)
        assertEquals(9 * 60, decoded.timeStartMinutes)
        assertEquals(21 * 60, decoded.timeEndMinutes)
        assertEquals("9:00 AM – 9:00 PM", TodoCodec.scheduledTimeLabel(decoded))
    }

    @Test
    fun `rangeTimes spreads evenly across the range`() {
        assertEquals(listOf(540), TodoCodec.rangeTimes(540, 1260, 1))
        assertEquals(listOf(540, 1260), TodoCodec.rangeTimes(540, 1260, 2))
        assertEquals(listOf(540, 900, 1260), TodoCodec.rangeTimes(540, 1260, 3))
        assertEquals(listOf(540, 720, 900, 1080, 1260), TodoCodec.rangeTimes(540, 1260, 5))
        // A tiny range collapses to the distinct whole minutes available.
        assertEquals(listOf(540, 541), TodoCodec.rangeTimes(540, 541, 6))
        assertEquals(listOf(540), TodoCodec.rangeTimes(540, 540, 6))
    }

    @Test
    fun `scheduledTimeLabel handles none single and range`() {
        assertNull(TodoCodec.scheduledTimeLabel(item("a", start = TODAY)))
        assertEquals("8:00 PM", TodoCodec.scheduledTimeLabel(item("a", start = TODAY, time = 20 * 60)))
        assertEquals(
            "9:00 AM – 8:00 PM",
            TodoCodec.scheduledTimeLabel(item("a", start = TODAY, timeStart = 9 * 60, timeEnd = 20 * 60))
        )
    }

    @Test
    fun `strict interval flag round-trips and gates completion`() {
        val strict = item(
            id = "s1",
            start = TODAY,
            timeStart = 9 * 60,
            timeEnd = 11 * 60,
            strictInterval = true
        )
        // Round-trip survives persistence.
        assertEquals(strict, TodoCodec.decode(TodoCodec.encode(listOf(strict))).first())
        assertTrue(TodoCodec.hasStrictInterval(strict))
        assertFalse(TodoCodec.hasStrictInterval(item("n", start = TODAY, timeStart = 9 * 60, timeEnd = 11 * 60)))

        // Completion is only allowed INSIDE the window (end is inclusive;
        // one second past the end it is already locked as missed).
        assertFalse(TodoCodec.canCompleteOn(strict, TODAY, atMinutes(TODAY, 8 * 60 + 59)))
        assertTrue(TodoCodec.canCompleteOn(strict, TODAY, atMinutes(TODAY, 9 * 60)))
        assertTrue(TodoCodec.canCompleteOn(strict, TODAY, atMinutes(TODAY, 10 * 60)))
        assertTrue(TodoCodec.canCompleteOn(strict, TODAY, atMinutes(TODAY, 11 * 60)))
        assertFalse(TodoCodec.canCompleteOn(strict, TODAY, atMinutes(TODAY, 11 * 60) + 1))

        // A todo without a strict window is always completable on its due day.
        val plain = item("p", start = TODAY)
        assertTrue(TodoCodec.canCompleteOn(plain, TODAY, atMinutes(TODAY, 23 * 60 + 59)))
    }

    @Test
    fun `closed strict window today counts as missed immediately`() {
        val closed = item(
            id = "closed",
            start = TODAY,
            timeStart = 9 * 60,
            timeEnd = 11 * 60,
            strictInterval = true
        )
        val nowAfter = atMinutes(TODAY, 11 * 60) + 1
        assertTrue(TodoCodec.intervalEnded(closed, TODAY, nowAfter))
        assertFalse(TodoCodec.canCompleteOn(closed, TODAY, nowAfter))
        // It appears in History right away — locked as missed ("can't redo").
        assertTrue(TodoCodec.hasHistory(closed, TODAY, nowAfter))
        assertEquals(0 to 1, TodoCodec.occurrenceCounts(closed, TODAY, nowAfter))
        assertEquals(
            listOf("closed"),
            TodoCodec.historyMissed(listOf(closed), TODAY, nowAfter).map { it.item.id }
        )

        // While the window is still open, nothing is recorded yet.
        assertFalse(TodoCodec.hasHistory(closed, TODAY, atMinutes(TODAY, 10 * 60)))
        assertEquals(0 to 0, TodoCodec.occurrenceCounts(closed, TODAY, atMinutes(TODAY, 10 * 60)))
    }

    @Test
    fun `time and reminder labels format cleanly`() {
        assertEquals("8:00 PM", TodoCodec.timeLabel(20 * 60))
        // 20:08 local time on 2026-08-10 → "8:08 PM".
        val local = java.time.ZonedDateTime.of(
            2026, 8, 10, 20, 8, 0, 0, java.time.ZoneId.systemDefault()
        )
        assertEquals("8:08 PM", TodoCodec.timeLabelFromMillis(local.toInstant().toEpochMilli()))
        assertEquals("12:00 PM · 1:00 PM", TodoCodec.reminderLabel(
            item("a", start = TODAY, reminder = ReminderConfig(listOf(12 * 60, 13 * 60), repeat = false))
        ))
        assertNull(TodoCodec.reminderLabel(item("a", start = TODAY)))
    }
}
