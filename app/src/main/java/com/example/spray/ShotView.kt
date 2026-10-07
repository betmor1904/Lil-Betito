package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Lil Betito seen from above. Head and legs tuck into the shell when he bumps something
 * and pop back out a moment later while he flies. heading: 0 = facing right, -90 = facing up.
 */
class ShotView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var heading = -90f
    var radiusPx = 14 * d
    private var bumpAt = 0L
    private var kickAt = 0L
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowFor = -1f

    private val shellDark = Color.parseColor("#2E7D32")
    private val shellLight = Color.parseColor("#66BB6A")
    private val skin = Color.parseColor("#9CCC65")
    private val skinDark = Color.parseColor("#7CB342")

    /** Call when he hits something: tucks everything in, then it pops back out. */
    fun bump() { bumpAt = SystemClock.uptimeMillis() }

    /** Call on a nudge: he squashes and stretches along his direction for a moment. */
    fun kick() { kickAt = SystemClock.uptimeMillis() }

    private fun tuckAmount(now: Long): Float {
        if (bumpAt == 0L) return 0f
        val t = now - bumpAt
        return when {
            t < 120L -> 1f
            t < 520L -> {
                val k = (t - 120L) / 400f
                1f - k * k * (3f - 2f * k)
            }
            else -> 0f
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun leg(c: Canvas, x: Float, y: Float, angle: Float, u: Float) {
        c.save()
        c.rotate(angle, x, y)
        c.drawOval(x - 0.22f * u, y - 0.12f * u, x + 0.22f * u, y + 0.12f * u, p)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val out = 1f - tuckAmount(now)
        val u = radiusPx
        c.save()
        c.translate(width / 2f, height / 2f)
        // bright halo so he pops against any background
        if (glowFor != u) {
            glow.shader = RadialGradient(
                0f, 0f, 2.1f * u,
                intArrayOf(Color.argb(200, 0, 229, 255), Color.argb(90, 0, 229, 255), Color.argb(0, 0, 229, 255)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            glowFor = u
        }
        c.drawCircle(0f, 0f, 2.1f * u, glow)
        c.rotate(heading)
        val kt = now - kickAt
        val sq = if (kickAt == 0L || kt > 180L) 0f else 1f - kt / 180f
        c.scale(1f + 0.4f * sq, 1f - 0.3f * sq)
        p.style = Paint.Style.FILL

        // paddling legs (drawn first so the shell covers them when tucked)
        val flap = sin(now / 90.0).toFloat() * 18f * out
        p.color = skinDark
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            leg(c, lerp(0.15f * u, 0.34f * u, out), s * lerp(0.2f * u, 0.66f * u, out), s * (40f + flap), u)
            leg(c, lerp(-0.15f * u, -0.34f * u, out), s * lerp(0.2f * u, 0.60f * u, out), s * (-40f - flap), u)
        }

        // tail
        p.color = skinDark
        val tail = Path()
        tail.moveTo(-0.5f * u, -0.08f * u)
        tail.lineTo(lerp(-0.5f * u, -0.82f * u, out), 0f)
        tail.lineTo(-0.5f * u, 0.08f * u)
        tail.close()
        c.drawPath(tail, p)

        // head
        val hx = lerp(0.35f * u, 0.80f * u, out)
        p.color = skin
        c.drawCircle(hx, 0f, 0.2f * u, p)
        if (out > 0.5f) {
            p.color = Color.BLACK
            c.drawCircle(hx + 0.07f * u, -0.08f * u, 0.035f * u, p)
            c.drawCircle(hx + 0.07f * u, 0.08f * u, 0.035f * u, p)
        }

        // shell
        p.color = shellDark
        c.drawOval(-0.62f * u, -0.52f * u, 0.62f * u, 0.52f * u, p)
        p.color = shellLight
        c.drawOval(-0.52f * u, -0.43f * u, 0.52f * u, 0.43f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, 0.06f * u)
        p.color = shellDark
        c.drawOval(-0.22f * u, -0.17f * u, 0.22f * u, 0.17f * u, p)
        c.drawLine(0.2f * u, -0.12f * u, 0.42f * u, -0.3f * u, p)
        c.drawLine(0.2f * u, 0.12f * u, 0.42f * u, 0.3f * u, p)
        c.drawLine(-0.2f * u, -0.12f * u, -0.42f * u, -0.3f * u, p)
        c.drawLine(-0.2f * u, 0.12f * u, -0.42f * u, 0.3f * u, p)
        c.drawLine(0.22f * u, 0f, 0.5f * u, 0f, p)
        c.drawLine(-0.22f * u, 0f, -0.5f * u, 0f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
}
