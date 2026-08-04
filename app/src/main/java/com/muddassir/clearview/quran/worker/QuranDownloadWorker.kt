package com.muddassir.clearview.quran.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.muddassir.clearview.quran.data.QuranRepository
import com.muddassir.clearview.quran.widget.QuranReminderWidgetProvider

/**
 * Background worker for the Quran reminder.
 *
 * Two modes (via input data [KEY_MODE]):
 *  - [MODE_ONLY_IF_MISSING]: download + cache the full translation only when it
 *    is not yet cached, then pick a first verse. Used as the initial one-time
 *    work so a fresh install gets data without waiting 6 hours.
 *  - [MODE_REFRESH]: pick a new random verse from the cache (no network when
 *    already cached). Used by the periodic 6-hour refresh.
 *
 * After picking a verse the widget is re-rendered so the new verse shows up
 * without the user touching it.
 */
class QuranDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = QuranRepository(applicationContext)
        val mode = inputData.getInt(KEY_MODE, MODE_REFRESH)

        return try {
            // English is required; Arabic (for the verse screen) is best-effort
            // inside ensureEnglishAndArabic — a missing Arabic cache never fails
            // the worker, the reminder stays fully functional in English.
            val downloaded = repository.ensureEnglishAndArabic()
            if (!downloaded) {
                Log.w(TAG, "Quran download failed (offline?) — will retry")
                return Result.retry()
            }

            val picked = if (mode == MODE_REFRESH || repository.getCurrentVerse() == null) {
                repository.pickRandomVerse()
            } else {
                null
            }
            // A background refresh chose a fresh verse → notify the user (when
            // the Quran-notifications toggle + OS permission allow it). The
            // initial one-time download never notifies.
            if (mode == MODE_REFRESH && picked != null) {
                QuranNotifier.notifyNewVerse(applicationContext, picked)
            }

            QuranReminderWidgetProvider.refreshAllWidgets(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Quran worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "QuranDownloadWorker"

        const val KEY_MODE = "quran_worker_mode"
        const val MODE_ONLY_IF_MISSING = 0
        const val MODE_REFRESH = 1
    }
}
