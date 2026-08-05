package com.muddassir.clearview.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device NSFW classifier for YouTube feed thumbnails (the user's "image
 * later" step of the feed-blocking flow).
 *
 * The model binary ships in the APK (app/src/main/assets/[MODEL_ASSET]) — the
 * Yahoo open_nsfw float MobileNet (224x224 input, binary [safe,nsfw] softmax
 * output, Apache-2.0). Replace it with any other 1/2/5-output 224x224
 * classifier by swapping the asset. When the model is absent (or fails to
 * load) [isAvailable] returns false and the feed image-blocking signal
 * simply does not fire; all text-based signals still work.
 *
 * Preprocessing:
 *  - Reads the model's input tensor shape at load time to derive the required
 *    input size (e.g. 224x224) and data type, so any MobileNet-style
 *    classifier works without hardcoding the size.
 *  - Center-crops the bitmap to a square, then scales to the input size —
 *    robust to the 16:9 thumbnails.
 *  - FLOAT input: the shipped open_nsfw model expects OpenNSFW/VGG-style
 *    preprocessing — BGR channel order with per-channel mean subtraction
 *    (B-104, G-117, R-123), NO [0,1] scaling. Feeding RGB [0,1] instead
 *    miscalibrates the scores (measured ~5x inflated NSFW probability on a
 *    safe image, inviting false positives near the threshold).
 *  - UINT8 (quantized) input: raw 0..255 RGB bytes, the convention for most
 *    quantized classification models (they bake their own normalization in).
 *    A quantized model with different preprocessing would need matching code.
 *
 * Output interpretation (detected from the output tensor size):
 *  - 1 output: direct sigmoid probability (0..1).
 *  - 2 outputs: binary [safe, nsfw] softmax — nsfw = index 1.
 *  - 5 outputs: nsfwjs-style [drawings, hentai, neutral, porn, sexy] —
 *    nsfw = max(hentai, porn, sexy) (neutral/drawings excluded).
 *  - Anything else: returns null (analyzed-but-unknown layout → no signal).
 *
 * NOT thread-safe by itself: callers must serialize [analyze] (the service
 * runs it from one throttled coroutine at a time).
 */
class ThumbnailSafetyAnalyzer(context: Context) {

    companion object {
        private const val TAG = "ThumbnailSafetyAnalyzer"

        /** Asset name the user's model must have. */
        const val MODEL_ASSET = "nsfw_detector.tflite"

        /**
         * Confidence at or above which a thumbnail is treated as a block
         * signal. The matcher adds its +0.4 weight only when the score is
         * >= this value.
         */
        const val NSFW_THRESHOLD = 0.6f

        /**
         * Build a Java object whose shape EXACTLY mirrors the model's output
         * tensor shape, as required by Interpreter.run: a [1,2] tensor needs
         * Array(1){FloatArray(2)} (i.e. float[1][2]) — a flat float[2] throws
         * "Cannot copy from a TensorFlowLite tensor (predictions) with shape
         * [1, 2] to a Java object with shape [2]" (observed on-device).
         * 1-D → FloatArray, 2-D → Array(FloatArray), 3+ → nested arrays.
         * Internal so unit tests can cover it without a device/model.
         */
        fun buildOutput(shape: IntArray): Any {
            if (shape.isEmpty()) return FloatArray(1)
            return when (shape.size) {
                1 -> FloatArray(shape[0].coerceAtLeast(1))
                2 -> Array(shape[0].coerceAtLeast(1)) { FloatArray(shape[1].coerceAtLeast(1)) }
                else -> {
                    // 3+ dims (batch-1 classifiers never need this): build nested
                    // arrays from the innermost dimension outwards.
                    var out: Any = FloatArray(shape.last().coerceAtLeast(1))
                    for (d in shape.size - 2 downTo 0) {
                        val inner = out
                        out = Array(shape[d].coerceAtLeast(1)) { inner }
                    }
                    out
                }
            }
        }

        /** Flatten a nested float array (from [buildOutput]) into a flat FloatArray. */
        fun flattenOutput(obj: Any): FloatArray = when (obj) {
            is FloatArray -> obj
            is Array<*> -> {
                val out = java.util.ArrayList<Float>()
                for (item in obj) {
                    when (item) {
                        is FloatArray -> item.forEach { out.add(it) }
                        is Array<*> -> flattenOutput(item).forEach { out.add(it) }
                        else -> {}
                    }
                }
                out.toFloatArray()
            }
            else -> FloatArray(0)
        }

        /**
         * Interpret the raw model output into an NSFW probability 0..1, or
         * null when the output layout is not one of the supported shapes.
         * Pure so unit tests can cover it without a device/model.
         */
        fun interpretOutput(outputs: FloatArray): Float? {
            return when (outputs.size) {
                1 -> outputs[0].coerceIn(0f, 1f)
                2 -> outputs[1].coerceIn(0f, 1f)
                5 -> {
                    // nsfwjs layout: [drawings, hentai, neutral, porn, sexy]
                    maxOf(outputs[1], outputs[3], outputs[4]).coerceIn(0f, 1f)
                }
                else -> null
            }
        }
    }

    private var interpreter: Interpreter? = null
    private var inputSize = 224
    private var inputIsFloat = true

    init {
        try {
            val model = loadModelFile(context)
            if (model != null) {
                val interpreter = Interpreter(model)
                this.interpreter = interpreter
                val inputTensor = interpreter.getInputTensor(0)
                val shape = inputTensor.shape() // e.g. [1, 224, 224, 3]
                if (shape.size >= 2 && shape[1] > 0) {
                    inputSize = shape[1]
                }
                inputIsFloat = inputTensor.dataType() == DataType.FLOAT32
                val outputShape = interpreter.getOutputTensor(0).shape()
                Log.i(
                    TAG,
                    "MODEL_LOADED input=${shape.contentToString()} inputIsFloat=$inputIsFloat " +
                        "output=${outputShape.contentToString()}"
                )
            } else {
                Log.w(TAG, "MODEL_MISSING — no $MODEL_ASSET in assets; feed image blocking disabled (text signals still work)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MODEL_LOAD_FAILED: ${e.message}")
            interpreter?.close()
            interpreter = null
        }
    }

    /** True when the model is loaded and inference can run. */
    fun isAvailable(): Boolean = interpreter != null

    /**
     * Classify [bitmap] and return its NSFW probability 0..1, or null when
     * the model is unavailable or the output layout is unsupported.
     */
    fun analyze(bitmap: Bitmap): Float? {
        val interpreter = this.interpreter ?: return null
        val software = toSoftwareBitmap(bitmap) ?: return null
        val size = inputSize
        // Preprocess is INSIDE the try: a crop/scale failure (e.g. OOM) must
        // degrade to "no signal" (null) — never escape to kill the whole
        // enrichment pass — and must still recycle the software copy.
        var processed: Bitmap? = null
        try {
            processed = preprocess(software, size)
            val input: ByteBuffer = if (inputIsFloat) {
                toFloatInput(processed, size)
            } else {
                toByteInput(processed, size)
            }
            // The Java API requires the output object shape to EXACTLY mirror
            // the tensor shape — a [1,2] tensor needs float[1][2], not a flat
            // float[2] (which throws "Cannot copy ... shape [1, 2] ... shape
            // [2]"). Build the nested array from the shape, then flatten for
            // interpretOutput.
            val output = buildOutput(interpreter.getOutputTensor(0).shape())
            interpreter.run(input, output)
            return interpretOutput(flattenOutput(output))
        } catch (e: Exception) {
            Log.e(TAG, "INFERENCE_FAILED: ${e.message}")
            return null
        } finally {
            // preprocess() returns `software` itself when it's already the model
            // size — never recycle a bitmap the caller owns.
            if (processed != null && processed !== software) processed.recycle()
            if (software !== bitmap) software.recycle()
        }
    }

    /**
     * Accessibility screenshots arrive as [Bitmap.Config.HARDWARE] (from
     * [Bitmap.wrapHardwareBuffer]): fast to draw, but their pixels are
     * GPU-resident and cannot be CPU-read — [Bitmap.getPixels], cropping and
     * [Bitmap.copy] all throw "Config#HARDWARE is not supported" (the user's
     * observed INFERENCE_FAILED). Convert to a software ARGB_8888 copy (the
     * documented hardware→software path) so preprocessing can read pixels.
     * Returns the input unchanged when it is already software. Null on failure
     * (e.g. recycled bitmap) — the caller then simply gets no image signal.
     */
    private fun toSoftwareBitmap(bitmap: Bitmap): Bitmap? {
        return try {
            // Bitmap.Config.HARDWARE only exists on API 26+ (and HARDWARE
            // bitmaps can't appear below it) — guard the reference so API 24/25
            // devices don't hit a NoSuchFieldError on this exact line.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE
            ) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "BITMAP_CONVERT_FAILED: ${e.message}")
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // ── Loading ──────────────────────────────────────────────────

    private fun loadModelFile(context: Context): MappedByteBuffer? {
        val descriptor = try {
            context.assets.openFd(MODEL_ASSET)
        } catch (e: java.io.FileNotFoundException) {
            return null
        }
        return try {
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "MODEL_MMAP_FAILED: ${e.message}")
            null
        }
    }

    // ── Preprocessing ────────────────────────────────────────────

    /** Center-crop [src] to a square and scale it to [size]x[size]. */
    private fun preprocess(src: Bitmap, size: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w == size && h == size) return src
        val side = minOf(w, h)
        val x = (w - side) / 2
        val y = (h - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped != src) cropped.recycle()
        return scaled
    }

    /**
     * Float input for float models: OpenNSFW (VGG-style) preprocessing — BGR
     * channel order, raw 0..255 values minus per-channel means (B=104, G=117,
     * R=123), no scaling. This is what the shipped nsfw_detector.tflite was
     * trained with; verified against the model's reference implementation
     * (feeding RGB [0,1] instead inflates NSFW scores ~5x on safe images).
     */
    private fun toFloatInput(bitmap: Bitmap, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.putFloat(((pixel and 0xFF) - 104f))            // B
            buffer.putFloat((((pixel shr 8) and 0xFF) - 117f))    // G
            buffer.putFloat((((pixel shr 16) and 0xFF) - 123f))   // R
        }
        buffer.rewind()
        return buffer
    }

    /** uint8 0..255 RGB input for quantized models. */
    private fun toByteInput(bitmap: Bitmap, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size * size * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.put(((pixel shr 16) and 0xFF).toByte())
            buffer.put(((pixel shr 8) and 0xFF).toByte())
            buffer.put((pixel and 0xFF).toByte())
        }
        buffer.rewind()
        return buffer
    }
}
