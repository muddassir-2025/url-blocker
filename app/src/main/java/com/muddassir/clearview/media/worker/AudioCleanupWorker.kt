package com.muddassir.clearview.media.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.muddassir.clearview.media.download.AudioDownloads

/**
 * Daily background cleanup for offline audio downloads:
 *
 *  - drops orphaned metadata rows (audio file missing),
 *  - deletes stale `.part` files from interrupted downloads,
 *  - refreshes the storage state so the storage card stays accurate.
 *
 * Downloads are NEVER deleted automatically — no expiry, no eviction — so
 * this worker only ever removes leftovers, never the user's audio.
 *
 * Runs once a day via [AudioWorkScheduler] (and once at app startup through
 * [AudioDownloads.initialize]).
 */
class AudioCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AudioDownloads.initialize(applicationContext)
        // Suspends until the cleanup actually finished (orphans/parts).
        AudioDownloads.runMaintenance()
        return Result.success()
    }
}
