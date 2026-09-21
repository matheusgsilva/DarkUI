package com.matheus.darkui.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.matheus.darkui.util.BitmapUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkIconEngineTest {
    private val engine = DarkIconEngine(256)
    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun adaptiveIconDarkensBackgroundAndPreservesLightGlyph() {
        val green = Color.rgb(37, 211, 102)
        val icon = AdaptiveIconDrawable(
            ColorDrawable(green),
            CircleGlyphDrawable(Color.WHITE)
        )

        val original = BitmapUtils.drawableToBitmap(icon, 256)
        val result = engine.generate(icon)

        val background = result.bitmap.getPixel(128, 20)
        val foreground = result.bitmap.getPixel(128, 128)

        assertTrue(
            "adaptive background luminance=${BitmapUtils.luminance(background)}",
            BitmapUtils.luminance(background) < 0.16f
        )
        assertTrue(
            "adaptive foreground luminance=${BitmapUtils.luminance(foreground)}",
            BitmapUtils.luminance(foreground) > 0.65f
        )

        for (y in 0 until 256 step 8) {
            for (x in 0 until 256 step 8) {
                val originalVisible = Color.alpha(original.getPixel(x, y)) > 8
                val generatedVisible = Color.alpha(result.bitmap.getPixel(x, y)) > 8
                assertTrue(
                    "adaptive mask/scale changed at x=$x y=$y",
                    originalVisible == generatedVisible
                )
            }
        }
    }

    @Test
    fun adaptiveIconKeepsForegroundPositionAndScale() {
        val icon = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            CircleGlyphDrawable(Color.BLACK)
        )

        val original = BitmapUtils.drawableToBitmap(icon, 256)
        val result = engine.generate(icon).bitmap

        fun visibleBounds(bitmap: Bitmap): IntArray {
            var minX = 256
            var minY = 256
            var maxX = -1
            var maxY = -1
            for (y in 0 until 256) {
                for (x in 0 until 256) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 8) {
                        minX = minOf(minX, x)
                        minY = minOf(minY, y)
                        maxX = maxOf(maxX, x)
                        maxY = maxOf(maxY, y)
                    }
                }
            }
            return intArrayOf(minX, minY, maxX, maxY)
        }

        val before = visibleBounds(original)
        val after = visibleBounds(result)

        assertTrue("left bound changed", before[0] == after[0])
        assertTrue("top bound changed", before[1] == after[1])
        assertTrue("right bound changed", before[2] == after[2])
        assertTrue("bottom bound changed", before[3] == after[3])
    }

    @Test
    fun flatLegacyIconConvertsBackgroundWithoutDestroyingLogo() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 119, 242))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRect(104f, 64f, 152f, 192f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))

        val background = result.bitmap.getPixel(128, 24)
        val logo = result.bitmap.getPixel(128, 128)

        assertTrue("legacy background luminance=${BitmapUtils.luminance(background)}", BitmapUtils.luminance(background) < 0.14f)
        assertTrue("legacy logo luminance=${BitmapUtils.luminance(logo)}", BitmapUtils.luminance(logo) > 0.70f)
    }

    @Test
    fun gameArtworkKeepsCompositionAndOnlyDarkensIt() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(230, 55, 50)
        canvas.drawRect(0f, 0f, 256f, 128f, paint)
        paint.color = Color.rgb(45, 90, 230)
        canvas.drawRect(0f, 128f, 256f, 256f, paint)
        paint.color = Color.rgb(245, 210, 60)
        canvas.drawCircle(128f, 128f, 48f, paint)

        val originalLuminance = averageLuminance(bitmap)
        val result = engine.generate(BitmapDrawable(resources, bitmap), isGame = true)
        val generatedLuminance = averageLuminance(result.bitmap)

        assertTrue("game luminance original=$originalLuminance generated=$generatedLuminance", generatedLuminance < originalLuminance * 0.90f)

        val redArea = result.bitmap.getPixel(64, 64)
        val blueArea = result.bitmap.getPixel(64, 192)

        assertTrue("red area must remain red-dominant", Color.red(redArea) > Color.blue(redArea))
        assertTrue("blue area must remain blue-dominant", Color.blue(blueArea) > Color.red(blueArea))
        assertTrue(result.method.contains("jogo"))
    }

    @Test
    fun transparentDarkGlyphIsLiftedForDarkBackground() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawCircle(128f, 128f, 70f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val glyph = result.bitmap.getPixel(128, 128)
        val outside = result.bitmap.getPixel(128, 24)

        assertTrue(
            "transparent glyph luminance=${BitmapUtils.luminance(glyph)}",
            BitmapUtils.luminance(glyph) > 0.55f
        )
        assertTrue(
            "transparent area must remain transparent",
            Color.alpha(outside) == 0
        )
    }

    @Test
    fun alreadyDarkIconStaysRecognizable() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(16, 18, 24))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(128f, 128f, 58f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))

        assertTrue(BitmapUtils.luminance(result.bitmap.getPixel(128, 128)) > 0.65f)
        assertTrue(BitmapUtils.luminance(result.bitmap.getPixel(128, 24)) < 0.10f)
    }

    @Test
    fun blackGlyphOnLightBackgroundIsLiftedAfterDarkConversion() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRect(112f, 54f, 144f, 202f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val background = result.bitmap.getPixel(42, 42)
        val glyph = result.bitmap.getPixel(128, 128)

        assertTrue(
            "light background must convert to dark; luminance=${BitmapUtils.luminance(background)}",
            BitmapUtils.luminance(background) < 0.14f
        )
        assertTrue(
            "black glyph must be lifted; luminance=${BitmapUtils.luminance(glyph)}",
            BitmapUtils.luminance(glyph) > 0.55f
        )
    }

    @Test
    fun smoothBrandGradientBecomesDarkWithoutDestroyingWhiteLogo() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (y in 0 until 256) {
            val t = y / 255f
            val r = (210 + (110 - 210) * t).toInt()
            val g = (55 + (60 - 55) * t).toInt()
            val b = (180 + (235 - 180) * t).toInt()
            paint.color = Color.rgb(r, g, b)
            canvas.drawRect(0f, y.toFloat(), 256f, y + 1f, paint)
        }

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        canvas.drawCircle(128f, 128f, 62f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(128f, 128f, 13f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val background = result.bitmap.getPixel(40, 40)
        val logo = result.bitmap.getPixel(128, 66)

        assertTrue(
            "gradient background luminance=${BitmapUtils.luminance(background)}",
            BitmapUtils.luminance(background) < 0.16f
        )
        assertTrue(
            "white logo luminance=${BitmapUtils.luminance(logo)}",
            BitmapUtils.luminance(logo) > 0.65f
        )
        assertTrue(result.method.contains("gradiente"))
    }

    @Test
    fun detailedNonGameArtworkIsNotFlattenedIntoBrandBackground() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        val cell = 16
        for (y in 0 until 256 step cell) {
            for (x in 0 until 256 step cell) {
                val even = ((x / cell) + (y / cell)) % 2 == 0
                paint.color = if (even) {
                    Color.rgb(235, 70, 55)
                } else {
                    Color.rgb(40, 105, 230)
                }
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + cell).toFloat(),
                    (y + cell).toFloat(),
                    paint
                )
            }
        }

        val result = engine.generate(BitmapDrawable(resources, bitmap), isGame = false)

        val a = result.bitmap.getPixel(48, 48)
        val b = result.bitmap.getPixel(64, 48)
        assertTrue("detailed artwork should retain distinct regions", BitmapUtils.colorDistance(a, b) > 60f)
        assertTrue("first region must remain red-dominant", Color.red(a) > Color.blue(a))
        assertTrue("second region must remain blue-dominant", Color.blue(b) > Color.red(b))
    }

    @Test
    fun transparentIconKeepsExactSilhouetteAndPosition() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        canvas.drawRect(72f, 44f, 184f, 212f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))

        for (y in 0 until 256 step 8) {
            for (x in 0 until 256 step 8) {
                val originalVisible = Color.alpha(bitmap.getPixel(x, y)) > 8
                val generatedVisible = Color.alpha(result.bitmap.getPixel(x, y)) > 8
                assertTrue(
                    "alpha silhouette changed at x=$x y=$y",
                    originalVisible == generatedVisible
                )
            }
        }
    }

    @Test
    fun mediumDarkGlyphIsInvertedInsteadOfLeftMuddy() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(230, 230, 230))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 55, 55)
        }
        canvas.drawCircle(128f, 128f, 54f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val background = result.bitmap.getPixel(40, 40)
        val glyph = result.bitmap.getPixel(128, 128)

        assertTrue(
            "background should be truly dark; luminance=${BitmapUtils.luminance(background)}",
            BitmapUtils.luminance(background) < 0.10f
        )
        assertTrue(
            "medium dark glyph should be inverted/lightened; luminance=${BitmapUtils.luminance(glyph)}",
            BitmapUtils.luminance(glyph) > 0.45f
        )
    }

    @Test
    fun fullBleedIconKeepsOriginalOpaqueCanvas() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(240, 240, 240))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRect(100f, 70f, 156f, 186f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))

        val points = listOf(
            0 to 0,
            0 to 255,
            255 to 0,
            255 to 255,
            128 to 0,
            0 to 128,
            255 to 128,
            128 to 255
        )

        points.forEach { (x, y) ->
            assertTrue(
                "full-bleed canvas alpha changed at x=$x y=$y",
                Color.alpha(result.bitmap.getPixel(x, y)) == 255
            )
        }
    }

    @Test
    fun maskedBrandIconDarkensSurfaceButKeepsMaskAndWhiteLogo() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(24, 119, 242)
        canvas.drawRoundRect(RectF(28f, 28f, 228f, 228f), 48f, 48f, paint)

        paint.color = Color.WHITE
        canvas.drawRect(116f, 76f, 140f, 188f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "masked background should be dark; luminance=${BitmapUtils.luminance(result.getPixel(70, 70))}",
            BitmapUtils.luminance(result.getPixel(70, 70)) < 0.12f
        )
        assertTrue(
            "white logo should stay bright; luminance=${BitmapUtils.luminance(result.getPixel(128, 128))}",
            BitmapUtils.luminance(result.getPixel(128, 128)) > 0.70f
        )

        for (y in 0 until 256 step 8) {
            for (x in 0 until 256 step 8) {
                val before = Color.alpha(bitmap.getPixel(x, y)) > 8
                val after = Color.alpha(result.getPixel(x, y)) > 8
                assertTrue("masked alpha changed at x=$x y=$y", before == after)
            }
        }
    }

    @Test
    fun maskedLightNeutralBaseBecomesDarkWithoutChangingGeometry() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(240, 240, 240)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.rgb(22, 120, 65)
        canvas.drawCircle(128f, 128f, 56f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "neutral masked base should be dark",
            BitmapUtils.luminance(result.getPixel(64, 64)) < 0.12f
        )
        assertTrue(
            "transparent corner must stay transparent",
            Color.alpha(result.getPixel(4, 4)) == 0
        )
    }

    @Test
    fun alreadyDarkMaskedIconDoesNotWhitenDarkArtwork() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(18, 20, 24)
        canvas.drawRoundRect(RectF(28f, 28f, 228f, 228f), 48f, 48f, paint)

        paint.color = Color.rgb(70, 75, 82)
        canvas.drawCircle(128f, 128f, 54f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        val beforeBackground = bitmap.getPixel(70, 70)
        val afterBackground = result.getPixel(70, 70)
        val beforeGlyph = bitmap.getPixel(128, 128)
        val afterGlyph = result.getPixel(128, 128)

        assertTrue(
            "dark background should remain dark",
            BitmapUtils.luminance(afterBackground) < 0.10f
        )
        assertTrue(
            "dark glyph must not be whitened",
            BitmapUtils.luminance(afterGlyph) < 0.16f
        )
        assertTrue(
            "dark background changed too much",
            BitmapUtils.colorDistance(beforeBackground, afterBackground) < 8f
        )
        assertTrue(
            "dark glyph changed too much",
            BitmapUtils.colorDistance(beforeGlyph, afterGlyph) < 8f
        )
    }

    @Test
    fun darkBackgroundWithBrightLogoKeepsBrightLogoAndDarkBase() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(20, 22, 26)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.WHITE
        canvas.drawRect(116f, 72f, 140f, 188f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "existing dark base should remain dark",
            BitmapUtils.luminance(result.getPixel(64, 64)) < 0.10f
        )
        assertTrue(
            "existing bright logo should stay bright",
            BitmapUtils.luminance(result.getPixel(128, 128)) > 0.70f
        )
    }

    @Test
    fun smallMonochromeGlyphCanInvertAfterLightBackgroundTurnsDark() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 12f
        canvas.drawCircle(128f, 128f, 42f, paint)
        canvas.drawLine(104f, 128f, 152f, 128f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "background should be dark",
            BitmapUtils.luminance(result.getPixel(40, 40)) < 0.12f
        )
        assertTrue(
            "small monochrome glyph should lift",
            BitmapUtils.luminance(result.getPixel(128, 86)) > 0.45f
        )
    }

    @Test
    fun largeDarkArtworkDoesNotWhitenWhenBackgroundTurnsDark() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(235, 235, 235))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 38) }
        canvas.drawCircle(92f, 128f, 48f, paint)
        canvas.drawCircle(164f, 128f, 48f, paint)

        paint.color = Color.WHITE
        paint.strokeWidth = 8f
        canvas.drawLine(72f, 128f, 112f, 128f, paint)
        canvas.drawLine(164f, 108f, 164f, 148f, paint)
        canvas.drawLine(144f, 128f, 184f, 128f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "large dark control should stay dark",
            BitmapUtils.luminance(result.getPixel(92, 100)) < 0.16f
        )
        assertTrue(
            "second large dark control should stay dark",
            BitmapUtils.luminance(result.getPixel(164, 100)) < 0.16f
        )
        assertTrue(
            "white symbol should remain bright",
            BitmapUtils.luminance(result.getPixel(92, 128)) > 0.70f
        )
    }

    @Test
    fun thinDarkGlyphInvertsButSolidDarkDiscDoesNot() {
        val glyphBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val glyphCanvas = Canvas(glyphBitmap)
        glyphCanvas.drawColor(Color.WHITE)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        glyphCanvas.drawCircle(128f, 128f, 52f, glyphPaint)
        glyphCanvas.drawLine(102f, 128f, 154f, 128f, glyphPaint)

        val glyphResult = engine.generate(BitmapDrawable(resources, glyphBitmap)).bitmap
        assertTrue(
            "thin glyph should invert",
            BitmapUtils.luminance(glyphResult.getPixel(128, 76)) > 0.45f
        )

        val solidBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val solidCanvas = Canvas(solidBitmap)
        solidCanvas.drawColor(Color.WHITE)

        val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 34)
            style = Paint.Style.FILL
        }
        solidCanvas.drawCircle(128f, 128f, 58f, solidPaint)

        val solidResult = engine.generate(BitmapDrawable(resources, solidBitmap)).bitmap
        assertTrue(
            "solid dark disc must stay dark",
            BitmapUtils.luminance(solidResult.getPixel(128, 128)) < 0.16f
        )
    }

    @Test
    fun generatedIconsAlwaysUseRequestedOutputSize() {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(40, 140, 220))

        val result = engine.generate(BitmapDrawable(resources, bitmap))

        assertTrue(result.bitmap.width == 256)
        assertTrue(result.bitmap.height == 256)
    }

    private fun averageLuminance(bitmap: Bitmap): Float {
        var total = 0f
        var count = 0

        var y = 8
        while (y < bitmap.height) {
            var x = 8
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) > 32) {
                    total += BitmapUtils.luminance(color)
                    count++
                }
                x += 8
            }
            y += 8
        }

        return if (count == 0) 0f else total / count
    }

    private class CircleGlyphDrawable(
        private val color: Int
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@CircleGlyphDrawable.color }
            val radius = minOf(bounds.width(), bounds.height()) * 0.23f
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
