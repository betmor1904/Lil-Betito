package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.sin
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
    var rockets = 3       // 0..3 special rockets strapped on
    var buster = false    // Buster Carrot power: glow + smash through barriers
    private val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 40 * d, intArrayOf(Color.argb(170, 255, 152, 0), Color.argb(0, 255, 152, 0)), null, Shader.TileMode.CLAMP)
    }
    private val slotY = floatArrayOf(-9f, -17f, -1f)

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

        if (buster) {
            aura.alpha = (200 + 55 * sin(SystemClock.uptimeMillis() / 110.0)).toInt().coerceIn(0, 255)
            c.save()
            c.translate(cx, cy)
            c.drawCircle(0f, 0f, 40 * d, aura)
            c.restore()
        }

        // rocket boosters (strapped to the back of the shell)
        for (k in 0 until rockets.coerceIn(0, 3)) {
            val y = cy + slotY[k] * d
            if (boost) {
                val len = (8 + Random.nextFloat() * 8) * d
                p.color = Color.parseColor(if (buster) "#FF4081" else "#FF9800")
                path.reset()
                path.moveTo(cx - 37 * d, y - 4 * d)
                path.lineTo(cx - 37 * d - len, y)
                path.lineTo(cx - 37 * d, y + 4 * d)
                path.close()
                c.drawPath(path, p)
                p.color = Color.parseColor(if (buster) "#FFFF8D" else "#FFEB3B")
                path.reset()
                path.moveTo(cx - 37 * d, y - 2 * d)
                path.lineTo(cx - 37 * d - len * 0.6f, y)
                path.lineTo(cx - 37 * d, y + 2 * d)
                path.close()
                c.drawPath(path, p)
            }
            p.color = Color.parseColor("#455A64")
            c.drawRect(cx - 37 * d, y - 3 * d, cx - 34 * d, y + 3 * d, p)
            p.color = Color.parseColor("#FFD54F")
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

        // goofy face (drawn last so it sits in front of the shell)
        val hx = cx + 27 * d
        val hy = cy - 4 * d
        p.style = Paint.Style.FILL
        p.color = skin
        c.drawCircle(hx, hy, 12 * d, p)
        // big bulging eyes with blue irises
        for (ex in floatArrayOf(-5f, 5f)) {
            val x = hx + ex * d
            val y = hy - 5 * d
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawCircle(x, y, 4.8f * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * d
            p.color = Color.parseColor("#33691E")
            c.drawCircle(x, y, 4.8f * d, p)
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#1E88E5")
            c.drawCircle(x + 0.8f * d, y, 2.6f * d, p)
            p.color = Color.BLACK
            c.drawCircle(x + 1f * d, y, 1.2f * d, p)
            // eyelashes
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * d
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.BLACK
            c.drawLine(x - 3f * d, y - 4f * d, x - 4.5f * d, y - 6.5f * d, p)
            c.drawLine(x, y - 4.8f * d, x, y - 7.5f * d, p)
            c.drawLine(x + 3f * d, y - 4f * d, x + 4.5f * d, y - 6.5f * d, p)
        }
        // little nose
        p.style = Paint.Style.FILL
        p.color = skinDark
        oval.set(hx - 2.5f * d, hy - 0.5f * d, hx + 2.5f * d, hy + 2f * d)
        c.drawOval(oval, p)
        // big goofy grin
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.parseColor("#33691E")
        oval.set(hx - 9 * d, hy - 3 * d, hx + 9 * d, hy + 8 * d)
        c.drawArc(oval, 15f, 150f, false, p)
        // two big buck teeth
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        c.drawRoundRect(hx - 4 * d, hy + 3 * d, hx - 0.3f * d, hy + 9 * d, 1.2f * d, 1.2f * d, p)
        c.drawRoundRect(hx + 0.3f * d, hy + 3 * d, hx + 4 * d, hy + 9 * d, 1.2f * d, 1.2f * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.8f * d
        p.color = Color.parseColor("#33691E")
        c.drawRoundRect(hx - 4 * d, hy + 3 * d, hx - 0.3f * d, hy + 9 * d, 1.2f * d, 1.2f * d, p)
        c.drawRoundRect(hx + 0.3f * d, hy + 3 * d, hx + 4 * d, hy + 9 * d, 1.2f * d, 1.2f * d, p)
        p.style = Paint.Style.FILL
        c.restore()
        if (buster) postInvalidateOnAnimation()
    }
}
