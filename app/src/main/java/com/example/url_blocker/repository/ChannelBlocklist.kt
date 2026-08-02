package com.example.url_blocker.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent store of permanently blocked YouTube channels, with a
 * strike-based escalation policy.
 *
 * A channel is only permanently blocked after [BLOCK_THRESHOLD] distinct
 * blocked videos from it (2 by default). Each blocked video from an
 * unidentified channel records a strike; once the threshold is reached the
 * channel is added to the blocked set. Every subsequent video from a blocked
 * channel is blocked immediately, regardless of its title.
 *
 * Channel identity is normalized (lowercase, trimmed, leading "@" stripped)
 * so "CNN" and "@CNN" match the same block.
 */
class ChannelBlocklist(context: Context) {

    companion object {
        private const val TAG = "ChannelBlocklist"
        private const val PREFS_NAME = "channel_blocks"
        private const val KEY_BLOCKED = "blocked_channels"
        private const val KEY_HISTORY = "block_history"

        /** Number of distinct blocked videos before a channel is permanently blocked. */
        const val BLOCK_THRESHOLD = 2

        /** Normalize a channel name/handle for storage and matching. */
        fun normalize(name: String?): String? {
            if (name.isNullOrBlank()) return null
            var n = name.trim().lowercase(Locale.ROOT)
            while (n.startsWith("@")) n = n.removePrefix("@").trim()
            return n.ifEmpty { null }
        }

        /**
         * Compute the new strike count for a channel given its current count
         * and the video ids already seen. Re-blocking a video whose id is
         * already present does NOT increment — only DISTINCT blocked videos
         * count toward the threshold. A null id is treated as new (the id is
         * unavailable to detect a repeat). Pure so unit tests can cover the
         * strike policy without an Android Context.
         */
        fun nextStrikeCount(current: Int, videoId: String?, existingVideoIds: List<String>): Int =
            current + if (videoId == null || videoId !in existingVideoIds) 1 else 0
    }

    /** Outcome of recording a strike for a video from a channel. */
    enum class StrikeResult {
        /** No channel name was supplied — nothing recorded. */
        IGNORED,
        /** Strike recorded, but threshold not yet reached. */
        RECORDED,
        /** Strike crossed the threshold — the channel is now permanently blocked. */
        CHANNEL_BLOCKED,
        /** Channel was already permanently blocked. */
        ALREADY_BLOCKED
    }

    /** Metadata about why/when a channel was blocked (for future UI + logs). */
    class BlockRecord(
        val channelName: String,
        val blockedAt: Long,
        val strikeCount: Int,
        val sourceVideoIds: List<String>,
        val reason: String?
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val blockedChannels = mutableSetOf<String>()
    private val blockHistory = mutableMapOf<String, BlockRecord>()

    init {
        load()
        Log.i(TAG, "BLOCKLIST_LOADED blockedChannels=${blockedChannels.size}")
    }

    /** True when [channelName] is permanently blocked (normalized match). */
    fun isBlocked(channelName: String?): Boolean {
        val key = normalize(channelName) ?: return false
        return key in blockedChannels
    }

    /**
     * Record that a blocked video came from [channelName]. When the channel
     * reaches [BLOCK_THRESHOLD] strikes it is permanently blocked.
     */
    fun recordStrike(
        channelName: String?,
        videoId: String?,
        videoTitle: String?,
        reason: String?
    ): StrikeResult {
        val name = channelName?.trim() ?: return StrikeResult.IGNORED
        val key = normalize(name) ?: return StrikeResult.IGNORED
        if (key in blockedChannels) return StrikeResult.ALREADY_BLOCKED

        val record = blockHistory[key]
        val existingIds = record?.sourceVideoIds ?: emptyList()

        // Only DISTINCT blocked videos count toward the threshold (2-strike
        // policy): re-blocking a video already seen is not a new strike.
        val videoIds = if (videoId != null && videoId !in existingIds) {
            existingIds + videoId
        } else {
            existingIds
        }
        val strikeCount = nextStrikeCount(record?.strikeCount ?: 0, videoId, existingIds)

        blockHistory[key] = BlockRecord(
            channelName = name,
            blockedAt = record?.blockedAt ?: System.currentTimeMillis(),
            strikeCount = strikeCount,
            sourceVideoIds = videoIds,
            reason = reason
        )

        if (strikeCount >= BLOCK_THRESHOLD) {
            blockedChannels.add(key)
            save()
            Log.w(
                TAG,
                "CHANNEL_BLOCKED name='$name' strikes=$strikeCount " +
                    "threshold=$BLOCK_THRESHOLD reason='$reason'"
            )
            return StrikeResult.CHANNEL_BLOCKED
        }

        save()
        Log.i(TAG, "CHANNEL_STRIKE name='$name' strikes=$strikeCount/$BLOCK_THRESHOLD")
        return StrikeResult.RECORDED
    }

    /** Explicitly block a channel (e.g. manual action from future UI). */
    fun addChannel(channelName: String, reason: String?) {
        val key = normalize(channelName) ?: return
        blockedChannels.add(key)
        if (blockHistory[key] == null) {
            blockHistory[key] = BlockRecord(
                channelName = channelName.trim(),
                blockedAt = System.currentTimeMillis(),
                strikeCount = BLOCK_THRESHOLD,
                sourceVideoIds = emptyList(),
                reason = reason
            )
        }
        save()
        Log.w(TAG, "CHANNEL_ADDED name='${channelName.trim()}' reason='$reason'")
    }

    /** Remove a channel from the blocklist (and its history). */
    fun removeChannel(channelName: String?) {
        val name = channelName?.trim() ?: return
        val key = normalize(name) ?: return
        blockedChannels.remove(key)
        blockHistory.remove(key)
        save()
        Log.i(TAG, "CHANNEL_UNBLOCKED name='$name'")
    }

    fun getBlockedChannels(): Set<String> = blockedChannels.toSet()

    fun getBlockRecord(channelName: String?): BlockRecord? =
        normalize(channelName)?.let { blockHistory[it] }

    fun getBlockedCount(): Int = blockedChannels.size

    // ── Persistence ────────────────────────────────────────────────

    private fun load() {
        try {
            blockedChannels.addAll(prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())

            val historyJson = prefs.getString(KEY_HISTORY, null)
            if (historyJson != null) {
                val arr = JSONArray(historyJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val key = obj.getString("key")
                    val videoIds = obj.optJSONArray("videoIds")?.let { json ->
                        (0 until json.length()).map { json.getString(it) }
                    } ?: emptyList()
                    blockHistory[key] = BlockRecord(
                        channelName = obj.getString("name"),
                        blockedAt = obj.getLong("blockedAt"),
                        strikeCount = obj.getInt("strikes"),
                        sourceVideoIds = videoIds,
                        reason = obj.optString("reason").ifEmpty { null }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLOCKLIST_LOAD_FAILED: ${e.message}")
        }
    }

    private fun save() {
        try {
            prefs.edit().putStringSet(KEY_BLOCKED, blockedChannels).apply()

            val arr = JSONArray()
            for ((key, record) in blockHistory) {
                val videoIds = JSONArray()
                record.sourceVideoIds.forEach { videoIds.put(it) }
                arr.put(
                    JSONObject()
                        .put("key", key)
                        .put("name", record.channelName)
                        .put("blockedAt", record.blockedAt)
                        .put("strikes", record.strikeCount)
                        .put("videoIds", videoIds)
                        .put("reason", record.reason ?: "")
                )
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "BLOCKLIST_SAVE_FAILED: ${e.message}")
        }
    }
}
