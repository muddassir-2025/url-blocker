package com.muddassir.clearview.todo.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoAlarmService
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.ui.theme.UrlblockerTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val ALARM_BG = Color(0xFF101418)
private val ALARM_GREEN = Color(0xFF43A047)

/**
 * The REAL full-screen alarm for alarm-style todos. The system shows this
 * activity over the lock screen via the notification's full-screen intent
 * (the sanctioned alarm pattern — launching it directly from a background
 * alarm is blocked by the background-activity-launch restriction).
 *
 * [TodoAlarmService] plays the looping ringtone (one full minute, even if
 * this screen is dismissed without acting); this screen is the visible alarm
 * with Complete / Snooze / Dismiss. All three cancel the day's notification,
 * which also stops the ringing service.
 *
 * Note: the reminder receiver already did the bookkeeping (consumed the fired
 * record, chained the next occurrence) when the alarm broadcast arrived.
 */
class TodoAlarmActivity : ComponentActivity() {

    private var todoId: String = ""
    private var reminderIndex: Int = 0
    private var epochDay: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the device (FLAG_* works on all
        // APIs; showWhenLocked/turnScreenOn in the manifest cover API 27+).
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        // Make sure the looping ringtone is running (the receiver usually
        // already started it; start() is idempotent for the same occurrence).
        todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: run {
            finish()
            return
        }
        reminderIndex = intent.getIntExtra(TodoNotifier.EXTRA_REMINDER_INDEX, 0)
        epochDay = intent.getLongExtra(
            TodoNotifier.EXTRA_EPOCH_DAY,
            LocalDate.now().toEpochDay()
        )
        val store = TodoStore(this)
        val item = store.getItems().firstOrNull { it.id == todoId }
        val day = LocalDate.ofEpochDay(epochDay)
        // A deleted todo, or a day already completed, must never ring.
        if (item == null || TodoCodec.completedOn(item, day)) {
            TodoAlarmService.stop(this)
            finish()
            return
        }
        TodoAlarmService.start(this, todoId, reminderIndex, epochDay)

        setContent {
            UrlblockerTheme {
                AlarmScreen(
                    item = item,
                    onComplete = {
                        complete(store, item, day)
                        finish()
                    },
                    onSnooze = {
                        startActivity(
                            Intent(this, TodoSnoozeActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                action = TodoNotifier.ACTION_SNOOZE
                                putExtra(TodoNotifier.EXTRA_TODO_ID, todoId)
                                putExtra(TodoNotifier.EXTRA_REMINDER_INDEX, reminderIndex)
                                putExtra(TodoNotifier.EXTRA_EPOCH_DAY, epochDay)
                            }
                        )
                        finish()
                    },
                    onDismiss = {
                        TodoNotifier.cancelDayNotification(this, todoId, epochDay)
                        finish()
                    }
                )
            }
        }
    }

    private fun complete(store: TodoStore, item: TodoItem, day: LocalDate) {
        // Same date rule as everywhere: only a completion INSIDE an open
        // window counts — a strict-interval todo whose window closed cannot
        // be redone, so Complete degrades to a dismiss.
        if (TodoCodec.canCompleteOn(item, day, System.currentTimeMillis())) {
            val items = store.getItems()
            store.saveItems(
                TodoCodec.completed(items, item.id, day, System.currentTimeMillis())
            )
            // Completing cancels the day's pending reminders and re-schedules
            // the remaining future ones.
            TodoScheduler.rescheduleAll(this)
        }
        // Stop the ringing notification either way.
        TodoNotifier.cancelDayNotification(this, todoId, epochDay)
    }
}

/** The full-screen alarm UI: big clock, todo, and Complete / Snooze / Dismiss. */
@Composable
private fun AlarmScreen(
    item: TodoItem,
    onComplete: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    val today = LocalDate.now()
    Surface(modifier = Modifier.fillMaxSize(), color = ALARM_BG) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.todo_alarm_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = DateTimeFormatter.ofPattern("h:mm").format(now),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 84.sp
            )
            Text(
                text = DateTimeFormatter.ofPattern("EEEE, MMMM d · a").format(now),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(40.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.details.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = TodoCodec.scheduleLabel(item, today),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ALARM_GREEN)
                ) {
                    Text(
                        text = stringResource(R.string.todo_notification_complete),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(
                            text = stringResource(R.string.todo_notification_snooze),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
                    ) {
                        Text(
                            text = stringResource(R.string.todo_notification_dismiss),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
