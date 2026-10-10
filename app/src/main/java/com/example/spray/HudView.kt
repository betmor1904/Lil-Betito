package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the room: ceiling with the exit gap, the rising floor, level/score, the spin meter,
 * the slot reels, the wager, lives, combo, steering line and messages.
 */
class HudView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    var floorY = 0f
    var ceilY = 0f
    var ceilThick = 0f
    var gapL = 0f
    var gapR = 0f
    var level = 1
    var best = 0
    var score = 0
    var worth = 0
    var energy = 0f
    var spinReady = false
    var flash = 0f
    var lives = 3
    var steerOn = false
    var steerTwo = false
    var steerMode = 0          // 0 plain, 1 climbing, -1 braking
    var sx0 = 0f
    var sy0 = 0f
    var sx1 = 0f
    var sy1 = 0f
    var message: String? = null
    var banner: String? = null
    var popup: String? = null
    var popupAt = 0L
    var combo = 0
    var comboMult = 1
    var comboLeft = 0f
    var reels = intArrayOf(-1, -1, -1)
    var reelResolving = false
    var wager = 0
    var wagerPct = 10
    var wagerLocked = false
    var betSecs = 0
    var zones: List<WaterZone> = emptyList()
    private val water = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val neon = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF4081"), Color.parseColor("#00E5FF"),
        Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        grid.strokeWidth = 1.5f * d
        grid.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(56, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun fitText(c: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    /** One slot symbol centered at (x, y). 0 cherry, 1 bell, 2 bar, 3 seven, 4 coin. */
    private fun symbol(c: Canvas, s: Int, x: Float, y: Float, size: Float) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        when (s) {
            0 -> { // cherry: two red balls on stems
                p.color = Color.parseColor("#2E7D32")
                p.strokeWidth = 1.5f * d
                p.style = Paint.Style.STROKE
                c.drawLine(x - size * 0.18f, y + size * 0.05f, x + size * 0.12f, y - size * 0.32f, p)
                c.drawLine(x + size * 0.2f, y + size * 0.1f, x + size * 0.12f, y - size * 0.32f, p)
                p.style = Paint.Style.FILL
                p.color = Color.parseColor("#E53935")
                c.drawCircle(x - size * 0.18f, y + size * 0.15f, size * 0.17f, p)
                c.drawCircle(x + size * 0.2f, y + size * 0.2f, size * 0.17f, p)
            }
            1 -> { // bell
                p.color = Color.parseColor("#FFD54F")
                path.reset()
                path.moveTo(x - size * 0.32f, y + size * 0.2f)
                path.quadTo(x - size * 0.28f, y - size * 0.35f, x, y - size * 0.35f)
                path.quadTo(x + size * 0.28f, y - size * 0.35f, x + size * 0.32f, y + size * 0.2f)
                path.close()
                c.drawPath(path, p)
                c.drawCircle(x, y + size * 0.27f, size * 0.08f, p)
            }
            2 -> { // BAR
                p.color = Color.WHITE
                c.drawRect(x - size * 0.38f, y - size * 0.16f, x + size * 0.38f, y + size * 0.16f, p)
                p.color = Color.BLACK
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = size * 0.28f
                c.drawText("BAR", x, y + size * 0.1f, p)
            }
            3 -> { // seven
                p.color = Color.parseColor("#FF1744")
                p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
                p.textSize = size * 0.8f
                c.drawText("7", x, y + size * 0.28f, p)
            }
            4 -> { // coin
                p.color = Color.parseColor("#FFC107")
                c.drawCircle(x, y, size * 0.3f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Color.parseColor("#FF8F00")
                c.drawCircle(x, y, size * 0.3f, p)
                p.style = Paint.Style.FILL
            }
        }
        p.typeface = Typeface.DEFAULT_BOLD
    }

    private fun zoneColor(kind: Int): IntArray = when (kind) {
        WaterZone.UP -> intArrayOf(0, 229, 255)
        WaterZone.SIDE -> intArrayOf(29, 233, 182)
        WaterZone.RIP -> intArrayOf(255, 82, 82)
        else -> intArrayOf(68, 138, 255)
    }

    /** Currents: a tinted patch with streaks sliding along the flow. Whirlpools: spinning arcs. */
    private fun drawWater(c: Canvas, now: Long) {
        for (z in zones) {
            val pw = z.power
            if (pw < 0.03f) continue
            val col = zoneColor(z.kind)
            water.style = Paint.Style.FILL
            water.color = Color.argb((34 * pw).toInt(), col[0], col[1], col[2])
            if (z.kind == WaterZone.WHIRL) {
                c.drawCircle(z.cx, z.cy, z.rad, water)
                water.style = Paint.Style.STROKE
                water.strokeWidth = 3 * d
                water.strokeCap = Paint.Cap.ROUND
                val turn = (now % 100000L) / 1000f * 160f * z.spin
                for (i in 0 until 5) {
                    val rr = z.rad * (0.25f + 0.17f * i)
                    arc.set(z.cx - rr, z.cy - rr, z.cx + rr, z.cy + rr)
                    water.color = Color.argb((150 * pw).toInt(), col[0], col[1], col[2])
                    c.drawArc(arc, turn * (1.4f - 0.15f * i) + i * 72f, 70f, false, water)
                    c.drawArc(arc, turn * (1.4f - 0.15f * i) + i * 72f + 180f, 70f, false, water)
                }
                continue
            }
            c.drawRect(z.l, z.t, z.r, z.b, water)
            // streaks move along the flow at the water's speed
            c.save()
            c.clipRect(z.l, z.t, z.r, z.b)
            water.style = Paint.Style.STROKE
            water.strokeWidth = 2.5f * d
            water.strokeCap = Paint.Cap.ROUND
            water.color = Color.argb((150 * pw).toInt(), col[0], col[1], col[2])
            val along = 44 * d
            val across = 22 * d
            val dash = 16 * d
            val shift = (now % 100000L) * (z.speed / 16.67f) % along
            if (z.dy != 0f) {
                // vertical flow
                var x = z.l + across / 2
                var lane = 0
                while (x < z.r) {
                    val stagger = if (lane % 2 == 0) 0f else along / 2
                    var y = z.t - along + (shift + stagger) % along
                    if (z.dy < 0f) y = z.t - along + (along - (shift + stagger) % along)
                    while (y < z.b + along) {
                        c.drawLine(x, y, x, y + dash, water)
                        y += along
                    }
                    x += across
                    lane++
                }
            } else {
                // horizontal flow
                var y = z.t + across / 2
                var lane = 0
                while (y < z.b) {
                    val stagger = if (lane % 2 == 0) 0f else along / 2
                    var x = z.l - along + (shift + stagger) % along
                    if (z.dx < 0f) x = z.l - along + (along - (shift + stagger) % along)
                    while (x < z.r + along) {
                        c.drawLine(x, y, x + dash, y, water)
                        x += along
                    }
                    y += across
                    lane++
                }
            }
            c.restore()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD

        // dark background + grid lines that ride up with the floor so you can feel the motion
        c.drawColor(Color.argb(225, 10, 14, 36))
        var gy = floorY - 100 * d
        while (gy > ceilY) {
            c.drawLine(0f, gy, w, gy, grid)
            gy -= 100 * d
        }

        drawWater(c, now)

        // rising floor: red and dangerous
        p.color = Color.parseColor("#E64A19")
        c.drawRect(0f, floorY, w, h, p)
        p.color = Color.parseColor("#FFEB3B")
        c.drawRect(0f, floorY, w, floorY + 6 * d, p)

        // ceiling: bright neon bands with the exit gap (always open)
        val top = ceilY - ceilThick
        p.color = Color.parseColor("#E0F7FA")
        c.drawRect(0f, top, gapL, ceilY, p)
        c.drawRect(gapR, top, w, ceilY, p)
        p.color = Color.parseColor("#00E5FF")
        c.drawRect(0f, ceilY - 4 * d, gapL, ceilY, p)
        c.drawRect(gapR, ceilY - 4 * d, w, ceilY, p)

        val mid = (gapL + gapR) / 2f
        val pulse = 0.6f + 0.4f * sin(now / 220.0).toFloat()
        for (i in 0 until 4) {
            p.color = Color.argb(((90 * pulse).toInt() - i * 18).coerceIn(0, 255), 118, 255, 3)
            c.drawRect(gapL, ceilY, gapR, ceilY + (i + 1) * 16 * d, p)
        }
        p.color = Color.parseColor("#76FF03")
        path.reset()
        path.moveTo(mid, top - 26 * d)
        path.lineTo(mid - 14 * d, top - 6 * d)
        path.lineTo(mid + 14 * d, top - 6 * d)
        path.close()
        c.drawPath(path, p)

        // ---- left column: level, score, best, escape worth, spin meter ----
        p.textAlign = Paint.Align.LEFT
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        p.color = Color.WHITE
        p.textSize = 20 * d
        c.drawText("LEVEL $level", 14 * d, ceilY + 28 * d, p)
        p.color = Color.parseColor("#FFEB3B")
        p.textSize = 14 * d
        c.drawText("SCORE $score", 14 * d, ceilY + 47 * d, p)
        p.textSize = 11 * d
        c.drawText("BEST $best", 14 * d, ceilY + 63 * d, p)
        p.color = Color.parseColor("#76FF03")
        p.textSize = 14 * d
        c.drawText("WORTH $worth", 14 * d, ceilY + 82 * d, p)
        p.color = if (spinReady) Color.parseColor("#80D8FF") else Color.WHITE
        p.textSize = 12 * d
        c.drawText(if (spinReady) "SPIN READY! TAP SPIN" else "SPIN ${energy.toInt()}%", 14 * d, ceilY + 100 * d, p)
        p.clearShadowLayer()
        p.color = Color.argb(70, 255, 255, 255)
        c.drawRect(14 * d, ceilY + 106 * d, 134 * d, ceilY + 113 * d, p)
        p.color = if (spinReady) Color.parseColor("#80D8FF") else Color.parseColor("#40C4FF")
        c.drawRect(14 * d, ceilY + 106 * d, (14 + 120 * (energy / 100f).coerceIn(0f, 1f)) * d, ceilY + 113 * d, p)

        // ---- right column: slot reels, (wager buttons + close are their own windows), wager text, lives ----
        val bs = 30 * d
        val gap = 6 * d
        val rx0 = w - 14 * d - 3 * bs - 2 * gap
        for (i in 0 until 3) {
            val x0 = rx0 + i * (bs + gap)
            box.set(x0, ceilY + 4 * d, x0 + bs, ceilY + 4 * d + bs)
            p.style = Paint.Style.FILL
            p.color = Color.argb(220, 25, 10, 40)
            c.drawRoundRect(box, 6 * d, 6 * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = if (reelResolving) {
                if ((now / 120) % 2 == 0L) Color.parseColor("#FFD54F") else Color.WHITE
            } else Color.parseColor("#B388FF")
            c.drawRoundRect(box, 6 * d, 6 * d, p)
            p.style = Paint.Style.FILL
            val s = reels.getOrElse(i) { -1 }
            if (s >= 0) symbol(c, s, box.centerX(), box.centerY(), bs)
            else {
                p.color = Color.argb(90, 255, 255, 255)
                p.textAlign = Paint.Align.CENTER
                p.textSize = 14 * d
                c.drawText("?", box.centerX(), box.centerY() + 5 * d, p)
            }
        }

        p.textAlign = Paint.Align.RIGHT
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        if (wagerLocked) {
            p.color = Color.parseColor("#FFD54F")
            p.textSize = 15 * d
            c.drawText("WAGER $wager", w - 14 * d, ceilY + 100 * d, p)
        } else {
            val blink = betSecs > 0 && (now / 300) % 2 == 0L
            p.color = if (blink) Color.WHITE else Color.parseColor("#FFD54F")
            p.textSize = 13 * d
            val t = if (betSecs > 0) "BET $wagerPct% = $wager  ${betSecs}s" else "BET $wagerPct% = $wager"
            c.drawText(t, w - 14 * d, ceilY + 100 * d, p)
        }
        p.color = Color.parseColor("#FF1744")
        p.textSize = 18 * d
        c.drawText("♥".repeat(lives.coerceIn(0, 3)) + "♡".repeat((3 - lives).coerceIn(0, 3)), w - 14 * d, ceilY + 122 * d, p)

        // banner
        p.textAlign = Paint.Align.CENTER
        val b = banner
        if (b != null) {
            p.color = Color.WHITE
            fitText(c, b, w / 2, ceilY + 172 * d, 15 * d, w * 0.9f)
        }

        // close-call popup floats up for a moment
        val pu = popup
        if (pu != null) {
            val age = now - popupAt
            if (age > 900L) popup = null
            else {
                p.color = Color.argb((255 * (1f - age / 900f)).toInt().coerceIn(0, 255), 128, 216, 255)
                fitText(c, pu, w / 2, h * 0.36f - age / 900f * 30 * d, 16 * d, w * 0.9f)
            }
        }
        p.clearShadowLayer()

        // steering line: white for one finger, green when climbing, amber when braking
        if (steerOn) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (steerMode != 0) 3 else 2) * d
            p.color = when (steerMode) {
                1 -> Color.argb(200, 118, 255, 3)
                -1 -> Color.argb(200, 255, 179, 0)
                else -> Color.argb(110, 255, 255, 255)
            }
            c.drawLine(sx0, sy0, sx1, sy1, p)
            p.style = Paint.Style.FILL
            c.drawCircle(sx1, sy1, 10 * d, p)
            if (steerTwo) c.drawCircle(sx0, sy0, 10 * d, p)
        }

        // combo counter + the bar that shows how long until it runs out
        if (combo >= 2) {
            val txt = if (comboMult > 1) "COMBO $combo  ×$comboMult" else "COMBO $combo"
            val size = (26 + min(combo, 10) * 2.5f) * d
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
            p.style = Paint.Style.STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = 6 * d
            p.color = Color.parseColor("#4A148C")
            fitText(c, txt, w / 2, h * 0.30f, size, w * 0.9f)
            p.style = Paint.Style.FILL
            p.color = neon[combo % neon.size]
            fitText(c, txt, w / 2, h * 0.30f, size, w * 0.9f)
            p.typeface = Typeface.DEFAULT_BOLD
            val bw = w * 0.5f
            p.color = Color.argb(70, 255, 255, 255)
            c.drawRect(w / 2 - bw / 2, h * 0.30f + 12 * d, w / 2 + bw / 2, h * 0.30f + 18 * d, p)
            p.color = neon[combo % neon.size]
            c.drawRect(w / 2 - bw / 2, h * 0.30f + 12 * d, w / 2 - bw / 2 + bw * comboLeft, h * 0.30f + 18 * d, p)
        }

        // explosion flash
        if (flash > 0f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((210 * flash).toInt().coerceIn(0, 255), 170, 225, 255)
            c.drawRect(0f, 0f, w, h, p)
        }

        // big message
        val m = message
        if (m != null) {
            p.textAlign = Paint.Align.CENTER
            p.color = Color.WHITE
            p.setShadowLayer(8f, 3f, 3f, Color.BLACK)
            fitText(c, m, w / 2, h * 0.4f, 34 * d, w * 0.92f)
            p.clearShadowLayer()
        }
    }
}
