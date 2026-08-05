package com.muddassir.clearview.media.download

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Streams the best audio-only stream for a video from the Node.js audio
 * backend (`GET /api/audio?url=…`) straight into `cache/audio/`.
 *
 * The backend itself streams from YouTube, so nothing is stored server-side —
 * this app simply saves the bytes locally as `<videoId>.<ext>` (via a
 * `<videoId>.part` file that is renamed on success, so an interrupted
 * download never looks like a complete one).
 *
 * The server may take a while to wake up (Render free tier cold starts), so
 * the read timeout is long; the UI shows an animated "Preparing…" state for
 * the whole wait. 503/429 ("server still booting / busy") are retried a few
 * times automatically.
 */
object AudioDownloader {

    private const val TAG = "AudioDownloader"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_WAIT_MS = 4_000L
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ClearView/1.4"

    class Result(val file: File, val bytes: Long)

    /**
     * Downloads the audio for [videoId]. [onProgress] receives 0..1 when the
     * total size is known, or -1 when it isn't (indeterminate). [isCancelled]
     * is polled between chunks; when it returns true the download aborts with
     * a [CancellationException].
     */
    fun download(
        context: Context,
        videoId: String,
        serverUrl: String,
        token: String?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        /** Called with the live connection so callers can abort it (cancel). */
        onConnection: (HttpURLConnection) -> Unit = {}
    ): Result {
        val audioDir = AudioDownloadStore(context).audioDir
        var lastError: DownloadException? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (isCancelled()) throw CancellationException("Download cancelled")
            val conn = open(serverUrl, videoId, token)
            onConnection(conn)
            try {
                val status = conn.responseCode
                // Render is still booting (cold start) or busy — wait and retry.
                if (status == HttpURLConnection.HTTP_UNAVAILABLE ||
                    status == 429
                ) {
                    if (attempt < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_WAIT_MS)
                        continue
                    }
                    throw DownloadException(
                        "The audio server is busy. Try again in a moment.",
                        retryable = true
                    )
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw DownloadException(
                        readError(conn.errorStream) ?: "Server error ($status)"
                    )
                }
                val total = conn.contentLengthLong
                val mime = conn.contentType
                val disposition = conn.getHeaderField("Content-Disposition")
                val ext = extensionFor(mime, disposition)
                val part = File(audioDir, "$videoId.part")
                val finalFile = File(audioDir, "$videoId.$ext")

                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            if (isCancelled()) {
                                part.delete()
                                throw CancellationException("Download cancelled")
                            }
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            onProgress(
                                if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f)
                                else -1f
                            )
                        }
                    }
                }
                if (part.exists() && !part.renameTo(finalFile)) {
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
                return Result(finalFile, finalFile.length())
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                lastError = e
                if (!e.retryable) throw e
            } catch (e: Exception) {
                // Read/IO failure mid-stream — a retry is reasonable only for
                // connection-level errors, and only if the user is still waiting.
                lastError = DownloadException(
                    "Couldn't reach the audio server. Check your connection and the server address."
                )
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: DownloadException("Download failed")
    }

    private fun open(serverUrl: String, videoId: String, token: String?): HttpURLConnection {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val api = serverUrl.trimEnd('/') + "/api/audio?url=" +
            URLEncoder.encode(watchUrl, "UTF-8")
        return (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "audio/*")
            token?.let { setRequestProperty("X-Audio-Token", it) }
        }
    }

    /**
     * Quick health probe of the audio backend (`GET /health`) for the
     * Server-settings screen. Returns (ok, detail).
     */
    fun checkHealth(serverUrl: String, token: String?): Pair<Boolean, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(serverUrl.trimEnd('/') + "/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                token?.let { setRequestProperty("X-Audio-Token", it) }
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                true to "Server reachable"
            } else {
                false to "Server responded ${conn.responseCode}"
            }
        } catch (e: Exception) {
            false to "Couldn't reach the server"
        } finally {
            conn?.disconnect()
        }
    }

    private fun readError(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText().take(300) }.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Extension from the server's filename hint, else the MIME type. */
    internal fun extensionFor(mime: String?, disposition: String?): String {
        disposition?.let { d ->
            val match = Regex("filename=\"([^\"]+)\"").find(d)
            match?.groupValues?.get(1)
                ?.substringAfterLast('.', "")
                ?.takeIf { it.length in 1..5 }
                ?.let { return it }
        }
        return when {
            mime == null -> "m4a"
            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            mime.contains("webm") -> "webm"
            mime.contains("ogg") || mime.contains("opus") -> "ogg"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("aac") -> "aac"
            mime.contains("wav") -> "wav"
            else -> "m4a"
        }
    }
}

/** A download failure with a user-friendly [message]. */
class DownloadException(
    override val message: String,
    val retryable: Boolean = false
) : Exception(message)
