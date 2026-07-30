package com.example.url_blocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.url_blocker.LauncherActivity
import com.example.url_blocker.R

/**
 * Foreground service that:
 * 1. Shows a persistent high-priority notification (making the app harder to ignore/disable)
 * 2. Monitors the Accessibility Service status via ContentObserver
 * 3. After the service has been disabled for 5 minutes, opens Accessibility Settings as a prompt
 *
 * This service AUTO-STARTS when the Accessibility Service is enabled and runs continuously.
 * It is the user-facing "always-on" monitor that provides the re-enable prompt behavior.
 *
 * NOTE: This service CANNOT programmatically re-enable the Accessibility Service.
 * Android security requires user action for that. It can only open the settings page
 * as a prompt/reminder.
 */
class ProtectionMonitorService : Service() {

    companion object {
        private const val TAG = "ProtectionMonitor"
        private const val CHANNEL_ID = "protection_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val REENABLE_DELAY_MS = 5 * 60 * 1000L // 5 minutes
        private const val OUR_SERVICE_CLASS = "com.example.url_blocker.service.UrlBlockerService"

        fun start(context: Context) {
            val intent = Intent(context, ProtectionMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionMonitorService::class.java))
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var serviceEnabledObserver: ContentObserver? = null
    private var disableDetectedTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reenablePromptRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.i(TAG, "ProtectionMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ProtectionMonitorService starting...")

        // Show persistent notification
        startForeground(NOTIFICATION_ID, buildProtectionNotification(true))

        // Register ContentObserver to watch Accessibility Service state
        registerServiceObserver()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterServiceObserver()
        cancelReenablePrompt()
        Log.i(TAG, "ProtectionMonitorService destroyed")
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.protection_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.protection_notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProtectionNotification(isProtected: Boolean): Notification {
        val openIntent = Intent(this, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isProtected) "Protection Active" else "Protection Inactive"
        val text = if (isProtected)
            "Monitoring Chrome, Google, and YouTube for blocked content"
        else
            "Tap to re-enable protection"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(isProtected: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, buildProtectionNotification(isProtected))
    }

    // ── Service Monitoring ─────────────────────────────────────────

    private fun registerServiceObserver() {
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                checkServiceEnabled()
            }
        }
        serviceEnabledObserver = observer
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer
        )
        // Initial check
        checkServiceEnabled()
    }

    private fun unregisterServiceObserver() {
        serviceEnabledObserver?.let { contentResolver.unregisterContentObserver(it) }
        serviceEnabledObserver = null
    }

    private fun checkServiceEnabled() {
        val isEnabled = isAccessibilityServiceEnabled()
        Log.d(TAG, "Service enabled check: $isEnabled")
        updateNotification(isEnabled)

        if (isEnabled) {
            // Service is active — reset any pending re-enable prompt
            disableDetectedTime = 0L
            cancelReenablePrompt()
        } else {
            // Service is disabled — start 5-minute countdown if not already started
            if (disableDetectedTime == 0L) {
                disableDetectedTime = System.currentTimeMillis()
                scheduleReenablePrompt()
            }
        }
    }

    private fun scheduleReenablePrompt() {
        cancelReenablePrompt()
        val runnable = Runnable {
            // 5 minutes have elapsed since the service was disabled
            Log.w(TAG, "Service has been disabled for 5+ minutes — prompting user")
            openAccessibilitySettings()
        }
        reenablePromptRunnable = runnable
        mainHandler.postDelayed(runnable, REENABLE_DELAY_MS)
        Log.i(TAG, "Re-enable prompt scheduled in ${REENABLE_DELAY_MS / 60000} minutes")
    }

    private fun cancelReenablePrompt() {
        reenablePromptRunnable?.let { mainHandler.removeCallbacks(it) }
        reenablePromptRunnable = null
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Accessibility Settings opened as re-enable prompt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Accessibility Settings: ${e.message}")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        } ?: return false
        return enabledServices.split(':').any { it.equals(OUR_SERVICE_CLASS, ignoreCase = true) }
    }
}
