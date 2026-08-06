package com.muddassir.clearview.media.download

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Extracts the direct audio-stream URL for a YouTube video ON-DEVICE, using
 * NewPipeExtractor's innertube client (android_vr — the same mobile chain the
 * app's old Render server used). Running from the phone's own residential /
 * mobile IP means the requests come from an IP YouTube trusts, so no
 * server-side bot-block applies.
 *
 * Only ever needs the audio bytes: these are audio-only streams, so there is
 * no FFmpeg/merge step anywhere in the pipeline.
 */
internal object OnDeviceStreamExtractor {

    /** A resolvable audio-stream URL plus the container metadata for it. */
    data class AudioSource(
        val url: String,
        /** File extension for the saved audio (e.g. "m4a", "webm", "ogg"). */
        val extension: String,
        /** MIME type, or null when the extractor couldn't determine it. */
        val mimeType: String?
    )

    private val initLock = Any()
    private var initialized = false

    /**
     * Resolves the best audio stream for [videoId]. Expensive-ish on first
     * call (initializes the extractor); call from a background thread.
     */
    fun extract(videoId: String): AudioSource {
        ensureInitialized()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams
        val best = selectBestAudio(streams) ?: throw DownloadException(
            "No audio track is available for this video."
        )
        // The content must be a direct URL (not, say, a DASH manifest or inline
        // data) — otherwise it can't be downloaded with a plain GET.
        if (!best.isUrl) {
            throw DownloadException("No direct audio stream is available for this video.")
        }
        val format = best.format
        return AudioSource(
            url = best.content,
            extension = format?.suffix ?: DEFAULT_EXTENSION,
            mimeType = format?.mimeType
        )
    }

    /**
     * Picks the highest-quality stream of the ORIGINAL-language track.
     *
     * YouTube videos with several audio tracks tag every stream with an
     * [AudioTrackType] (parsed from the format's `xtags`): ORIGINAL marks the
     * video's own language, DUBBED / DESCRIPTIVE / SECONDARY are alternates.
     * Picking by raw bitrate alone frequently lands on a DUB — dubs are often
     * higher-bitrate than the original — which is exactly the "downloads came
     * out in Arabic" bug.
     *
     * Selection order: ORIGINAL tracks first → streams with no track metadata
     * (single-track videos, where the only track IS the original) → any track
     * as a last resort. Within the chosen pool the highest bitrate wins,
     * restricted to formats MediaPlayer can definitely play (AAC / Opus-in-WebM,
     * both supported from API 21+).
     */
    /**
     * Pure stream-selection logic (internal so the track preference is
     * unit-testable — see OnDeviceStreamExtractorTest).
     */
    internal fun selectBestAudio(streams: List<AudioStream>): AudioStream? {
        val original = streams.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
        val untagged = streams.filter { it.audioTrackType == null }
        val pool = when {
            original.isNotEmpty() -> original
            untagged.isNotEmpty() -> untagged
            else -> streams
        }
        val playable = pool.filter { it.format in PLAYABLE_FORMATS }
        // maxByOrNull on the bitrate: prefer the higher bitrate (unknown -1
        // sorts last naturally). Ties fall through to the first stream.
        return (if (playable.isNotEmpty()) playable else pool).maxByOrNull { it.averageBitrate }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            // Default localization/country is fine for stream extraction.
            NewPipe.init(NewPipeDownloader())
            initialized = true
        }
    }

    private val PLAYABLE_FORMATS = setOf(
        MediaFormat.M4A,
        MediaFormat.WEBMA,
        MediaFormat.WEBMA_OPUS,
        MediaFormat.OPUS
    )

    private const val DEFAULT_EXTENSION = "m4a"
}
