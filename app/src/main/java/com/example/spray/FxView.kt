package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/** Casino jackpot celebration: marquee lights, flashing color and raining glowing vegetables. */
class FxView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()
    var durationMs = 3600L
    var onFinished: (() -> Unit)? = null

    private var t0 = 0L
    private var last = 0L
    private var done = false

    private class Veg(
        var x: Float, var y: Float, val vy: Float, var rot: Float, val vr: Float,
        val type: Int, val scale: Float, val phase: Float
    )
    private val vegs = ArrayList<Veg>()

    private val glow = intArrayOf(
        Color.parseColor("#FF9800"), Color.parseColor("#69F0AE"), Color.parseColor("#FF5252"),
        Color.parseColor("#FFEA00"), Color.parseColor("#E040FB"), Color.parseColor("#76FF03")
    )
    private val halos = Array(6) { i ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val g = glow[i]
            shader = RadialGradient(
                0f, 0f, 26f,
                intArrayOf(Color.argb(200, Color.red(g), Color.green(g), Color.blue(g)),
                    Color.argb(0, Color.red(g), Color.green(g), Color.blue(g))),
                null, Shader.TileMode.CLAMP
            )
        }
    }
    private val bulbColors = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF1744"), Color.parseColor("#00E5FF"),
        Color.parseColor("#FF4081"), Color.parseColor("#76FF03")
    )
    private val tints = intArrayOf(
        Color.parseColor("#FFC107"), Color.parseColor("#E91E63"), Color.parseColor("#00BCD4")
    )

    private fun newVeg(w: Float, h: Float, initial: Boolean) = Veg(
        Random.nextFloat() * w,
        if (initial) (Random.nextFloat() * 1.2f - 0.4f) * h else -40 * d,
        (3.5f + Random.nextFloat() * 5f) * d,
        Random.nextFloat() * 360f,
        (Random.nextFloat() - 0.5f) * 8f,
        Random.nextInt(6),
        0.9f + Random.nextFloat() * 0.8f,
        Random.nextFloat() * 6.28f
    )

    private fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color }
    private fun stroke(color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND
    }

    private fun sprite(c: Canvas, type: Int) {
        when (type) {
            0 -> { // carrot
                fill(Color.parseColor("#FF8F00"))
                path.reset(); path.moveTo(-6f, -9f); path.lineTo(6f, -9f); path.lineTo(0f, 15f); path.close()
                c.drawPath(path, p)
                stroke(Color.parseColor("#43A047"), 2.5f)
                c.drawLine(0f, -9f, -5f, -17f, p); c.drawLine(0f, -9f, 0f, -19f, p); c.drawLine(0f, -9f, 5f, -17f, p)
            }
            1 -> { // broccoli
                fill(Color.parseColor("#9CCC65")); c.drawRect(-3f, 1f, 3f, 13f, p)
                fill(Color.parseColor("#2E7D32"))
                c.drawCircle(-7f, -2f, 7f, p); c.drawCircle(7f, -2f, 7f, p)
                c.drawCircle(0f, -8f, 8f, p); c.drawCircle(0f, 0f, 8f, p)
                fill(Color.parseColor("#66BB6A")); c.drawCircle(-3f, -6f, 3f, p); c.drawCircle(5f, -3f, 3f, p)
            }
            2 -> { // tomato
                fill(Color.parseColor("#E53935")); c.drawCircle(0f, 2f, 11f, p)
                fill(Color.argb(120, 255, 255, 255)); c.drawCircle(-4f, -2f, 3f, p)
                stroke(Color.parseColor("#2E7D32"), 2.5f)
                c.drawLine(0f, -9f, -5f, -12f, p); c.drawLine(0f, -9f, 5f, -12f, p); c.drawLine(0f, -9f, 0f, -14f, p)
            }
            3 -> { // corn
                fill(Color.parseColor("#FFD600")); rf.set(-6f, -13f, 6f, 13f); c.drawOval(rf, p)
                stroke(Color.parseColor("#F9A825"), 1.2f)
                c.drawLine(-5f, -6f, 5f, -6f, p); c.drawLine(-6f, 0f, 6f, 0f, p); c.drawLine(-5f, 6f, 5f, 6f, p)
                c.drawLine(0f, -12f, 0f, 12f, p)
                fill(Color.parseColor("#43A047"))
                path.reset(); path.moveTo(0f, 13f); path.lineTo(-9f, 3f); path.lineTo(-2f, 6f); path.close()
                path.moveTo(0f, 13f); path.lineTo(9f, 3f); path.lineTo(2f, 6f); path.close()
                c.drawPath(path, p)
            }
            4 -> { // eggplant
                fill(Color.parseColor("#6A1B9A")); rf.set(-8f, -6f, 8f, 14f); c.drawOval(rf, p)
                fill(Color.argb(90, 255, 255, 255)); rf.set(-5f, -3f, -2f, 6f); c.drawOval(rf, p)
                fill(Color.parseColor("#2E7D32")); c.drawRect(-5f, -9f, 5f, -4f, p)
                stroke(Color.parseColor("#2E7D32"), 2.5f); c.drawLine(0f, -9f, 2f, -14f, p)
            }
            else -> { // pepper
                fill(Color.parseColor("#00C853")); rf.set(-8f, -8f, 8f, 11f); c.drawRoundRect(rf, 6f, 6f, p)
                fill(Color.argb(80, 255, 255, 255)); c.drawRect(-5f, -5f, -3f, 6f, p)
                stroke(Color.parseColor("#5D4037"), 3f); c.drawLine(0f, -8f, 1f, -13f, p)
            }
        }
    }

    private fun bulb(c: Canvas, x: Float, y: Float, idx: Int, t: Double) {
        val on = ((idx + (t * 14).toInt()) % 3) != 0
        val col = bulbColors[((idx / 3) + (t * 3).toInt()) % bulbColors.size]
        p.style = Paint.Style.FILL
        if (on) {
            p.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(x, y, 11 * d, p)
            p.color = col
            c.drawCircle(x, y, 5.5f * d, p)
            p.color = Color.WHITE
            c.drawCircle(x, y, 2.2f * d, p)
        } else {
            p.color = Color.argb(120, 90, 90, 90)
            c.drawCircle(x, y, 4 * d, p)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        if (t0 == 0L) t0 = now
        val dtF = if (last == 0L) 1f else ((now - last) / 16.667f).coerceAtMost(3f)
        last = now
        val t = (now - t0) / 1000.0
        val ending = now - t0 > durationMs - 600

        if (vegs.isEmpty()) for (i in 0 until 46) vegs.add(newVeg(w, h, true))

        // flashing color wash
        val tc = tints[(t * 7).toInt() % 3]
        c.drawColor(Color.argb((22 + 22 * sin(t * 20)).toInt(), Color.red(tc), Color.green(tc), Color.blue(tc)))

        // raining glowing vegetables
        for (i in vegs.indices) {
            val v = vegs[i]
            v.y += v.vy * dtF
            v.rot += v.vr * dtF
            if (v.y > h + 60 * d && !ending) {
                vegs[i] = newVeg(w, h, false)
                continue
            }
            c.save()
            c.translate(v.x + (sin(t * 2 + v.phase) * 14 * d).toFloat(), v.y)
            c.rotate(v.rot)
            c.scale(d * v.scale, d * v.scale)
            c.drawCircle(0f, 0f, 26f, halos[v.type])
            sprite(c, v.type)
            c.restore()
        }

        // marquee bulbs around the screen edge
        val sp = 30 * d
        val m = 12 * d
        var idx = 0
        var x = m
        while (x < w - m) { bulb(c, x, m, idx++, t); x += sp }
        var y = m
        while (y < h - m) { bulb(c, w - m, y, idx++, t); y += sp }
        x = w - m
        while (x > m) { bulb(c, x, h - m, idx++, t); x -= sp }
        y = h - m
        while (y > m) { bulb(c, m, y, idx++, t); y -= sp }

        // JACKPOT text
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textAlign = Paint.Align.CENTER
        var size = 58 * d * (1 + 0.07 * sin(t * 14)).toFloat()
        p.textSize = size
        val mw = p.measureText("JACKPOT!")
        if (mw > w * 0.9f) { size *= w * 0.9f / mw; p.textSize = size }
        val ty = h * 0.18f
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 8 * d
        p.color = Color.parseColor("#4A148C")
        c.drawText("JACKPOT!", w / 2, ty, p)
        p.style = Paint.Style.FILL
        p.color = bulbColors[(t * 8).toInt() % bulbColors.size]
        c.drawText("JACKPOT!", w / 2, ty, p)

        if (!done && now - t0 > durationMs) {
            done = true
            post { onFinished?.invoke() }
        }
        if (!done) postInvalidateOnAnimation()
    }
}
