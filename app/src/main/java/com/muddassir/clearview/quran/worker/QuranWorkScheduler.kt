package com.muddassir.clearview.quran.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.muddassir.clearview.quran.data.QuranStore
import java.util.concurrent.TimeUnit

/**
 * Schedules the Quran reminder's background work:
 *  - a one-time initial download (only runs when the cache is missing), and
 *  - a periodic refresh that shows a new random verse on the user's chosen
 *    interval (stored in [QuranStore], default 6 hours).
 *
 * WorkManager persists these across app restarts and device reboots, so this
 * only needs to be called once (idempotent).
 */
object QuranWorkScheduler {

    private const val INITIAL_WORK = "quran_reminder_initial_download"
    private const val PERIODIC_WORK = "quran_reminder_periodic_refresh"
    private const val REFRESH_NOW_WORK = "quran_reminder_refresh_now"

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

        // Periodic refresh on the user-chosen interval (default 6 hours).
        // Deliberately NO network constraint: MODE_REFRESH works fully offline
        // (it picks a random verse from the local cache), so an offline device
        // must still refresh the verse instead of deferring it until
        // connectivity returns.
        //
        // KEEP (not UPDATE): ensureScheduled is also called on every app
        // launch, and UPDATE cancels + re-enqueues the periodic work — which
        // would reset the interval countdown each time the app opens. KEEP
        // preserves the original schedule, so the verse genuinely refreshes on
        // schedule regardless of how often the app is launched. (Changing the
        // interval goes through [reschedule], which uses UPDATE.)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(context)
        )
    }

    /**
     * Re-schedules the periodic refresh at the interval currently stored in
     * [QuranStore]. Called from the verse screen when the user picks a new
     * frequency (1h, 2h, 3h…). UPDATE cancels the old periodic work and
     * enqueues a fresh one, so the new interval takes effect immediately
     * (the countdown restarts from the moment of the change).
     */
    fun reschedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(context)
        )
    }

    private fun periodicRequest(context: Context): PeriodicWorkRequest {
        val intervalHours = QuranStore(context).getRefreshIntervalHours()
        return PeriodicWorkRequestBuilder<QuranDownloadWorker>(
            intervalHours.toLong(),
            TimeUnit.HOURS
        )
            .setInputData(
                workDataOf(QuranDownloadWorker.KEY_MODE to QuranDownloadWorker.MODE_REFRESH)
            )
            .build()
    }

    /**
     * Picks a new random verse NOW (the widget's Refresh button). Offline-safe:
     * same MODE_REFRESH path as the periodic work. KEEP so a second tap while
     * one refresh is already running doesn't stack a duplicate worker.
     */
    fun refreshNow(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val refresh = OneTimeWorkRequestBuilder<QuranDownloadWorker>()
            .setInputData(
                workDataOf(QuranDownloadWorker.KEY_MODE to QuranDownloadWorker.MODE_REFRESH)
            )
            .build()
        workManager.enqueueUniqueWork(REFRESH_NOW_WORK, ExistingWorkPolicy.KEEP, refresh)
    }
}
