package com.muddassir.clearview.media.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the media (channel-update) checks:
 *
 *  - a periodic run every 60 minutes that refreshes every saved channel's RSS
 *    and notifies about new uploads. It stays scheduled even when the toggle
 *    is off — the worker itself reads the toggle and no-ops — so flipping the
 *    switch never needs to wait for a new schedule.
 *  - [checkNow] forces an immediate one-time run (used when the user turns the
 *    toggle on, so existing uploads are notified right away).
 *
 * WorkManager persists both across restarts, so [ensureScheduled] is
 * idempotent (KEEP policy) and is called once from app startup.
 */
object MediaWorkScheduler {

    private const val PERIODIC_WORK = "media_updates_periodic"
    private const val CHECK_NOW_WORK = "media_updates_check_now"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueues the periodic check (idempotent). */
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaUpdateWorker>(60, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
        )
    }

    /** Runs an immediate check (used when the user enables the toggle). */
    fun checkNow(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            CHECK_NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaUpdateWorker>()
                .setConstraints(networkConstraints)
                .build()
        )
    }
}
