package com.matheus.darkui.engine

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Local salient-object segmentation for app icons.
 *
 * U2NetP is used only to understand icon structure. It never generates pixels.
 * The original icon remains the source of truth; this class only returns a
 * foreground confidence mask that DarkIconEngine can accept or reject.
 */
class AiIconSegmenter(
    context: Context,
    private val outputSize: Int = 256
) : AutoCloseable {

    data class Segmentation(
        val mask: FloatArray,
        val width: Int,
        val height: Int,
        val confidence: Float,
        val foregroundRatio: Float
    )

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputShape: LongArray

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }

        val options = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            setIntraOpNumThreads(4)
        }

        session = environment.createSession(modelBytes, options)

        val rawShape = (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape
        inputShape = if (rawShape != null && rawShape.size == 4) {
            longArrayOf(
                1,
                3,
                if (rawShape[2] > 0) rawShape[2] else MODEL_SIZE.toLong(),
                if (rawShape[3] > 0) rawShape[3] else MODEL_SIZE.toLong()
            )
        } else {
            longArrayOf(1, 3, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
        }
    }

    fun segment(bitmap: Bitmap): Segmentation? {
        val modelH = inputShape[2].toInt()
        val modelW = inputShape[3].toInt()
        if (modelH <= 0 || modelW <= 0) return null

        val resized = Bitmap.createScaledBitmap(bitmap, modelW, modelH, true)
        val pixels = IntArray(modelW * modelH)
        resized.getPixels(pixels, 0, modelW, 0, 0, modelW, modelH)
        if (resized !== bitmap) resized.recycle()

        val input = FloatBuffer.allocate(3 * modelW * modelH)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        for (channel in 0 until 3) {
            for (pixel in pixels) {
                val raw = when (channel) {
                    0 -> (pixel shr 16) and 0xFF
                    1 -> (pixel shr 8) and 0xFF
                    else -> pixel and 0xFF
                }
                val value = (raw / 255f - mean[channel]) / std[channel]
                input.put(value)
            }
        }
        input.rewind()

        val tensor = OnnxTensor.createTensor(environment, input, inputShape)
        val result = try {
            session.run(mapOf(session.inputNames.first() to tensor))
        } catch (_: Throwable) {
            tensor.close()
            return null
        }

        val rawMask = try {
            val outputTensor = result.first().value as? OnnxTensor ?: return null
            val shape = outputTensor.info.shape
            if (shape.size < 4) return null
            val h = shape[shape.size - 2].toInt()
            val w = shape[shape.size - 1].toInt()
            val data = FloatArray(w * h)
            outputTensor.floatBuffer.rewind()
            outputTensor.floatBuffer.get(data)
            Triple(data, w, h)
        } finally {
            tensor.close()
            result.close()
        }

        val normalized = normalize(rawMask.first)
        val resizedMask = resizeMask(
            source = normalized,
            sourceW = rawMask.second,
            sourceH = rawMask.third,
            targetW = outputSize,
            targetH = outputSize
        )

        val stats = analyzeMask(resizedMask, outputSize, outputSize)
        if (stats.foregroundRatio !in 0.025f..0.88f) return null
        if (stats.dynamicRange < 0.34f) return null

        return Segmentation(
            mask = resizedMask,
            width = outputSize,
            height = outputSize,
            confidence = stats.confidence,
            foregroundRatio = stats.foregroundRatio
        )
    }

    private data class MaskStats(
        val foregroundRatio: Float,
        val dynamicRange: Float,
        val confidence: Float
    )

    private fun analyzeMask(mask: FloatArray, width: Int, height: Int): MaskStats {
        var minValue = 1f
        var maxValue = 0f
        var foreground = 0
        var centerTotal = 0f
        var centerCount = 0
        var edgeTotal = 0f
        var edgeCount = 0

        val centerLeft = (width * 0.18f).toInt()
        val centerRight = (width * 0.82f).toInt()
        val centerTop = (height * 0.18f).toInt()
        val centerBottom = (height * 0.82f).toInt()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = mask[y * width + x]
                minValue = min(minValue, value)
                maxValue = max(maxValue, value)
                if (value >= FOREGROUND_THRESHOLD) foreground++

                if (x in centerLeft..centerRight && y in centerTop..centerBottom) {
                    centerTotal += value
                    centerCount++
                }

                if (
                    x < width * 0.12f ||
                    x > width * 0.88f ||
                    y < height * 0.12f ||
                    y > height * 0.88f
                ) {
                    edgeTotal += value
                    edgeCount++
                }
            }
        }

        val centerMean = if (centerCount == 0) 0f else centerTotal / centerCount
        val edgeMean = if (edgeCount == 0) 0f else edgeTotal / edgeCount
        val dynamicRange = maxValue - minValue
        val centerAdvantage = (centerMean - edgeMean).coerceIn(-1f, 1f)

        val confidence = (
            dynamicRange * 0.55f +
                centerAdvantage.coerceAtLeast(0f) * 0.30f +
                0.15f
            ).coerceIn(0f, 1f)

        return MaskStats(
            foregroundRatio = foreground.toFloat() / mask.size.coerceAtLeast(1),
            dynamicRange = dynamicRange,
            confidence = confidence
        )
    }

    private fun normalize(values: FloatArray): FloatArray {
        var minValue = Float.MAX_VALUE
        var maxValue = -Float.MAX_VALUE
        for (value in values) {
            if (value < minValue) minValue = value
            if (value > maxValue) maxValue = value
        }

        val range = (maxValue - minValue).coerceAtLeast(1e-6f)
        return FloatArray(values.size) { index ->
            ((values[index] - minValue) / range).coerceIn(0f, 1f)
        }
    }

    private fun resizeMask(
        source: FloatArray,
        sourceW: Int,
        sourceH: Int,
        targetW: Int,
        targetH: Int
    ): FloatArray {
        if (sourceW == targetW && sourceH == targetH) return source

        val output = FloatArray(targetW * targetH)
        for (y in 0 until targetH) {
            val sy = if (targetH <= 1) 0f else y * (sourceH - 1f) / (targetH - 1f)
            val y0 = sy.toInt().coerceIn(0, sourceH - 1)
            val y1 = (y0 + 1).coerceAtMost(sourceH - 1)
            val fy = sy - y0

            for (x in 0 until targetW) {
                val sx = if (targetW <= 1) 0f else x * (sourceW - 1f) / (targetW - 1f)
                val x0 = sx.toInt().coerceIn(0, sourceW - 1)
                val x1 = (x0 + 1).coerceAtMost(sourceW - 1)
                val fx = sx - x0

                val top = source[y0 * sourceW + x0] * (1f - fx) +
                    source[y0 * sourceW + x1] * fx
                val bottom = source[y1 * sourceW + x0] * (1f - fx) +
                    source[y1 * sourceW + x1] * fx

                output[y * targetW + x] = top * (1f - fy) + bottom * fy
            }
        }
        return output
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_VERSION = 1
        private const val MODEL_FILE = "u2netp.onnx"
        private const val MODEL_SIZE = 320
        private const val FOREGROUND_THRESHOLD = 0.54f
    }
}
