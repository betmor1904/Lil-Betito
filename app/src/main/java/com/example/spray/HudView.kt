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

        // aim dots
        p.color = Color.WHITE
        var i = 0
        while (i + 1 < dots.size) {
            c.drawCircle(dots[i], dots[i + 1], max(2 * d, 5 * d - (i / 2) * 0.25f * d), p)
            i += 2
        }

        // message
        val m = message
        if (m != null) {
            p.color = Color.WHITE
            p.textSize = 34 * d
            p.setShadowLayer(8f, 3f, 3f, Color.BLACK)
            c.drawText(m, w / 2, h * 0.4f, p)
            p.clearShadowLayer()
        }
    }
}
