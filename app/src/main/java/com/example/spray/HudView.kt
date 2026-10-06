package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.max
import kotlin.math.min

class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var goalY = 0f
    var shotsLeft = 3
    var dots = FloatArray(0)
    var message: String? = null
    var banner: String? = null
    var score: String? = null
    var rockets = 3
    var powerLabel: String? = null
    var powerColor = Color.parseColor("#FF9800")
    var combo = 0
    private val path = Path()
    private val neon = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF4081"), Color.parseColor("#00E5FF"),
        Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )

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
        val n = max(3, shotsLeft)
        for (i in 0 until n) {
            p.color = if (i < shotsLeft) Color.parseColor("#FF2D6F") else Color.argb(90, 255, 255, 255)
            c.drawCircle(w / 2 + (i - (n - 1) / 2f) * 26 * d, goalY + 24 * d, 8 * d, p)
        }

        // rocket icons (earned boosters)
        for (i in 0 until 3) {
            val rx = w - 110 * d + i * 30 * d
            val ry = goalY + 24 * d
            p.style = Paint.Style.FILL
            p.color = if (i < rockets) Color.parseColor("#FFD54F") else Color.argb(90, 255, 255, 255)
            c.drawRoundRect(rx - 4 * d, ry - 6 * d, rx + 4 * d, ry + 9 * d, 3 * d, 3 * d, p)
            path.reset()
            path.moveTo(rx - 4 * d, ry - 5 * d)
            path.lineTo(rx, ry - 13 * d)
            path.lineTo(rx + 4 * d, ry - 5 * d)
            path.close()
            c.drawPath(path, p)
            path.reset()
            path.moveTo(rx - 4 * d, ry + 3 * d)
            path.lineTo(rx - 8 * d, ry + 10 * d)
            path.lineTo(rx - 4 * d, ry + 9 * d)
            path.close()
            path.moveTo(rx + 4 * d, ry + 3 * d)
            path.lineTo(rx + 8 * d, ry + 10 * d)
            path.lineTo(rx + 4 * d, ry + 9 * d)
            path.close()
            c.drawPath(path, p)
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
        val pl = powerLabel
        if (pl != null) {
            p.color = powerColor
            fitText(c, "READY - $pl", w / 2, goalY + 102 * d, 13 * d, w * 0.9f)
        }
        p.clearShadowLayer()

        // aim dots
        p.color = Color.WHITE
        var i = 0
        while (i + 1 < dots.size) {
            c.drawCircle(dots[i], dots[i + 1], max(2 * d, 5 * d - (i / 2) * 0.25f * d), p)
            i += 2
        }

        // combo counter
        if (combo >= 2) {
            val txt = "COMBO x$combo"
            val size = (26 + min(combo, 10) * 2.5f) * d
            p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
            p.style = Paint.Style.STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = 6 * d
            p.color = Color.parseColor("#4A148C")
            fitText(c, txt, w / 2, h * 0.30f, size, w * 0.9f)
            p.style = Paint.Style.FILL
            p.color = neon[combo % neon.size]
            fitText(c, txt, w / 2, h * 0.30f, size, w * 0.9f)
            p.typeface = Typeface.DEFAULT_BOLD
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
