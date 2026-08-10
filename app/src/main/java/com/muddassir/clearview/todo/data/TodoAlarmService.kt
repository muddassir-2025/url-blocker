package com.muddassir.clearview.todo.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.ui.TodoAlarmActivity
import java.time.LocalDate

/**
 * The LOUD half of an alarm-style todo.
 *
 * A foreground service so the alarm ringtone keeps ringing for a full minute
 * — even with the app in the background and the screen off, and even after
 * the full-screen alarm screen is dismissed (swiped away) without acting. A
 * notification channel sound cannot do this: it plays once and stops with the
 * notification.
 *
 * The ringtone (system ALARM sound, STREAM_ALARM, looping) is played HERE —
 * the alarm notification channel is deliberately silent so there is never
 * double audio. The foreground notification is the same alarm notification
 * [TodoNotifier] builds (full-screen intent → [TodoAlarmActivity] over the
 * lock screen, plus Complete / Snooze / ✕ actions).
 *
 * Lifecycle:
 *  - [start] — fired by [TodoReminderReceiver] when an alarm-style reminder's
 *    alarm goes off (setAlarmClock → exact-alarm exemption lets a background
 *    receiver start a foreground service). Idempotent: re-starting the same
 *    occurrence keeps the ring, it is never restarted.
 *  - Ring for [RING_DURATION_MS] (one minute), then the sound stops on its own
 *    but the notification stays in the shade so the user can still act.
 *  - Any Complete / Snooze / Dismiss stops the ring immediately — every one of
 *    those paths calls [TodoNotifier.cancelDayNotification], which stops this
 *    service ([stop]).
 */
class TodoAlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    private var ringingTodoId: String? = null
    private var ringingIndex: Int = -1
    private var ringingEpochDay: Long = -1L
    private var notificationId: Int = 0

    private val stopRing = Runnable { stopRingtone() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val todoId = intent?.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: return finishStart()
        val index = intent.getIntExtra(TodoNotifier.EXTRA_REMINDER_INDEX, 0)
        val epochDay = intent.getLongExtra(
            TodoNotifier.EXTRA_EPOCH_DAY,
            LocalDate.now().toEpochDay()
        )

        // Already ringing for this exact occurrence — keep ringing, never
        // restart (the full-screen activity also calls start()).
        if (ringingTodoId == todoId && ringingIndex == index && ringingEpochDay == epochDay) {
            return START_NOT_STICKY
        }

        val item: TodoItem? = TodoStore(this).getItems().firstOrNull { it.id == todoId }
        val day = LocalDate.ofEpochDay(epochDay)
        // A deleted todo or an already-completed day must never ring.
        if (item == null || !TodoCodec.isActiveOn(item, day) || TodoCodec.completedOn(item, day)) {
            return finishStart()
        }

        ringingTodoId = todoId
        ringingIndex = index
        ringingEpochDay = epochDay
        notificationId = TodoNotifier.notificationId(todoId, epochDay)

        // The todo_alarms channel MUST exist before startForeground — missing
        // channels crash the process. Idempotent; also repairs a legacy loud
        // copy so the service loop is never doubled.
        TodoNotifier.ensureChannel(this)

        // Foreground FIRST — required within 5s of startForegroundService. The
        // notification carries the full-screen intent + actions (see TodoNotifier).
        startForeground(
            notificationId,
            TodoNotifier.buildReminderNotification(this, item, epochDay, index)
        )
        startRingtone()
        // Show the full-screen alarm screen even while the phone is being used:
        // Android only launches a notification's full-screen intent over a
        // LOCKED / OFF screen — when the screen is on and unlocked it degrades
        // to a mere heads-up card, which looked like the alarm lost its full
        // screen. Launching the alarm activity directly succeeds while this
        // app is in the foreground (screen on); when the app is backgrounded
        // the system blocks the launch and the notification's full-screen
        // intent takes over (the lock-screen path). singleInstance + the
        // extras make a concurrent FSI launch a no-op, never a stack.
        showAlarmScreen()
        handler.postDelayed(stopRing, RING_DURATION_MS)
        return START_NOT_STICKY
    }

    /** The looping alarm ringtone + repeating vibration. */
    private fun startRingtone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setDataSource(this@TodoAlarmService, uri)
                prepare()
                start()
            }
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = longArrayOf(0, 500, 400, 500, 400, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "ALARM_RINGING todoId=$ringingTodoId uri=$uri")
        } catch (t: Throwable) {
            Log.e(TAG, "ALARM_RINGTONE_FAILED", t)
        }
    }

    /**
     * Launches the full-screen alarm activity for the ringing occurrence.
     * Succeeds while this app is in the foreground; when the app is
     * backgrounded the background-activity-launch restriction blocks it and
     * the notification's own full-screen intent handles the lock-screen path.
     */
    private fun showAlarmScreen() {
        val todoId = ringingTodoId ?: return
        try {
            val intent = Intent(this, TodoAlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(TodoNotifier.EXTRA_TODO_ID, todoId)
                putExtra(TodoNotifier.EXTRA_REMINDER_INDEX, ringingIndex)
                putExtra(TodoNotifier.EXTRA_EPOCH_DAY, ringingEpochDay)
            }
            startActivity(intent)
        } catch (e: SecurityException) {
            // Background activity launch is blocked while the app is not in the
            // foreground — exactly the case the notification's full-screen
            // intent covers (locked / screen off). Nothing to do here.
        }
    }

    /** Silences the ring without tearing down the service (used by the 60s timer). */
    private fun stopRingtone() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
        vibrator?.cancel()
        handler.removeCallbacks(stopRing)
        Log.d(TAG, "ALARM_RING_END (60s auto-stop or manual)")
    }

    private fun finishStart(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRingtone()
        if (notificationId != 0) {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        ringingTodoId = null
        ringingIndex = -1
        ringingEpochDay = -1L
        Log.d(TAG, "ALARM_STOPPED")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TodoAlarmService"

        /** How long the alarm rings: one full minute, then quiet. */
        const val RING_DURATION_MS = 60_000L

        /** Starts the ringing alarm service for an alarm-style occurrence. */
        fun start(context: Context, todoId: String, reminderIndex: Int, epochDay: Long) {
            val intent = Intent(context, TodoAlarmService::class.java).apply {
                putExtra(TodoNotifier.EXTRA_TODO_ID, todoId)
                putExtra(TodoNotifier.EXTRA_REMINDER_INDEX, reminderIndex)
                putExtra(TodoNotifier.EXTRA_EPOCH_DAY, epochDay)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the ring immediately (no-op when the service is not running). */
        fun stop(context: Context) {
            context.stopService(Intent(context, TodoAlarmService::class.java))
        }
    }
}
