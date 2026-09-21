package com.matheus.darkui.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
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

        val result = engine.generate(icon)

        val background = result.bitmap.getPixel(128, 20)
        val foreground = result.bitmap.getPixel(128, 128)

        assertTrue("adaptive background luminance=${BitmapUtils.luminance(background)}", BitmapUtils.luminance(background) < 0.12f)
        assertTrue("adaptive foreground luminance=${BitmapUtils.luminance(foreground)}", BitmapUtils.luminance(foreground) > 0.65f)
        assertTrue(result.method.contains("adaptive"))
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
        val background = result.bitmap.getPixel(128, 24)

        assertTrue("transparent glyph luminance=${BitmapUtils.luminance(glyph)}", BitmapUtils.luminance(glyph) > 0.18f)
        assertTrue("One UI frame luminance=${BitmapUtils.luminance(background)}", BitmapUtils.luminance(background) < 0.12f)
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
