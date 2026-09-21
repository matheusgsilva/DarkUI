package com.matheus.darkui.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.matheus.darkui.model.SmartIconResult
import com.matheus.darkui.util.BitmapUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Automatic dark-icon renderer.
 *
 * The strategy follows the behavior Apple documents for generated icon appearances:
 * preserve the icon's core visual features and legibility, generate a dark appearance
 * automatically when the developer did not supply one, and avoid destructive
 * transformations on detailed artwork.
 *
 * Android gives us one extra advantage for AdaptiveIconDrawable: foreground and
 * background layers can be transformed independently.
 */
class DarkIconEngine(private val size: Int = 256) {
    companion object {
        const val ENGINE_VERSION = 8

        private const val DARK_SURFACE = 0xFF171719.toInt()
        private const val DARK_NEUTRAL = 0xFF151517.toInt()

        private const val SEGMENT_EDGE_UNIFORMITY_MAX = 42f
        private const val SEGMENT_MIN_BACKGROUND_COVERAGE = 0.24f
        private const val SEGMENT_MAX_BACKGROUND_COVERAGE = 0.94f
        private const val SEGMENT_MAX_COLOR_BINS = 88
        private const val SEGMENT_MAX_DETAIL = 0.34f
    }

    private data class IconAnalysis(
        val edgeMean: Int,
        val edgeOpaqueRatio: Float,
        val edgeUniformity: Float,
        val backgroundCoverage: Float,
        val opaqueCoverage: Float,
        val dominantColor: Int,
        val dominantRatio: Float,
        val dominantCanvasCoverage: Float,
        val colorBins: Int,
        val detail: Float,
        val averageLuminance: Float,
        val shouldSegment: Boolean
    ) {
        val isAlreadyDark: Boolean
            get() = averageLuminance < 0.16f
    }

    fun generate(drawable: Drawable, isGame: Boolean = false): SmartIconResult {
        if (drawable is AdaptiveIconDrawable && !isGame) {
            return generateAdaptive(drawable)
        }

        val source = BitmapUtils.drawableToBitmap(drawable, size)
        val analysis = analyze(source)

        if (isGame) {
            return preserveArtwork(
                source = source,
                analysis = analysis,
                factor = 0.74f,
                method = "Dark automático • jogo preservado",
                confidence = 0.99f
            )
        }

        if (analysis.shouldSegment) {
            val recolored = recolorBackgroundLikePixels(source, analysis.edgeMean)
            return SmartIconResult(
                bitmap = renderOneUiFrame(recolored),
                method = "Dark automático • fundo convertido",
                confidence = segmentationConfidence(analysis)
            )
        }

        // Smooth, full-bleed brand gradients (for example many social/media icons)
        // are not uniform enough for the flat-background branch above. Estimate
        // their background from the four corners and only darken pixels that fit
        // that smooth surface, keeping logo strokes and glyphs intact.
        if (
            !analysis.isAlreadyDark &&
            analysis.opaqueCoverage >= 0.92f &&
            analysis.edgeOpaqueRatio >= 0.85f &&
            analysis.detail <= 0.18f
        ) {
            val recolored = recolorSmoothFullBleedBackground(source)
            return SmartIconResult(
                bitmap = renderOneUiFrame(recolored),
                method = "Dark automático • gradiente convertido",
                confidence = 0.92f
            )
        }

        if (analysis.edgeOpaqueRatio < 0.45f) {
            val foreground = liftTransparentForeground(source)
            return SmartIconResult(
                bitmap = renderOneUiFrame(foreground),
                method = "Dark automático • símbolo preservado",
                confidence = 0.94f
            )
        }

        if (
            analysis.dominantCanvasCoverage >= 0.28f &&
            isUsefulBackgroundColor(analysis.dominantColor) &&
            analysis.detail <= 0.38f
        ) {
            val recolored = recolorDominantRegion(source, analysis.dominantColor)
            return SmartIconResult(
                bitmap = renderOneUiFrame(recolored),
                method = "Dark automático • cor dominante convertida",
                confidence = 0.88f
            )
        }

        if (analysis.isAlreadyDark) {
            return preserveArtwork(
                source = source,
                analysis = analysis,
                factor = 0.96f,
                method = "Dark automático • já escuro",
                confidence = 0.96f
            )
        }

        return preserveArtwork(
            source = source,
            analysis = analysis,
            factor = 0.82f,
            method = "Dark automático • arte preservada",
            confidence = 0.82f
        )
    }

    private fun generateAdaptive(icon: AdaptiveIconDrawable): SmartIconResult {
        val foreground = BitmapUtils.drawableToBitmap(icon.foreground, size)
        val background = BitmapUtils.drawableToBitmap(icon.background, size)
        val foregroundAnalysis = analyze(foreground)

        if (
            foregroundAnalysis.opaqueCoverage > 0.72f &&
            foregroundAnalysis.detail > 0.18f
        ) {
            val complete = BitmapUtils.drawableToBitmap(icon, size)
            val completeAnalysis = analyze(complete)
            return preserveArtwork(
                source = complete,
                analysis = completeAnalysis,
                factor = if (completeAnalysis.isAlreadyDark) 0.96f else 0.80f,
                method = "Dark automático • adaptive complexo",
                confidence = 0.90f
            )
        }

        val darkBackground = darkenAdaptiveBackground(background)
        val darkForeground = transformAdaptiveForeground(foreground, foregroundAnalysis)

        val composite = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(darkBackground, 0f, 0f, paint)
        canvas.drawBitmap(darkForeground, 0f, 0f, paint)

        return SmartIconResult(
            bitmap = renderOneUiFrame(composite),
            method = "Dark automático • adaptive em camadas",
            confidence = 0.99f
        )
    }

    private fun transformAdaptiveForeground(
        source: Bitmap,
        analysis: IconAnalysis
    ): Bitmap {
        if (
            analysis.dominantCanvasCoverage >= 0.20f &&
            isUsefulBackgroundColor(analysis.dominantColor)
        ) {
            return recolorDominantRegion(source, analysis.dominantColor)
        }
        return improveForegroundContrast(source)
    }

    private fun darkenAdaptiveBackground(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        val hsv = FloatArray(3)

        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = Color.alpha(c)
            if (alpha == 0) continue

            Color.colorToHSV(c, hsv)
            val originalValue = hsv[2]

            if (hsv[1] < 0.10f) {
                hsv[1] = 0f
                hsv[2] = (0.09f + originalValue * 0.08f).coerceIn(0.09f, 0.17f)
            } else {
                hsv[1] = (hsv[1] * 0.94f + 0.03f).coerceIn(0f, 1f)
                hsv[2] = (0.10f + originalValue * 0.16f).coerceIn(0.11f, 0.26f)
            }

            pixels[i] = Color.HSVToColor(alpha, hsv)
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun recolorBackgroundLikePixels(source: Bitmap, background: Int): Bitmap {
        val target = deriveDarkVariant(background)
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) < 8) continue

            val distance = BitmapUtils.colorDistance(c, background)
            val foregroundWeight = BitmapUtils.smoothStep(18f, 76f, distance)
            val backgroundWeight = 1f - foregroundWeight

            pixels[i] = if (backgroundWeight > 0.025f) {
                blend(c, target, backgroundWeight * 0.97f)
            } else {
                improveForegroundPixel(c)
            }
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun recolorSmoothFullBleedBackground(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        val left = 2.coerceAtMost(out.width - 1)
        val top = 2.coerceAtMost(out.height - 1)
        val right = (out.width - 3).coerceAtLeast(0)
        val bottom = (out.height - 3).coerceAtLeast(0)

        val c00 = out.getPixel(left, top)
        val c10 = out.getPixel(right, top)
        val c01 = out.getPixel(left, bottom)
        val c11 = out.getPixel(right, bottom)

        for (y in 0 until out.height) {
            val ty = if (out.height <= 1) 0f else y / (out.height - 1f)
            for (x in 0 until out.width) {
                val index = y * out.width + x
                val c = pixels[index]
                if (Color.alpha(c) < 8) continue

                val tx = if (out.width <= 1) 0f else x / (out.width - 1f)
                val expected = bilinearColor(c00, c10, c01, c11, tx, ty)
                val distance = BitmapUtils.colorDistance(c, expected)
                val backgroundWeight = 1f - BitmapUtils.smoothStep(34f, 92f, distance)

                pixels[index] = if (backgroundWeight > 0.04f) {
                    val target = deriveDarkVariant(expected)
                    blend(c, target, backgroundWeight * 0.96f)
                } else {
                    improveForegroundPixel(c)
                }
            }
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun bilinearColor(
        c00: Int,
        c10: Int,
        c01: Int,
        c11: Int,
        tx: Float,
        ty: Float
    ): Int {
        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        fun channel(selector: (Int) -> Int): Int {
            val topValue = lerp(selector(c00).toFloat(), selector(c10).toFloat(), tx)
            val bottomValue = lerp(selector(c01).toFloat(), selector(c11).toFloat(), tx)
            return lerp(topValue, bottomValue, ty).roundToInt().coerceIn(0, 255)
        }

        return Color.argb(
            channel { Color.alpha(it) },
            channel { Color.red(it) },
            channel { Color.green(it) },
            channel { Color.blue(it) }
        )
    }

    private fun recolorDominantRegion(source: Bitmap, dominantColor: Int): Bitmap {
        val target = deriveDarkVariant(dominantColor)
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) < 8) continue

            val distance = BitmapUtils.colorDistance(c, dominantColor)
            val weight = 1f - BitmapUtils.smoothStep(24f, 88f, distance)

            pixels[i] = if (weight > 0.03f) {
                blend(c, target, weight * 0.96f)
            } else {
                improveForegroundPixel(c)
            }
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun deriveDarkVariant(original: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(original, hsv)

        if (hsv[1] < 0.11f) {
            return DARK_NEUTRAL
        }

        hsv[1] = (hsv[1] * 0.94f + 0.03f).coerceIn(0.22f, 1f)
        hsv[2] = 0.20f
        return Color.HSVToColor(255, hsv)
    }

    private fun preserveArtwork(
        source: Bitmap,
        analysis: IconAnalysis,
        factor: Float,
        method: String,
        confidence: Float
    ): SmartIconResult {
        val actualFactor = if (analysis.isAlreadyDark) maxOf(factor, 0.94f) else factor
        return SmartIconResult(
            bitmap = renderOneUiFrame(dimArtwork(source, actualFactor)),
            method = method,
            confidence = confidence
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

    private fun improveForegroundContrast(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) < 8) continue
            pixels[i] = improveForegroundPixel(c)
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun liftTransparentForeground(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

        for (i in pixels.indices) {
            val c = pixels[i]
            if (Color.alpha(c) < 8) continue

            val lum = BitmapUtils.luminance(c)
            pixels[i] = if (lum < 0.12f) {
                mixWithWhite(c, 0.52f)
            } else {
                improveForegroundPixel(c)
            }
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun improveForegroundPixel(color: Int): Int {
        val lum = BitmapUtils.luminance(color)
        return when {
            lum < 0.07f -> mixWithWhite(color, 0.55f)
            lum < 0.14f -> mixWithWhite(color, 0.30f)
            else -> color
        }
    }

    private fun mixWithWhite(color: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) + (255 - Color.red(color)) * t).roundToInt().coerceIn(0, 255),
            (Color.green(color) + (255 - Color.green(color)) * t).roundToInt().coerceIn(0, 255),
            (Color.blue(color) + (255 - Color.blue(color)) * t).roundToInt().coerceIn(0, 255)
        )
    }

    private fun renderOneUiFrame(content: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pad = size * 0.018f
        val rect = RectF(pad, pad, size - pad, size - pad)
        val radius = size * 0.235f

        paint.color = DARK_SURFACE
        canvas.drawRoundRect(rect, radius, radius, paint)

        val clip = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(content, null, rect, paint)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.006f
        paint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)

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

        val bins = IntArray(512)
        var totalSamples = 0
        var opaqueSamples = 0
        var backgroundSamples = 0
        var detailTotal = 0f
        var detailCount = 0
        var luminanceTotal = 0f

        val step = 4
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                totalSamples++
                val c = bitmap.getPixel(x, y)

                if (Color.alpha(c) > 32) {
                    opaqueSamples++
                    luminanceTotal += BitmapUtils.luminance(c)

                    if (
                        Color.alpha(edgeMean) > 0 &&
                        BitmapUtils.colorDistance(c, edgeMean) < 54f
                    ) {
                        backgroundSamples++
                    }

                    val r = Color.red(c) shr 5
                    val g = Color.green(c) shr 5
                    val b = Color.blue(c) shr 5
                    bins[(r shl 6) or (g shl 3) or b]++

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

        val dominantIndex = bins.indices.maxByOrNull { bins[it] } ?: 0
        val dominantCount = bins[dominantIndex]
        val dominantColor = quantizedColor(dominantIndex)

        val backgroundCoverage = if (opaqueSamples == 0) 0f
        else backgroundSamples.toFloat() / opaqueSamples

        val opaqueCoverage = if (totalSamples == 0) 0f
        else opaqueSamples.toFloat() / totalSamples

        val dominantRatio = if (opaqueSamples == 0) 0f
        else dominantCount.toFloat() / opaqueSamples

        val dominantCanvasCoverage = if (totalSamples == 0) 0f
        else dominantCount.toFloat() / totalSamples

        val averageLuminance = if (opaqueSamples == 0) 0f
        else luminanceTotal / opaqueSamples

        val detail = if (detailCount == 0) 0f
        else (detailTotal / detailCount).coerceIn(0f, 1f)

        val colorBins = bins.count { it > 0 }

        val shouldSegment =
            edgeOpaqueRatio >= 0.60f &&
                edgeUniformity <= SEGMENT_EDGE_UNIFORMITY_MAX &&
                backgroundCoverage in SEGMENT_MIN_BACKGROUND_COVERAGE..SEGMENT_MAX_BACKGROUND_COVERAGE &&
                colorBins <= SEGMENT_MAX_COLOR_BINS &&
                detail <= SEGMENT_MAX_DETAIL

        return IconAnalysis(
            edgeMean = edgeMean,
            edgeOpaqueRatio = edgeOpaqueRatio,
            edgeUniformity = edgeUniformity,
            backgroundCoverage = backgroundCoverage,
            opaqueCoverage = opaqueCoverage,
            dominantColor = dominantColor,
            dominantRatio = dominantRatio,
            dominantCanvasCoverage = dominantCanvasCoverage,
            colorBins = colorBins,
            detail = detail,
            averageLuminance = averageLuminance,
            shouldSegment = shouldSegment
        )
    }

    private fun segmentationConfidence(analysis: IconAnalysis): Float {
        val uniformityScore =
            (1f - analysis.edgeUniformity / SEGMENT_EDGE_UNIFORMITY_MAX).coerceIn(0f, 1f)
        val coverageScore =
            (1f - abs(analysis.backgroundCoverage - 0.62f) / 0.62f).coerceIn(0f, 1f)
        val complexityScore =
            (1f - analysis.colorBins / SEGMENT_MAX_COLOR_BINS.toFloat()).coerceIn(0f, 1f)

        return (
            0.76f +
                uniformityScore * 0.10f +
                coverageScore * 0.06f +
                complexityScore * 0.05f
            ).coerceIn(0.76f, 0.97f)
    }

    private fun isUsefulBackgroundColor(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val lum = BitmapUtils.luminance(color)
        return lum > 0.10f && (hsv[1] > 0.12f || lum > 0.55f)
    }

    private fun quantizedColor(index: Int): Int {
        val r = (index shr 6) and 0x7
        val g = (index shr 3) and 0x7
        val b = index and 0x7
        return Color.rgb(r * 32 + 16, g * 32 + 16, b * 32 + 16)
    }

    private fun sampleEdge(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val samples = ArrayList<Int>(128)
        val points = 32

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
}
