package com.muddassir.clearview.media.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloaderTest {

    // The parallel fast path thresholds in AudioDownloader: 2 chunks from 1 MB
    // (MIN_PARALLEL_BYTES), 3 chunks from 3 MB (FULL_PARALLEL_BYTES), and the
    // full 4-way fan-out from 10 MB (VERY_LARGE_BYTES) — so even short videos'
    // small audio files parallelize, and very large files go even faster.
    private val MIN = 1024L * 1024
    private val FULL = 3L * 1024 * 1024
    private val VERY_LARGE = 10L * 1024 * 1024

    @Test
    fun `tiny files stay on a single chunk`() {
        assertEquals(listOf(0L..(MIN - 2)), AudioDownloader.chunkRanges(MIN - 1))
        assertEquals(listOf(0L..99L), AudioDownloader.chunkRanges(100))
    }

    @Test
    fun `small files split into two equal chunks`() {
        // 1 MB → two 512 KB chunks; 2 MB → two 1 MB chunks.
        assertEquals(
            listOf(0L..(MIN / 2 - 1), (MIN / 2)..(MIN - 1)),
            AudioDownloader.chunkRanges(MIN)
        )
        assertEquals(
            listOf(0L..(MIN - 1), MIN..(2L * MIN - 1)),
            AudioDownloader.chunkRanges(2L * MIN)
        )
    }

    @Test
    fun `large files split into three chunks covering the whole file`() {
        val total = FULL
        val ranges = AudioDownloader.chunkRanges(total)
        assertEquals(3, ranges.size)
        // Contiguous, no overlap, no gaps, last chunk ends at total-1.
        assertEquals(0L, ranges.first().first)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(total - 1, ranges.last().last)
    }

    @Test
    fun `very large files split into four chunks covering the whole file`() {
        val total = VERY_LARGE
        val ranges = AudioDownloader.chunkRanges(total)
        assertEquals(4, ranges.size)
        // Contiguous, no overlap, no gaps, last chunk ends at total-1.
        assertEquals(0L, ranges.first().first)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(total - 1, ranges.last().last)
    }

    @Test
    fun `files just under ten MB stay at three chunks`() {
        // 10 MB - 1 is still below the very-large tier → 3 chunks.
        val ranges = AudioDownloader.chunkRanges(VERY_LARGE - 1)
        assertEquals(3, ranges.size)
        assertEquals(0L, ranges.first().first)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(VERY_LARGE - 2, ranges.last().last)
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
