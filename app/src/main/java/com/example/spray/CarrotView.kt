package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** The Buster Carrot: a glowing, bobbing carrot. */
class CarrotView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 28 * d,
            intArrayOf(Color.argb(190, 255, 193, 7), Color.argb(0, 255, 193, 7)),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000.0
        val cx = width / 2f
        val cy = height / 2f + (3 * d * sin(t * 3)).toFloat()
        halo.alpha = (200 + 55 * sin(t * 5)).toInt().coerceIn(0, 255)
        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, 28 * d, halo)
        c.rotate(-25f)
        c.scale(d * 1.2f, d * 1.2f)
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
        c.restore()
        postInvalidateOnAnimation()
    }
}
