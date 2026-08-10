package com.muddassir.clearview.todo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoAlarmService
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.ui.theme.UrlblockerTheme
import java.time.LocalDate

/**
 * The notification Snooze picker: a tiny dialog-styled activity (themed as a
 * dialog window, launched from the notification's Snooze action — and from the
 * full-screen alarm's Snooze button) offering **10 minutes, 30 minutes,
 * 1 hour, or a CUSTOM number of minutes** (1 minute up to hours).
 * Selecting one re-schedules that exact reminder occurrence (same request
 * code → no duplicates) and dismisses the original notification. Exported=false
 * — only the app's own PendingIntent opens it.
 */
class TodoSnoozeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID)
        if (todoId == null) {
            finish()
            return
        }
        val index = intent.getIntExtra(TodoNotifier.EXTRA_REMINDER_INDEX, 0)
        val epochDay = intent.getLongExtra(
            TodoNotifier.EXTRA_EPOCH_DAY,
            LocalDate.now().toEpochDay()
        )
        // The user chose Snooze: pause the looping ringtone NOW. The re-armed
        // alarm rings again (full screen + audio) when it fires.
        TodoAlarmService.stop(this)
        setContent {
            UrlblockerTheme {
                // The activity window is already dialog-themed (Theme.Urlblocker.Dialog),
                // so the card is rendered directly — no nested Dialog window.
                SnoozePickerContent(
                    onSnooze = { minutes ->
                        TodoScheduler.snoozeFromNotification(
                            this, todoId, index, epochDay, minutes
                        )
                        // The original notification is handled: it leaves the
                        // shade, and the fresh reminder posts when it fires.
                        TodoNotifier.cancelDayNotification(this, todoId, epochDay)
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun SnoozePickerContent(
    onSnooze: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.todo_snooze_for),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
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
                Spacer(Modifier.height(8.dp))
            }
            // Custom: any number of minutes — 1 minute up to hours.
            OutlinedButton(
                onClick = { customOpen = !customOpen },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.todo_snooze_custom), modifier = Modifier.weight(1f))
            }
            if (customOpen) {
                Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.width(10.dp))
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
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.todo_cancel))
            }
        }
    }
}
