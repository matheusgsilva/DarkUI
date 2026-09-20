package com.matheus.darkui.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.matheus.darkui.model.IconStyle
import com.matheus.darkui.model.SmartIconResult
import com.matheus.darkui.util.BitmapUtils
import kotlin.math.roundToInt

class DarkIconEngine(private val size: Int = 256) {
    companion object {
        const val ENGINE_VERSION = 3
        private const val DARK_BG = 0xFF18181B.toInt()
        private const val AMOLED_BG = Color.BLACK
        private const val TINT_BG = 0xFF1C1C22.toInt()
        private const val TINT_FG = 0xFFE0E0EA.toInt()
    }

    fun generate(drawable: Drawable, style: IconStyle): SmartIconResult {
        return if (drawable is AdaptiveIconDrawable) generateAdaptive(drawable, style)
        else generateLegacy(drawable, style)
    }

    private fun generateAdaptive(icon: AdaptiveIconDrawable, style: IconStyle): SmartIconResult {
        if (style == IconStyle.TINTED && Build.VERSION.SDK_INT >= 33) {
            val mono = icon.monochrome
            if (mono != null) {
                val mask = BitmapUtils.drawableToBitmap(mono, size)
                return SmartIconResult(
                    bitmap = renderTintedMask(mask),
                    method = "Adaptive monochrome",
                    confidence = 1.0f
                )
            }
        }

        val foreground = BitmapUtils.drawableToBitmap(icon.foreground, size)
        return if (style == IconStyle.TINTED) {
            SmartIconResult(
                bitmap = renderTintedMask(foreground, deriveFromLuminance = true),
                method = "Adaptive foreground mask",
                confidence = 0.94f
            )
        } else {
            val result = BitmapUtils.roundedBackground(size, backgroundFor(style))
            val corrected = improveForegroundForDark(foreground)
            Canvas(result).drawBitmap(corrected, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            SmartIconResult(result, "Adaptive layers", 0.98f)
        }
    }

    private fun generateLegacy(drawable: Drawable, style: IconStyle): SmartIconResult {
        val source = BitmapUtils.drawableToBitmap(drawable, size)
        val edge = sampleEdge(source)
        val opaqueEdge = edge.count { Color.alpha(it) > 32 }
        val edgeMean = BitmapUtils.meanOpaqueColor(edge)
        val edgeUniformity = if (opaqueEdge == 0) 999f else edge
            .filter { Color.alpha(it) > 32 }
            .map { BitmapUtils.colorDistance(it, edgeMean) }
            .average().toFloat()

        if (style == IconStyle.TINTED) {
            val mask = when {
                opaqueEdge <= edge.size / 3 -> source
                edgeUniformity < 44f -> removeFlatBackground(source, edgeMean, tintMode = true)
                else -> source
            }
            return SmartIconResult(
                renderTintedMask(mask, deriveFromLuminance = opaqueEdge > edge.size / 3),
                if (edgeUniformity < 44f) "Legacy segmented + tint" else "Legacy tint fallback",
                if (edgeUniformity < 44f) 0.86f else 0.62f
            )
        }

        val base = BitmapUtils.roundedBackground(size, backgroundFor(style))
        val canvas = Canvas(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        return when {
            opaqueEdge <= edge.size / 3 -> {
                val corrected = improveForegroundForDark(source)
                val pad = size * 0.09f
                canvas.drawBitmap(corrected, null, RectF(pad, pad, size - pad, size - pad), paint)
                SmartIconResult(base, "Legacy transparent", 0.91f)
            }
            edgeUniformity < 44f -> {
                val segmented = removeFlatBackground(source, edgeMean, tintMode = false)
                val corrected = improveForegroundForDark(segmented)
                canvas.drawBitmap(corrected, 0f, 0f, paint)
                SmartIconResult(base, "Legacy smart segmentation", confidenceFromUniformity(edgeUniformity))
            }
            else -> {
                // Difficult artwork: preserve the complete icon instead of destroying brand details.
                val corrected = improveForegroundForDark(source, conservative = true)
                val pad = size * 0.14f
                canvas.drawBitmap(corrected, null, RectF(pad, pad, size - pad, size - pad), paint)
                SmartIconResult(base, "Legacy safe inset", 0.58f)
            }
        }
    }

    private fun removeFlatBackground(source: Bitmap, background: Int, tintMode: Boolean): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = Color.alpha(c)
            if (alpha == 0) continue
            val distance = BitmapUtils.colorDistance(c, background)
            val mask = BitmapUtils.smoothStep(18f, 68f, distance)
            val newAlpha = (alpha * mask).roundToInt().coerceIn(0, 255)
            pixels[i] = if (tintMode) Color.argb(newAlpha, 255, 255, 255)
            else Color.argb(newAlpha, Color.red(c), Color.green(c), Color.blue(c))
        }
        out.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return out
    }

    private fun improveForegroundForDark(source: Bitmap, conservative: Boolean = false): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) < 12) continue
            val lum = BitmapUtils.luminance(c)
            if (lum < if (conservative) 0.045f else 0.14f) {
                pixels[i] = if (conservative) {
                    val a = Color.alpha(c)
                    val amount = 0.20f
                    Color.argb(
                        a,
                        (Color.red(c) + (255 - Color.red(c)) * amount).roundToInt(),
                        (Color.green(c) + (255 - Color.green(c)) * amount).roundToInt(),
                        (Color.blue(c) + (255 - Color.blue(c)) * amount).roundToInt()
                    )
                } else BitmapUtils.lightenForDarkBackground(c)
            }
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun renderTintedMask(maskSource: Bitmap, deriveFromLuminance: Boolean = false): Bitmap {
        val out = BitmapUtils.roundedBackground(size, TINT_BG)
        val overlay = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        maskSource.getPixels(pixels, 0, size, 0, 0, size, size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val baseAlpha = Color.alpha(c) / 255f
            val strength = if (deriveFromLuminance) {
                val lum = BitmapUtils.luminance(c)
                (0.25f + lum * 0.75f).coerceIn(0f, 1f)
            } else 1f
            val alpha = (255 * baseAlpha * strength).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, Color.red(TINT_FG), Color.green(TINT_FG), Color.blue(TINT_FG))
        }
        overlay.setPixels(pixels, 0, size, 0, 0, size, size)
        Canvas(out).drawBitmap(overlay, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun sampleEdge(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val samples = ArrayList<Int>(40)
        val points = 10
        repeat(points) { i ->
            val x = (i * (w - 1) / (points - 1f)).roundToInt().coerceIn(0, w - 1)
            val y = (i * (h - 1) / (points - 1f)).roundToInt().coerceIn(0, h - 1)
            samples += bitmap.getPixel(x, 1.coerceAtMost(h - 1))
            samples += bitmap.getPixel(x, (h - 2).coerceAtLeast(0))
            samples += bitmap.getPixel(1.coerceAtMost(w - 1), y)
            samples += bitmap.getPixel((w - 2).coerceAtLeast(0), y)
        }
        return samples
    }

    private fun confidenceFromUniformity(uniformity: Float): Float {
        return (0.93f - (uniformity / 44f) * 0.18f).coerceIn(0.72f, 0.93f)
    }

    private fun backgroundFor(style: IconStyle): Int = when (style) {
        IconStyle.DARK -> DARK_BG
        IconStyle.AMOLED -> AMOLED_BG
        IconStyle.TINTED -> TINT_BG
    }
}
