package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws the room: ceiling with the exit gap, the rising floor, level, nudges, combo and messages. */
class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    var floorY = 0f
    var ceilY = 0f
    var ceilThick = 0f
    var gapL = 0f
    var gapR = 0f
    var level = 1
    var best = 0
    var score = 0
    var worth = 0
    var nudgesLeft = 3
    var dots = FloatArray(0)
    var message: String? = null
    var banner: String? = null
    var combo = 0
    private val neon = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF4081"), Color.parseColor("#00E5FF"),
        Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        grid.strokeWidth = 1.5f * d
        grid.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(56, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun fitText(c: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL

        // dark background + grid lines that ride up with the floor so you can feel the motion
        c.drawColor(Color.argb(225, 10, 14, 36))
        var gy = floorY - 100 * d
        while (gy > ceilY) {
            c.drawLine(0f, gy, w, gy, grid)
            gy -= 100 * d
        }

        // rising floor: red and dangerous
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#E64A19")
        c.drawRect(0f, floorY, w, h, p)
        p.color = Color.parseColor("#FFEB3B")
        c.drawRect(0f, floorY, w, floorY + 6 * d, p)

        // ceiling: bright neon bands with the exit gap
        val top = ceilY - ceilThick
        p.color = Color.parseColor("#E0F7FA")
        c.drawRect(0f, top, gapL, ceilY, p)
        c.drawRect(gapR, top, w, ceilY, p)
        p.color = Color.parseColor("#00E5FF")
        c.drawRect(0f, ceilY - 4 * d, gapL, ceilY, p)
        c.drawRect(gapR, ceilY - 4 * d, w, ceilY, p)

        // pulsing green glow under the exit
        val pulse = 0.6f + 0.4f * sin(SystemClock.uptimeMillis() / 220.0).toFloat()
        for (i in 0 until 4) {
            p.color = Color.argb(((90 * pulse).toInt() - i * 18).coerceIn(0, 255), 118, 255, 3)
            c.drawRect(gapL, ceilY, gapR, ceilY + (i + 1) * 16 * d, p)
        }

        // exit arrow above the gap
        val mid = (gapL + gapR) / 2f
        p.color = Color.parseColor("#76FF03")
        path.reset()
        path.moveTo(mid, top - 26 * d)
        path.lineTo(mid - 14 * d, top - 6 * d)
        path.lineTo(mid + 14 * d, top - 6 * d)
        path.close()
        c.drawPath(path, p)

        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT_BOLD
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)

        // level, score, best, what this escape is worth right now
        p.color = Color.WHITE
        p.textSize = 20 * d
        c.drawText("LEVEL $level", 14 * d, ceilY + 28 * d, p)
        p.color = Color.parseColor("#FFEB3B")
        p.textSize = 14 * d
        c.drawText("SCORE $score", 14 * d, ceilY + 47 * d, p)
        p.textSize = 11 * d
        c.drawText("BEST $best", 14 * d, ceilY + 63 * d, p)
        p.color = Color.parseColor("#76FF03")
        p.textSize = 14 * d
        c.drawText("WORTH $worth", 14 * d, ceilY + 82 * d, p)
        p.clearShadowLayer()

        // nudges left
        for (i in 0 until 3) {
            p.color = if (i < nudgesLeft) Color.parseColor("#E040FB") else Color.argb(90, 255, 255, 255)
            c.drawCircle(24 * d + i * 30 * d, ceilY + 106 * d, 10 * d, p)
        }
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        p.color = Color.WHITE
        p.textSize = 11 * d
        c.drawText("NUDGES $nudgesLeft", 12 * d, ceilY + 134 * d, p)

        // banner
        p.textAlign = Paint.Align.CENTER
        val b = banner
        if (b != null) {
            p.color = Color.WHITE
            fitText(c, b, w / 2, ceilY + 156 * d, 15 * d, w * 0.9f)
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
