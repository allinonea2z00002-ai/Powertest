package com.purelock.app.service.image

import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * On-device image classifier for the experimental image detection.
 *
 * Loads `assets/nsfw_model.tflite` when it exists. **No model file is bundled with the app yet**,
 * so [available] is false until one is added — the service refuses to start and the UI says so.
 * Nothing is ever uploaded: classification runs entirely on the phone, and frames are never
 * stored.
 *
 * Model contract (documented so a future model can be dropped in):
 * - Input: one float32 RGB image tensor `[1, height, width, 3]`, pixels normalised to [0, 1].
 * - Output: either a single float (adult probability) or two floats `[safe, adult]`; the
 *   adult probability is the second value. Any other shape reports unavailable for that frame.
 */
class ImageClassifier(context: Context) {

    private val interpreter: Interpreter? = loadModel(context)

    /** True when a model file was found and loaded. */
    val available: Boolean get() = interpreter != null

    private val inputHeight: Int
    private val inputWidth: Int
    private val outputSize: Int

    init {
        val it = interpreter
        if (it == null) {
            inputHeight = 0
            inputWidth = 0
            outputSize = 0
        } else {
            val inShape = it.getInputTensor(0).shape()
            // Expected [1, height, width, 3]; fall back to 224x224 when the shape is unexpected.
            inputHeight = inShape.getOrNull(1)?.takeIf { n -> n > 0 } ?: 224
            inputWidth = inShape.getOrNull(2)?.takeIf { n -> n > 0 } ?: 224
            val outShape = it.getOutputTensor(0).shape()
            outputSize = outShape.fold(1) { acc, dim -> acc * dim.coerceAtLeast(1) }
        }
    }

    /**
     * Adult probability for [bitmap] (0.0–1.0), or null when no model is loaded, the model
     * shape is unsupported, or classification fails. The caller decides the threshold.
     */
    fun classify(bitmap: Bitmap): Float? {
        val it = interpreter ?: return null
        if (it.getInputTensor(0).dataType() != DataType.FLOAT32) return null
        if (outputSize != 1 && outputSize != 2) return null
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val pixels = IntArray(inputWidth * inputHeight)
            scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            if (scaled !== bitmap) scaled.recycle()
            val input = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3)
                .order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 0xFF) / 255f)
                input.putFloat(((pixel shr 8) and 0xFF) / 255f)
                input.putFloat((pixel and 0xFF) / 255f)
            }
            input.rewind()
            val output = Array(1) { FloatArray(outputSize) }
            it.run(input, output)
            val value = if (outputSize == 1) output[0][0] else output[0][1]
            value.coerceIn(0f, 1f)
        }.getOrNull()
    }

    fun close() {
        runCatching { interpreter?.close() }
    }

    companion object {
        const val MODEL_ASSET = "nsfw_model.tflite"

        /** True when the model asset exists (cheap existence check, no interpreter created). */
        fun modelExists(context: Context): Boolean =
            runCatching {
                context.assets.openFd(MODEL_ASSET).close()
                true
            }.getOrDefault(false)

        private fun loadModel(context: Context): Interpreter? {
            if (!modelExists(context)) return null
            return runCatching {
                val fd = context.assets.openFd(MODEL_ASSET)
                val input = FileInputStream(fd.fileDescriptor)
                val buffer: MappedByteBuffer = input.channel.map(
                    FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                )
                fd.close()
                Interpreter(buffer)
            }.getOrNull()
        }
    }
}
