package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View
import java.io.File
import kotlin.math.min

/** Red striped obstacle with BETO on it (and Beto's photo if one was chosen). */
class BarrierView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photo: Bitmap? = try {
        BitmapFactory.decodeFile(File(ctx.filesDir, "beto.png").path)
    } catch (e: Exception) {
        null
    }
    var coin = false
    var value = 10
    var solid = false
    var bar = false
    private val src = Rect()
    private val dst = RectF()
    private val clip = Path()

    private fun face(c: Canvas, left: Float, size: Float) {
        val bmp = photo ?: return
        val mid = height / 2f
        clip.reset()
        clip.addCircle(left + size / 2, mid, size / 2, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(left, mid - size / 2, left + size, mid + size / 2)
        c.drawBitmap(bmp, src, dst, null)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.WHITE
        c.drawCircle(left + size / 2, mid, size / 2, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = when {
            solid -> Color.argb(250, 12, 12, 12)
            coin -> Color.argb(235, 255, 160, 0)
            bar -> Color.argb(235, 0, 150, 136)
            value >= 25 -> Color.argb(230, 126, 87, 194)
            else -> Color.argb(215, 211, 47, 47)
        }
        val cr = min(8 * d, h / 3f)
        c.drawRoundRect(0f, 0f, w, h, cr, cr, p)

        if (!solid) {
            c.save()
            c.clipRect(0f, 0f, w, h)
            p.style = Paint.Style.STROKE
            p.strokeWidth = min(8 * d, h * 0.4f)
            p.color = if (coin) Color.argb(120, 255, 255, 255) else Color.argb(110, 255, 235, 59)
            var x = -h
            while (x < w) {
                c.drawLine(x, h, x + h, 0f, p)
                x += 24 * d
            }
            c.restore()
        }

        val ow = min(3 * d, h * 0.2f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = ow
        p.color = if (solid) Color.argb(200, 200, 200, 200) else Color.WHITE
        c.drawRoundRect(ow / 2f, ow / 2f, w - ow / 2f, h - ow / 2f, cr, cr, p)

        var left = 0f
        var right = w
        if (photo != null && !solid && h >= 20 * d && w >= 40 * d) {
            val size = h - 8 * d
            face(c, 4 * d, size)
            left = 8 * d + size
            if (w >= 140 * d) {
                face(c, w - 4 * d - size, size)
                right = w - 8 * d - size
            }
        }
        if (solid || h < 14 * d || w < 26 * d) return
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(15 * d, h * 0.62f)
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        c.drawText(value.toString(), (left + right) / 2, h / 2 + p.textSize * 0.35f, p)
        p.clearShadowLayer()
    }
}
