package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class SkaterView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    var facing = 1
    var grinding = false
    private var trick: String? = null
    private var t0 = 0L
    private val dur = mapOf("ollie" to 650f, "kickflip" to 700f, "shove" to 700f, "tre" to 750f)

    fun startTrick(name: String) {
        trick = name; t0 = SystemClock.uptimeMillis(); invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        var flip = 0f; var shove = 0f; var tilt = 0f; var crouch = 0f
        val t = trick
        if (t != null) {
            val pr = (SystemClock.uptimeMillis() - t0) / dur.getValue(t)
            if (pr >= 1f) trick = null else {
                tilt = sin(2 * PI * pr).toFloat() * 15f
                crouch = max(0f, 1f - pr * 4f)
                if (t == "kickflip" || t == "tre") flip = (2 * PI * pr).toFloat()
                if (t == "shove") shove = (PI * pr).toFloat()
                if (t == "tre") shove = (2 * PI * pr).toFloat()
            }
        }

        // board
        val cx = w / 2; val cy = h * 0.86f
        val cf = cos(flip)
        val len = max(0.1f, abs(cos(shove))) * 90 * d
        val thick = max(0.15f, abs(cf)) * 7 * d
        c.save()
        c.rotate(tilt * facing, cx, cy)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        for (k in floatArrayOf(-0.3f, 0.3f)) c.drawCircle(cx + len * k, cy + 5.5f * d * cf, 4.5f * d, p)
        p.color = if (cf >= 0) Color.parseColor("#232323") else Color.parseColor("#FF2D6F")
        c.drawRoundRect(cx - len / 2, cy - thick / 2, cx + len / 2, cy + thick / 2, thick / 2, thick / 2, p)
        c.restore()

        // rider
        val feetY = cy - 3.5f * d
        val leg = 34 * d * (1 - 0.35f * crouch)
        val lean = facing * (4 + if (grinding) 6 else 0) * d
        val hipX = cx + lean * 0.5f; val hipY = feetY - leg
        val shX = cx + lean; val shY = hipY - 30 * d
        p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = 5 * d
        for (fx in floatArrayOf(-15f, 15f)) c.drawLine(cx + fx * d, feetY, hipX, hipY, p)
        c.drawLine(hipX, hipY, shX, shY, p)
        c.drawLine(shX, shY, shX - 24 * d, shY + 10 * d, p)
        c.drawLine(shX, shY, shX + 24 * d, shY + 10 * d, p)
        p.style = Paint.Style.FILL
        val hx = shX + lean * 0.2f; val hy = shY - 11 * d
        c.drawCircle(hx, hy, 9 * d, p)
        p.color = Color.parseColor("#FF2D6F")
        c.drawRect(hx - 9 * d, hy - 11 * d, hx + 9 * d, hy - 4 * d, p)

        // grind sparks
        if (grinding) {
            p.color = Color.parseColor("#FFB300")
            repeat(6) {
                c.drawCircle(cx + Random.nextFloat() * 60 * d - 30 * d, h * 0.95f - Random.nextFloat() * 8 * d,
                    Random.nextFloat() * 2 * d + d, p)
            }
        }
        if (trick != null || grinding) postInvalidateOnAnimation()
    }
}
