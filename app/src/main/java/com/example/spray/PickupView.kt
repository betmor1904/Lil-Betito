package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Floating power-up. type 1 = Buster Carrot (breaks the next wall),
 * type 2 = Warp Bomb (screen sides wrap around), type 3 = Sticky Bomb (sticks where you land).
 */
class PickupView(ctx: Context, private val type: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()
    private val glow = when (type) {
        1 -> Color.rgb(255, 193, 7)
        2 -> Color.rgb(171, 71, 255)
        else -> Color.rgb(118, 255, 3)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 28 * d,
            intArrayOf(Color.argb(190, Color.red(glow), Color.green(glow), Color.blue(glow)),
                Color.argb(0, Color.red(glow), Color.green(glow), Color.blue(glow))),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun carrot(c: Canvas) {
        c.rotate(-25f)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FF8F00")
        path.reset()
        path.moveTo(-6f, -9f)
        path.lineTo(6f, -9f)
        path.lineTo(0f, 15f)
        path.close()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 1.2f
        p.color = Color.parseColor("#E65100")
        c.drawLine(-3f, -4f, 1f, -4f, p)
        c.drawLine(-1f, 2f, 3f, 2f, p)
        c.drawLine(-1f, 8f, 1f, 8f, p)
        p.strokeWidth = 2.5f
        p.color = Color.parseColor("#43A047")
        c.drawLine(0f, -9f, -5f, -17f, p)
        c.drawLine(0f, -9f, 0f, -19f, p)
        c.drawLine(0f, -9f, 5f, -17f, p)
    }

    private fun bomb(c: Canvas, body: Int, t: Double) {
        p.style = Paint.Style.FILL
        p.color = body
        c.drawCircle(0f, 2f, 11f, p)
        p.color = Color.argb(90, 255, 255, 255)
        c.drawCircle(-4f, -2f, 3.5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.parseColor("#8D6E63")
        c.drawLine(3f, -8f, 7f, -14f, p)
        p.style = Paint.Style.FILL
        p.color = if (((t * 12).toInt() % 2) == 0) Color.parseColor("#FFEB3B") else Color.parseColor("#FF5722")
        c.drawCircle(7.5f, -15f, 2.6f, p)
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000.0
        val cx = width / 2f
        val cy = height / 2f + (3 * d * sin(t * 3)).toFloat()
        halo.alpha = (200 + 55 * sin(t * 5)).toInt().coerceIn(0, 255)
        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, 28 * d, halo)
        c.scale(d * 1.2f, d * 1.2f)
        when (type) {
            1 -> carrot(c)
            2 -> {
                bomb(c, Color.parseColor("#4A148C"), t)
                // spinning portal swirl
                c.save()
                c.translate(0f, 2f)
                c.rotate((t * 220).toFloat())
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.8f
                p.color = Color.parseColor("#80DEEA")
                rf.set(-7f, -7f, 7f, 7f)
                c.drawArc(rf, 0f, 250f, false, p)
                rf.set(-3.5f, -3.5f, 3.5f, 3.5f)
                c.drawArc(rf, 180f, 250f, false, p)
                c.restore()
            }
            else -> {
                bomb(c, Color.parseColor("#1B5E20"), t)
                // dripping green goo
                p.style = Paint.Style.FILL
                p.color = Color.parseColor("#B2FF59")
                rf.set(-9f, -6f, 9f, 3f)
                c.drawRoundRect(rf, 5f, 5f, p)
                rf.set(-7f, 1f, -4f, 10f)
                c.drawRoundRect(rf, 1.5f, 1.5f, p)
                rf.set(0f, 1f, 3f, 13f)
                c.drawRoundRect(rf, 1.5f, 1.5f, p)
                rf.set(5f, 1f, 8f, 8f)
                c.drawRoundRect(rf, 1.5f, 1.5f, p)
            }
        }
        c.restore()
        postInvalidateOnAnimation()
    }
}
