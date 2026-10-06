package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.random.Random

/** Lil Betito: a turtle with rocket boosters. Faces right; mirror flips it to face left. */
class ShotView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val path = Path()
    var angle = 0f        // tilt in degrees (positive = nose down)
    var boost = false     // true while the boosters are firing
    var mirror = false    // true = facing left

    private val shellDark = Color.parseColor("#2E7D32")
    private val shellLight = Color.parseColor("#66BB6A")
    private val skin = Color.parseColor("#9CCC65")
    private val skinDark = Color.parseColor("#7CB342")

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.save()
        if (mirror) c.scale(-1f, 1f, cx, cy)
        c.rotate(angle, cx, cy)
        p.style = Paint.Style.FILL

        // rocket boosters (strapped to the back of the shell)
        for (yOff in floatArrayOf(-13f, -5f)) {
            val y = cy + yOff * d
            if (boost) {
                val len = (8 + Random.nextFloat() * 8) * d
                p.color = Color.parseColor("#FF9800")
                path.reset()
                path.moveTo(cx - 37 * d, y - 4 * d)
                path.lineTo(cx - 37 * d - len, y)
                path.lineTo(cx - 37 * d, y + 4 * d)
                path.close()
                c.drawPath(path, p)
                p.color = Color.parseColor("#FFEB3B")
                path.reset()
                path.moveTo(cx - 37 * d, y - 2 * d)
                path.lineTo(cx - 37 * d - len * 0.6f, y)
                path.lineTo(cx - 37 * d, y + 2 * d)
                path.close()
                c.drawPath(path, p)
            }
            p.color = Color.parseColor("#455A64")
            c.drawRect(cx - 37 * d, y - 3 * d, cx - 34 * d, y + 3 * d, p)
            p.color = Color.parseColor("#B0BEC5")
            c.drawRoundRect(cx - 34 * d, y - 4 * d, cx - 12 * d, y + 4 * d, 3 * d, 3 * d, p)
            p.color = Color.parseColor("#E53935")
            c.drawRect(cx - 26 * d, y - 4 * d, cx - 22 * d, y + 4 * d, p)
        }

        if (!boost) {
            // legs
            p.color = skinDark
            c.drawRoundRect(cx - 16 * d, cy + 4 * d, cx - 7 * d, cy + 17 * d, 4 * d, 4 * d, p)
            c.drawRoundRect(cx + 7 * d, cy + 4 * d, cx + 16 * d, cy + 17 * d, 4 * d, 4 * d, p)
            // tail
            path.reset()
            path.moveTo(cx - 20 * d, cy + 4 * d)
            path.lineTo(cx - 28 * d, cy + 9 * d)
            path.lineTo(cx - 20 * d, cy + 10 * d)
            path.close()
            c.drawPath(path, p)
        }

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

        // shell
        p.color = shellDark
        oval.set(cx - 22 * d, cy - 20 * d, cx + 22 * d, cy + 20 * d)
        c.drawArc(oval, 180f, 180f, true, p)
        p.color = shellLight
        c.drawCircle(cx, cy - 9 * d, 6 * d, p)
        c.drawCircle(cx - 12 * d, cy - 4 * d, 4 * d, p)
        c.drawCircle(cx + 12 * d, cy - 4 * d, 4 * d, p)
        c.restore()
    }
}
