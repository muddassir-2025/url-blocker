package com.muddassir.clearview.phonelimit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.muddassir.clearview.R

/**
 * Handles everything Phone Limit needs outside the UI process:
 *
 *  - [PhoneLimitCoordinator.ACTION_EXPIRE] — the AlarmManager backstop fired
 *    at the end timestamp (the service may be dead or the device dozing):
 *    expire, lock the phone and clean up.
 *  - [PhoneLimitCoordinator.ACTION_START] — the home-screen widget's START
 *    button: parse the typed duration and start the countdown service. A
 *    widget interaction is a user action, so the foreground-service start is
 *    allowed on Android 12+.
 *  - [Intent.ACTION_BOOT_COMPLETED] — after a reboot the OS loses alarms and
 *    kills services: re-arm the expiry alarm and resume the countdown service.
 *
 * Exported=false: every delivery here is either a system broadcast
 * (BOOT_COMPLETED) or the app's own explicit PendingIntent.
 */
class PhoneLimitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PhoneLimitCoordinator.ACTION_EXPIRE -> {
                PhoneLimitCoordinator.handleExpiry(context)
                PhoneLimitService.stop(context)
            }

            PhoneLimitCoordinator.ACTION_START -> {
                val duration = intent.getLongExtra(
                    PhoneLimitCoordinator.EXTRA_DURATION_MILLIS, 0L
                ).takeIf { it > 0L } ?: PhoneLimitCoordinator.parseDuration(
                    intent.getStringExtra(PhoneLimitCoordinator.EXTRA_TEXT_INPUT).orEmpty()
                )
                if (duration != null) {
                    PhoneLimitService.start(context, duration)
                } else {
                    // Nothing parseable — tell the user and leave the widget
                    // on the idle screen (it re-renders on the next update).
                    Toast.makeText(
                        context,
                        context.getString(R.string.phone_limit_invalid_input),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                val end = PhoneLimitCoordinator.activeEndTime(context) ?: return
                if (end <= System.currentTimeMillis()) {
                    // Expired while the phone was off — lock now.
                    PhoneLimitCoordinator.handleExpiry(context)
                } else {
                    PhoneLimitCoordinator.scheduleExpiryAlarm(context)
                    PhoneLimitService.start(context, 0L)
                }
            }
        }
    }
}
