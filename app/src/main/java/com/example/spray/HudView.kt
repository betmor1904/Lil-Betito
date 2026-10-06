package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.max

class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var goalY = 0f
    var shotsLeft = 3
    var dots = FloatArray(0)
    var message: String? = null
    var banner: String? = null
    var score: String? = null

    private fun fitText(c: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // goal line
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.parseColor("#FFEB3B")
        p.pathEffect = DashPathEffect(floatArrayOf(18 * d, 10 * d), 0f)
        c.drawLine(0f, goalY, w, goalY, p)
        p.pathEffect = null

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 14 * d
        c.drawText("GOAL", w / 2, goalY - 8 * d, p)

        // shots left
        for (i in 0 until 3) {
            p.color = if (i < shotsLeft) Color.parseColor("#FF2D6F") else Color.argb(90, 255, 255, 255)
            c.drawCircle(w / 2 + (i - 1) * 26 * d, goalY + 24 * d, 8 * d, p)
        }

        // banner + score
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        val b = banner
        if (b != null) {
            p.color = Color.WHITE
            fitText(c, b, w / 2, goalY + 58 * d, 15 * d, w * 0.9f)
        }
        val s = score
        if (s != null) {
            p.color = Color.parseColor("#FFEB3B")
            fitText(c, s, w / 2, goalY + 80 * d, 14 * d, w * 0.9f)
        }
        p.clearShadowLayer()

        // aim dots
        p.color = Color.WHITE
        var i = 0
        while (i + 1 < dots.size) {
            c.drawCircle(dots[i], dots[i + 1], max(2 * d, 5 * d - (i / 2) * 0.25f * d), p)
            i += 2
        }

        // big message
        val m = message
        if (m != null) {
            p.color = Color.WHITE
            p.setShadowLayer(8f, 3f, 3f, Color.BLACK)
            fitText(c, m, w / 2, h * 0.4f, 34 * d, w * 0.92f)
            p.clearShadowLayer()
        }
    }
}
