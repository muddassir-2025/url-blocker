package com.muddassir.clearview.media.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloaderTest {

    private val MB = 1024L * 1024

    @Test
    fun `small files stay on a single chunk`() {
        assertEquals(listOf(0L..(4L * MB - 1)), AudioDownloader.chunkRanges(4L * MB))
        assertEquals(listOf(0L..99L), AudioDownloader.chunkRanges(100))
    }

    @Test
    fun `medium files split into two equal chunks`() {
        // 8 MB → two 4 MB chunks; 10 MB → two 5 MB chunks.
        assertEquals(
            listOf(0L..(4L * MB - 1), (4L * MB)..(8L * MB - 1)),
            AudioDownloader.chunkRanges(8L * MB)
        )
        assertEquals(
            listOf(0L..(5L * MB - 1), (5L * MB)..(10L * MB - 1)),
            AudioDownloader.chunkRanges(10L * MB)
        )
    }

    @Test
    fun `large files split into three chunks covering the whole file`() {
        val total = 12L * MB
        val ranges = AudioDownloader.chunkRanges(total)
        assertEquals(3, ranges.size)
        // Contiguous, no overlap, no gaps, last chunk ends at total-1.
        assertEquals(0L, ranges.first().first)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(total - 1, ranges.last().last)
    }

    @Test
    fun `zero and negative sizes produce no chunks`() {
        assertEquals(emptyList<LongRange>(), AudioDownloader.chunkRanges(0))
        assertEquals(emptyList<LongRange>(), AudioDownloader.chunkRanges(-5))
    }

    @Test
    fun `parseContentRangeTotal reads the total from the header`() {
        assertEquals(1_234_567L, AudioDownloader.parseContentRangeTotal("bytes 0-0/1234567"))
        assertEquals(42L, AudioDownloader.parseContentRangeTotal("Bytes 0-0/42"))
        assertEquals(null, AudioDownloader.parseContentRangeTotal(null))
        assertEquals(null, AudioDownloader.parseContentRangeTotal("bytes */1234"))
        assertEquals(null, AudioDownloader.parseContentRangeTotal("garbage"))
    }
}
