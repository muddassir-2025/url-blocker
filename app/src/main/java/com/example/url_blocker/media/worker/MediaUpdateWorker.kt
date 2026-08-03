package com.example.url_blocker.media.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.url_blocker.media.data.MediaRepository

/**
 * Background job that checks every saved channel for new uploads and posts a
 * media notification ("… has an update") for each channel with something new.
 *
 * No-ops fast when the media-notifications toggle is off or no channels are
 * saved. New videos are tracked by id so a video is only ever notified once
 * (the set is capped at [MediaRepository.MAX_NOTIFIED_VIDEOS]).
 */
class MediaUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = MediaRepository(applicationContext)

        // Toggle off (or system notifications disabled) → nothing to do.
        if (!repository.isMediaNotificationsEnabled()) return Result.success()

        val channels = repository.getSavedChannels()
        if (channels.isEmpty()) return Result.success()

        // Refresh every feed. null = every channel failed (offline etc.) — the
        // next periodic run retries; a partial failure keeps the successes.
        val fresh = repository.refreshAllVideos(channels) ?: return Result.success()

        val alreadyNotified = repository.getNotifiedVideoIds()
        val newVideos = fresh.filter { it.videoId !in alreadyNotified }
        if (newVideos.isEmpty()) return Result.success()

        // One update per channel with a new video, newest channel first.
        val updates = repository.buildChannelUpdates(newVideos)
        MediaNotifier.notifyUpdates(applicationContext, updates)

        repository.markVideosNotified(alreadyNotified + newVideos.map { it.videoId })
        return Result.success()
    }
}
