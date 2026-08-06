package com.muddassir.clearview.media.download

import android.content.Context
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads a video's audio entirely ON-DEVICE, straight into `cache/audio/`.
 *
 * 1. [OnDeviceStreamExtractor] resolves the direct audio-stream URL via
 *    NewPipeExtractor's innertube client — from the phone's own residential /
 *    mobile IP, so YouTube trusts the request (no server involved at all).
 * 2. The bytes are streamed from that URL with a plain GET, saved as
 *    `<videoId>.<ext>` via a `<videoId>.part` file that is renamed on success,
 *    so an interrupted download never looks like a complete one.
 *
 * Audio-only streams — no FFmpeg/merge anywhere. Transient network failures
 * are retried once; extraction failures surface the specific reason (e.g.
 * "video unavailable", "YouTube wants a captcha").
 */
object AudioDownloader {

    private const val MAX_ATTEMPTS = 2
    private const val RETRY_WAIT_MS = 3_000L
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ClearView/1.4"

    class Result(val file: File, val bytes: Long)

    /**
     * Live download progress. [fraction] is 0..1 when the total size is known,
     * or -1 when it isn't (indeterminate). [etaSeconds] is the estimated time
     * remaining, or -1 while the estimate is too noisy to report (e.g. the
     * first second, or the total size is unknown).
     */
    class Progress(val fraction: Float, val etaSeconds: Long)

    /**
     * Downloads the audio for [videoId]. [onProgress] fires with a
     * [Progress] on every chunk. [isCancelled] is polled between chunks; when
     * it returns true the download aborts with a [CancellationException].
     * [onConnection] receives the live stream connection so callers can abort
     * it (cancel) mid-download.
     */
    fun download(
        context: Context,
        videoId: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        /** Called with the live connection so callers can abort it (cancel). */
        onConnection: (HttpURLConnection) -> Unit = {}
    ): Result {
        val audioDir = AudioDownloadStore(context).audioDir
        var source: OnDeviceStreamExtractor.AudioSource? = null
        var lastError: DownloadException? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (isCancelled()) throw CancellationException("Download cancelled")

            // 1) Resolve the direct stream URL (the phone's own IP, trusted).
            //    Only once per download — but a transient network error during
            //    extraction earns the retry pass like any other.
            if (source == null) {
                try {
                    source = OnDeviceStreamExtractor.extract(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReCaptchaException) {
                    throw DownloadException(
                        "YouTube is asking to verify you're not a robot right now. Try again in a few minutes."
                    )
                } catch (e: ContentNotAvailableException) {
                    throw DownloadException("This video isn't available for download.")
                } catch (e: ExtractionException) {
                    throw DownloadException("Couldn't read this video's audio details. Try another video.")
                } catch (e: IOException) {
                    lastError = DownloadException(
                        "Network error while preparing the download. Check your connection.",
                        retryable = true
                    )
                    if (attempt < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_WAIT_MS)
                        continue
                    }
                    throw lastError
                }
            }

            // 2) Stream the bytes.
            val conn = open(source!!.url)
            onConnection(conn)
            try {
                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw DownloadException(
                        "YouTube refused the download (HTTP $status). Try again in a moment.",
                        retryable = status == 429 || status >= 500
                    )
                }
                val total = conn.contentLengthLong
                val part = File(audioDir, "$videoId.part")
                val finalFile = File(audioDir, "$videoId.${source.extension}")

                val startedAt = System.currentTimeMillis()
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
                                Progress(
                                    fraction = if (total > 0) {
                                        (written.toFloat() / total).coerceIn(0f, 1f)
                                    } else {
                                        -1f
                                    },
                                    etaSeconds = estimateEtaSeconds(total, written, startedAt)
                                )
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
                // Connection-level failure mid-stream — worth one retry.
                lastError = DownloadException(
                    "Download interrupted. Check your connection and try again.",
                    retryable = true
                )
            } finally {
                conn.disconnect()
            }
            if (attempt < MAX_ATTEMPTS && !isCancelled()) Thread.sleep(RETRY_WAIT_MS)
        }
        throw lastError ?: DownloadException("Download failed")
    }

    /**
     * Remaining seconds at the average download speed so far, or -1 when the
     * total is unknown, nothing is downloaded yet, or the run is too young
     * for a meaningful average (first ~1 s).
     */
    private fun estimateEtaSeconds(total: Long, written: Long, startedAt: Long): Long {
        if (total <= 0L || written <= 0L) return -1L
        val elapsedMs = System.currentTimeMillis() - startedAt
        if (elapsedMs < 1_000L) return -1L
        val speed = written.toDouble() / elapsedMs  // bytes per ms
        if (speed <= 0.0) return -1L
        val remainingMs = (total - written).toDouble() / speed
        return (remainingMs / 1_000.0).toLong().coerceAtLeast(0L)
    }

    private fun open(streamUrl: String): HttpURLConnection {
        return (URL(streamUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "audio/*,*/*;q=0.9")
        }
    }
}

/** A download failure with a user-friendly [message]. */
class DownloadException(
    override val message: String,
    val retryable: Boolean = false
) : Exception(message)
