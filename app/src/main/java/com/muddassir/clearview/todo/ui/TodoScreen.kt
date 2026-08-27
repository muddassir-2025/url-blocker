package com.muddassir.clearview.todo.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoFilter
import com.muddassir.clearview.todo.data.TodoSort
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.data.TodoStats
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DONE_GREEN = Color(0xFF43A047)
private val MISSED_RED = Color(0xFFE53935)
// Card accents: Temporary = warm amber (it will expire), Permanent = cool blue
// (it stays) — a thin left bar on each card instead of a text pill. Upcoming =
// violet (scheduled, not yet actionable), a small dot before the title.
private val TEMP_AMBER = Color(0xFFB26A00)
private val PERM_BLUE = Color(0xFF1565C0)
private val UPCOMING_VIOLET = Color(0xFF6A1B9A)
// Locale-aware formatters: built per locale (from the observable Compose
// configuration) instead of frozen to the app's locale at class-load time, so
// they react to locale changes while the app runs (ConstantLocale fix).
private fun historyDateFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", locale)

private fun monthNameFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", locale)

/** Which metric the weekly bar graph shows. */
private enum class BarMode { COMPLETED, INCOMPLETE, TOTAL }

/** A request to open the day dialog: the day and the slice to show. */
private data class DayDialogRequest(val day: LocalDate, val mode: BarMode)

/**
 * The Todo screen — a calm productivity dashboard with a clean hierarchy:
 *
 *   Today → Upcoming → History → All → Temporary → Permanent
 *
 * Today is the completion tab: todos due today carry a checkbox — the ONLY
 * place completion happens and the only interactive thing on the tab.
 * Tapping ticks it; tapping again un-completes (a strict-interval day is
 * locked either way once its window closes — "can't redo"). The ⋮ menu
 * (Edit / Snooze / Delete) lives in the Temporary and Permanent lists; a
 * future todo can never be completed early. Upcoming groups future todos by
 * their required date, purely informational. History separates completed and
 * missed past todos with destructive-action confirmations.
 * Below the list: the daily target + weekly progress strip, the explainable
 * Weekly Score (tap for the full breakdown), Weekly Insights, Statistics and
 * a month calendar that works together with the weekly system.
 *
 * Opened from the Quran settings sheet (Todo card); rendered as a full-screen
 * dialog like the other hub screens.
 */
@Composable
fun TodoScreen(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TodoScreenContent(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun TodoScreenContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TodoStore(context.applicationContext) }
    var items by remember { mutableStateOf(store.getItems()) }
    var filter by remember { mutableStateOf(TodoFilter.TODAY) }
    var sort by remember { mutableStateOf(TodoSort.SMART) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoItem?>(null) }
    var pendingDelete by remember { mutableStateOf<TodoItem?>(null) }
    var snoozing by remember { mutableStateOf<TodoItem?>(null) }
    var dayDialog by remember { mutableStateOf<DayDialogRequest?>(null) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var showScoreDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // Shareable Progress Card generator (Statistics section header button).
    var showProgressCard by remember { mutableStateOf(false) }
    var dailyTarget by remember { mutableStateOf(store.getDailyTarget()) }
    var calendarMonth by remember { mutableStateOf(YearMonth.from(LocalDate.now())) }
    // \"Today\" refreshes at midnight while the screen stays open, so the list,
    // day strip, calendar and stats always reflect the actual current day.
    var today by remember { mutableStateOf(LocalDate.now()) }
    // Bumped after an in-app snooze so the card's snoozed window updates
    // immediately; nowMinute refreshes every minute so a snoozed window also
    // disappears right after its alarm fires.
    var snoozeTick by remember { mutableStateOf(0) }
    var nowMinute by remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
    // nowMillis refreshes every minute too — it drives strict-interval rules
    // (a window closing today locks that todo as missed within a minute) and
    // the history / stats that depend on it.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            today = LocalDate.now()
            nowMillis = System.currentTimeMillis()
            nowMinute = nowMillis / 60_000L
        }
    }

    // Single source of truth: notification actions (Complete / snooze) and any
    // other screen publish the persisted list through the store's flow, so
    // this screen stays in lock-step with them.
    LaunchedEffect(Unit) {
        store.items.collect { latest -> if (latest != null) items = latest }
    }

    // Reminders may be stale after a restart; re-sync cheaply on open.
    LaunchedEffect(Unit) { TodoScheduler.rescheduleAll(context) }

    fun save(newItems: List<TodoItem>) {
        items = newItems
        store.saveItems(newItems)
        TodoScheduler.rescheduleAll(context)
    }

    fun toggle(item: TodoItem, day: LocalDate = today) {
        // Date rule: a todo can only be toggled on a day it is applicable on
        // (for the list that is always today — future todos show no
        // checkbox). Strict-interval todos add a second rule: completion is
        // only allowed while the window is OPEN, and a COMPLETED day is
        // equally locked once the window closes ("can't redo" works both
        // ways) — so un-completing is only possible while the window is still
        // open. nowMillis (refreshed every minute) matches the checkbox state
        // exactly, so the visible affordance and the enforcement can never
        // disagree. The notification Complete path enforces the same rule
        // with the real clock.
        if (!TodoCodec.isActiveOn(item, day)) return
        if (TodoCodec.completedOn(item, day)) {
            // Un-complete — blocked once a strict window has closed (the day
            // is locked as done, mirroring "can't redo" for missed days).
            if (TodoCodec.intervalEnded(item, day, nowMillis)) return
        } else if (!TodoCodec.canCompleteOn(item, day, nowMillis)) {
            return
        }
        val (updated, _) = TodoCodec.toggled(items, item.id, day, nowMillis)
        save(updated)
    }

    val filtered = remember(items, filter, sort, query, today, nowMillis) {
        val q = query.trim().lowercase()
        val matches = { it: TodoItem ->
            q.isEmpty() || it.title.lowercase().contains(q) || it.details.lowercase().contains(q)
        }
        if (filter == TodoFilter.HISTORY) {
            TodoCodec.historySorted(items, today, nowMillis).map { it.item }.filter(matches)
        } else {
            TodoCodec.sorted(
                TodoCodec.filter(items, filter, today, nowMillis).filter(matches),
                sort,
                today
            )
        }
    }
    // History sections honor the search query exactly like the header count,
    // so the list, the sections and the header can never disagree.
    val q = query.trim().lowercase()
    val historyCompleted = remember(items, today, q, nowMillis) {
        TodoCodec.historyCompleted(items, today, nowMillis).filter {
            q.isEmpty() || it.item.title.lowercase().contains(q) ||
                it.item.details.lowercase().contains(q)
        }
    }
    val historyMissed = remember(items, today, q, nowMillis) {
        TodoCodec.historyMissed(items, today, nowMillis).filter {
            q.isEmpty() || it.item.title.lowercase().contains(q) ||
                it.item.details.lowercase().contains(q)
        }
    }
    val dueToday = remember(items, today) { items.count { TodoCodec.isActiveOn(it, today) } }
    val completedToday = remember(items, today) {
        items.count { TodoCodec.isActiveOn(it, today) && TodoCodec.completedOn(it, today) }
    }
    val weekStats = remember(items, today, nowMillis) {
        TodoStats.weekStats(items, today, nowMillis)
    }
    val monthStats = remember(items, today) { TodoStats.monthStats(items, today) }
    val calendarStats = remember(items, calendarMonth, today) {
        TodoStats.monthStats(items, calendarMonth, today)
    }
    // Upcoming todos (grouped by required date) for the Upcoming tab AND the
    // \"all done for today\" mini section on the Today tab.
    val upcomingList = remember(items, today) {
        TodoCodec.filter(items, TodoFilter.UPCOMING, today)
            .sortedBy { TodoCodec.nextActiveDate(it, today) }
    }
    val upcomingGroups = remember(items, today) {
        val list = TodoCodec.filter(items, TodoFilter.UPCOMING, today)
            .sortedBy { TodoCodec.nextActiveDate(it, today) }
        list.groupBy { TodoCodec.nextActiveDate(it, today)!! }
            .toSortedMap(compareBy { it })
    }
    val todayAllDone = dueToday > 0 && completedToday == dueToday
    // Snoozed reminders, per todo: "1:58 PM → 2:08 PM" (original reminder time
    // → new snoozed fire time). Shown on the card's meta line until the
    // snoozed alarm fires (or the snooze is cancelled).
    val snoozedWindows = remember(items, today, snoozeTick, nowMinute) {
        val snoozed = store.getSnoozedReminders()
        if (snoozed.isEmpty()) return@remember emptyMap()
        val now = System.currentTimeMillis()
        buildMap<String, String> {
            items.forEach { item ->
                val reminder = item.reminder ?: return@forEach
                var best: Pair<Long, String>? = null
                reminder.timesMinutes.indices.forEach { index ->
                    val rec = snoozed["${item.id}#$index"] ?: return@forEach
                    if (rec.fireAtMillis <= now) return@forEach
                    val window = TodoCodec.timeLabel(reminder.timesMinutes[index]) +
                        " → " + TodoCodec.timeLabelFromMillis(rec.fireAtMillis)
                    if (best == null || rec.fireAtMillis < best!!.first) {
                        best = rec.fireAtMillis to window
                    }
                }
                best?.let { put(item.id, it.second) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Top bar: back · title · search · sort ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.todo_back)
                )
            }
            Text(
                text = stringResource(R.string.todo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.todo_search),
                    tint = if (showSearch) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                var sortMenuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        Icons.Filled.Sort,
                        contentDescription = stringResource(R.string.todo_sort_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    Text(
                        text = stringResource(R.string.todo_sort_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    listOf(
                        TodoSort.SMART to R.string.todo_sort_smart,
                        TodoSort.TIME to R.string.todo_sort_time,
                        TodoSort.PRIORITY to R.string.todo_sort_priority,
                        TodoSort.CREATED to R.string.todo_sort_created,
                        TodoSort.STATUS to R.string.todo_sort_status
                    ).forEach { (option, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(label),
                                    fontWeight = if (sort == option) FontWeight.Bold else null
                                )
                            },
                            onClick = {
                                sort = option
                                sortMenuOpen = false
                            },
                            leadingIcon = {
                                if (sort == option) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Filter chips: Today · Upcoming · History · All · Temporary · Permanent ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                TodoFilter.TODAY to R.string.todo_filter_today,
                TodoFilter.UPCOMING to R.string.todo_filter_upcoming,
                TodoFilter.HISTORY to R.string.todo_filter_history,
                TodoFilter.ALL to R.string.todo_filter_all,
                TodoFilter.TEMPORARY to R.string.todo_filter_temporary,
                TodoFilter.PERMANENT to R.string.todo_filter_permanent
            ).forEach { (option, label) ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(stringResource(label)) }
                )
            }
        }

        // ── Search ──
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.todo_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(onAdd = { adding = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        ListHeader(
                            filter = filter,
                            completed = completedToday,
                            due = dueToday,
                            target = dailyTarget,
                            shown = filtered.size,
                            onEditTarget = { showTargetDialog = true }
                        )
                    }
                    when (filter) {
                        TodoFilter.HISTORY -> {
                            item {
                                HistorySection(
                                    completed = historyCompleted,
                                    missed = historyMissed,
                                    // "Clear" only hides the cards from the
                                    // History view — the completion data stays
                                    // (today's count / progress / stats keep
                                    // counting it). Reset is the destructive
                                    // one, gated by a typed confirmation.
                                    onClearCompleted = {
                                        save(TodoCodec.removeCompletedHistory(items, today, nowMillis))
                                    },
                                    onClearMissed = {
                                        save(TodoCodec.removeMissedHistory(items, today, nowMillis))
                                    },
                                    onClearAll = {
                                        save(TodoCodec.clearHistory(items, today, nowMillis))
                                    },
                                    onReset = { showResetDialog = true }
                                )
                            }
                        }

                        TodoFilter.UPCOMING -> {
                            if (upcomingGroups.isEmpty()) {
                                item {
                                    EmptyLine(stringResource(R.string.todo_upcoming_empty))
                                }
                            } else {
                                upcomingGroups.forEach { (date, list) ->
                                    item {
                                        UpcomingGroupHeader(date = date, today = today)
                                    }
                                    items(list, key = { it.id }) { todo ->
                                        TodoCard(
                                            item = todo,
                                            isDueToday = false,
                                            completedToday = false,
                                            canComplete = false,
                                            missedToday = false,
                                            snoozeWindow = snoozedWindows[todo.id],
                                            // Upcoming is a view-only list — the manage-actions
                                            // (Edit / Snooze / Delete) live in the Temporary /
                                            // Permanent lists.
                                            showActions = false,
                                            onToggle = {},
                                            onEdit = { editing = todo },
                                            onDelete = { pendingDelete = todo },
                                            onSnooze = { snoozing = todo }
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            if (filtered.isEmpty()) {
                                item {
                                    EmptyLine(stringResource(R.string.todo_no_match))
                                }
                            } else {
                                items(filtered, key = { it.id }) { todo ->
                                    TodoCard(
                                        item = todo,
                                        isDueToday = TodoCodec.isActiveOn(todo, today),
                                        // A completion only counts as "completed today" when the todo
                                        // is actually due TODAY — a stale completion on a day the new
                                        // plan doesn't apply to (e.g. a tomorrow todo that was completed
                                        // before being moved) must never strike it through or tint it.
                                        completedToday = TodoCodec.isActiveOn(todo, today) &&
                                            TodoCodec.completedOn(todo, today),
                                        // The completion checkbox is ONLY for Today — the one
                                        // place a todo is actionable. Temporary / Permanent
                                        // cards manage the plan (⋮ menu) but never complete;
                                        // All stays a strictly view-only aggregate.
                                        canComplete = filter == TodoFilter.TODAY &&
                                            TodoCodec.canCompleteOn(todo, today, nowMillis),
                                        // A todo completed today keeps its checkmark ONLY on the
                                        // Today tab — the same card in the other lists shows the
                                        // completion through styling, never a checkbox.
                                        showCheckbox = filter == TodoFilter.TODAY,
                                        // A strict-interval todo whose window closed today
                                        // uncompleted is LOCKED as missed — shown at a glance.
                                        missedToday = TodoCodec.isActiveOn(todo, today) &&
                                            !TodoCodec.completedOn(todo, today) &&
                                            TodoCodec.intervalEnded(todo, today, nowMillis),
                                        // A completed strict-interval todo whose window closed
                                        // is equally locked (as done) — its checkbox shows
                                        // ticked but cannot be un-completed.
                                        toggleLocked = TodoCodec.completedOn(todo, today) &&
                                            TodoCodec.intervalEnded(todo, today, nowMillis),
                                        snoozeWindow = snoozedWindows[todo.id],
                                        // Temporary and Permanent are the manageable lists
                                        // (⋮ menu: Edit / Snooze / Delete) — Today is purely
                                        // checkbox-based, and All stays a view-only aggregate.
                                        showActions = filter == TodoFilter.TEMPORARY ||
                                            filter == TodoFilter.PERMANENT ||
                                            filter == TodoFilter.TODAY,
                                        onToggle = { toggle(todo) },
                                        onEdit = { editing = todo },
                                        onDelete = { pendingDelete = todo },
                                        onSnooze = { snoozing = todo },
                                        onAttempt = { store.markAttempted(todo.id, today) },
                                        onAddTime = { mins -> store.addTime(todo.id, mins, today) }
                                    )
                                }
                            }
                            // Today all done → naturally surface what's next.
                            if (filter == TodoFilter.TODAY && todayAllDone && upcomingList.isNotEmpty()) {
                                item {
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))
                                }
                                item {
                                    Text(
                                        text = stringResource(R.string.todo_today_all_done),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.todo_upcoming_section),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                upcomingList.take(5).forEach { todo ->
                                    item(key = "up-$todo.id") {
                                        TodoCard(
                                            item = todo,
                                            isDueToday = false,
                                            completedToday = false,
                                            canComplete = false,
                                            missedToday = false,
                                            snoozeWindow = snoozedWindows[todo.id],
                                            // Today is purely checkbox-based — these "up next"
                                            // teasers are view-only (not due today, so no
                                            // completion checkbox and no ⋮ menu).
                                            showActions = false,
                                            onToggle = {},
                                            onEdit = { editing = todo },
                                            onDelete = { pendingDelete = todo },
                                            onSnooze = { snoozing = todo }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                    }
                    item {
                        WeeklyProgressSection(
                            stats = weekStats,
                            today = today,
                            onDayTap = { dayDialog = DayDialogRequest(it, BarMode.TOTAL) }
                        )
                    }
                    item {
                        WeeklyScoreSection(stats = weekStats, onClick = { showScoreDialog = true })
                    }
                    item { InsightsSection(stats = weekStats) }
                    item {
                        StatisticsSection(
                            stats = weekStats,
                            items = items,
                            today = today,
                            onShareProgress = { showProgressCard = true }
                        )
                    }
                    item {
                        CalendarSection(
                            stats = calendarStats,
                            month = calendarMonth,
                            today = today,
                            onPrevMonth = { calendarMonth = calendarMonth.minusMonths(1) },
                            onNextMonth = { calendarMonth = calendarMonth.plusMonths(1) },
                            onDayTap = { dayDialog = DayDialogRequest(it, BarMode.TOTAL) }
                        )
                    }
                    item {
                        WeeklyBarGraph(
                            stats = weekStats,
                            onBarTap = { day, mode -> dayDialog = DayDialogRequest(day, mode) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }

            // ── Add Todo ──
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.todo_add))
            }
        }
    }

    // ── Add / edit ──
    if (adding) {
        TodoEditorDialog(
            initial = null,
            onSave = { item ->
                save(TodoCodec.added(items, item))
                adding = false
            },
            onDismiss = { adding = false }
        )
    }
    editing?.let { item ->
        TodoEditorDialog(
            initial = item,
            onSave = { updated ->
                // Editing a completed todo gives it a fresh start: its
                // completion record is cleared, so it becomes a new, actionable
                // todo and leaves Completed history ("it becomes new").
                save(
                    if (item.completions.isEmpty()) TodoCodec.updated(items, updated)
                    else TodoCodec.editedAsNew(items, updated)
                )
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    // ── Delete confirmation ──
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.todo_delete_confirm_title)) },
            text = { Text(stringResource(R.string.todo_delete_confirm_text, item.title)) },
            confirmButton = {
                TextButton(onClick = {
                    // Cancel its alarms first — after removal the store no
                    // longer knows this todo.
                    TodoScheduler.cancelTodo(context, item)
                    save(TodoCodec.removed(items, item.id))
                    pendingDelete = null
                }) {
                    Text(
                        text = stringResource(R.string.todo_delete_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        )
    }

    // ── Snooze (in-app) ──
    val snoozedMsg = stringResource(R.string.todo_snoozed)
    snoozing?.let { item ->
        SnoozeSheet(
            onSnooze = { minutes ->
                TodoScheduler.snoozeNext(context, item.id, minutes)
                snoozeTick++
                Toast.makeText(context, snoozedMsg, Toast.LENGTH_SHORT).show()
                snoozing = null
            },
            onDismiss = { snoozing = null }
        )
    }

    // ── Day dialog (weekly strip / calendar / bar graph) ──
    dayDialog?.let { request ->
        DayTodosDialog(
            day = request.day,
            mode = request.mode,
            items = items,
            today = today,
            nowMillis = nowMillis,
            onToggle = { toggle(it, request.day) },
            onDismiss = { dayDialog = null }
        )
    }

    // ── Daily completion target ──
    if (showTargetDialog) {
        TargetDialog(
            current = dailyTarget,
            onSave = { target ->
                dailyTarget = target
                store.setDailyTarget(target)
                showTargetDialog = false
            },
            onDismiss = { showTargetDialog = false }
        )
    }

    // ── Weekly score breakdown ──
    if (showScoreDialog) {
        weekStats.breakdown?.let {
            ScoreBreakdownDialog(stats = weekStats, onDismiss = { showScoreDialog = false })
        }
    }

    // ── Shareable Progress Card ──
    if (showProgressCard) {
        ProgressCardDialog(
            items = items,
            today = today,
            nowMillis = nowMillis,
            store = store,
            onDismiss = { showProgressCard = false }
        )
    }

    // ── History Reset (DESTRUCTIVE — typed confirmation) ──
    if (showResetDialog) {
        ResetHistoryDialog(
            onConfirm = {
                save(TodoCodec.resetHistory(items, today))
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false }
        )
    }
}

/** Header above the list: filter name + a filter-specific subtitle line. */
@Composable
private fun ListHeader(
    filter: TodoFilter,
    completed: Int,
    due: Int,
    target: Int,
    shown: Int,
    onEditTarget: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = stringResource(
                when (filter) {
                    TodoFilter.TODAY -> R.string.todo_today_title
                    TodoFilter.UPCOMING -> R.string.todo_upcoming_title
                    TodoFilter.ALL -> R.string.todo_all_title
                    TodoFilter.HISTORY -> R.string.todo_history_title
                    TodoFilter.TEMPORARY -> R.string.todo_temporary_title
                    TodoFilter.PERMANENT -> R.string.todo_permanent_title
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        when (filter) {
            TodoFilter.TODAY -> {
                if (due > 0) {
                    val pct = (completed.toFloat() / target * 100).toInt().coerceIn(0, 100)
                    Text(
                        text = stringResource(
                            R.string.todo_today_target_progress,
                            completed, target, pct
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                } else {
                    Text(
                        text = stringResource(R.string.todo_today_none),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.todo_daily_target_label, target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onEditTarget, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.todo_daily_target_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            else -> Text(
                text = pluralStringResource(R.plurals.todo_count, shown, shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** A short centered empty line for a filter with no matches. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        textAlign = TextAlign.Center
    )
}

/** A group header for the Upcoming list: \"Tomorrow\" / \"Wednesday\" / \"Mon, Aug 12\". */
@Composable
private fun UpcomingGroupHeader(date: LocalDate, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (date) {
        today.plusDays(1) -> stringResource(R.string.todo_tomorrow)
        else -> if (date <= today.plusDays(7)) {
            date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        } else {
            historyDateFormat(locale).format(date)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/**
 * One todo card: checkbox (only in Today, when due today) — ticking it
 * completes, tapping it again un-completes; a completed strict-interval todo
 * whose window closed shows it ticked but DISABLED (locked as done, "can't
 * redo" both ways). Title, details, schedule meta, and — only when
 * [showActions] — the ⋮ menu with Edit / Snooze / Delete. A strict-interval
 * todo whose window closed today uncompleted shows a red Missed chip and NO
 * checkbox — it is locked (\"can't redo\"). When a snooze is pending,
 * [snoozeWindow] (\"1:58 PM → 2:08 PM\") replaces the reminder portion.
 */
@Composable
private fun TodoCard(
    item: TodoItem,
    isDueToday: Boolean,
    completedToday: Boolean,
    canComplete: Boolean,
    missedToday: Boolean,
    /**
     * True only in the Today view. Completion checkboxes are exclusive to
     * Today: outside it [completedToday] still styles the card (strike-through
     * + tint), but a checkbox is never rendered — a todo completed today in
     * Temporary / Permanent / All is presented as done, not toggleable.
     */
    showCheckbox: Boolean = false,
    /**
     * A completed strict-interval todo whose window has closed: its checkbox
     * renders ticked but disabled — the day is locked as done ("can't redo"
     * works both ways), so it cannot be un-completed.
     */
    toggleLocked: Boolean = false,
    snoozeWindow: String?,
    /** False in the Today / All / Upcoming views, which are view-only. */
    showActions: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: () -> Unit,
    onAttempt: (() -> Unit)? = null,
    onAddTime: ((Int) -> Unit)? = null
) {
    val today = LocalDate.now()
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    val meta = remember(item, today, snoozeWindow, missedToday) {
        if (snoozeWindow != null) {
            // "Today: 1:58 PM → 2:08 PM" — the day + the snoozed window.
            "${TodoCodec.scheduleLabel(item, today)}: $snoozeWindow"
        } else {
            val scheduled = TodoCodec.scheduledTimeLabel(item)
            val reminders = TodoCodec.reminderLabel(item)
            buildString {
                append(TodoCodec.scheduleLabel(item, today))
                scheduled?.let { append(" • ").append(it) }
                // Reminders are automatic now — skip them when they are just the
                // scheduled time itself (avoids \"8:00 PM · 8:00 PM\" on the card).
                if (reminders != null && reminders != scheduled) {
                    append(" · ").append(reminders)
                }
            }
        }
    }
    // Type accent for the left bar: amber = Temporary, blue = Permanent.
    val typeAccent = (if (item.type == TodoType.TEMPORARY) TEMP_AMBER else PERM_BLUE)
        .let { if (completedToday) it.copy(alpha = 0.45f) else it }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (completedToday) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Thin colored bar — the type indicator (no more pill chips).
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(typeAccent)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (missedToday) {
                    // Strict window closed uncompleted → LOCKED as missed. No
                    // checkbox, ever — "can't redo". A warning icon marks it.
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MISSED_RED,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (showCheckbox && isDueToday && (canComplete || completedToday)) {
                    // Today-only: a completed todo keeps its checkbox — ticked,
                    // and tapping it again un-completes. Only a strict-interval
                    // todo whose window already closed renders it DISABLED
                    // (locked as done, "can't redo" both ways).
                    Checkbox(
                        checked = completedToday,
                        onCheckedChange = { onToggle() },
                        enabled = !toggleLocked
                    )
                } else {
                    // Upcoming / not applicable today / window not yet open: no
                    // completion checkbox — a calendar icon marks it as scheduled.
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A small violet dot marks "scheduled, not yet due".
                        if (!isDueToday && !missedToday) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(UPCOMING_VIOLET)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (completedToday) TextDecoration.LineThrough else null,
                            color = if (completedToday || missedToday) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        if (missedToday) {
                            MissedChip()
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (item.details.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val isAttempted = remember(item, today) { TodoCodec.isAttemptedOn(item, today) }
                    val timeSpent = remember(item, today) { TodoCodec.timeSpentOn(item, today) }
                    if (item.behavior == TodoBehavior.ATTEMPTED && !completedToday) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isAttempted) "State: Attempted ✓" else "State: Not started",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAttempted) Color(0xFF00897B) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (item.behavior == TodoBehavior.TIME) {
                        Spacer(Modifier.height(2.dp))
                        val target = item.targetDurationMinutes ?: 60
                        Text(
                            text = "Time: ${timeSpent}m / ${target}m",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (timeSpent >= target || completedToday) DONE_GREEN else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // The ⋮ menu (Edit / Snooze / Delete) shows in the manageable
                // lists: Temporary and Permanent (Today / All / Upcoming are
                // view-only). Missed (locked "can't redo") cards offer Delete
                // ONLY — they are history. Completed cards keep Edit + Delete
                // (editing restarts the todo as new, clearing its completion).
                // Active cards get Edit / Snooze / Delete. Completion is the
                // checkbox's job — and the checkbox only appears in Today.
                if (showActions) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!missedToday) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.todo_edit)) },
                                    onClick = { menuOpen = false; onEdit() },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            if (!completedToday && !missedToday) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.todo_snooze)) },
                                    onClick = { menuOpen = false; onSnooze() },
                                    // Only when a reminder actually fires — a
                                    // todo whose reminders are switched OFF
                                    // (reminderStyle Off) must not offer a
                                    // Snooze that would silently re-arm an
                                    // alarm the user disabled.
                                    enabled = item.reminder?.enabled == true,
                                    leadingIcon = {
                                        Icon(Icons.Filled.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            if (item.behavior == TodoBehavior.ATTEMPTED && !completedToday && !missedToday && onAttempt != null) {
                                DropdownMenuItem(
                                    text = { Text("Mark Attempted") },
                                    onClick = { menuOpen = false; onAttempt() },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            if (item.behavior == TodoBehavior.TIME && !completedToday && !missedToday && onAddTime != null) {
                                DropdownMenuItem(
                                    text = { Text("+15 min") },
                                    onClick = { menuOpen = false; onAddTime(15) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("+30 min") },
                                    onClick = { menuOpen = false; onAddTime(30) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.todo_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = { menuOpen = false; onDelete() },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small red \"Missed\" chip on a strict-interval card whose window closed
 * today uncompleted — locked, cannot be redone. (Type is shown by the card's
 * left accent bar; upcoming by the violet dot before the title.)
 */
@Composable
private fun MissedChip() {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = MISSED_RED.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(R.string.todo_missed_chip),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MISSED_RED,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * History: Completed + Incomplete/Missed sections with History-only cleanup
 * actions. Each section has a **Clear** (hides that section's cards from the
 * History view — completion data stays, so progress/statistics keep counting
 * it). The bottom row offers **Clear All** (hides every card, same non-
 * destructive semantics) and **Reset** (the genuinely destructive one — wipes
 * the progress/history/statistics data, gated behind a typed confirmation).
 * The buttons are always visible (disabled while their section is empty) so
 * the user always knows the options exist.
 */
@Composable
private fun HistorySection(
    completed: List<TodoCodec.HistoryEntry>,
    missed: List<TodoCodec.HistoryEntry>,
    onClearCompleted: () -> Unit,
    onClearMissed: () -> Unit,
    onClearAll: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.todo_history_completed_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearCompleted, enabled = completed.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.todo_history_clear),
                    color = if (completed.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (completed.isEmpty()) {
            Text(
                text = stringResource(R.string.todo_history_completed_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            // Cards sit in a spaced column — flush cards would touch/overlap.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                completed.forEach { HistoryRow(it) }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.todo_history_missed_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearMissed, enabled = missed.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.todo_history_clear),
                    color = if (missed.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (missed.isEmpty()) {
            Text(
                text = stringResource(R.string.todo_history_missed_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            // Cards sit in a spaced column — flush cards would touch/overlap.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                missed.forEach { HistoryRow(it) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onClearAll,
                enabled = completed.isNotEmpty() || missed.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.todo_history_clear_all))
            }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.todo_history_reset),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * The DESTRUCTIVE Reset confirmation: the user must type exactly "RESET"
 * before the button enables — a plain Yes/No is never enough for this one.
 */
@Composable
private fun ResetHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val matches = typed == "RESET"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_history_reset_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.todo_history_reset_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.todo_history_reset_type_hint)) },
                    singleLine = true,
                    isError = typed.isNotEmpty() && !matches,
                    supportingText = if (typed.isNotEmpty() && !matches) {
                        {
                            Text(
                                stringResource(R.string.todo_history_reset_type_error),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else null
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = matches) {
                Text(
                    text = stringResource(R.string.todo_history_reset_button),
                    color = if (matches) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_cancel)) }
        }
    )
}

/** One history row: an item with its completed / missed past-occurrence counts. */
@Composable
private fun HistoryRow(entry: TodoCodec.HistoryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.missedCount > 0) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MISSED_RED,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = DONE_GREEN,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val locale = LocalConfiguration.current.locales[0]
                val parts = mutableListOf<String>()
                if (entry.completedCount > 0) {
                    parts.add(stringResource(R.string.todo_history_completed, entry.completedCount))
                }
                if (entry.missedCount > 0) {
                    parts.add(stringResource(R.string.todo_history_missed, entry.missedCount))
                }
                parts.add(stringResource(R.string.todo_history_last, historyDateFormat(locale).format(entry.lastOccurrence)))
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Weekly Progress: the Mon..Sun strip (tap a day) + completion bar. */
@Composable
private fun WeeklyProgressSection(
    stats: TodoStats.WeekStats,
    today: LocalDate,
    onDayTap: (LocalDate) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.todo_weekly_progress),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            stats.days.forEach { day ->
                val letter = DAY_LETTERS[day.date.dayOfWeek.value - 1]
                val isToday = day.date == today
                val completed = day.completed > 0
                val missed = !isToday && day.due > 0 && day.completed == 0
                val circleColor = when {
                    completed -> DONE_GREEN
                    missed -> MISSED_RED
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val letterColor = if (completed || missed) Color.White
                else if (isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDayTap(day.date) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(circleColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = letterColor
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = day.completed.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day.completed > 0) DONE_GREEN
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.todo_weekly_count, stats.completed, stats.due),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${stats.percent}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { stats.rate },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
        )
    }
}

/**
 * Weekly Productivity Score — tapping it opens the full breakdown dialog.
 * A week with no due todos (v2 "empty week") deliberately shows NO numeric
 * score: a subdued "No todos due this week" message instead — a 0/0 week
 * must never silently become a number.
 */
@Composable
private fun WeeklyScoreSection(stats: TodoStats.WeekStats, onClick: () -> Unit) {
    val score = stats.score
    val label = score?.let {
        stringResource(
            when {
                it >= 80 -> R.string.todo_score_excellent
                it >= 60 -> R.string.todo_score_good
                it >= 40 -> R.string.todo_score_fair
                else -> R.string.todo_score_low
            }
        )
    }
    Card(
        // An empty week has no breakdown to show — keep the card readable but
        // not tappable (tapping would open an empty dialog).
        onClick = onClick,
        enabled = score != null,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.todo_weekly_score),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (score == null) R.string.todo_score_empty_week
                        else R.string.todo_score_tap_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (score == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (score != null) {
                Text(
                    text = stringResource(R.string.todo_score_value, score),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** \"How your score was calculated\" — every component with its contribution. */
@Composable
private fun ScoreBreakdownDialog(stats: TodoStats.WeekStats, onDismiss: () -> Unit) {
    val b = stats.breakdown ?: return
    val daysWithDue = stats.days.count { it.due > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_score_how_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.todo_score_how_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_completion),
                    value = b.completion,
                    max = b.completionMax,
                    explanation = stringResource(
                        R.string.todo_score_expl_completion_weighted, b.doneWeight, b.dueWeight
                    )
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_consistency),
                    value = b.consistency,
                    max = b.consistencyMax,
                    explanation = stringResource(
                        R.string.todo_score_expl_consistency, stats.activeDays, daysWithDue
                    )
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_streak),
                    value = b.streak,
                    max = b.streakMax,
                    explanation = stringResource(R.string.todo_score_expl_streak, b.streakDays)
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_timeliness),
                    value = b.timeliness,
                    max = b.timelinessMax,
                    // Excluded (nothing closed yet) is informative, not a loss:
                    // being "on track" on a not-yet-due todo is not an achievement.
                    explanation = if (b.closedItems == 0) {
                        stringResource(R.string.todo_score_expl_timeliness_none)
                    } else {
                        val onTime = (b.closedItems - b.overdueCount - b.missedCount).coerceAtLeast(0)
                        stringResource(
                            R.string.todo_score_expl_timeliness, onTime, b.closedItems
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.todo_score_total),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.todo_score_value, b.total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_ok)) }
        }
    )
}

/** One score-breakdown row: label + explanation + earned value. */
@Composable
private fun ScoreRow(
    label: String,
    value: Int,
    max: Int,
    explanation: String,
    minusZero: Boolean = false
) {
    val isPenalty = value < 0 || minusZero
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = when {
                isPenalty -> if (value == 0) "−0" else value.toString()
                max > 0 -> stringResource(R.string.todo_score_plus_of, value, max)
                else -> "+$value"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPenalty) MISSED_RED
            else if (value == max && max > 0) DONE_GREEN
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Weekly Insights — the exact 5-row structure with distinct emojis. */
@Composable
private fun InsightsSection(stats: TodoStats.WeekStats) {
    if (stats.due <= 0) return
    val rows = mutableListOf<Pair<String, String>>()
    val locale = LocalConfiguration.current.locales[0]
    stats.bestDay?.let { best ->
        val dayName = best.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        rows.add(
            "🔥" to stringResource(
                R.string.todo_insight_best_day, dayName, (best.rate * 100).toInt()
            )
        )
    }
    rows.add("📈" to stringResource(R.string.todo_insight_completion, stats.percent))
    rows.add("📅" to stringResource(R.string.todo_insight_active_days, stats.activeDays))
    rows.add(
        "⚡" to (if (stats.streak > 0) {
            pluralStringResource(R.plurals.todo_streak, stats.streak, stats.streak)
        } else {
            stringResource(R.string.todo_insight_streak_none)
        })
    )
    rows.add("🎯" to pluralStringResource(
        R.plurals.todo_insight_remaining, stats.remainingToday, stats.remainingToday
    ))

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.todo_weekly_insights),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        rows.forEach { (emoji, text) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = emoji, fontSize = 16.sp, modifier = Modifier.width(30.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // First week — no previous week to compare against. Otherwise show the
        // week-over-week comparison once real previous-week data exists.
        if (stats.firstWeek) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🌱", fontSize = 16.sp, modifier = Modifier.width(30.dp))
                Text(
                    text = stringResource(R.string.todo_insight_first_week),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            stats.improvementPoints?.let { points ->
                val sign = if (points > 0) "+" else ""
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📊", fontSize = 16.sp, modifier = Modifier.width(30.dp))
                    Text(
                        text = stringResource(R.string.todo_insight_improvement, "$sign$points"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Statistics: this week, this month, best day and the most productive window. */
@Composable
private fun StatisticsSection(
    stats: TodoStats.WeekStats,
    items: List<TodoItem>,
    today: LocalDate,
    onShareProgress: () -> Unit
) {
    val month = remember(items, today) { TodoStats.monthStats(items, today) }
    val monthWindow = remember(items, today) {
        val start = YearMonth.from(today).atDay(1).toEpochDay()
        val end = YearMonth.from(today).atDay(1).plusMonths(1).toEpochDay()
        TodoStats.mostProductiveWindow(items, start, end)
    }
    val productiveWindow = stats.mostProductiveWindow ?: monthWindow
    if (stats.due == 0 && month.due == 0 && stats.bestDay == null && productiveWindow == null) return
    val locale = LocalConfiguration.current.locales[0]

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.todo_stats_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // "Share Progress": opens the shareable Progress Card generator.
                IconButton(onClick = onShareProgress, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.progress_card_share_progress),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (stats.due > 0) {
                StatRow(
                    label = stringResource(R.string.todo_stats_this_week),
                    value = stringResource(
                        R.string.todo_stats_week, stats.completed, stats.due, stats.percent
                    )
                )
            }
            if (month.due > 0) {
                StatRow(
                    label = stringResource(R.string.todo_stats_this_month),
                    value = stringResource(
                        R.string.todo_stats_week, month.completed, month.due, month.percent
                    )
                )
            }
            stats.bestDay?.let { best ->
                val dayName = best.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                StatRow(
                    label = stringResource(R.string.todo_stats_best_day_label),
                    value = stringResource(
                        R.string.todo_stats_best_day, dayName, (best.rate * 100).toInt()
                    )
                )
            }
            productiveWindow?.let { (start, end) ->
                StatRow(
                    label = stringResource(R.string.todo_stats_productive_label),
                    value = stringResource(
                        R.string.todo_stats_productive_time,
                        TodoCodec.timeLabel(start * 60),
                        TodoCodec.timeLabel(end * 60)
                    )
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Month calendar: activity dots per day, tap a day for its todos. */
@Composable
private fun CalendarSection(
    stats: TodoStats.MonthStats,
    month: YearMonth,
    today: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayTap: (LocalDate) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = monthNameFormat(locale).format(month.atDay(1)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPrevMonth, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.todo_calendar_prev),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.todo_calendar_next),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (stats.due > 0 || stats.futureScheduled > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.todo_calendar_month_progress, stats.percent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                DAY_LETTERS.forEach { letter ->
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // Leading blanks before the 1st, then a padded 7-column grid.
            val leading = (month.atDay(1).dayOfWeek.value - 1) % 7
            val cells: List<TodoStats.MonthDayStats?> = buildList {
                repeat(leading) { add(null) }
                stats.days.forEach { add(it) }
                while (size % 7 != 0) add(null)
            }
            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        CalendarCell(
                            day = day,
                            today = today,
                            onClick = { onDayTap(it) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarLegend(color = DONE_GREEN, label = stringResource(R.string.todo_calendar_done))
                Spacer(Modifier.width(12.dp))
                CalendarLegend(color = MISSED_RED, label = stringResource(R.string.todo_calendar_missed))
                Spacer(Modifier.width(12.dp))
                CalendarLegend(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    label = stringResource(R.string.todo_calendar_scheduled)
                )
            }
        }
    }
}

/** One calendar day cell: number + activity dot. Null = leading/trailing blank. */
@Composable
private fun RowScope.CalendarCell(
    day: TodoStats.MonthDayStats?,
    today: LocalDate,
    onClick: (LocalDate) -> Unit
) {
    if (day == null) {
        Spacer(Modifier.weight(1f).height(38.dp))
        return
    }
    val isToday = day.date == today
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isToday) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable { onClick(day.date) }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        val dotColor = when {
            day.completed > 0 -> DONE_GREEN
            day.due > 0 && day.completed == 0 && !day.isFuture -> MISSED_RED
            day.due > 0 -> null // scheduled (outline dot)
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor ?: Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (dotColor == null && day.due > 0) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }
}

@Composable
private fun CalendarLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The minimal per-day bar graph with Completed / Incomplete / Total toggle.
 * Days that haven't arrived are empty (the stats carry zero for them), so a
 * future schedule never renders as a completion bar. Tapping a bar opens that
 * day's todos filtered to the current slice — see [DayTodosDialog].
 */
@Composable
private fun WeeklyBarGraph(
    stats: TodoStats.WeekStats,
    onBarTap: (LocalDate, BarMode) -> Unit
) {
    var mode by remember { mutableStateOf(BarMode.COMPLETED) }
    // One shared scale per mode: completed / incomplete counts, or the total
    // (due) count for the stacked bars.
    val max = stats.days.map { day ->
        when (mode) {
            BarMode.COMPLETED -> day.completed
            BarMode.INCOMPLETE -> (day.due - day.completed).coerceAtLeast(0)
            BarMode.TOTAL -> day.due
        }
    }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    when (mode) {
                        BarMode.COMPLETED -> R.string.todo_graph_completed
                        BarMode.INCOMPLETE -> R.string.todo_graph_incomplete
                        BarMode.TOTAL -> R.string.todo_graph_total
                    }
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            listOf(
                BarMode.COMPLETED to R.string.todo_filter_completed,
                BarMode.INCOMPLETE to R.string.todo_filter_incomplete,
                BarMode.TOTAL to R.string.todo_filter_all
            ).forEach { (option, label) ->
                FilterChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            stats.days.forEach { day ->
                val completed = day.completed
                val incomplete = (day.due - day.completed).coerceAtLeast(0)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onBarTap(day.date, mode) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (mode == BarMode.TOTAL) {
                            // Stacked mix: green (completed) sits on top of red
                            // (incomplete), both scaled to the shared max.
                            if (day.due <= 0) {
                                BarStub()
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)),
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    if (incomplete > 0) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(64.dp * incomplete / max)
                                                .background(MISSED_RED.copy(alpha = 0.7f))
                                        )
                                    }
                                    if (completed > 0) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(64.dp * completed / max)
                                                .background(DONE_GREEN)
                                        )
                                    }
                                }
                            }
                        } else {
                            val value = if (mode == BarMode.COMPLETED) completed else incomplete
                            val color = if (mode == BarMode.COMPLETED) MaterialTheme.colorScheme.primary
                            else MISSED_RED.copy(alpha = 0.7f)
                            if (value <= 0) {
                                BarStub()
                            } else {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height((64.dp * value / max).coerceAtLeast(6.dp))
                                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                        .background(color)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = DAY_LETTERS[day.date.dayOfWeek.value - 1],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** The tiny neutral stub shown for a day with nothing to measure. */
@Composable
private fun BarStub() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
    )
}

/**
 * Tapping a calendar / weekly-strip / bar-graph day shows that day's todos.
 * Only TODAY is interactive: past days are history (read-only), future days
 * show their SCHEDULED todos (read-only) — a future todo is informational and
 * never appears as incomplete.
 */
@Composable
private fun DayTodosDialog(
    day: LocalDate,
    mode: BarMode,
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    onToggle: (TodoItem) -> Unit,
    onDismiss: () -> Unit
) {
    val active = items.filter { TodoCodec.isActiveOn(it, day) }
    val isFuture = day.isAfter(today)
    // Same date rule as the list: only today is editable, and a strict-interval
    // todo whose window has already closed today is LOCKED ("can't redo").
    val editable = day == today
    val list = when {
        isFuture -> active
        mode == BarMode.COMPLETED -> active.filter { TodoCodec.completedOn(it, day) }
        mode == BarMode.INCOMPLETE -> active.filterNot { TodoCodec.completedOn(it, day) }
        else -> active
    }
    val locale = LocalConfiguration.current.locales[0]
    val modeLabel = if (isFuture) stringResource(R.string.todo_calendar_scheduled)
    else stringResource(
        when (mode) {
            BarMode.COMPLETED -> R.string.todo_filter_completed
            BarMode.INCOMPLETE -> R.string.todo_filter_incomplete
            BarMode.TOTAL -> R.string.todo_filter_all
        }
    )
    val title = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.todo_day_title, title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.todo_day_mode,
                        list.size,
                        modeLabel,
                        list.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.todo_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    list.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (editable && TodoCodec.isActiveOn(item, day)) {
                                val done = TodoCodec.completedOn(item, day)
                                val lockedMissed = !done && TodoCodec.intervalEnded(item, day, nowMillis)
                                when {
                                    // Strict window closed today uncompleted → locked.
                                    lockedMissed -> {
                                        Icon(
                                            Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MISSED_RED,
                                            modifier = Modifier.size(20.dp).padding(start = 6.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    // Same toggle semantics as the Today list: a ticked
                                    // completed day can be un-completed unless its strict
                                    // window already closed (locked both ways), and an
                                    // uncompleted day only shows a checkbox while the rules
                                    // allow completing — a strict window that hasn't opened
                                    // yet falls through to the DateRange marker, matching
                                    // the list.
                                    done || TodoCodec.canCompleteOn(item, day, nowMillis) -> {
                                        Checkbox(
                                            checked = done,
                                            onCheckedChange = { onToggle(item) },
                                            enabled = !(done && TodoCodec.intervalEnded(item, day, nowMillis))
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Filled.DateRange,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp).padding(start = 6.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                }
                            } else {
                                Icon(
                                    Icons.Filled.DateRange,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp).padding(start = 6.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = if (TodoCodec.completedOn(item, day)) {
                                    TextDecoration.LineThrough
                                } else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TodoCodec.scheduledTimeLabel(item)?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_ok)) }
        }
    )
}

/** Snooze options: 10 / 30 / 60 min or a custom number of minutes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeSheet(
    onSnooze: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.todo_snooze),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                10L to R.string.todo_snooze_10m,
                30L to R.string.todo_snooze_30m,
                60L to R.string.todo_snooze_1h
            ).forEach { (minutes, label) ->
                OutlinedButton(
                    onClick = { onSnooze(minutes) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(label), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
            TextButton(
                onClick = { customOpen = !customOpen },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.todo_snooze_custom), modifier = Modifier.weight(1f))
            }
            if (customOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { input ->
                            customText = input.filter { it.isDigit() }.take(4)
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.todo_snooze_custom_minutes)) },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            customText.toLongOrNull()?.takeIf { it > 0 }?.let { onSnooze(it) }
                        },
                        enabled = customText.toLongOrNull()?.let { it > 0 } == true
                    ) {
                        Text(stringResource(R.string.todo_ok))
                    }
                }
            }
        }
    }
}

/** Daily completion target editor: presets + a custom number. */
@Composable
private fun TargetDialog(
    current: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_target_set_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.todo_target_set_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8, 10).forEach { preset ->
                        FilterChip(
                            selected = value == preset.toString(),
                            onClick = { value = preset.toString() },
                            label = { Text("$preset") }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter { it.isDigit() }.take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.todo_target_label)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value.toIntOrNull()?.coerceIn(1, 50) ?: current) },
                enabled = value.toIntOrNull()?.let { it in 1..50 } == true
            ) {
                Text(stringResource(R.string.todo_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.todo_cancel))
            }
        }
    )
}

/** Beautiful empty state when there are no todos at all. */
@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🗒️", fontSize = 52.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.todo_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.todo_empty_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.todo_add))
        }
    }
}
