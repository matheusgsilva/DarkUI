package com.matheus.darkui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BitmapUtils {
    fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val old = Rect(drawable.bounds)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        drawable.bounds = old
        return out
    }

    fun copyScaledInside(source: Bitmap, target: Canvas, rect: RectF, paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)) {
        target.drawBitmap(source, null, rect, paint)
    }

    fun luminance(color: Int): Float {
        fun channel(v: Int): Float {
            val s = v / 255f
            return if (s <= 0.04045f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(Color.red(color)) +
            0.7152f * channel(Color.green(color)) +
            0.0722f * channel(Color.blue(color))
    }

    fun colorDistance(a: Int, b: Int): Float {
        val dr = (Color.red(a) - Color.red(b)).toFloat()
        val dg = (Color.green(a) - Color.green(b)).toFloat()
        val db = (Color.blue(a) - Color.blue(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    fun meanOpaqueColor(colors: List<Int>): Int {
        val usable = colors.filter { Color.alpha(it) > 32 }
        if (usable.isEmpty()) return Color.TRANSPARENT
        var r = 0L; var g = 0L; var b = 0L
        usable.forEach { r += Color.red(it); g += Color.green(it); b += Color.blue(it) }
        return Color.rgb((r / usable.size).toInt(), (g / usable.size).toInt(), (b / usable.size).toInt())
    }

    fun lightenForDarkBackground(color: Int): Int {
        val a = Color.alpha(color)
        if (a == 0) return color
        val lum = luminance(color)
        if (lum >= 0.14f) return color
        val boost = ((0.14f - lum) / 0.14f).coerceIn(0f, 1f)
        val amount = 0.32f + 0.28f * boost
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun roundedBackground(size: Int, color: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val pad = size * 0.02f
        c.drawRoundRect(RectF(pad, pad, size - pad, size - pad), size * 0.235f, size * 0.235f, p)
        return out
    }

    fun clampByte(v: Float): Int = max(0, min(255, v.toInt()))
}
