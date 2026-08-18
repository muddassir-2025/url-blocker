package com.muddassir.clearview.media.download

import android.content.Context
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads a video's audio entirely ON-DEVICE, straight into the app's
 * permanent downloads folder (`filesDir/downloads/`).
 *
 * 1. [OnDeviceStreamExtractor] resolves the direct audio-stream URL via
 *    NewPipeExtractor's innertube client — from the phone's own residential /
 *    mobile IP, so YouTube trusts the request (no server involved at all).
 * 2. The bytes are streamed from that URL through a `<videoId>.part` file in
 *    the CACHE (temporary storage, so interrupted downloads never occupy the
 *    permanent folder) that is renamed into the downloads folder on success —
 *    an interrupted download never looks like a complete one.
 *
 * FAST PATH: when the CDN answers with `Accept-Ranges`-style partial content
 * (YouTube's media servers do), the file is downloaded in up to
 * [MAX_PARALLEL_CHUNKS] PARALLEL HTTP range requests, one per segment, which
 * typically finishes several times faster than a single stream. Servers that
 * don't support ranges fall back to the single-stream GET automatically.
 * The parallel path kicks in from a small size on, so SHORT videos (whose
 * audio files are small) get the same fast download as long ones.
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
    private const val BUFFER_SIZE = 256 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ClearView/1.4"

    // Parallel range-download settings: at most MAX_PARALLEL_CHUNKS
    // simultaneous connections. Files under MIN_PARALLEL_BYTES stay on a single
    // connection — only sub-minute clips (under ~1 MB of audio) are that small,
    // and they finish almost instantly either way. Everything ≥ MIN_PARALLEL_BYTES
    // uses the parallel fast path, so SHORT videos download as quickly as long
    // ones. Chunk count scales with size so every chunk stays large enough for
    // parallel connections to pay off (a 3-way split of a 1.5 MB file would lose
    // the transfer to connection setup): 2 chunks 1–3 MB, 3 chunks 3–10 MB, and
    // the full [MAX_PARALLEL_CHUNKS] fan-out for very large files (≥ 10 MB) to
    // push their downloads even faster.
    private const val MAX_PARALLEL_CHUNKS = 4
    private const val MIN_PARALLEL_BYTES = 1024L * 1024
    private const val FULL_PARALLEL_BYTES = 3L * 1024 * 1024
    private const val VERY_LARGE_BYTES = 10L * 1024 * 1024

    /** Report progress at most every this many new bytes (UI throttle). */
    private const val PROGRESS_REPORT_BYTES = 512 * 1024

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
     * [onConnection] receives every live connection so callers can abort them
     * (cancel) mid-download. [onSizeKnown] fires with the total size in bytes
     * as soon as it is known — BEFORE any audio bytes are downloaded — so the
     * UI can show "≈ X MB" while the transfer is still starting (the size is
     * read from the server's response headers, since NewPipeExtractor v0.26
     * doesn't expose stream sizes).
     */
    fun download(
        context: Context,
        videoId: String,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        /** Called with the live connection(s) so callers can abort them (cancel). */
        onConnection: (HttpURLConnection) -> Unit = {},
        /** Called with the total size in bytes before the transfer starts. */
        onSizeKnown: (Long) -> Unit = {}
    ): Result {
        val store = AudioDownloadStore(context)
        val audioDir = store.audioDir
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

            // 2) Stream the bytes (parallel ranges when supported, else one
            //    plain GET). The .part file is cleaned up on any failure.
            val part = store.partFile(videoId)
            val finalFile = File(audioDir, "$videoId.${source!!.extension}")
            try {
                return streamFile(
                    url = source.url,
                    part = part,
                    finalFile = finalFile,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                    onConnection = onConnection,
                    onSizeKnown = onSizeKnown
                )
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: DownloadException) {
                part.delete()
                lastError = e
                if (!e.retryable) throw e
            } catch (e: Exception) {
                // Connection-level failure mid-stream — worth one retry.
                part.delete()
                lastError = DownloadException(
                    "Download interrupted. Check your connection and try again.",
                    retryable = true
                )
            }
            if (attempt < MAX_ATTEMPTS && !isCancelled()) Thread.sleep(RETRY_WAIT_MS)
        }
        throw lastError ?: DownloadException("Download failed")
    }

    /**
     * Decides the transfer strategy: probes the stream URL with a tiny range
     * request; when the server answers 206 with a known total it uses the
     * parallel path, otherwise the plain single-stream GET.
     */
    private fun streamFile(
        url: String,
        part: File,
        finalFile: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit,
        onSizeKnown: (Long) -> Unit
    ): Result {
        // Probe: does the CDN honor range requests, and what's the total?
        val probe = open(url).apply { setRequestProperty("Range", "bytes=0-0") }
        onConnection(probe)
        try {
            if (probe.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val total = parseContentRangeTotal(probe.getHeaderField("Content-Range"))
                if (total != null && total > 0L) {
                    // Size known before any audio bytes are downloaded.
                    onSizeKnown(total)
                    return downloadParallel(
                        url = url,
                        total = total,
                        part = part,
                        finalFile = finalFile,
                        onProgress = onProgress,
                        isCancelled = isCancelled,
                        onConnection = onConnection,
                        onSizeKnown = onSizeKnown
                    )
                }
            }
        } catch (e: Exception) {
            // Probe failed — fall through to the single-stream path.
        } finally {
            runCatching { probe.disconnect() }
        }
        return downloadSingle(
            url = url,
            part = part,
            finalFile = finalFile,
            onProgress = onProgress,
            isCancelled = isCancelled,
            onConnection = onConnection,
            onSizeKnown = onSizeKnown
        )
    }

    /**
     * Single-stream GET fallback (servers without range support). Mirrors the
     * original downloader exactly: bytes flow straight into the .part file.
     */
    private fun downloadSingle(
        url: String,
        part: File,
        finalFile: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit,
        onSizeKnown: (Long) -> Unit
    ): Result {
        val conn = open(url)
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
            // Size known before the transfer actually starts.
            if (total > 0L) onSizeKnown(total)
            val startedAt = System.currentTimeMillis()
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        if (isCancelled()) throw CancellationException("Download cancelled")
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
            return finish(part, finalFile)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parallel range download: splits [total] bytes into chunk ranges, fetches
     * each with its own connection and writes it at its offset (positional
     * FileChannel writes are thread-safe, so the chunks never clobber each
     * other). Progress is aggregated across chunks. Each chunk gets one retry
     * on a transient failure (it re-downloads its own range).
     */
    private fun downloadParallel(
        url: String,
        total: Long,
        part: File,
        finalFile: File,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit,
        onSizeKnown: (Long) -> Unit
    ): Result {
        val ranges = chunkRanges(total)
        if (ranges.size <= 1) {
            // Tiny file — parallel overhead isn't worth it.
            return downloadSingle(url, part, finalFile, onProgress, isCancelled, onConnection, onSizeKnown)
        }

        val startedAt = System.currentTimeMillis()
        val writtenTotal = AtomicLong(0L)
        val lastReported = AtomicLong(0L)
        val bail = AtomicBoolean(false)
        // Pre-size the .part file to the full length so every chunk can write
        // at its own offset; positional writes need no cross-thread locking.
        val raf = RandomAccessFile(part, "rw")
        try {
            raf.setLength(total)
            val channel = raf.channel
            val executor = Executors.newFixedThreadPool(ranges.size)
            try {
                val futures = ranges.map { range ->
                    executor.submit(Callable {
                        downloadChunk(
                            url = url,
                            range = range,
                            channel = channel,
                            total = total,
                            writtenTotal = writtenTotal,
                            lastReported = lastReported,
                            bail = bail,
                            startedAt = startedAt,
                            onProgress = onProgress,
                            isCancelled = isCancelled,
                            onConnection = onConnection
                        )
                    })
                }
                try {
                    // get() rethrows a chunk's exception (wrapped) — the
                    // caller's retry loop handles it, and the .part file is
                    // deleted there.
                    futures.forEach { it.get() }
                } catch (e: CancellationException) {
                    // User cancelled (or one chunk bailed): signal the sibling
                    // chunks to stop and WAIT for them before propagating, so
                    // no download thread keeps writing / lingering after the
                    // cancel is acknowledged.
                    bail.set(true)
                    futures.forEach { runCatching { it.get() } }
                    throw e
                } catch (e: RangeIgnoredException) {
                    // The probe honored ranges but a chunk's range request
                    // came back as a full 200 — an inconsistent CDN. Signal
                    // the other chunks to stop, wait for them, and if nothing
                    // was written yet fall back to the plain GET (a clean
                    // restart on a fresh, un-sized .part). If bytes already
                    // landed, the .part is mixed and must fail instead of
                    // saving corrupted audio.
                    bail.set(true)
                    futures.forEach { runCatching { it.get() } }
                    if (writtenTotal.get() == 0L) {
                        part.delete()
                        return downloadSingle(
                            url, part, finalFile, onProgress, isCancelled,
                            onConnection, onSizeKnown
                        )
                    }
                    throw e
                } catch (e: Exception) {
                    // Any other chunk failure: stop the siblings before
                    // propagating to the caller's retry loop.
                    bail.set(true)
                    futures.forEach { runCatching { it.get() } }
                    throw e
                }
            } finally {
                executor.shutdown()
            }
            return finish(part, finalFile)
        } finally {
            runCatching { raf.close() }
        }
    }

    /** Downloads one byte range into [channel] at its offset (with one retry). */
    private fun downloadChunk(
        url: String,
        range: LongRange,
        channel: FileChannel,
        total: Long,
        writtenTotal: AtomicLong,
        lastReported: AtomicLong,
        bail: AtomicBoolean,
        startedAt: Long,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
        onConnection: (HttpURLConnection) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 1..2) {
            if (bail.get() || isCancelled()) throw CancellationException("Download cancelled")
            var conn: HttpURLConnection? = null
            // Bytes this attempt actually wrote (rewound from the shared
            // counter on a retry so the same range never counts twice).
            var chunkWritten = 0L
            try {
                conn = open(url).apply {
                    setRequestProperty("Range", "bytes=${range.first}-${range.last}")
                }
                onConnection(conn)
                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_OK) {
                    // The server ignored the Range header and returned the full
                    // body — writing it at the chunk offset would corrupt the
                    // file. Signal the parallel path to fall back to a plain
                    // GET (or fail) instead of mixing offsets.
                    throw RangeIgnoredException()
                }
                if (status != HttpURLConnection.HTTP_PARTIAL) {
                    throw DownloadException(
                        "YouTube refused the download (HTTP $status). Try again in a moment.",
                        retryable = status == 429 || status >= 500
                    )
                }
                val buffer = ByteArray(BUFFER_SIZE)
                var offset = range.first
                conn.inputStream.use { input ->
                    while (true) {
                        if (bail.get() || isCancelled()) {
                            throw CancellationException("Download cancelled")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        // Positional write — safe to call from parallel threads
                        // on the same channel.
                        val bb = ByteBuffer.wrap(buffer, 0, n)
                        while (bb.hasRemaining()) channel.write(bb, offset)
                        offset += n
                        chunkWritten += n
                        // Report the DELTA (n), not the cumulative chunk count —
                        // the shared writtenTotal must only ever be incremented
                        // by newly downloaded bytes.
                        reportProgress(
                            writtenTotal, lastReported, total, n.toLong(),
                            startedAt, onProgress
                        )
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RangeIgnoredException) {
                throw e
            } catch (e: DownloadException) {
                if (!e.retryable) throw e
                // Rewind this attempt's bytes so the retry starts from a clean
                // progress count (otherwise the same range counts twice).
                writtenTotal.addAndGet(-chunkWritten)
                lastError = e
            } catch (e: Exception) {
                writtenTotal.addAndGet(-chunkWritten)
                lastError = e
            } finally {
                conn?.disconnect()
            }
            if (attempt < 2 && !bail.get() && !isCancelled()) Thread.sleep(RETRY_WAIT_MS)
        }
        throw lastError ?: DownloadException("Chunk download failed")
    }

    /** Aggregated progress across chunks, throttled to the UI's comfort. */
    private fun reportProgress(
        writtenTotal: AtomicLong,
        lastReported: AtomicLong,
        total: Long,
        chunkWritten: Long,
        startedAt: Long,
        onProgress: (Progress) -> Unit
    ) {
        val written = writtenTotal.addAndGet(chunkWritten)
        val last = lastReported.get()
        if (written - last < PROGRESS_REPORT_BYTES && written < total) return
        lastReported.set(written)
        onProgress(
            Progress(
                fraction = (written.toFloat() / total).coerceIn(0f, 1f),
                etaSeconds = estimateEtaSeconds(total, written, startedAt)
            )
        )
    }

    /** Replaces the .part file with the final file and returns its size. */
    private fun finish(part: File, finalFile: File): Result {
        if (part.exists() && !part.renameTo(finalFile)) {
            part.copyTo(finalFile, overwrite = true)
            part.delete()
        }
        return Result(finalFile, finalFile.length())
    }

    /**
     * Splits [total] bytes into the parallel chunk ranges: 1 chunk for tiny
     * files (under ~1 MB), 2 chunks up to 3 MB, 3 chunks up to 10 MB, then the
     * full [MAX_PARALLEL_CHUNKS] fan-out for very large files. Pure
     * (unit-testable). Ranges are inclusive byte ranges (HTTP semantics).
     */
    internal fun chunkRanges(total: Long): List<LongRange> {
        if (total <= 0L) return emptyList()
        val count = when {
            total >= VERY_LARGE_BYTES -> MAX_PARALLEL_CHUNKS
            total >= FULL_PARALLEL_BYTES -> 3
            total >= MIN_PARALLEL_BYTES -> 2
            else -> 1
        }
        if (count <= 1) return listOf(0L..(total - 1).coerceAtLeast(0L))
        val chunk = total / count
        val ranges = ArrayList<LongRange>(count)
        var start = 0L
        for (i in 0 until count) {
            val end = if (i == count - 1) total - 1 else start + chunk - 1
            ranges.add(start..end)
            start = end + 1
        }
        return ranges
    }

    /** "bytes 0-0/123456" → 123456; null when absent/unparseable. */
    internal fun parseContentRangeTotal(contentRange: String?): Long? {
        if (contentRange == null) return null
        val m = Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)
            .find(contentRange)
            ?: return null
        return m.groupValues[1].toLongOrNull()
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

/** A range request was answered with the full body (ranges ignored). */
private class RangeIgnoredException : Exception()
