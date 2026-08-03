package com.muddassir.clearview

import com.muddassir.clearview.vision.ThumbnailSafetyAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSafetyAnalyzerTest {

    // ── interpretOutput (pure output-layout mapping) ─────────────────

    @Test
    fun singleOutputIsDirectProbability() {
        assertEquals(0.85f, ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.85f))!!, 0.0001f)
        assertEquals(0f, ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0f))!!, 0.0001f)
        // Sigmoid outputs are already 0..1; clamp defensively anyway.
        assertEquals(1f, ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(1.4f))!!, 0.0001f)
    }

    @Test
    fun twoOutputsTakeNsfwClass() {
        // Binary [safe, nsfw] softmax — the nsfw probability is index 1.
        assertEquals(0.7f, ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.3f, 0.7f))!!, 0.0001f)
        assertEquals(0.05f, ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.95f, 0.05f))!!, 0.0001f)
    }

    @Test
    fun fiveOutputsTakeMaxOfExplicitClasses() {
        // nsfwjs layout: [drawings, hentai, neutral, porn, sexy].
        // porn = 0.8 dominates.
        assertEquals(
            0.8f,
            ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.05f, 0.05f, 0.05f, 0.8f, 0.05f))!!,
            0.0001f
        )
        // sexy (bikini-style) = 0.6 is the max explicit class.
        assertEquals(
            0.6f,
            ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.6f))!!,
            0.0001f
        )
        // All-neutral thumbnail scores low.
        assertEquals(
            0.05f,
            ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.1f, 0.05f, 0.8f, 0.03f, 0.02f))!!,
            0.0001f
        )
    }

    @Test
    fun unsupportedOutputLayoutReturnsNull() {
        // 3 classes (or any other unknown layout) is not a supported NSFW
        // shape — analyzed-but-unknown must yield NO signal, never a bogus
        // score. (1 and 2-element layouts ARE supported and handled by the
        // tests above.)
        assertNull(ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.3f, 0.3f, 0.4f)))
        assertNull(ThumbnailSafetyAnalyzer.interpretOutput(floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f)))
    }

    // ── buildOutput / flattenOutput (shape-mirroring output allocation) ─
    // Interpreter.run requires the output object's shape to EXACTLY mirror
    // the tensor shape: a [1,2] tensor needs float[1][2], not a flat float[2]
    // (the on-device "Cannot copy from a TensorFlowLite tensor (predictions)
    // with shape [1, 2] to a Java object with shape [2]" crash).

    @Test
    fun buildOutputMirrors2DTensorShape() {
        // The shipped open_nsfw model: output [1, 2] = [safe, nsfw].
        val out = ThumbnailSafetyAnalyzer.buildOutput(intArrayOf(1, 2))
        val nested = out as Array<*>
        assertEquals(1, nested.size)
        assertEquals(2, (nested[0] as FloatArray).size)
    }

    @Test
    fun buildOutputMirrors1DTensorShape() {
        val out = ThumbnailSafetyAnalyzer.buildOutput(intArrayOf(2))
        assertTrue(out is FloatArray)
        assertEquals(2, (out as FloatArray).size)
    }

    @Test
    fun buildOutputFallsBackOnEmptyShape() {
        // Defensive: a degenerate/empty shape must not produce a 0-sized
        // array that Interpreter.run rejects.
        val out = ThumbnailSafetyAnalyzer.buildOutput(intArrayOf())
        assertTrue(out is FloatArray)
        assertEquals(1, (out as FloatArray).size)
    }

    @Test
    fun flattenRoundTripsNestedOutput() {
        // The exact [1,2] case: build → fill → flatten → interpret.
        val out = ThumbnailSafetyAnalyzer.buildOutput(intArrayOf(1, 2))
        val nested = out as Array<*>
        (nested[0] as FloatArray)[0] = 0.3f
        (nested[0] as FloatArray)[1] = 0.7f
        val flat = ThumbnailSafetyAnalyzer.flattenOutput(out)
        assertEquals(2, flat.size)
        assertEquals(0.3f, flat[0], 0.0001f)
        assertEquals(0.7f, flat[1], 0.0001f)
        // End-to-end: the flattened [safe, nsfw] yields the nsfw score.
        assertEquals(0.7f, ThumbnailSafetyAnalyzer.interpretOutput(flat)!!, 0.0001f)
    }
}
