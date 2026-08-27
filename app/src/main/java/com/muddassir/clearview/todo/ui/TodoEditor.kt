package com.muddassir.clearview.todo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoPriority
import com.muddassir.clearview.todo.model.TodoType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Temporary-todo period presets. */
private enum class PeriodChoice { TODAY, TOMORROW, THIS_WEEK, CUSTOM }

/** Time options in the editor: none / a single time / a start–end range. */
private enum class TimeChoice { NONE, SINGLE, RANGE }

/** How a set reminder fires: off, a notification, or a real system alarm. */
private enum class ReminderStyle { OFF, NOTIFICATION, ALARM }

/** What the time picker is editing right now. */
private sealed class TimeTarget {
    object Scheduled : TimeTarget()
    object RangeStart : TimeTarget()
    object RangeEnd : TimeTarget()
    data class Reminder(val index: Int) : TimeTarget()
}

/** Which date the date picker is editing (custom-range only). */
private enum class DateTarget { START, END }

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

/**
 * Add / edit a Todo — a full-screen form (like the Dhikr settings screen):
 * name + optional details, Temporary vs Permanent, the date/period (Today /
 * Tomorrow / This week / custom range, or repeat weekdays for permanent), an
 * optional scheduled time (none / one time / a start–end range) and priority.
 * Reminders are AUTOMATIC, derived from the time choice: no time → none, a
 * single time → one notification at that time, a range → notifications spread
 * across it (a chosen count, or your own specific times). Only the name is
 * required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditorDialog(
    initial: TodoItem?,
    onSave: (TodoItem) -> Unit,
    onDismiss: () -> Unit
) {
    val today = LocalDate.now()

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var details by remember { mutableStateOf(initial?.details ?: "") }
    var titleError by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initial?.type ?: TodoType.TEMPORARY) }
    var period by remember {
        mutableStateOf(
            if (initial == null) PeriodChoice.TODAY
            else when {
                initial.type == TodoType.PERMANENT -> PeriodChoice.CUSTOM
                initial.startDateEpochDay == initial.endDateEpochDay -> {
                    val d = LocalDate.ofEpochDay(initial.startDateEpochDay)
                    when (d) {
                        today -> PeriodChoice.TODAY
                        today.plusDays(1) -> PeriodChoice.TOMORROW
                        else -> PeriodChoice.CUSTOM
                    }
                }
                else -> PeriodChoice.CUSTOM
            }
        )
    }
    var startDate by remember {
        mutableStateOf(initial?.startDateEpochDay?.let(LocalDate::ofEpochDay) ?: today)
    }
    var endDate by remember {
        mutableStateOf(initial?.endDateEpochDay?.let(LocalDate::ofEpochDay) ?: today)
    }
    var selectedDays by remember {
        mutableStateOf(
            initial?.scheduledDays?.takeIf { it.isNotEmpty() } ?: (1..7).toSet()
        )
    }
    var timeMinutes by remember { mutableStateOf(initial?.timeMinutes) }
    var timeStartMinutes by remember { mutableStateOf(initial?.timeStartMinutes ?: 9 * 60) }
    var timeEndMinutes by remember { mutableStateOf(initial?.timeEndMinutes ?: 20 * 60) }
    var timeChoice by remember {
        mutableStateOf(
            when {
                initial?.timeStartMinutes != null && initial?.timeEndMinutes != null -> TimeChoice.RANGE
                initial?.timeMinutes != null -> TimeChoice.SINGLE
                else -> TimeChoice.NONE
            }
        )
    }
    // How many reminders to spread across the range (2..6); the generated times
    // land in [reminderTimes] and stay manually editable afterwards.
    var rangeReminderCount by remember {
        mutableStateOf(initial?.reminder?.timesMinutes?.size?.coerceIn(2, 6) ?: 3)
    }
    var reminderTimes by remember {
        mutableStateOf(initial?.reminder?.timesMinutes ?: emptyList())
    }
    // Off / Notification / Alarm — how the automatic reminder fires. Alarm
    // rings the system alarm clock (exact, full-screen, Clock-app entry);
    // Notification posts the usual in-app notification.
    var reminderStyle by remember {
        mutableStateOf(
            when {
                initial?.reminder == null -> ReminderStyle.NOTIFICATION
                !initial.reminder.enabled -> ReminderStyle.OFF
                initial.reminder.asAlarm -> ReminderStyle.ALARM
                else -> ReminderStyle.NOTIFICATION
            }
        )
    }
    var priority by remember { mutableStateOf(initial?.priority ?: TodoPriority.NORMAL) }
    var behavior by remember { mutableStateOf(initial?.behavior ?: TodoBehavior.NORMAL) }
    var targetDurationMinutes by remember { mutableStateOf(initial?.targetDurationMinutes ?: 60) }
    // Strict interval: a RANGE todo is only completable INSIDE its start–end
    // window. When the window ends uncompleted, that day is locked as missed
    // ("can't redo") — the checkbox disables and the notification Complete
    // action is rejected. Only meaningful (and shown) for RANGE todos.
    var strictInterval by remember { mutableStateOf(initial?.strictInterval ?: false) }
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }

    // Android 13+ runtime permission: a Notification-style reminder is silently
    // dropped without POST_NOTIFICATIONS, so request it right where the style
    // is chosen (same pattern as the Settings toggles).
    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied: no extra action — the UI just reflects it */ }

    fun effectiveStart(): LocalDate = when (period) {
        PeriodChoice.TODAY -> today
        PeriodChoice.TOMORROW -> today.plusDays(1)
        PeriodChoice.THIS_WEEK -> today
        PeriodChoice.CUSTOM -> startDate
    }

    fun effectiveEnd(): LocalDate? = when {
        type == TodoType.PERMANENT -> null
        period == PeriodChoice.TODAY -> today
        period == PeriodChoice.TOMORROW -> today.plusDays(1)
        period == PeriodChoice.THIS_WEEK -> today.plusDays(7 - today.dayOfWeek.value.toLong())
        period == PeriodChoice.CUSTOM -> if (endDate < startDate) startDate else endDate
        else -> endDate
    }

    /** Regenerates the reminder times spread evenly across the range. */
    fun setRangeCount(count: Int) {
        rangeReminderCount = count.coerceIn(2, 6)
        reminderTimes = TodoCodec.rangeTimes(timeStartMinutes, timeEndMinutes, rangeReminderCount)
    }

    /** Clamps a start/end pair to a sane ≥60-minute window (no overnight wrap). */
    fun adjustRange(start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        if (e <= s) {
            e = (s + 60).coerceAtMost(1439)
            s = (e - 60).coerceAtLeast(0)
        }
        return s to e
    }

    /** Adds the next sensible free reminder time inside the range. */
    fun addRangeReminderTime() {
        if (reminderTimes.size >= 6) return
        val base = reminderTimes.lastOrNull() ?: (timeStartMinutes - 60)
        var candidate = (base + 60).coerceAtMost(timeEndMinutes)
        if (candidate < timeStartMinutes) candidate = timeStartMinutes
        while (candidate in reminderTimes && candidate > timeStartMinutes) candidate -= 30
        if (candidate !in reminderTimes) {
            reminderTimes = (reminderTimes + candidate).sorted()
        }
    }

    fun submit() {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            titleError = true
            return
        }
        val start = effectiveStart()
        val end = effectiveEnd()
        val days = if (type == TodoType.PERMANENT) {
            if (selectedDays.size == 7) null else selectedDays
        } else null
        val singleTime = if (timeChoice == TimeChoice.SINGLE) timeMinutes else null
        val rangeStart = if (timeChoice == TimeChoice.RANGE) timeStartMinutes else null
        val rangeEnd = if (timeChoice == TimeChoice.RANGE) timeEndMinutes else null
        // The reminder is AUTOMATIC, derived from the time choice: no time → no
        // reminder; a single time → one reminder at that time; a range →
        // reminders at the range times (your count or your own picks), repeating
        // on every active day. [reminderStyle] chooses HOW it fires: Off (no
        // reminder at all), a Notification, or a real system Alarm.
        val styleOn = reminderStyle != ReminderStyle.OFF
        val reminder = when (timeChoice) {
            TimeChoice.NONE -> null
            TimeChoice.SINGLE -> ReminderConfig(
                timesMinutes = listOf(timeMinutes ?: 20 * 60),
                repeat = true,
                enabled = styleOn,
                asAlarm = reminderStyle == ReminderStyle.ALARM
            )
            TimeChoice.RANGE -> ReminderConfig(
                timesMinutes = reminderTimes.ifEmpty {
                    TodoCodec.rangeTimes(rangeStart!!, rangeEnd!!, rangeReminderCount)
                }.distinct(),
                repeat = true,
                enabled = styleOn,
                asAlarm = reminderStyle == ReminderStyle.ALARM
            )
        }
        onSave(
            TodoItem(
                id = initial?.id ?: "",
                title = trimmed,
                details = details.trim(),
                type = type,
                startDateEpochDay = start.toEpochDay(),
                endDateEpochDay = end?.toEpochDay(),
                scheduledDays = days,
                timeMinutes = singleTime,
                timeStartMinutes = rangeStart,
                timeEndMinutes = rangeEnd,
                reminder = reminder,
                priority = priority,
                strictInterval = strictInterval && timeChoice == TimeChoice.RANGE,
                behavior = behavior,
                targetDurationMinutes = if (behavior == TodoBehavior.TIME) targetDurationMinutes else null,
                events = initial?.events ?: emptyList(),
                isDeleted = initial?.isDeleted ?: false
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Top bar ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.todo_back)
                        )
                    }
                    Text(
                        text = stringResource(
                            if (initial == null) R.string.todo_add else R.string.todo_edit
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                ) {
                    // ── Name + details ──
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; titleError = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.todo_title_label)) },
                        singleLine = true,
                        isError = titleError
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.todo_details_label)) },
                        minLines = 2
                    )

                    Spacer(Modifier.height(20.dp))
                    SectionTitle(stringResource(R.string.todo_type))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TodoType.entries.forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = {
                                    Text(
                                        stringResource(
                                            if (t == TodoType.TEMPORARY) R.string.todo_type_temporary
                                            else R.string.todo_type_permanent
                                        )
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            if (type == TodoType.TEMPORARY) R.string.todo_type_temporary_note
                            else R.string.todo_type_permanent_note
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))
                    SectionTitle("Task Variety")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TodoBehavior.entries.forEach { b ->
                            FilterChip(
                                selected = behavior == b,
                                onClick = { behavior = b },
                                label = {
                                    Text(
                                        when (b) {
                                            TodoBehavior.NORMAL -> "Normal"
                                            TodoBehavior.ATTEMPTED -> "Attempted"
                                            TodoBehavior.TIME -> "Time-based"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (behavior) {
                            TodoBehavior.NORMAL -> "Simple checkbox: directly mark as completed"
                            TodoBehavior.ATTEMPTED -> "3-step progress: Not started → Attempted → Completed"
                            TodoBehavior.TIME -> "Duration tracker: log completed time against a target"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (behavior == TodoBehavior.TIME) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Target Duration",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                15 to "15m",
                                30 to "30m",
                                45 to "45m",
                                60 to "1h",
                                120 to "2h",
                                180 to "3h"
                            ).forEach { (mins, lbl) ->
                                FilterChip(
                                    selected = targetDurationMinutes == mins,
                                    onClick = { targetDurationMinutes = mins },
                                    label = { Text(lbl) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionTitle(stringResource(R.string.todo_date))
                    Spacer(Modifier.height(6.dp))
                    if (type == TodoType.TEMPORARY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                PeriodChoice.TODAY to R.string.todo_today,
                                PeriodChoice.TOMORROW to R.string.todo_tomorrow,
                                PeriodChoice.THIS_WEEK to R.string.todo_this_week,
                                PeriodChoice.CUSTOM to R.string.todo_custom
                            ).forEach { (choice, label) ->
                                FilterChip(
                                    selected = period == choice,
                                    onClick = { period = choice },
                                    label = { Text(stringResource(label)) }
                                )
                            }
                        }
                        if (period == PeriodChoice.CUSTOM) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { dateTarget = DateTarget.START }) {
                                    Text(stringResource(R.string.todo_date_range_start, DATE_FORMAT.format(startDate)))
                                }
                                OutlinedButton(onClick = { dateTarget = DateTarget.END }) {
                                    Text(stringResource(R.string.todo_date_range_end, DATE_FORMAT.format(endDate)))
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.todo_active_days),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { selectedDays = (1..7).toSet() }) {
                                Text(stringResource(R.string.todo_every_day))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..7).forEach { dow ->
                                FilterChip(
                                    selected = dow in selectedDays,
                                    onClick = {
                                        selectedDays = if (dow in selectedDays) selectedDays - dow
                                        else selectedDays + dow
                                    },
                                    label = { Text(DAY_LETTERS[dow - 1]) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // Live summary of the current selection — clicking the day
                        // chips updates this instantly (previously only a static
                        // "Every day" label was shown, so the change was invisible).
                        Text(
                            text = if (selectedDays.isEmpty() || selectedDays.size == 7) {
                                stringResource(R.string.todo_every_day)
                            } else {
                                selectedDays.sorted().joinToString(" • ") { DAY_NAMES[it - 1] }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionTitle(stringResource(R.string.todo_time))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = timeChoice == TimeChoice.NONE,
                            onClick = { timeChoice = TimeChoice.NONE },
                            label = { Text(stringResource(R.string.todo_no_time)) }
                        )
                        FilterChip(
                            selected = timeChoice == TimeChoice.SINGLE,
                            onClick = { timeChoice = TimeChoice.SINGLE },
                            label = { Text(stringResource(R.string.todo_at_time)) }
                        )
                        FilterChip(
                            selected = timeChoice == TimeChoice.RANGE,
                            onClick = {
                                timeChoice = TimeChoice.RANGE
                                if (reminderTimes.isEmpty()) {
                                    reminderTimes = TodoCodec.rangeTimes(
                                        timeStartMinutes,
                                        timeEndMinutes,
                                        rangeReminderCount
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.todo_time_range)) }
                        )
                    }
                    when (timeChoice) {
                        TimeChoice.NONE -> {}
                        TimeChoice.SINGLE -> {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (timeMinutes != null) {
                                    FilterChip(
                                        selected = true,
                                        onClick = { timeTarget = TimeTarget.Scheduled },
                                        label = { Text(TodoCodec.timeLabel(timeMinutes!!)) }
                                    )
                                } else {
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            timeMinutes = 20 * 60
                                            timeTarget = TimeTarget.Scheduled
                                        },
                                        label = { Text(stringResource(R.string.todo_set_time)) }
                                    )
                                }
                            }
                            // The reminder is automatic — one reminder at the
                            // chosen time (Off / Notification / Alarm style).
                            // The "you'll be reminded at" hint is only shown
                            // when a reminder will actually fire.
                            if (timeMinutes != null) {
                                Spacer(Modifier.height(6.dp))
                                if (reminderStyle != ReminderStyle.OFF) {
                                    Text(
                                        text = stringResource(
                                            R.string.todo_reminder_at_label,
                                            TodoCodec.timeLabel(timeMinutes!!)
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                ReminderStyleRow(
                                    reminderStyle,
                                    { reminderStyle = it },
                                    { notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                )
                            }
                        }
                        TimeChoice.RANGE -> {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { timeTarget = TimeTarget.RangeStart }) {
                                    Text(stringResource(R.string.todo_time_from, TodoCodec.timeLabel(timeStartMinutes)))
                                }
                                OutlinedButton(onClick = { timeTarget = TimeTarget.RangeEnd }) {
                                    Text(stringResource(R.string.todo_time_to, TodoCodec.timeLabel(timeEndMinutes)))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            // Strict interval: completion only inside the window.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { strictInterval = !strictInterval }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = strictInterval,
                                    onCheckedChange = { strictInterval = it }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.todo_strict_interval),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = stringResource(R.string.todo_strict_interval_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // Reminders are automatic — N notifications spread
                            // across the window (or your own specific times).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.todo_remind_times_in_range),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = stringResource(R.string.todo_remind_times_in_range_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { setRangeCount(rangeReminderCount - 1) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Remove,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "$rangeReminderCount",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(26.dp),
                                    textAlign = TextAlign.Center
                                )
                                IconButton(
                                    onClick = { setRangeCount(rangeReminderCount + 1) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            reminderTimes.forEachIndexed { index, minutes ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { timeTarget = TimeTarget.Reminder(index) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(TodoCodec.timeLabel(minutes), modifier = Modifier.weight(1f))
                                    }
                                    IconButton(onClick = {
                                        reminderTimes = reminderTimes.filterIndexed { i, _ -> i != index }
                                    }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.todo_remove_time),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            if (reminderTimes.size < 6) {
                                TextButton(
                                    onClick = { addRangeReminderTime() },
                                    modifier = Modifier.align(Alignment.Start)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.todo_reminder_add_time))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            ReminderStyleRow(
                                reminderStyle,
                                { reminderStyle = it },
                                { notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionTitle(stringResource(R.string.todo_priority))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TodoPriority.LOW to R.string.todo_priority_low,
                            TodoPriority.NORMAL to R.string.todo_priority_normal,
                            TodoPriority.HIGH to R.string.todo_priority_high
                        ).forEach { (p, label) ->
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text(stringResource(label)) }
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(onClick = ::submit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.todo_save))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ── Date picker (custom range) ──
    dateTarget?.let { target ->
        val initialDate = when (target) {
            DateTarget.START -> startDate
            DateTarget.END -> endDate
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        when (target) {
                            DateTarget.START -> {
                                startDate = date
                                if (endDate < startDate) endDate = startDate
                            }
                            DateTarget.END -> {
                                endDate = date
                                if (endDate < startDate) startDate = endDate
                            }
                        }
                    }
                    dateTarget = null
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = null }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // ── Time picker ──
    timeTarget?.let { target ->
        val initialMinutes = when (target) {
            TimeTarget.Scheduled -> timeMinutes ?: 20 * 60
            TimeTarget.RangeStart -> timeStartMinutes
            TimeTarget.RangeEnd -> timeEndMinutes
            is TimeTarget.Reminder -> reminderTimes.getOrNull(target.index) ?: 20 * 60
        }
        val state = rememberTimePickerState(
            initialHour = initialMinutes / 60,
            initialMinute = initialMinutes % 60,
            is24Hour = false
        )
        TimePickerDialog(
            onDismissRequest = { timeTarget = null },
            title = { Text(stringResource(R.string.todo_time)) },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = state.hour * 60 + state.minute
                    when (target) {
                        TimeTarget.Scheduled -> timeMinutes = minutes
                        TimeTarget.RangeStart -> {
                            val (s, e) = adjustRange(minutes, timeEndMinutes)
                            timeStartMinutes = s
                            timeEndMinutes = e
                            reminderTimes = TodoCodec.rangeTimes(s, e, rangeReminderCount)
                        }
                        TimeTarget.RangeEnd -> {
                            val (s, e) = adjustRange(timeStartMinutes, minutes)
                            timeStartMinutes = s
                            timeEndMinutes = e
                            reminderTimes = TodoCodec.rangeTimes(s, e, rangeReminderCount)
                        }
                        is TimeTarget.Reminder -> {
                            reminderTimes = if (target.index < reminderTimes.size) {
                                reminderTimes.mapIndexed { i, m -> if (i == target.index) minutes else m }
                            } else {
                                reminderTimes + minutes
                            }
                        }
                    }
                    timeTarget = null
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { timeTarget = null }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            TimePicker(state = state)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

/** How the automatic reminder fires: Off / Notification / real system Alarm. */
@Composable
private fun ReminderStyleRow(
    style: ReminderStyle,
    onSelect: (ReminderStyle) -> Unit,
    onRequestNotifications: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.todo_reminder_style),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ReminderStyle.OFF to R.string.todo_reminder_style_off,
                ReminderStyle.NOTIFICATION to R.string.todo_reminder_style_notification,
                ReminderStyle.ALARM to R.string.todo_reminder_style_alarm
            ).forEach { (option, label) ->
                FilterChip(
                    selected = style == option,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(label)) }
                )
            }
        }
    }
    Spacer(Modifier.height(3.dp))
    Text(
        text = stringResource(
            when (style) {
                ReminderStyle.OFF -> R.string.todo_reminder_style_off_note
                ReminderStyle.NOTIFICATION -> R.string.todo_reminder_style_notification_note
                ReminderStyle.ALARM -> R.string.todo_reminder_style_alarm_note
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Notification-style reminders are ALSO scheduled with exact alarms
    // (setExactAndAllowWhileIdle), so the exact-alarm permission matters for
    // both styles — without it, reminders silently fall back to inexact
    // timing and arrive minutes late. Explain and offer the one-tap
    // system-settings link for either style (not just Alarm).
    val context = LocalContext.current
    if (style != ReminderStyle.OFF) {
        if (!TodoScheduler.hasExactAlarmPermission(context)) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(
                        text = stringResource(R.string.todo_exact_alarm_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { openExactAlarmSettings(context) }) {
                        Text(stringResource(R.string.todo_exact_alarm_allow))
                    }
                }
            }
        }
    }
    // Android 13+: a Notification-style reminder is silently dropped when
    // POST_NOTIFICATIONS isn't granted — ask for it right here so the user
    // knows the reminder will actually appear.
    if (style == ReminderStyle.NOTIFICATION && needsNotificationPermission(context)) {
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    text = stringResource(R.string.todo_notification_permission_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onRequestNotifications) {
                    Text(stringResource(R.string.todo_notification_permission_allow))
                }
            }
        }
    }
}

/** True when the app still needs the Android 13+ notification permission. */
private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}

/** Opens the system page where the user can allow exact alarms (Android 12+). */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }
}
