package com.muddassir.clearview.media.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.muddassir.clearview.media.data.MediaBadge
import com.muddassir.clearview.media.data.MediaRepository

/**
 * Background job that checks every saved channel for new uploads and posts a
 * media notification ("… has an update") for each channel with something new.
 *
 * No-ops fast when the media-notifications toggle is off or no channels are
 * saved. A video is only ever notified once, guarded by TWO independent
 * conditions:
 *
 *  1. The persisted notified-id set — a video id is baselined when its channel
 *     is added (and after every notification), so re-fetches of the same RSS
 *     can never re-notify, across restarts and worker retries.
 *  2. The channel's subscription timestamp — a video is only even eligible if
 *     it was published at/after the channel was added. This is the safety net
 *     for the add-time baseline: if that first fetch failed (offline etc.) and
 *     no ids were baselined, the channel's pre-existing backlog still never
 *     notifies. Legacy channels (addedAt == 0) are unaffected.
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
        // Videos only count as new when they were published at/after the moment
        // the channel was subscribed AND their id was never seen before.
        val addedAtByChannel = channels.associate { it.channelId to it.addedAtEpochMillis }
        val newVideos = fresh.filter { v ->
            isNotificationEligible(
                videoId = v.videoId,
                publishedAtEpochMillis = v.publishedAtEpochMillis,
                addedAtEpochMillis = addedAtByChannel[v.channelId] ?: 0L,
                alreadyNotified = alreadyNotified
            )
        }
        if (newVideos.isEmpty()) return Result.success()

        // One update per channel with a new video, newest channel first.
        val updates = repository.buildChannelUpdates(newVideos)
        MediaNotifier.notifyUpdates(applicationContext, updates)

        // Store what was notified in the in-app "Latest Updates" feed (home
        // tab) so every notification also appears there — even when the OS
        // blocks the notification itself, the update is still recorded.
        repository.recordChannelUpdates(updates)

        repository.markVideosNotified(alreadyNotified + newVideos.map { it.videoId })

        // New uploads detected → the launcher badge should show them (the
        // in-app unread count is recomputed from the same persisted history).
        MediaBadge.setBadge(
            applicationContext,
            repository.countUnreadUpdates(repository.getUpdatesHistory())
        )
        return Result.success()
    }
}

/**
 * A video deserves a notification when its id was never seen before AND it was
 * published at/after the moment its channel was subscribed. The timestamp
 * guard is the safety net for the add-time baseline: if that first fetch
 * failed (offline etc.) and no ids were baselined, a channel's pre-existing
 * backlog must still never notify. Legacy channels carry [addedAtEpochMillis]
 * == 0, which admits every video — exactly their pre-feature behavior.
 */
internal fun isNotificationEligible(
    videoId: String,
    publishedAtEpochMillis: Long,
    addedAtEpochMillis: Long,
    alreadyNotified: Set<String>
): Boolean =
    videoId !in alreadyNotified &&
        publishedAtEpochMillis >= addedAtEpochMillis.coerceAtLeast(0L)
