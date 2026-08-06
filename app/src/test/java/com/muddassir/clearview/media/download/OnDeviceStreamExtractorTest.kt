package com.muddassir.clearview.media.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType

/**
 * Regression tests for [OnDeviceStreamExtractor.selectBestAudio] — the fix for
 * the "downloads came out in Arabic" bug. YouTube tags every audio stream with
 * an [AudioTrackType] (parsed from the format's `xtags`): ORIGINAL marks the
 * video's own language, DUBBED / DESCRIPTIVE / SECONDARY are alternates. The
 * selector must prefer the ORIGINAL track even when a dub has a higher bitrate.
 */
class OnDeviceStreamExtractorTest {

    private fun audio(
        id: String,
        bitrate: Int,
        format: MediaFormat? = MediaFormat.M4A,
        trackType: AudioTrackType? = null
    ): AudioStream = AudioStream.Builder()
        .setId(id)
        .setContent("https://example.com/$id.m4a", true)
        .setMediaFormat(format)
        .setAverageBitrate(bitrate)
        .setAudioTrackType(trackType)
        .build()

    /** The headline bug: a 160 kbps Arabic dub must NOT beat a 128 kbps original. */
    @Test
    fun prefersOriginalTrackOverHigherBitrateDub() {
        val original = audio("140", bitrate = 128, trackType = AudioTrackType.ORIGINAL)
        val dub = audio("251", bitrate = 160, trackType = AudioTrackType.DUBBED)
        assertEquals(original, OnDeviceStreamExtractor.selectBestAudio(listOf(dub, original)))
    }

    /** Original wins over descriptive/secondary alternates regardless of bitrate. */
    @Test
    fun prefersOriginalOverOtherAlternates() {
        val original = audio("140", bitrate = 96, trackType = AudioTrackType.ORIGINAL)
        val descriptive = audio("251", bitrate = 128, trackType = AudioTrackType.DESCRIPTIVE)
        val secondary = audio("252", bitrate = 192, trackType = AudioTrackType.SECONDARY)
        val result = OnDeviceStreamExtractor.selectBestAudio(listOf(secondary, descriptive, original))
        assertEquals(original, result)
    }

    /** Single-track videos carry no track metadata — pick the highest bitrate. */
    @Test
    fun untaggedStreamsPickHighestBitrate() {
        val low = audio("139", bitrate = 48)
        val high = audio("251", bitrate = 160)
        assertEquals(high, OnDeviceStreamExtractor.selectBestAudio(listOf(low, high)))
    }

    /** A single stream is always the answer. */
    @Test
    fun singleTrackPicksTheOnlyStream() {
        val only = audio("140", bitrate = 128)
        assertEquals(only, OnDeviceStreamExtractor.selectBestAudio(listOf(only)))
    }

    /** Dubs are used only as a last resort when nothing else exists. */
    @Test
    fun fallsBackToDubsWhenNoOriginalOrUntaggedExists() {
        val dub = audio("251", bitrate = 160, trackType = AudioTrackType.DUBBED)
        assertEquals(dub, OnDeviceStreamExtractor.selectBestAudio(listOf(dub)))
    }

    /** A mix of original + untagged: the tagged original still wins. */
    @Test
    fun originalTrackBeatsUntaggedStreams() {
        val original = audio("140", bitrate = 96, trackType = AudioTrackType.ORIGINAL)
        val untaggedHigh = audio("251", bitrate = 160)
        assertEquals(original, OnDeviceStreamExtractor.selectBestAudio(listOf(untaggedHigh, original)))
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(OnDeviceStreamExtractor.selectBestAudio(emptyList()))
    }
}
