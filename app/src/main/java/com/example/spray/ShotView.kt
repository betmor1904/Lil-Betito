package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View

class ShotView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    var angle = 0f
    var tucked = false   // true while flying: turtle hides in its shell

    private val shellDark = Color.parseColor("#2E7D32")
    private val shellLight = Color.parseColor("#66BB6A")
    private val skin = Color.parseColor("#9CCC65")
    private val skinDark = Color.parseColor("#7CB342")

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.save()
        c.rotate(angle, cx, cy)
        p.style = Paint.Style.FILL

        if (!tucked) {
            // legs
            p.color = skinDark
            c.drawRoundRect(cx - 16 * d, cy + 4 * d, cx - 7 * d, cy + 17 * d, 4 * d, 4 * d, p)
            c.drawRoundRect(cx + 7 * d, cy + 4 * d, cx + 16 * d, cy + 17 * d, 4 * d, 4 * d, p)
            // tail
            val tail = Path()
            tail.moveTo(cx - 20 * d, cy + 2 * d)
            tail.lineTo(cx - 30 * d, cy + 6 * d)
            tail.lineTo(cx - 20 * d, cy + 8 * d)
            tail.close()
            c.drawPath(tail, p)
            // head
            p.color = skin
            c.drawCircle(cx + 24 * d, cy - 3 * d, 9 * d, p)
            p.color = Color.WHITE
            c.drawCircle(cx + 27 * d, cy - 6 * d, 3.2f * d, p)
            p.color = Color.BLACK
            c.drawCircle(cx + 28 * d, cy - 6 * d, 1.6f * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.strokeCap = Paint.Cap.ROUND
            c.drawArc(cx + 20 * d, cy - 4 * d, cx + 30 * d, cy + 5 * d, 20f, 90f, false, p)
            p.style = Paint.Style.FILL
            // belly
            p.color = Color.parseColor("#D7CCC8")
            c.drawRoundRect(cx - 22 * d, cy + 1 * d, cx + 22 * d, cy + 8 * d, 3 * d, 3 * d, p)
            // shell dome
            p.color = shellDark
            oval.set(cx - 22 * d, cy - 20 * d, cx + 22 * d, cy + 20 * d)
            c.drawArc(oval, 180f, 180f, true, p)
            p.color = shellLight
            c.drawCircle(cx, cy - 9 * d, 6 * d, p)
            c.drawCircle(cx - 12 * d, cy - 4 * d, 4 * d, p)
            c.drawCircle(cx + 12 * d, cy - 4 * d, 4 * d, p)
        } else {
            // tucked in: round shell only
            p.color = shellDark
            c.drawCircle(cx, cy, 21 * d, p)
            p.color = shellLight
            c.drawCircle(cx, cy, 7 * d, p)
            for (k in 0 until 6) {
                val a = Math.toRadians(k * 60.0 + 30.0)
                c.drawCircle(cx + (14 * d * Math.cos(a)).toFloat(), cy + (14 * d * Math.sin(a)).toFloat(), 3.5f * d, p)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.parseColor("#1B5E20")
            c.drawCircle(cx, cy, 21 * d, p)
            p.style = Paint.Style.FILL
        }
        c.restore()
    }
}
