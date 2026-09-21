package com.matheus.darkui.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.matheus.darkui.model.IconStyle
import com.matheus.darkui.model.SmartIconResult
import com.matheus.darkui.util.BitmapUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dark icon renderer inspired by the automatic iOS 18 treatment:
 *
 * 1. Analyze the complete icon and its edge/background statistics.
 * 2. Recolor the background only when segmentation confidence is high.
 * 3. Preserve complex/illustrative artwork and only dim it when segmentation is unsafe.
 * 4. Keep every result inside one consistent One UI-style rounded frame.
 *
 * This is deliberately deterministic and local: no ML model and no network are required.
 */
class DarkIconEngine(private val size: Int = 256) {
    companion object {
        const val ENGINE_VERSION = 4

        private const val DARK_BG = 0xFF17171A.toInt()
        private const val AMOLED_BG = Color.BLACK
        private const val TINT_BG = 0xFF17171A.toInt()
        private const val TINT_FG = 0xFFE5E5EA.toInt()

        private const val SEGMENT_EDGE_UNIFORMITY_MAX = 36f
        private const val SEGMENT_MIN_BACKGROUND_COVERAGE = 0.28f
        private const val SEGMENT_MAX_BACKGROUND_COVERAGE = 0.91f
        private const val SEGMENT_MAX_COLOR_BINS = 48
        private const val SEGMENT_MAX_DETAIL = 0.42f
    }

    private data class IconAnalysis(
        val edgeMean: Int,
        val edgeOpaqueRatio: Float,
        val edgeUniformity: Float,
        val backgroundCoverage: Float,
        val colorBins: Int,
        val detail: Float,
        val segmentConfidence: Float,
        val shouldSegment: Boolean
    )

    fun generate(drawable: Drawable, style: IconStyle, isGame: Boolean = false): SmartIconResult {
        if (style == IconStyle.TINTED && drawable is AdaptiveIconDrawable && Build.VERSION.SDK_INT >= 33) {
            drawable.monochrome?.let { monochrome ->
                val mask = BitmapUtils.drawableToBitmap(monochrome, size)
                return SmartIconResult(
                    bitmap = renderTintedMask(mask),
                    method = "iOS-style • monochrome",
                    confidence = 1f
                )
            }
        }

        val source = BitmapUtils.drawableToBitmap(drawable, size)
        val analysis = analyze(source)

        if (style == IconStyle.TINTED) {
            return generateTinted(source, analysis)
        }

        if (isGame) {
            return preserveArtwork(
                source = source,
                style = style,
                strongerDim = true,
                method = "iOS-style • game artwork",
                confidence = 0.98f
            )
        }

        if (analysis.shouldSegment) {
            val recolored = recolorFlatBackground(source, analysis.edgeMean, style)
            return SmartIconResult(
                bitmap = renderOneUiFrame(recolored, backgroundFor(style)),
                method = "iOS-style • segmented",
                confidence = analysis.segmentConfidence
            )
        }

        if (analysis.edgeOpaqueRatio < 0.45f) {
            val foreground = improveForegroundForDark(source, conservative = true)
            return SmartIconResult(
                bitmap = renderOneUiFrame(foreground, backgroundFor(style)),
                method = "iOS-style • transparent",
                confidence = 0.90f
            )
        }

        return preserveArtwork(
            source = source,
            style = style,
            strongerDim = false,
            method = "iOS-style • artwork fallback",
            confidence = (0.72f + (1f - analysis.detail) * 0.12f).coerceIn(0.72f, 0.84f)
        )
    }

    private fun generateTinted(source: Bitmap, analysis: IconAnalysis): SmartIconResult {
        val mask = if (analysis.shouldSegment) {
            removeFlatBackground(source, analysis.edgeMean, tintMode = true)
        } else {
            source
        }

        return SmartIconResult(
            bitmap = renderTintedMask(mask, deriveFromLuminance = !analysis.shouldSegment),
            method = if (analysis.shouldSegment) {
                "iOS-style • segmented tint"
            } else {
                "iOS-style • universal tint"
            },
            confidence = if (analysis.shouldSegment) analysis.segmentConfidence else 0.78f
        )
    }

    /**
     * The iOS-style fallback: never try to extract a logo from detailed artwork.
     * Preserve the full composition and only reduce luminance before applying the
     * common One UI frame.
     */
    private fun preserveArtwork(
        source: Bitmap,
        style: IconStyle,
        strongerDim: Boolean,
        method: String,
        confidence: Float
    ): SmartIconResult {
        val factor = when {
            style == IconStyle.AMOLED && strongerDim -> 0.64f
            style == IconStyle.AMOLED -> 0.72f
            strongerDim -> 0.74f
            else -> 0.82f
        }
        val dimmed = dimArtwork(source, factor)
        return SmartIconResult(
            bitmap = renderOneUiFrame(dimmed, backgroundFor(style)),
            method = method,
            confidence = confidence
        )
    }

    /**
     * Recolors only pixels statistically close to the detected edge/background.
     * Foreground geometry, color relationships, gradients and anti-aliasing stay in place.
     */
    private fun recolorFlatBackground(source: Bitmap, detectedBackground: Int, style: IconStyle): Bitmap {
        val target = deriveDarkBackground(detectedBackground, style)
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = Color.alpha(c)
            if (alpha < 8) continue

            val distance = BitmapUtils.colorDistance(c, detectedBackground)
            val foregroundWeight = BitmapUtils.smoothStep(20f, 72f, distance)
            val backgroundWeight = 1f - foregroundWeight

            pixels[i] = if (backgroundWeight > 0.03f) {
                blend(c, target, backgroundWeight * 0.96f)
            } else {
                improveSingleForegroundPixel(c)
            }
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun deriveDarkBackground(original: Int, style: IconStyle): Int {
        if (style == IconStyle.AMOLED) return AMOLED_BG

        val hsv = FloatArray(3)
        Color.colorToHSV(original, hsv)

        if (hsv[1] < 0.12f) {
            return DARK_BG
        }

        // Keep the original hue, similar to iOS dark variants of colored backgrounds,
        // while moving the value into a dark range.
        hsv[1] = (hsv[1] * 0.92f + 0.06f).coerceIn(0.30f, 0.92f)
        hsv[2] = 0.20f
        return Color.HSVToColor(Color.alpha(original).coerceAtLeast(255), hsv)
    }

    private fun improveSingleForegroundPixel(color: Int): Int {
        val lum = BitmapUtils.luminance(color)
        if (lum >= 0.055f) return color

        val amount = 0.16f
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) + (255 - Color.red(color)) * amount).roundToInt().coerceIn(0, 255),
            (Color.green(color) + (255 - Color.green(color)) * amount).roundToInt().coerceIn(0, 255),
            (Color.blue(color) + (255 - Color.blue(color)) * amount).roundToInt().coerceIn(0, 255)
        )
    }

    private fun dimArtwork(source: Bitmap, factor: Float): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) == 0) continue

            pixels[i] = Color.argb(
                Color.alpha(c),
                (Color.red(c) * factor).roundToInt().coerceIn(0, 255),
                (Color.green(c) * factor).roundToInt().coerceIn(0, 255),
                (Color.blue(c) * factor).roundToInt().coerceIn(0, 255)
            )
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun renderOneUiFrame(content: Bitmap, background: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pad = size * 0.018f
        val rect = RectF(pad, pad, size - pad, size - pad)
        val radius = size * 0.235f

        paint.color = background
        canvas.drawRoundRect(rect, radius, radius, paint)

        val clip = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(content, null, rect, paint)
        canvas.restore()

        // Very subtle edge definition, close to the visual framing used by One UI.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.006f
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)

        return out
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

            pixels[i] = if (tintMode) {
                Color.argb(newAlpha, 255, 255, 255)
            } else {
                Color.argb(newAlpha, Color.red(c), Color.green(c), Color.blue(c))
            }
        }

        out.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return out
    }

    private fun improveForegroundForDark(source: Bitmap, conservative: Boolean): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) < 12) continue

            val lum = BitmapUtils.luminance(c)
            if (lum < if (conservative) 0.045f else 0.14f) {
                pixels[i] = if (conservative) {
                    improveSingleForegroundPixel(c)
                } else {
                    BitmapUtils.lightenForDarkBackground(c)
                }
            }
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun renderTintedMask(maskSource: Bitmap, deriveFromLuminance: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TINT_BG }
        val pad = size * 0.018f
        val rect = RectF(pad, pad, size - pad, size - pad)
        val radius = size * 0.235f
        canvas.drawRoundRect(rect, radius, radius, framePaint)

        val overlay = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        maskSource.getPixels(pixels, 0, size, 0, 0, size, size)

        for (i in pixels.indices) {
            val c = pixels[i]
            val baseAlpha = Color.alpha(c) / 255f
            val strength = if (deriveFromLuminance) {
                val lum = BitmapUtils.luminance(c)
                (0.20f + lum * 0.80f).coerceIn(0f, 1f)
            } else {
                1f
            }
            val alpha = (255 * baseAlpha * strength).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(
                alpha,
                Color.red(TINT_FG),
                Color.green(TINT_FG),
                Color.blue(TINT_FG)
            )
        }

        overlay.setPixels(pixels, 0, size, 0, 0, size, size)

        val clip = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(
            overlay,
            null,
            rect,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.restore()

        return out
    }

    private fun analyze(bitmap: Bitmap): IconAnalysis {
        val edge = sampleEdge(bitmap)
        val opaqueEdge = edge.filter { Color.alpha(it) > 32 }
        val edgeOpaqueRatio = opaqueEdge.size.toFloat() / edge.size.coerceAtLeast(1)
        val edgeMean = BitmapUtils.meanOpaqueColor(edge)

        val edgeUniformity = if (opaqueEdge.isEmpty()) {
            999f
        } else {
            opaqueEdge
                .map { BitmapUtils.colorDistance(it, edgeMean) }
                .average()
                .toFloat()
        }

        var opaqueSamples = 0
        var backgroundSamples = 0
        var detailTotal = 0f
        var detailCount = 0
        val colorBins = HashSet<Int>()

        val step = 4
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                if (Color.alpha(c) > 32) {
                    opaqueSamples++

                    if (BitmapUtils.colorDistance(c, edgeMean) < 52f) {
                        backgroundSamples++
                    }

                    val bin =
                        ((Color.red(c) shr 5) shl 6) or
                            ((Color.green(c) shr 5) shl 3) or
                            (Color.blue(c) shr 5)
                    colorBins += bin

                    val nextX = (x + step).coerceAtMost(bitmap.width - 1)
                    val nextY = (y + step).coerceAtMost(bitmap.height - 1)

                    if (nextX != x) {
                        detailTotal += BitmapUtils.colorDistance(c, bitmap.getPixel(nextX, y)) / 441.7f
                        detailCount++
                    }
                    if (nextY != y) {
                        detailTotal += BitmapUtils.colorDistance(c, bitmap.getPixel(x, nextY)) / 441.7f
                        detailCount++
                    }
                }
                x += step
            }
            y += step
        }

        val backgroundCoverage = if (opaqueSamples == 0) {
            0f
        } else {
            backgroundSamples.toFloat() / opaqueSamples
        }

        val detail = if (detailCount == 0) 0f else (detailTotal / detailCount).coerceIn(0f, 1f)

        val shouldSegment =
            edgeOpaqueRatio >= 0.60f &&
                edgeUniformity <= SEGMENT_EDGE_UNIFORMITY_MAX &&
                backgroundCoverage in SEGMENT_MIN_BACKGROUND_COVERAGE..SEGMENT_MAX_BACKGROUND_COVERAGE &&
                colorBins.size <= SEGMENT_MAX_COLOR_BINS &&
                detail <= SEGMENT_MAX_DETAIL

        val uniformityScore = (1f - edgeUniformity / SEGMENT_EDGE_UNIFORMITY_MAX).coerceIn(0f, 1f)
        val coverageScore = (1f - abs(backgroundCoverage - 0.62f) / 0.62f).coerceIn(0f, 1f)
        val complexityScore = (1f - colorBins.size / SEGMENT_MAX_COLOR_BINS.toFloat()).coerceIn(0f, 1f)

        val confidence = (
            0.72f +
                uniformityScore * 0.11f +
                coverageScore * 0.07f +
                complexityScore * 0.06f
            ).coerceIn(0.72f, 0.96f)

        return IconAnalysis(
            edgeMean = edgeMean,
            edgeOpaqueRatio = edgeOpaqueRatio,
            edgeUniformity = edgeUniformity,
            backgroundCoverage = backgroundCoverage,
            colorBins = colorBins.size,
            detail = detail,
            segmentConfidence = confidence,
            shouldSegment = shouldSegment
        )
    }

    private fun sampleEdge(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val samples = ArrayList<Int>(96)
        val points = 24

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

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val inverse = 1f - t

        return Color.argb(
            (Color.alpha(from) * inverse + Color.alpha(to) * t).roundToInt().coerceIn(0, 255),
            (Color.red(from) * inverse + Color.red(to) * t).roundToInt().coerceIn(0, 255),
            (Color.green(from) * inverse + Color.green(to) * t).roundToInt().coerceIn(0, 255),
            (Color.blue(from) * inverse + Color.blue(to) * t).roundToInt().coerceIn(0, 255)
        )
    }

    private fun backgroundFor(style: IconStyle): Int = when (style) {
        IconStyle.DARK -> DARK_BG
        IconStyle.AMOLED -> AMOLED_BG
        IconStyle.TINTED -> TINT_BG
    }
}
