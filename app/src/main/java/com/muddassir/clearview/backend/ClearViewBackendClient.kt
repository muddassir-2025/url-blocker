package com.muddassir.clearview.backend

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal HTTP client for the shared ClearView backend. Mirrors the existing
 * [com.muddassir.clearview.media.data.ChannelIdResolver] pattern
 * (HttpURLConnection + coroutines + org.json) so NO new dependencies are
 * introduced. All calls are best-effort: every method returns null on any
 * failure and the caller falls back to local protection — the accessibility
 * hot path must never be blocked or crashed by a dead backend.
 *
 * The base URL is configurable (set from Settings later); the default points
 * at a locally-run backend (10.0.2.2 is the emulator's host loopback).
 */
class ClearViewBackendClient(private val context: Context) {

    companion object {
        private const val TAG = "ClearViewBackend"

        /** ClearView backend URL (Cloudflare tunnel). */
        @Volatile
        var baseUrl: String = "https://websites-checking-confirmed-pipes.trycloudflare.com"

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val MAX_RESPONSE_BYTES = 2_000_000
    }

    data class Rules(
        val keywords: List<String>,
        val domains: List<String>,
        val patterns: List<String>,
        val channels: List<BlockedChannel>
    )

    data class BlockedChannel(
        val channelId: String?,
        val channelHandle: String?,
        val channelName: String?
    )

    data class ChannelCheck(
        val channelId: String?,
        val handle: String?,
        val blocked: Boolean
    )

    /** GET /api/rules — the combined snapshot for client-side caching. */
    suspend fun getRules(): Rules? = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/rules") ?: return@withContext null
        try {
            Rules(
                keywords = json.optJSONArray("keywords")?.let(::jsonArrayToStrings).orEmpty(),
                domains = json.optJSONArray("domains")?.let(::jsonArrayToStrings).orEmpty(),
                patterns = json.optJSONArray("patterns")?.let(::jsonArrayToStrings).orEmpty(),
                channels = json.optJSONArray("channels")?.let(::parseChannels).orEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "getRules parse error: ${e.message}")
            null
        }
    }

    /**
     * GET /api/channels/check — resolve + check in one call. Pass whichever
     * identity is available (channelId > handle > videoId).
     */
    suspend fun checkChannel(
        channelId: String? = null,
        handle: String? = null,
        videoId: String? = null
    ): ChannelCheck? = withContext(Dispatchers.IO) {
        val params = mutableListOf<String>()
        channelId?.let { params.add("channelId=${enc(it)}") }
        handle?.let { params.add("handle=${enc(it)}") }
        videoId?.let { params.add("videoId=${enc(it)}") }
        if (params.isEmpty()) return@withContext null
        val json = request("GET", "/api/channels/check?${params.joinToString("&")}")
            ?: return@withContext null
        try {
            ChannelCheck(
                channelId = json.optString("channelId").takeIf { it.isNotBlank() },
                handle = json.optString("handle").takeIf { it.isNotBlank() },
                blocked = json.optBoolean("blocked", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkChannel parse error: ${e.message}")
            null
        }
    }

    /** POST /blocked-channels — idempotent by channelId. */
    suspend fun blockChannel(
        channelId: String? = null,
        handle: String? = null,
        channelName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            channelId?.let { put("channelId", it) }
            handle?.let { put("channelHandle", it) }
            channelName?.let { put("channelName", it) }
        }
        try {
            val conn = open("/api/blocked-channels")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "blockChannel error: ${e.message}")
            false
        }
    }

    // ── Internals ─────────────────────────────────────────────────

    private fun request(method: String, path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(path)
            conn.requestMethod = method
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .take(MAX_RESPONSE_BYTES)
            return JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "request $method $path failed: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ClearView-Android")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun jsonArrayToStrings(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }

    private fun parseChannels(arr: JSONArray): List<BlockedChannel> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BlockedChannel(
                channelId = o.optString("channelId").takeIf { it.isNotBlank() },
                channelHandle = o.optString("channelHandle").takeIf { it.isNotBlank() },
                channelName = o.optString("channelName").takeIf { it.isNotBlank() }
            )
        }
}
