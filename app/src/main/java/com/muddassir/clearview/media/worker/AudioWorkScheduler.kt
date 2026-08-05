package com.muddassir.clearview.media.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily offline-audio cleanup ([AudioCleanupWorker]).
 *
 * WorkManager persists the schedule across restarts, so [ensureScheduled] is
 * idempotent (KEEP policy) and is called from app startup (both activities).
 */
object AudioWorkScheduler {

    private const val PERIODIC_WORK = "audio_cleanup_daily"

    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AudioCleanupWorker>(1, TimeUnit.DAYS).build()
        )
    }
}
