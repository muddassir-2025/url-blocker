package com.muddassir.clearview.phonelimit

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The foreground service that runs the Phone Limit countdown reliably in the
 * background.
 *
 *  - A persistent notification (ongoing, no cancel action) shows the
 *    remaining time and updates every second — the closest Android allows to
 *    a status-bar countdown (the system clock itself cannot be replaced by
 *    an app).
 *  - It survives app/Activity recreation, screen locks, leaving the Quran
 *    tab and process death (START_STICKY restarts it; the persisted end
 *    timestamp resumes the countdown exactly where it left off).
 *  - On expiry it calls [PhoneLimitCoordinator.handleExpiry], which locks
 *    the phone via DevicePolicyManager.lockNow (when the app is a Device
 *    Admin). The AlarmManager backstop in the coordinator covers the case
 *    where even this service cannot run.
 *
 * There is deliberately no cancel path: stopping this service from the app
 * UI or the notification is not offered.
 */
class PhoneLimitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A fresh start carries the duration; a restart (START_STICKY after
        // the process was killed, or a boot resume) has none — the persisted
        // timer is resumed instead.
        val duration = intent?.getLongExtra(PhoneLimitCoordinator.EXTRA_DURATION_MILLIS, 0L) ?: 0L
        if (duration > 0L) {
            PhoneLimitCoordinator.begin(this, duration)
        }
        if (!PhoneLimitCoordinator.isActive(this)) {
            Log.i(TAG, "No active phone limit — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        PhoneLimitCoordinator.ensureChannel(this)
        startForeground(
            PhoneLimitCoordinator.NOTIF_ID,
            PhoneLimitCoordinator.buildCountdownNotification(
                this, PhoneLimitCoordinator.remainingMillis(this)
            )
        )
        startTicker()
        return START_STICKY
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var ticks = 0
            while (isActive) {
                val remaining = PhoneLimitCoordinator.remainingMillis(this@PhoneLimitService)
                if (remaining <= 0L) {
                    PhoneLimitCoordinator.handleExpiry(this@PhoneLimitService)
                    break
                }
                // Refresh the countdown notification every second; the widget
                // less often (RemoteViews churn is heavier than a notify). The
                // explicit SecurityException catch is what lint's
                // MissingPermission wants (a plain runCatching doesn't count)
                // — notifications disabled/revoked mid-run just skip the
                // shade update; the countdown itself keeps running.
                try {
                    NotificationManagerCompat.from(this@PhoneLimitService).notify(
                        PhoneLimitCoordinator.NOTIF_ID,
                        PhoneLimitCoordinator.buildCountdownNotification(
                            this@PhoneLimitService, remaining
                        )
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Countdown notification suppressed: ${e.message}")
                }
                if (++ticks % 5 == 0) {
                    PhoneLimitWidgetProvider.refreshAllWidgets(this@PhoneLimitService)
                }
                delay(1_000L)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhoneLimitService"

        /**
         * Starts the countdown service. [durationMillis] > 0 begins a new
         * limit; 0 resumes the persisted one (boot / sticky restart).
         */
        fun start(context: Context, durationMillis: Long) {
            val intent = Intent(context, PhoneLimitService::class.java)
                .putExtra(PhoneLimitCoordinator.EXTRA_DURATION_MILLIS, durationMillis)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // E.g. ForegroundServiceStartNotAllowedException on a
                // misbehaving OEM. The expiry alarm is still armed, so the
                // lock still happens; log it.
                Log.e(TAG, "Failed to start PhoneLimitService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneLimitService::class.java))
        }
    }
}
