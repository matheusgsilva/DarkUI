package com.matheus.darkui.engine

import android.graphics.Bitmap
import android.graphics.Color
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
        const val ENGINE_VERSION = 11

                private const val DARK_NEUTRAL = 0xFF111113.toInt()

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
        // Always flatten the exact launcher drawable first. This preserves the
        // launcher-provided mask, alpha silhouette, scale and positioning. The
        // renderer is allowed to change colors only, never geometry.
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
                bitmap = recolored,
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
                bitmap = recolored,
                method = "Dark automático • gradiente convertido",
                confidence = 0.92f
            )
        }

        if (analysis.edgeOpaqueRatio < 0.45f) {
            val masked = recolorMaskedIcon(source, analysis)
            return SmartIconResult(
                bitmap = masked,
                method = "Dark automático • máscara preservada",
                confidence = 0.96f
            )
        }

        if (
            analysis.dominantCanvasCoverage >= 0.28f &&
            isUsefulBackgroundColor(analysis.dominantColor) &&
            analysis.detail <= 0.38f
        ) {
            val recolored = recolorDominantRegion(source, analysis.dominantColor)
            return SmartIconResult(
                bitmap = recolored,
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

    private fun recolorMaskedIcon(source: Bitmap, analysis: IconAnalysis): Bitmap {
        val boundary = sampleOpaqueInnerBoundary(source)
        val opaqueBoundary = boundary.filter { Color.alpha(it) > 160 }

        if (opaqueBoundary.isNotEmpty()) {
            val boundaryMean = BitmapUtils.meanOpaqueColor(opaqueBoundary)
            val uniformity = opaqueBoundary
                .map { BitmapUtils.colorDistance(it, boundaryMean) }
                .average()
                .toFloat()

            if (uniformity <= 62f && isUsefulBackgroundColor(boundaryMean)) {
                return recolorBackgroundLikePixels(source, boundaryMean)
            }
        }

        if (
            analysis.dominantCanvasCoverage >= 0.16f &&
            isUsefulBackgroundColor(analysis.dominantColor) &&
            analysis.detail <= 0.42f
        ) {
            return recolorDominantRegion(source, analysis.dominantColor)
        }

        return darkenMaskedSurface(source)
    }

    private fun sampleOpaqueInnerBoundary(bitmap: Bitmap): List<Int> {
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1

        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 160) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }

        if (maxX < minX || maxY < minY) return emptyList()

        val width = (maxX - minX + 1).coerceAtLeast(1)
        val height = (maxY - minY + 1).coerceAtLeast(1)
        val insetX = (width * 0.10f).roundToInt().coerceAtLeast(1)
        val insetY = (height * 0.10f).roundToInt().coerceAtLeast(1)

        val left = (minX + insetX).coerceIn(0, bitmap.width - 1)
        val right = (maxX - insetX).coerceIn(0, bitmap.width - 1)
        val top = (minY + insetY).coerceIn(0, bitmap.height - 1)
        val bottom = (maxY - insetY).coerceIn(0, bitmap.height - 1)

        val samples = ArrayList<Int>(128)
        val points = 32
        repeat(points) { i ->
            val tx = if (points <= 1) 0f else i / (points - 1f)
            val x = (left + (right - left) * tx).roundToInt()
            val y = (top + (bottom - top) * tx).roundToInt()

            listOf(
                bitmap.getPixel(x, top),
                bitmap.getPixel(x, bottom),
                bitmap.getPixel(left, y),
                bitmap.getPixel(right, y)
            ).forEach { color ->
                if (Color.alpha(color) > 160) samples += color
            }
        }
        return samples
    }

    private fun darkenMaskedSurface(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        val hsv = FloatArray(3)

        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = Color.alpha(c)
            if (alpha < 8) continue

            val lum = BitmapUtils.luminance(c)
            Color.colorToHSV(c, hsv)

            pixels[i] = when {
                // Keep bright neutral logo strokes/details bright.
                hsv[1] < 0.14f && lum > 0.72f -> c

                // Dark monochrome strokes should invert instead of becoming muddy.
                lum < 0.20f -> improveForegroundPixel(c)

                // Colored surface: preserve hue, push it decisively into Dark.
                hsv[1] >= 0.18f -> {
                    hsv[1] = (hsv[1] * 0.98f).coerceIn(0f, 1f)
                    hsv[2] = minOf(hsv[2], 0.18f)
                    Color.HSVToColor(alpha, hsv)
                }

                // Light neutral surface/background.
                else -> blend(c, DARK_NEUTRAL, 0.90f)
            }
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

        hsv[1] = (hsv[1] * 0.96f + 0.02f).coerceIn(0.22f, 1f)
        hsv[2] = 0.13f
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
            bitmap = dimArtwork(source, actualFactor),
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

            pixels[i] = improveForegroundPixel(c)
        }

        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun improveForegroundPixel(color: Int): Int {
        val lum = BitmapUtils.luminance(color)
        return when {
            // iOS-like dark treatment: when a dark monochrome glyph would disappear
            // on the generated dark background, invert its visual role while keeping
            // the exact same pixels, alpha, position and geometry.
            lum < 0.07f -> mixWithWhite(color, 0.86f)
            lum < 0.14f -> mixWithWhite(color, 0.68f)
            lum < 0.22f -> mixWithWhite(color, 0.42f)
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
