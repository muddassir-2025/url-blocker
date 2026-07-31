package com.example.url_blocker.quran.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules the Quran reminder's background work:
 *  - a one-time initial download (only runs when the cache is missing), and
 *  - a periodic refresh that shows a new random verse every 6 hours.
 *
 * WorkManager persists these across app restarts and device reboots, so this
 * only needs to be called once (idempotent).
 */
object QuranWorkScheduler {

    private const val INITIAL_WORK = "quran_reminder_initial_download"
    private const val PERIODIC_WORK = "quran_reminder_periodic_refresh"

    // Only the INITIAL download needs a network connection.
    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueues the periodic refresh + a one-time initial download if needed. */
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context)

        // Initial download (one-time). KEEP: if a download is already pending or
        // running, don't stack another one.
        val initial = OneTimeWorkRequestBuilder<QuranDownloadWorker>()
            .setConstraints(networkConstraints)
            .setInputData(
                workDataOf(QuranDownloadWorker.KEY_MODE to QuranDownloadWorker.MODE_ONLY_IF_MISSING)
            )
            .build()
        workManager.enqueueUniqueWork(INITIAL_WORK, ExistingWorkPolicy.KEEP, initial)

        // Periodic refresh every 6 hours. Deliberately NO network constraint:
        // MODE_REFRESH works fully offline (it picks a random verse from the
        // local cache), so an offline device must still refresh the verse
        // instead of deferring it until connectivity returns.
        val periodic = PeriodicWorkRequestBuilder<QuranDownloadWorker>(6, TimeUnit.HOURS)
            .setInputData(
                workDataOf(QuranDownloadWorker.KEY_MODE to QuranDownloadWorker.MODE_REFRESH)
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
    }
}
