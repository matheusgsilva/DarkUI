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
    fun transparentAlreadyDarkGlyphStaysDarkAndTransparent() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawCircle(128f, 128f, 70f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val glyph = result.bitmap.getPixel(128, 128)
        val outside = result.bitmap.getPixel(128, 24)

        assertTrue(
            "already-dark transparent glyph must stay dark",
            BitmapUtils.luminance(glyph) < 0.03f
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

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        canvas.drawCircle(128f, 128f, 44f, paint)
        canvas.drawLine(104f, 128f, 152f, 128f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val background = result.bitmap.getPixel(42, 42)
        val glyph = result.bitmap.getPixel(128, 128)

        assertTrue(
            "light background must convert to dark; luminance=${BitmapUtils.luminance(background)}",
            BitmapUtils.luminance(background) < 0.14f
        )
        assertTrue(
            "thin black glyph must be lifted; luminance=${BitmapUtils.luminance(glyph)}",
            BitmapUtils.luminance(result.bitmap.getPixel(128, 84)) > 0.45f
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
    fun mediumDarkSolidArtworkStaysDarkInsteadOfTurningWhite() {
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
            "solid dark artwork should remain dark; luminance=${BitmapUtils.luminance(glyph)}",
            BitmapUtils.luminance(glyph) < 0.18f
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
    fun calendarLikeIconTurnsWhiteBaseDarkAndLiftsBlackNumber() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.rgb(28, 190, 181)
        canvas.drawRect(24f, 24f, 232f, 92f, paint)

        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 92f
        canvas.drawText("21", 128f, 182f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "calendar base should become dark",
            BitmapUtils.luminance(result.getPixel(72, 150)) < 0.12f
        )
        val calendarGlyphLuminance = averageResultLuminanceForOriginalDarkPixels(
            original = bitmap,
            result = result,
            left = 60,
            top = 96,
            right = 196,
            bottom = 210
        )
        assertTrue(
            "calendar number should be lifted; average=$calendarGlyphLuminance",
            calendarGlyphLuminance > 0.35f
        )
        assertTrue(
            "calendar header should keep its hue identity",
            Color.green(result.getPixel(128, 60)) > Color.red(result.getPixel(128, 60))
        )
    }

    @Test
    fun chatGptLikeCentralBlackMarkBecomesReadableOnDarkBase() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 12f
        repeat(6) { i ->
            val angle = Math.toRadians((i * 60.0))
            val cx = 128f + (24f * kotlin.math.cos(angle)).toFloat()
            val cy = 128f + (24f * kotlin.math.sin(angle)).toFloat()
            canvas.drawCircle(cx, cy, 34f, paint)
        }

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "ChatGPT-like base should become dark",
            BitmapUtils.luminance(result.getPixel(54, 54)) < 0.12f
        )
        val chatGptMarkLuminance = averageResultLuminanceForOriginalDarkPixels(
            original = bitmap,
            result = result,
            left = 60,
            top = 60,
            right = 196,
            bottom = 196
        )
        assertTrue(
            "ChatGPT-like central mark should stay readable; average=$chatGptMarkLuminance",
            chatGptMarkLuminance > 0.35f
        )
    }

    @Test
    fun warmPinkRedGradientStaysSaturatedWhenDarkened() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        for (x in 0 until 256) {
            val t = x / 255f
            val r = 255
            val g = (35 + 80 * t).toInt()
            val b = (145 - 90 * t).toInt().coerceAtLeast(20)
            paint.color = Color.rgb(r, g, b)
            canvas.drawRect(x.toFloat(), 0f, x + 1f, 256f, paint)
        }

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
        val sample = result.getPixel(96, 128)
        val hsv = FloatArray(3)
        Color.colorToHSV(sample, hsv)

        assertTrue(
            "warm gradient should be dark",
            hsv[2] < 0.22f
        )
        assertTrue(
            "warm gradient should keep saturation and not turn muddy",
            hsv[1] > 0.50f
        )
        assertTrue(
            "warm gradient should remain red/pink-family",
            hsv[0] < 40f || hsv[0] >= 300f
        )
    }

    @Test
    fun chromeLikeMulticolorLogoKeepsBrandColorsOnDarkBase() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.RED
        canvas.drawRect(76f, 82f, 128f, 128f, paint)
        paint.color = Color.rgb(255, 205, 0)
        canvas.drawRect(128f, 82f, 180f, 128f, paint)
        paint.color = Color.rgb(20, 150, 70)
        canvas.drawRect(76f, 128f, 128f, 174f, paint)
        paint.color = Color.rgb(45, 110, 230)
        canvas.drawCircle(128f, 128f, 28f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue("white base should become dark", BitmapUtils.luminance(result.getPixel(54, 54)) < 0.12f)
        val red = result.getPixel(90, 95)
        val yellow = result.getPixel(165, 95)
        val green = result.getPixel(90, 160)
        val blue = result.getPixel(128, 128)
        assertTrue("red identity lost", Color.red(red) > Color.green(red))
        assertTrue("yellow identity lost", Color.red(yellow) > Color.blue(yellow) && Color.green(yellow) > Color.blue(yellow))
        assertTrue("green identity lost", Color.green(green) > Color.red(green))
        assertTrue("blue identity lost", Color.blue(blue) > Color.red(blue))
    }

    @Test
    fun redBankLikeIconKeepsRedHueAndWhiteLogo() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(225, 35, 45)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(128f, 128f, 42f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
        val bg = result.getPixel(60, 60)
        val hsv = FloatArray(3)
        Color.colorToHSV(bg, hsv)

        assertTrue("red background should become dark", hsv[2] < 0.22f)
        assertTrue("red hue should remain red", hsv[0] < 30f || hsv[0] > 330f)
        assertTrue("red saturation should remain strong", hsv[1] > 0.50f)
        assertTrue("white logo should stay bright", BitmapUtils.luminance(result.getPixel(128, 128)) > 0.70f)
    }

    @Test
    fun yellowBankLikeIconDoesNotTurnBrownOrGray() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(250, 215, 20)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)
        paint.color = Color.rgb(20, 65, 170)
        canvas.drawRect(92f, 102f, 164f, 154f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
        val bg = result.getPixel(60, 60)
        val hsv = FloatArray(3)
        Color.colorToHSV(bg, hsv)

        assertTrue("yellow background should become dark", hsv[2] < 0.22f)
        assertTrue("yellow should keep saturation", hsv[1] > 0.45f)
        assertTrue("yellow hue should remain yellow/gold", hsv[0] in 35f..75f)
        val logo = result.getPixel(128, 128)
        assertTrue("blue bank logo should remain blue", Color.blue(logo) > Color.red(logo))
    }

    @Test
    fun purplePinkGradientKeepsHueSeparationWithoutMud() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        for (x in 0 until 256) {
            val t = x / 255f
            val r = (150 + 100 * t).toInt()
            val g = (40 + 5 * t).toInt()
            val b = (220 - 80 * t).toInt()
            paint.color = Color.rgb(r, g, b)
            canvas.drawRect(x.toFloat(), 0f, x + 1f, 256f, paint)
        }

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
        val left = result.getPixel(40, 128)
        val right = result.getPixel(216, 128)
        val leftHsv = FloatArray(3)
        val rightHsv = FloatArray(3)
        Color.colorToHSV(left, leftHsv)
        Color.colorToHSV(right, rightHsv)

        assertTrue("purple side lost saturation", leftHsv[1] > 0.45f)
        assertTrue("pink side lost saturation", rightHsv[1] > 0.45f)
        assertTrue("gradient hue separation collapsed", kotlin.math.abs(leftHsv[0] - rightHsv[0]) > 10f)
    }

    @Test
    fun cameraLikeMetallicBodyKeepsDarkLensAndBrightHousing() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(225, 228, 232)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(64f, 78f, 192f, 178f), 24f, 24f, paint)
        paint.color = Color.rgb(28, 30, 34)
        canvas.drawCircle(128f, 128f, 42f, paint)
        paint.color = Color.rgb(70, 105, 185)
        canvas.drawCircle(128f, 128f, 20f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue("metallic outer surface should darken", BitmapUtils.luminance(result.getPixel(50, 50)) < 0.18f)
        assertTrue("camera lens should remain dark", BitmapUtils.luminance(result.getPixel(128, 100)) < 0.20f)
        assertTrue("blue lens detail should remain blue", Color.blue(result.getPixel(128, 128)) > Color.red(result.getPixel(128, 128)))
    }

    @Test
    fun twoToneSamsungLikeIconPreservesSecondaryBrightColor() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(35, 155, 220)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)
        paint.color = Color.WHITE
        canvas.drawRect(74f, 72f, 102f, 188f, paint)
        paint.color = Color.rgb(85, 235, 155)
        canvas.drawCircle(88f, 128f, 16f, paint)
        paint.color = Color.rgb(245, 80, 210)
        canvas.drawCircle(160f, 170f, 17f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue("blue base should darken", BitmapUtils.luminance(result.getPixel(60, 60)) < 0.16f)
        assertTrue("white control should remain bright", BitmapUtils.luminance(result.getPixel(88, 90)) > 0.70f)
        val green = result.getPixel(88, 128)
        val pink = result.getPixel(160, 170)
        assertTrue("green accent lost", Color.green(green) > Color.red(green))
        assertTrue("pink accent lost", Color.red(pink) > Color.green(pink) && Color.blue(pink) > Color.green(pink))
    }

    @Test
    fun alreadyDarkColoredBrandIconIsNearlyPixelStable() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(12, 18, 38)
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)
        paint.color = Color.rgb(38, 95, 205)
        canvas.drawRect(102f, 72f, 154f, 184f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "already-dark background changed too much",
            BitmapUtils.colorDistance(bitmap.getPixel(60, 60), result.getPixel(60, 60)) < 8f
        )
        assertTrue(
            "already-dark colored logo changed too much",
            BitmapUtils.colorDistance(bitmap.getPixel(128, 128), result.getPixel(128, 128)) < 8f
        )
    }

    @Test
    fun detailedPhotoLikeArtworkUsesPreservationInsteadOfFlatRecolor() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        for (y in 0 until 256 step 8) {
            for (x in 0 until 256 step 8) {
                paint.color = Color.rgb(
                    (40 + x * 3 / 4).coerceAtMost(235),
                    (35 + y * 2 / 3).coerceAtMost(220),
                    (55 + (x + y) / 3).coerceAtMost(235)
                )
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
            }
        }

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
        assertTrue(
            "photo-like artwork should retain local color differences",
            BitmapUtils.colorDistance(result.getPixel(48, 48), result.getPixel(200, 200)) > 60f
        )
    }

    @Test
    fun saturatedBrandPaletteKeepsHueAcrossDarkConversion() {
        val brandColors = listOf(
            Color.rgb(235, 45, 55),
            Color.rgb(245, 115, 25),
            Color.rgb(245, 205, 25),
            Color.rgb(35, 180, 85),
            Color.rgb(25, 185, 190),
            Color.rgb(45, 115, 235),
            Color.rgb(125, 65, 220),
            Color.rgb(235, 45, 155)
        )

        brandColors.forEach { originalColor ->
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.color = originalColor
            canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(128f, 128f, 34f, paint)

            val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
            val converted = result.getPixel(60, 60)

            val before = FloatArray(3)
            val after = FloatArray(3)
            Color.colorToHSV(originalColor, before)
            Color.colorToHSV(converted, after)

            val hueDeltaRaw = kotlin.math.abs(before[0] - after[0])
            val hueDelta = minOf(hueDeltaRaw, 360f - hueDeltaRaw)

            assertTrue(
                "brand hue drifted too much: before=${before[0]} after=${after[0]}",
                hueDelta < 12f
            )
            assertTrue(
                "brand saturation collapsed: before=${before[1]} after=${after[1]}",
                after[1] > 0.42f
            )
            assertTrue(
                "brand background did not become dark: value=${after[2]}",
                after[2] < 0.22f
            )
            assertTrue(
                "white logo lost brightness",
                BitmapUtils.luminance(result.getPixel(128, 128)) > 0.70f
            )
        }
    }

    @Test
    fun instagramLikeMaskedGradientBecomesTrueDarkBaseWithWhiteGlyph() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val clip = android.graphics.Path().apply {
            addRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)

        for (x in 24 until 233) {
            val t = (x - 24) / 208f
            paint.color = Color.rgb(
                245,
                (35 + 85 * t).toInt(),
                (205 - 135 * t).toInt().coerceAtLeast(55)
            )
            canvas.drawRect(x.toFloat(), 24f, x + 1f, 233f, paint)
        }

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 14f
        canvas.drawRoundRect(RectF(72f, 72f, 184f, 184f), 30f, 30f, paint)
        canvas.drawCircle(128f, 128f, 26f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(164f, 92f, 7f, paint)
        canvas.restore()

        val result = engine.generate(BitmapDrawable(resources, bitmap))
        val out = result.bitmap

        assertTrue(
            "Instagram-like icon should use glyph-preserved mode: ${result.method}",
            result.method.contains("glyph preservado")
        )

        val leftBackground = out.getPixel(52, 128)
        val rightBackground = out.getPixel(204, 128)
        assertTrue(
            "left gradient background should become truly dark",
            BitmapUtils.luminance(leftBackground) < 0.08f
        )
        assertTrue(
            "right gradient background should become truly dark",
            BitmapUtils.luminance(rightBackground) < 0.08f
        )
        assertTrue(
            "dark enclosure should no longer retain a muddy gradient",
            BitmapUtils.colorDistance(leftBackground, rightBackground) < 24f
        )
        assertTrue(
            "white camera glyph should remain bright",
            BitmapUtils.luminance(out.getPixel(128, 72)) > 0.70f
        )
        assertTrue(
            "transparent outside corner must remain transparent",
            Color.alpha(out.getPixel(4, 4)) == 0
        )
    }

    @Test
    fun kodiLikeWhiteBaseKeepsColoredGlyphOnDarkEnclosure() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.rgb(45, 170, 225)
        val glyph = android.graphics.Path().apply {
            moveTo(128f, 64f)
            lineTo(190f, 128f)
            lineTo(128f, 192f)
            lineTo(66f, 128f)
            close()
        }
        canvas.drawPath(glyph, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap

        assertTrue(
            "white enclosure should become dark",
            BitmapUtils.luminance(result.getPixel(52, 52)) < 0.12f
        )
        val center = result.getPixel(128, 128)
        assertTrue(
            "blue glyph must remain blue",
            Color.blue(center) > Color.red(center) && Color.blue(center) > Color.green(center)
        )
        assertTrue(
            "colored glyph should not be unnecessarily dimmed",
            BitmapUtils.luminance(center) > 0.20f
        )
    }

    @Test
    fun kAccessLikeWhiteBaseKeepsGreenLineGlyph() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(24f, 24f, 232f, 232f), 52f, 52f, paint)

        paint.color = Color.rgb(110, 195, 55)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(82f, 76f, 82f, 178f, paint)
        canvas.drawLine(104f, 142f, 136f, 174f, paint)
        canvas.drawLine(136f, 174f, 176f, 92f, paint)

        val result = engine.generate(BitmapDrawable(resources, bitmap)).bitmap
        val background = result.getPixel(52, 52)
        val green = result.getPixel(82, 128)

        assertTrue(
            "white base should become dark",
            BitmapUtils.luminance(background) < 0.12f
        )
        assertTrue(
            "green glyph identity should be preserved",
            Color.green(green) > Color.red(green) && Color.green(green) > Color.blue(green)
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

    private fun averageResultLuminanceForOriginalDarkPixels(
        original: Bitmap,
        result: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float {
        var total = 0f
        var count = 0

        for (y in top until bottom) {
            for (x in left until right) {
                val sourceColor = original.getPixel(x, y)
                if (
                    Color.alpha(sourceColor) > 32 &&
                    BitmapUtils.luminance(sourceColor) < 0.10f
                ) {
                    total += BitmapUtils.luminance(result.getPixel(x, y))
                    count++
                }
            }
        }

        return if (count == 0) 0f else total / count
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
