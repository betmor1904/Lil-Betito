package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Flying wall chunks and neon sparks. Call burst() on a hit, smash() when the carrot breaks a barrier. */
class PartsView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Part(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, val vr: Float, val size: Float, val color: Int,
        var life: Float, val maxLife: Float, val chunk: Boolean
    )

    private val list = ArrayList<Part>()
    private var last = 0L
    private var running = false

    private val neon = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF1744"), Color.parseColor("#00E5FF"),
        Color.parseColor("#FF4081"), Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )
    private val barrierCols = intArrayOf(
        Color.parseColor("#D32F2F"), Color.WHITE, Color.parseColor("#FFEB3B"), Color.parseColor("#B71C1C")
    )
    private val edgeCols = intArrayOf(
        Color.parseColor("#90A4AE"), Color.parseColor("#607D8B"), Color.parseColor("#ECEFF1")
    )

    private fun spawn(x: Float, y: Float, angle: Float, speed: Float, color: Int, chunk: Boolean, sizeDp: Float) {
        if (list.size > 260) list.removeAt(0)
        val life = 30f + Random.nextFloat() * 28f
        list.add(
            Part(
                x, y, cos(angle) * speed, sin(angle) * speed,
                Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 24f,
                sizeDp * d, color, life, life, chunk
            )
        )
    }

    /** A normal hit. (nx, ny) points away from the wall, speed is the ball's speed in px/frame. */
    fun burst(x: Float, y: Float, nx: Float, ny: Float, speed: Float, barrier: Boolean, combo: Int) {
        val spd = speed / d
        val n = (6 + spd * 0.7f + combo).toInt().coerceAtMost(22)
        val baseAngle = atan2(ny, nx)
        for (i in 0 until n) {
            val angle = baseAngle + (Random.nextFloat() - 0.5f) * 2.2f
            val sp = (3f + Random.nextFloat() * 7f + spd * 0.2f) * d
            val chunk = Random.nextFloat() < (if (barrier) 0.75f else 0.45f)
            val col = when {
                chunk && barrier -> barrierCols[Random.nextInt(barrierCols.size)]
                chunk -> edgeCols[Random.nextInt(edgeCols.size)]
                else -> neon[Random.nextInt(neon.size)]
            }
            spawn(x, y, angle, sp, col, chunk, 3f + Random.nextFloat() * 4f)
        }
        kick()
    }

    /** Big explosion when the Buster Carrot smashes through a barrier. */
    fun smash(x: Float, y: Float) {
        for (i in 0 until 34) {
            val angle = Random.nextFloat() * 6.2832f
            val sp = (4f + Random.nextFloat() * 10f) * d
            val chunk = Random.nextFloat() < 0.8f
            val col = if (chunk) barrierCols[Random.nextInt(barrierCols.size)] else neon[Random.nextInt(neon.size)]
            spawn(x, y, angle, sp, col, chunk, if (chunk) 5f + Random.nextFloat() * 6f else 3f)
        }
        kick()
    }

    /** Green splat when the Sticky Bomb sticks. */
    fun goo(x: Float, y: Float) {
        val cols = intArrayOf(Color.parseColor("#76FF03"), Color.parseColor("#B2FF59"), Color.parseColor("#00C853"))
        for (i in 0 until 24) {
            val angle = Random.nextFloat() * 6.2832f
            val sp = (1.5f + Random.nextFloat() * 5f) * d
            spawn(x, y, angle, sp, cols[Random.nextInt(cols.size)], false, 3f + Random.nextFloat() * 3f)
        }
        kick()
    }

    private fun kick() {
        if (!running) {
            running = true
            last = 0L
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 1f else ((now - last) / 16.667f).coerceIn(0.2f, 3f)
        last = now
        val iter = list.iterator()
        while (iter.hasNext()) {
            val q = iter.next()
            q.vy += 0.45f * d * dt
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.rot += q.vr * dt
            q.life -= dt
            if (q.life <= 0f || q.y > height + 40 * d) {
                iter.remove()
                continue
            }
            val a = (255 * (q.life / q.maxLife)).toInt().coerceIn(0, 255)
            p.style = Paint.Style.FILL
            if (q.chunk) {
                c.save()
                c.translate(q.x, q.y)
                c.rotate(q.rot)
                p.color = q.color
                p.alpha = a
                c.drawRect(-q.size, -q.size * 0.6f, q.size, q.size * 0.6f, p)
                c.restore()
            } else {
                p.color = q.color
                p.alpha = a / 3
                c.drawCircle(q.x, q.y, q.size * 1.3f, p)
                p.color = q.color
                p.alpha = a
                c.drawCircle(q.x, q.y, q.size * 0.55f, p)
            }
        }
        if (list.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            running = false
            last = 0L
        }
    }
}
