package com.muddassir.clearview.backend

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Channel-blocking repository backed by the shared ClearView backend.
 *
 *  - Fetches and caches the combined rules (blocked channels + extra keywords)
 *    so the accessibility hot path never waits on the network.
 *  - Resolves and checks channels via GET /api/channels/check (channelId >
 *    handle > videoId), with a 24h local cache for handle→channelId and
 *    channelId→blocked so repeated checks of the same channel are free.
 *  - Never throws: a dead backend leaves the previous cache intact and every
 *    check returns "not blocked" (keyword protection still applies).
 *
 * Design mirrors [com.muddassir.clearview.media.data.ChannelIdResolver]:
 * HttpURLConnection + coroutines, no new dependencies.
 */
class ChannelBlockRepository(private val context: Context) {

    companion object {
        private const val TAG = "ChannelBlockRepository"
        private const val PREFS = "clearview_backend_cache"
        private const val KEY_RULES = "rules_json"
        private const val KEY_HANDLE_TO_ID = "handle_to_id_json"
        private const val KEY_ID_BLOCKED = "id_blocked_json"
        private const val KEY_LAST_SYNC = "last_sync_ms"
        private const val RULES_TTL_MS = 60 * 60 * 1000L      // 1h
        private const val LOOKUP_TTL_MS = 24 * 60 * 60 * 1000L // 24h
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val backend = ClearViewBackendClient(context)

    /** Cached extra keywords from the backend (merged into keyword checks). */
    @Volatile
    var backendKeywords: List<String> = emptyList()
        private set

    /** Cached blocked channels (channelId -> handle/name) from the backend. */
    @Volatile
    var blockedChannels: List<ClearViewBackendClient.BlockedChannel> = emptyList()
        private set

    /** True when a background sync is in flight (avoid duplicate work). */
    @Volatile
    private var syncing = false

    init {
        loadCachedState()
    }

    /** Load cached rules from disk (no network). Called on construction. */
    private fun loadCachedState() {
        try {
            val rulesJson = prefs.getString(KEY_RULES, null)?.let { JSONObject(it) }
            if (rulesJson != null) {
                blockedChannels = parseChannels(rulesJson.optJSONArray("channels"))
                backendKeywords = jsonArrayToStrings(rulesJson.optJSONArray("keywords"))
            }
            loadHandleMap()
            loadBlockedMap()
        } catch (e: Exception) {
            Log.e(TAG, "loadCachedState error: ${e.message}")
        }
    }

    /**
     * Refresh the cached rules from the backend. Safe to call from any thread;
     * guards against concurrent syncs. Returns true on success.
     */
    suspend fun syncRules(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (syncing) return@withContext false
        val last = prefs.getLong(KEY_LAST_SYNC, 0L)
        if (!force && System.currentTimeMillis() - last < RULES_TTL_MS) {
            return@withContext true // cache is fresh
        }
        syncing = true
        try {
            val rules = backend.getRules() ?: return@withContext false
            blockedChannels = rules.channels
            backendKeywords = rules.keywords
            persistRules(rules)
            rebuildLookupCaches(rules.channels)
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            Log.i(TAG, "RULES_SYNCED channels=${rules.channels.size} keywords=${rules.keywords.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncRules error: ${e.message}")
            false
        } finally {
            syncing = false
        }
    }

    /**
     * Is this channel blocked? Checks the local cache first (no network),
     * then the backend for uncached identities. channelId is canonical;
     * handle/videoId fall back to backend resolution.
     */
    suspend fun isChannelBlocked(
        channelId: String? = null,
        handle: String? = null,
        videoId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // 1. Local blocked-id cache (fast path).
        channelId?.let { id ->
            if (readBlockedMap().getOrElse(id) { false } == true) return@withContext true
        }
        handle?.let { h ->
            val id = readHandleMap()[h.lowercase()]
            if (id != null && readBlockedMap().getOrElse(id) { false } == true) {
                return@withContext true
            }
        }

        // 2. Backend check for anything not cached locally.
        val res = backend.checkChannel(channelId, handle, videoId)
        if (res == null) {
            // Backend unreachable: rely on cached rules only.
            return@withContext isBlockedByCachedRules(channelId, handle)
        }
        res.channelId?.let { writeBlockedMap(it, res.blocked) }
        // Backend gave a definitive answer — trust it over the stale local
        // cache. (Previously we fell through to isBlockedByCachedRules here,
        // which could return true from a stale cached list even after the
        // backend explicitly said the channel is NOT blocked.)
        return@withContext res.blocked
    }

    /** Local-only decision from the cached rules list (never network). */
    fun isBlockedByCachedRules(channelId: String?, handle: String?): Boolean {
        val id = channelId
        val h = handle?.trim()?.lowercase()
        return blockedChannels.any { c ->
            (id != null && c.channelId == id) ||
                (h != null && c.channelHandle?.lowercase() == h)
        }
    }

    /** Block a channel on the backend (idempotent) and refresh the cache. */
    suspend fun blockChannel(channelId: String? = null, handle: String? = null, channelName: String? = null): Boolean {
        val ok = backend.blockChannel(channelId, handle, channelName)
        if (ok) syncRules(force = true)
        return ok
    }

    // ── Persistence helpers ───────────────────────────────────────

    private fun persistRules(rules: ClearViewBackendClient.Rules) {
        val json = JSONObject().apply {
            put("channels", JSONArray().apply {
                rules.channels.forEach { c ->
                    put(JSONObject().apply {
                        c.channelId?.let { put("channelId", it) }
                        c.channelHandle?.let { put("channelHandle", it) }
                        c.channelName?.let { put("channelName", it) }
                    })
                }
            })
            put("keywords", JSONArray(rules.keywords))
        }
        prefs.edit().putString(KEY_RULES, json.toString()).apply()
    }

    private fun rebuildLookupCaches(channels: List<ClearViewBackendClient.BlockedChannel>) {
        val handleMap = JSONObject()
        val blockedMap = JSONObject()
        for (c in channels) {
            c.channelId?.let { id ->
                blockedMap.put(id, true)
                c.channelHandle?.let { handleMap.put(it.lowercase(), id) }
            }
        }
        prefs.edit()
            .putString(KEY_HANDLE_TO_ID, handleMap.toString())
            .putString(KEY_ID_BLOCKED, blockedMap.toString())
            .apply()
    }

    private fun readHandleMap(): Map<String, String> =
        prefs.getString(KEY_HANDLE_TO_ID, null)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } }
            .orEmpty()

    private fun readBlockedMap(): Map<String, Boolean> =
        prefs.getString(KEY_ID_BLOCKED, null)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?.let { obj -> obj.keys().asSequence().associateWith { obj.optBoolean(it) } }
            .orEmpty()

    private fun loadHandleMap() { readHandleMap() }
    private fun loadBlockedMap() { readBlockedMap() }

    private fun writeBlockedMap(id: String, blocked: Boolean) {
        val obj = prefs.getString(KEY_ID_BLOCKED, null)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: JSONObject()
        obj.put(id, blocked)
        prefs.edit().putString(KEY_ID_BLOCKED, obj.toString()).apply()
    }

    private fun parseChannels(arr: JSONArray?): List<ClearViewBackendClient.BlockedChannel> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ClearViewBackendClient.BlockedChannel(
                channelId = o.optString("channelId").takeIf { it.isNotBlank() },
                channelHandle = o.optString("channelHandle").takeIf { it.isNotBlank() },
                channelName = o.optString("channelName").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }
}
