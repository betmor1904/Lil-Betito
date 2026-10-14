package com.example.spray

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared drawing for menus, the item guide and Octo's tips: buttons, Octo the Caddie,
 * a little Betito, and one icon per item in the game.
 */
object Art {
    private val r = RectF()
    private val tmp = Path()

    val NEON = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF4081"), Color.parseColor("#00E5FF"),
        Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )

    fun zoneRGB(kind: Int): Int = when (kind) {
        WaterZone.UP -> Color.rgb(0, 229, 255)
        WaterZone.SIDE -> Color.rgb(29, 233, 182)
        WaterZone.RIP -> Color.rgb(255, 82, 82)
        else -> Color.rgb(179, 136, 255)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** Splits text into lines that fit maxW with the paint's current text size. */
    fun wrap(p: Paint, text: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(tryLine) <= maxW || line.isEmpty()) line = tryLine
                else {
                    out.add(line)
                    line = word
                }
            }
            out.add(line)
        }
        return out
    }

    /** Draws text centered at x, shrinking it if it is wider than maxW. */
    fun fitText(c: Canvas, p: Paint, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    /** A chunky rounded button with a gold rim. */
    fun button(c: Canvas, p: Paint, rect: RectF, label: String, fill: Int, d: Float, textSize: Float = 16f, pressed: Boolean = false) {
        val rad = min(rect.height() / 2f, 16 * d)
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 0, 0, 0)
        r.set(rect.left, rect.top + 4 * d, rect.right, rect.bottom + 4 * d)
        if (!pressed) c.drawRoundRect(r, rad, rad, p)
        r.set(rect)
        if (pressed) r.offset(0f, 3 * d)
        p.color = fill
        c.drawRoundRect(r, rad, rad, p)
        // shine on the top half
        p.color = Color.argb(45, 255, 255, 255)
        c.save()
        c.clipRect(r.left, r.top, r.right, r.centerY())
        c.drawRoundRect(r, rad, rad, p)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        p.color = Color.parseColor("#FFD54F")
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = Color.WHITE
        p.setShadowLayer(3f, 1f, 2f, Color.argb(160, 0, 0, 0))
        fitText(c, p, label, r.centerX(), r.centerY() + textSize * d * 0.36f, textSize * d, r.width() - 20 * d)
        p.clearShadowLayer()
    }

    fun star(c: Canvas, p: Paint, x: Float, y: Float, rad: Float) {
        tmp.reset()
        for (i in 0 until 10) {
            val a = -1.5708f + i * 0.6283f
            val rr = if (i % 2 == 0) rad else rad * 0.45f
            val sx = x + cos(a) * rr
            val sy = y + sin(a) * rr
            if (i == 0) tmp.moveTo(sx, sy) else tmp.lineTo(sx, sy)
        }
        tmp.close()
        c.drawPath(tmp, p)
    }

    fun lock(c: Canvas, p: Paint, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.28f
        p.color = Color.parseColor("#FFE082")
        r.set(x - s * 0.5f, y - s * 1.1f, x + s * 0.5f, y - s * 0.1f)
        c.drawArc(r, 180f, 180f, false, p)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FFC107")
        r.set(x - s * 0.75f, y - s * 0.4f, x + s * 0.75f, y + s * 0.7f)
        c.drawRoundRect(r, s * 0.2f, s * 0.2f, p)
        p.color = Color.parseColor("#5D4037")
        c.drawCircle(x, y + s * 0.1f, s * 0.15f, p)
    }

    // ============================================================
    // Octo the Caddie
    // ============================================================

    /**
     * Octo the octopus, Betito's caddie. s = head radius. When talking his mouth opens and closes.
     * He wears a little green golf visor.
     */
    fun octo(c: Canvas, p: Paint, path: Path, x: Float, y0: Float, s: Float, now: Long, talking: Boolean = false, face: Int = -1) {
        val y = y0 + sin(now / 400.0).toFloat() * s * 0.08f
        // the hand-made faces: smile, happy, wink, surprised
        val chosen = when {
            face == Sprites.FACE_SMILE && talking -> if ((now / 150) % 2 == 0L) Sprites.FACE_HAPPY else Sprites.FACE_SMILE
            face >= 0 -> face
            talking -> if ((now / 150) % 2 == 0L) Sprites.FACE_HAPPY else Sprites.FACE_SMILE
            (now % 3400L) < 220L -> Sprites.FACE_WINK       // a wink instead of a blink
            else -> Sprites.FACE_SMILE
        }
        if (Sprites.drawOcto(c, x, y, s, chosen)) return
        val body = Color.parseColor("#AB47BC")
        // tentacles
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.26f
        p.color = Color.parseColor("#8E24AA")
        for (k in 0 until 6) {
            val bx = x - s * 0.62f + k * s * 0.25f
            val wig = sin(now / 220.0 + k * 1.3).toFloat() * s * 0.28f
            val curl = if (k < 3) -1f else 1f
            path.reset()
            path.moveTo(bx, y + s * 0.55f)
            path.cubicTo(bx + wig, y + s * 1.0f, bx - wig + curl * s * 0.2f, y + s * 1.35f, bx + curl * s * 0.35f, y + s * 1.5f)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        // head
        p.color = body
        c.drawOval(x - s, y - s * 1.05f, x + s, y + s * 0.75f, p)
        p.color = Color.argb(70, 255, 255, 255)
        c.drawOval(x - s * 0.6f, y - s * 0.95f, x + s * 0.05f, y - s * 0.45f, p)
        // visor
        p.color = Color.parseColor("#43A047")
        r.set(x - s * 0.95f, y - s * 1.1f, x + s * 0.95f, y - s * 0.25f)
        c.drawArc(r, 180f, 180f, true, p)
        p.color = Color.parseColor("#2E7D32")
        r.set(x - s * 0.15f, y - s * 0.78f, x + s * 1.35f, y - s * 0.52f)
        c.drawRoundRect(r, s * 0.13f, s * 0.13f, p)
        p.color = Color.WHITE
        c.drawCircle(x - s * 0.1f, y - s * 0.95f, s * 0.08f, p)
        // eyes (blink every few seconds)
        val blink = (now % 3400L) < 130L
        val ey = y - s * 0.12f
        for (side in intArrayOf(-1, 1)) {
            val ex = x + side * s * 0.36f
            if (blink) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.08f
                p.color = Color.parseColor("#311B92")
                c.drawLine(ex - s * 0.18f, ey, ex + s * 0.18f, ey, p)
                p.style = Paint.Style.FILL
            } else {
                p.color = Color.WHITE
                c.drawCircle(ex, ey, s * 0.24f, p)
                p.color = Color.parseColor("#1A1035")
                c.drawCircle(ex + s * 0.05f, ey + s * 0.03f, s * 0.12f, p)
                p.color = Color.WHITE
                c.drawCircle(ex + s * 0.09f, ey - s * 0.03f, s * 0.04f, p)
            }
        }
        // cheeks
        p.color = Color.argb(120, 255, 128, 171)
        c.drawCircle(x - s * 0.62f, y + s * 0.18f, s * 0.13f, p)
        c.drawCircle(x + s * 0.62f, y + s * 0.18f, s * 0.13f, p)
        // mouth
        val open = if (talking) (0.5f + 0.5f * sin(now / 70.0).toFloat()) else 0f
        if (open > 0.15f) {
            p.color = Color.parseColor("#4A148C")
            c.drawOval(x - s * 0.17f, y + s * 0.18f, x + s * 0.17f, y + s * 0.2f + s * 0.26f * open, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = s * 0.08f
            p.color = Color.parseColor("#4A148C")
            r.set(x - s * 0.2f, y + s * 0.05f, x + s * 0.2f, y + s * 0.35f)
            c.drawArc(r, 20f, 140f, false, p)
            p.style = Paint.Style.FILL
        }
    }

    // ============================================================
    // a small Betito for menus and icons
    // ============================================================

    fun turtle(c: Canvas, p: Paint, path: Path, x: Float, y: Float, u: Float, headingDeg: Float, now: Long) {
        c.save()
        c.translate(x, y)
        c.rotate(headingDeg)
        if (Sprites.drawTurtle(c, u)) { c.restore(); return }
        val flap = sin(now / 140.0).toFloat() * 18f
        p.style = Paint.Style.FILL
        p.color = Color.rgb(124, 179, 66)
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            leg(c, p, 0.34f * u, s * 0.66f * u, s * (40f + flap), u)
            leg(c, p, -0.34f * u, s * 0.60f * u, s * (-40f - flap), u)
        }
        path.reset()
        path.moveTo(-0.5f * u, -0.08f * u)
        path.lineTo(-0.82f * u, 0f)
        path.lineTo(-0.5f * u, 0.08f * u)
        path.close()
        c.drawPath(path, p)
        p.color = Color.rgb(156, 204, 101)
        c.drawCircle(0.8f * u, 0f, 0.2f * u, p)
        p.color = Color.BLACK
        c.drawCircle(0.87f * u, -0.08f * u, 0.035f * u, p)
        c.drawCircle(0.87f * u, 0.08f * u, 0.035f * u, p)
        p.color = Color.rgb(46, 125, 50)
        c.drawOval(-0.62f * u, -0.52f * u, 0.62f * u, 0.52f * u, p)
        p.color = Color.rgb(102, 187, 106)
        c.drawOval(-0.52f * u, -0.43f * u, 0.52f * u, 0.43f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.06f * u
        p.color = Color.rgb(46, 125, 50)
        c.drawOval(-0.22f * u, -0.17f * u, 0.22f * u, 0.17f * u, p)
        c.drawLine(0.22f * u, 0f, 0.5f * u, 0f, p)
        c.drawLine(-0.22f * u, 0f, -0.5f * u, 0f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }

    private fun leg(c: Canvas, p: Paint, x: Float, y: Float, angle: Float, u: Float) {
        c.save()
        c.rotate(angle, x, y)
        c.drawOval(x - 0.22f * u, y - 0.12f * u, x + 0.22f * u, y + 0.12f * u, p)
        c.restore()
    }

    fun shark(c: Canvas, p: Paint, path: Path, x: Float, y: Float, u: Float, headingDeg: Float, now: Long) {
        c.save()
        c.translate(x, y)
        c.rotate(headingDeg)
        if (Sprites.drawShark(c, u)) { c.restore(); return }
        val wag = sin(now / 110.0).toFloat() * 0.25f * u
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#607D8B")
        path.reset()
        path.moveTo(-1.1f * u, 0f)
        path.lineTo(-1.8f * u, -0.6f * u + wag)
        path.lineTo(-1.6f * u, wag * 0.3f)
        path.lineTo(-1.8f * u, 0.6f * u + wag)
        path.close()
        c.drawPath(path, p)
        c.drawOval(-1.3f * u, -0.55f * u, 1.3f * u, 0.55f * u, p)
        p.color = Color.parseColor("#CFD8DC")
        c.drawOval(-0.9f * u, 0f, 1.1f * u, 0.45f * u, p)
        p.color = Color.parseColor("#455A64")
        path.reset()
        path.moveTo(-0.2f * u, -0.5f * u)
        path.lineTo(0.3f * u, -0.95f * u)
        path.lineTo(0.5f * u, -0.45f * u)
        path.close()
        c.drawPath(path, p)
        p.color = Color.BLACK
        c.drawCircle(0.8f * u, -0.18f * u, 0.11f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.08f * u
        r.set(0.45f * u, 0.0f, 0.95f * u, 0.35f * u)
        c.drawArc(r, 20f, 120f, false, p)
        p.style = Paint.Style.FILL
        c.restore()
    }

    // ============================================================
    // item icons (s = half the size of the icon's box)
    // ============================================================

    fun icon(c: Canvas, p: Paint, path: Path, key: String, x: Float, y: Float, s: Float, now: Long, d: Float) {
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        p.pathEffect = null
        when (key) {
            "goal" -> {
                // a wall with the glowing hole and an arrow flying in
                p.color = Color.parseColor("#4A3B5C")
                c.drawRect(x + s * 0.45f, y - s, x + s * 0.8f, y - s * 0.38f, p)
                c.drawRect(x + s * 0.45f, y + s * 0.38f, x + s * 0.8f, y + s, p)
                val pulse = 0.6f + 0.4f * sin(now / 220.0).toFloat()
                p.color = Color.argb((140 * pulse).toInt(), 118, 255, 3)
                c.drawRect(x + s * 0.1f, y - s * 0.38f, x + s, y + s * 0.38f, p)
                turtle(c, p, path, x - s * 0.45f, y, s * 0.5f, 0f, now)
                p.color = Color.parseColor("#76FF03")
                path.reset()
                path.moveTo(x + s * 0.95f, y)
                path.lineTo(x + s * 0.55f, y - s * 0.22f)
                path.lineTo(x + s * 0.55f, y + s * 0.22f)
                path.close()
                c.drawPath(path, p)
            }
            "shoot" -> {
                val k = (now % 1400L) / 1400f
                turtle(c, p, path, x + s * 0.25f, y, s * 0.55f, 0f, now)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = Color.argb(200, 255, 255, 255)
                val ex = x - s * 0.2f - s * 0.7f * k
                val ey = y + s * 0.35f * k
                c.drawLine(x - s * 0.2f, y, ex, ey, p)
                p.style = Paint.Style.FILL
                c.drawCircle(ex, ey, s * 0.16f, p)
                p.color = Color.argb(200, 118, 255, 3)
                for (i in 1..3) c.drawCircle(x + s * 0.55f + i * s * 0.14f, y, s * 0.05f, p)
            }
            "blue", "green", "red" -> {
                val kind = when (key) { "blue" -> WaterZone.UP; "green" -> WaterZone.SIDE; else -> WaterZone.RIP }
                val dx = when (key) { "blue" -> 1f; "red" -> -1f; else -> 0f }
                val dy = if (key == "green") -1f else 0f
                val col = zoneRGB(kind)
                r.set(x - s, y - s * 0.8f, x + s, y + s * 0.8f)
                p.color = withAlpha(col, 70)
                c.drawRoundRect(r, 6 * d, 6 * d, p)
                c.save()
                c.clipRect(r)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * d
                p.color = withAlpha(col, 200)
                val along = s * 0.7f
                val shift = (now % 1000L) / 1000f * along
                for (lane in -1..1) {
                    for (i in -2..3) {
                        val o = i * along + shift
                        if (dy == 0f) {
                            val xx = if (dx > 0) x - s + o else x + s - o
                            c.drawLine(xx, y + lane * s * 0.45f, xx + dx * s * 0.3f, y + lane * s * 0.45f, p)
                        } else {
                            val yy = y + s - o
                            c.drawLine(x + lane * s * 0.55f, yy, x + lane * s * 0.55f, yy - s * 0.3f, p)
                        }
                    }
                }
                c.restore()
                p.strokeWidth = 3.5f * d
                p.color = Color.WHITE
                val len = s * 0.5f
                c.drawLine(x - dx * len, y - dy * len, x + dx * len, y + dy * len, p)
                val hx = x + dx * len
                val hy = y + dy * len
                val k = s * 0.28f
                c.drawLine(hx, hy, hx - dx * k - dy * k, hy - dy * k + dx * k, p)
                c.drawLine(hx, hy, hx - dx * k + dy * k, hy - dy * k - dx * k, p)
                p.style = Paint.Style.FILL
            }
            "whirl" -> {
                val col = zoneRGB(WaterZone.WHIRL)
                p.color = withAlpha(col, 70)
                c.drawCircle(x, y, s, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = withAlpha(col, 230)
                val turn = (now % 100000L) / 1000f * 160f
                for (i in 0 until 3) {
                    val rr = s * (0.3f + 0.25f * i)
                    r.set(x - rr, y - rr, x + rr, y + rr)
                    c.drawArc(r, turn * (1.4f - 0.2f * i) + i * 72f, 80f, false, p)
                    c.drawArc(r, turn * (1.4f - 0.2f * i) + i * 72f + 180f, 80f, false, p)
                }
                p.style = Paint.Style.FILL
            }
            "buoy" -> {
                val rr = s * 0.55f
                p.color = Color.WHITE
                c.drawCircle(x, y, rr, p)
                p.color = zoneRGB(WaterZone.SIDE)
                c.drawCircle(x, y, rr * 0.72f, p)
                p.color = Color.WHITE
                c.drawCircle(x, y, rr * 0.42f, p)
                p.color = zoneRGB(WaterZone.SIDE)
                c.drawCircle(x, y, rr * 0.18f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * d
                p.color = Color.argb(230, 255, 255, 255)
                r.set(x - rr * 1.5f, y - rr * 1.5f, x + rr * 1.5f, y + rr * 1.5f)
                val spin = (now % 4000L) / 4000f * 360f
                c.drawArc(r, spin, 100f, false, p)
                c.drawArc(r, spin + 180f, 100f, false, p)
                p.style = Paint.Style.FILL
            }
            "shell" -> {
                val rr = s * 0.7f
                p.color = Color.rgb(255, 183, 77)
                c.drawCircle(x, y, rr, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f * d
                p.color = Color.rgb(130, 70, 20)
                c.drawCircle(x, y, rr, p)
                for (i in -1..1) c.drawLine(x + i * rr * 0.2f, y + rr * 0.85f, x + i * rr * 0.6f, y - rr * 0.8f, p)
                p.style = Paint.Style.FILL
            }
            "bomb" -> {
                val rr = s * 0.6f
                p.color = Color.rgb(35, 35, 45)
                c.drawCircle(x, y + rr * 0.15f, rr, p)
                p.color = Color.argb(110, 255, 255, 255)
                c.drawCircle(x - rr * 0.35f, y - rr * 0.2f, rr * 0.25f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = Color.rgb(200, 170, 110)
                c.drawLine(x + rr * 0.3f, y - rr * 0.75f, x + rr * 0.8f, y - rr * 1.3f, p)
                p.style = Paint.Style.FILL
                p.color = Color.rgb(255, 193, 7)
                c.drawCircle(x + rr * 0.85f, y - rr * 1.35f, 3 * d, p)
            }
            "turn" -> {
                val rr = s * 0.65f
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.5f * d
                p.strokeCap = Paint.Cap.ROUND
                p.color = Color.argb(120, 255, 255, 255)
                c.drawLine(x - rr, y + rr * 0.6f, x, y + rr * 0.6f, p)
                p.color = Color.rgb(128, 222, 234)
                c.drawLine(x, y + rr * 0.6f, x + rr, y - rr * 0.5f, p)
                c.drawLine(x + rr, y - rr * 0.5f, x + rr - rr * 0.5f, y - rr * 0.45f, p)
                c.drawLine(x + rr, y - rr * 0.5f, x + rr - rr * 0.12f, y - rr * 0.05f, p)
                p.style = Paint.Style.FILL
            }
            "lock" -> {
                val rr = s * 0.6f
                p.color = Color.parseColor("#3E2723")
                c.drawCircle(x, y, rr, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3 * d
                p.color = Color.rgb(255, 193, 7)
                c.drawCircle(x, y, rr, p)
                p.style = Paint.Style.FILL
                lock(c, p, x, y, rr * 0.55f)
            }
            "star" -> {
                c.save()
                c.rotate((now % 6000L) / 6000f * 360f, x, y)
                p.color = Color.WHITE
                star(c, p, x, y, s * 0.75f)
                p.color = Color.rgb(255, 64, 129)
                star(c, p, x, y, s * 0.55f)
                c.restore()
            }
            "apple" -> {
                val bob = sin(now / 300.0).toFloat() * 2 * d
                p.color = Color.parseColor("#E53935")
                c.drawCircle(x, y + bob + s * 0.08f, s * 0.6f, p)
                p.color = Color.argb(90, 255, 255, 255)
                c.drawCircle(x - s * 0.22f, y + bob - s * 0.12f, s * 0.15f, p)
                p.color = Color.parseColor("#43A047")
                c.drawOval(x, y + bob - s * 0.8f, x + s * 0.45f, y + bob - s * 0.4f, p)
            }
            "speed" -> {
                p.color = Color.parseColor("#FFEB3B")
                c.drawCircle(x, y, s * 0.65f, p)
                p.color = Color.parseColor("#F57F17")
                val k = s / 10f
                path.reset()
                path.moveTo(x + 2 * k, y - 8 * k)
                path.lineTo(x - 5 * k, y + 1 * k)
                path.lineTo(x, y + 1 * k)
                path.lineTo(x - 2 * k, y + 8 * k)
                path.lineTo(x + 5 * k, y - 1 * k)
                path.lineTo(x, y - 1 * k)
                path.close()
                c.drawPath(path, p)
            }
            "slide" -> {
                path.reset()
                path.moveTo(x - s * 0.85f, y - s * 0.75f)
                path.quadTo(x - s * 0.85f, y, x, y)
                path.quadTo(x + s * 0.85f, y, x + s * 0.85f, y + s * 0.75f)
                p.style = Paint.Style.STROKE
                p.strokeJoin = Paint.Join.ROUND
                p.strokeWidth = s * 0.5f
                p.color = Color.argb(70, 102, 187, 106)
                c.drawPath(path, p)
                p.strokeWidth = 3 * d
                p.color = Color.parseColor("#2E7D32")
                c.drawPath(path, p)
                val pulse = 0.5f + 0.5f * sin(now / 220.0).toFloat()
                p.color = Color.argb((150 + 100 * pulse).toInt(), 118, 255, 3)
                p.strokeWidth = 2.5f * d
                c.drawCircle(x - s * 0.85f, y - s * 0.75f, s * 0.25f, p)
                c.drawCircle(x + s * 0.85f, y + s * 0.75f, s * 0.25f, p)
                p.style = Paint.Style.FILL
            }
            "weed" -> {
                r.set(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f)
                p.color = Color.argb(60, 76, 175, 80)
                c.drawRoundRect(r, 8 * d, 8 * d, p)
                p.style = Paint.Style.STROKE
                for (i in 0 until 4) {
                    val bx = x - s * 0.6f + i * s * 0.4f
                    val sway = sin(now / 500.0 + i).toFloat() * s * 0.2f
                    p.strokeWidth = (4 - (i % 2)) * d
                    p.color = if (i % 2 == 0) Color.rgb(46, 125, 50) else Color.rgb(102, 187, 106)
                    path.reset()
                    path.moveTo(bx, y + s * 0.8f)
                    path.quadTo(bx - sway, y, bx + sway, y - s * 0.85f)
                    c.drawPath(path, p)
                }
                p.style = Paint.Style.FILL
            }
            "shark" -> shark(c, p, path, x + s * 0.1f, y, s * 0.5f, 0f, now)
            "combo" -> {
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
                p.textSize = s * 0.9f
                p.color = NEON[((now / 120) % NEON.size).toInt()]
                p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                c.drawText("x3", x, y + s * 0.3f, p)
                p.clearShadowLayer()
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = Color.parseColor("#FFD600")
                star(c, p, x - s * 0.75f, y - s * 0.55f, s * 0.2f)
                star(c, p, x + s * 0.75f, y - s * 0.5f, s * 0.16f)
            }
            "score" -> {
                // a golf flag in the hole
                p.color = Color.parseColor("#1B5E20")
                c.drawOval(x - s * 0.8f, y + s * 0.45f, x + s * 0.8f, y + s * 0.85f, p)
                p.color = Color.parseColor("#0A1A10")
                c.drawOval(x - s * 0.25f, y + s * 0.55f, x + s * 0.25f, y + s * 0.75f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = Color.WHITE
                c.drawLine(x, y + s * 0.65f, x, y - s * 0.85f, p)
                p.style = Paint.Style.FILL
                val wave = sin(now / 200.0).toFloat() * s * 0.08f
                p.color = Color.parseColor("#FF1744")
                path.reset()
                path.moveTo(x, y - s * 0.85f)
                path.lineTo(x + s * 0.7f, y - s * 0.6f + wave)
                path.lineTo(x, y - s * 0.35f)
                path.close()
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }
}
