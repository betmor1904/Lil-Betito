package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the room: ceiling with the exit gap, the rising floor, level/score, the spin meter,
 * the slot reels, lives, the bonus tracker, steering line and messages.
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
    var zones: List<WaterZone> = emptyList()
    var cleanRun = true
    var praise: String? = null
    var praiseAt = 0L
    var praiseBig = false
    var stars = 0
    var starsAt = 0L
    private val starPath = Path()
    var currentsVisited = 0
    var currentsTotal = 0
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
                // a fixed circular arrow so the spin direction reads at a glance
                water.color = Color.argb((220 * pw).toInt(), 255, 255, 255)
                water.strokeWidth = 3 * d
                val ar = z.rad * 0.55f
                arc.set(z.cx - ar, z.cy - ar, z.cx + ar, z.cy + ar)
                c.drawArc(arc, 0f, 270f * z.spin, false, water)
                val endA = Math.toRadians((270.0 * z.spin)).toFloat()
                val ex = z.cx + ar * kotlin.math.cos(endA)
                val ey = z.cy + ar * kotlin.math.sin(endA)
                val tx = -kotlin.math.sin(endA) * z.spin
                val ty = kotlin.math.cos(endA) * z.spin
                arrowHead(c, ex, ey, tx, ty)
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
            // a big arrow in the middle shows where this water goes
            val ax = (z.l + z.r) / 2f
            val ay = (z.t + z.b) / 2f
            val len = 18 * d
            water.style = Paint.Style.STROKE
            water.strokeWidth = 4 * d
            water.color = Color.argb((230 * pw).toInt(), 255, 255, 255)
            c.drawLine(ax - z.dx * len, ay - z.dy * len, ax + z.dx * len, ay + z.dy * len, water)
            arrowHead(c, ax + z.dx * len, ay + z.dy * len, z.dx, z.dy)
        }
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val k = 9 * d
        val px = -dy * k
        val py = dx * k
        c.drawLine(x, y, x - dx * k + px, y - dy * k + py, water)
        c.drawLine(x, y, x - dx * k - px, y - dy * k - py, water)
    }

    /** Pop-in bounce: shoots up past full size, settles back. */
    private fun bounce(age: Long, dur: Long): Float {
        val t = (age.toFloat() / dur).coerceIn(0f, 1f)
        return if (t < 0.6f) 0.3f + 1.0f * (t / 0.6f) else 1.3f - 0.3f * ((t - 0.6f) / 0.4f)
    }

    /** Big flashing praise word, candy-crush style, with sparkles flying out. */
    private fun drawPraise(c: Canvas, w: Float, h: Float, now: Long) {
        val txt = praise ?: return
        val age = now - praiseAt
        val life = if (praiseBig) 1600L else 1100L
        if (age > life) { praise = null; return }
        val sc = bounce(age, 280L)
        val alpha = if (age > life - 300) ((life - age) / 300f).coerceIn(0f, 1f) else 1f
        val y = h * 0.30f
        val size = (if (praiseBig) 44 else 32) * d
        c.save()
        c.scale(sc, sc, w / 2, y)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 7 * d
        p.color = Color.argb((255 * alpha).toInt(), 74, 20, 140)
        fitText(c, txt, w / 2, y, size, w * 0.9f)
        p.style = Paint.Style.FILL
        val col = neon[((now / 90) % neon.size).toInt()]
        p.color = Color.argb((255 * alpha).toInt(), Color.red(col), Color.green(col), Color.blue(col))
        fitText(c, txt, w / 2, y, size, w * 0.9f)
        c.restore()
        p.typeface = Typeface.DEFAULT_BOLD
        // sparkles bursting out from the word
        if (praiseBig) {
            val k = (age / 700f).coerceIn(0f, 1f)
            for (i in 0 until 12) {
                val a = i * 0.5236f
                val dist = (40 + 140 * k) * d
                val sx = w / 2 + kotlin.math.cos(a) * dist
                val sy = y - 12 * d + kotlin.math.sin(a) * dist * 0.6f
                val nc = neon[i % neon.size]
                p.color = Color.argb((255 * (1f - k) * alpha).toInt(), Color.red(nc), Color.green(nc), Color.blue(nc))
                star(c, sx, sy, (6 - 3 * k) * d)
            }
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, rad: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val a = -1.5708f + i * 0.6283f
            val rr = if (i % 2 == 0) rad else rad * 0.45f
            val px = x + kotlin.math.cos(a) * rr
            val py = y + kotlin.math.sin(a) * rr
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        c.drawPath(starPath, p)
    }

    /** 1-3 stars fly in one after another above the LEVEL CLEARED message. */
    private fun drawStars(c: Canvas, w: Float, h: Float, now: Long) {
        val y = h * 0.4f - 64 * d
        for (i in 0 until 3) {
            val age = now - starsAt - 300L * i
            if (age < 0) continue
            val sc = bounce(age, 320L)
            val x = w / 2 + (i - 1) * 62 * d
            val rad = (if (i == 1) 30 else 24) * d * sc
            p.style = Paint.Style.FILL
            p.color = if (i < stars) Color.parseColor("#FFD600") else Color.argb(120, 60, 60, 80)
            star(c, x, y - (if (i == 1) 8 * d else 0f), rad)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = if (i < stars) Color.parseColor("#FF6F00") else Color.argb(160, 200, 200, 220)
            star(c, x, y - (if (i == 1) 8 * d else 0f), rad)
            p.style = Paint.Style.FILL
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

        // live style-bonus tracker: what this run can still earn
        p.textAlign = Paint.Align.LEFT
        p.textSize = 11 * d
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        p.color = if (cleanRun) Color.parseColor("#76FF03") else Color.argb(120, 255, 255, 255)
        val cleanTxt = if (cleanRun) "CLEAN \u2713" else "CLEAN \u2717"
        c.drawText(cleanTxt, 14 * d, ceilY + 130 * d, p)
        val x2 = 14 * d + p.measureText(cleanTxt) + 10 * d
        val direct = currentsVisited == 0
        p.color = if (direct) Color.parseColor("#FFD54F") else Color.argb(120, 255, 255, 255)
        val directTxt = if (direct) "DIRECT \u2713" else "DIRECT \u2717"
        c.drawText(directTxt, x2, ceilY + 130 * d, p)
        val x3 = x2 + p.measureText(directTxt) + 10 * d
        p.color = if (currentsTotal > 0 && currentsVisited >= currentsTotal) Color.parseColor("#80D8FF") else Color.WHITE
        c.drawText("CURRENTS $currentsVisited/$currentsTotal", x3, ceilY + 130 * d, p)
        p.clearShadowLayer()

        // ---- right column: slot reels, (close button is its own window), lives ----
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
        p.color = Color.parseColor("#FF1744")
        p.textSize = 18 * d
        c.drawText("♥".repeat(lives.coerceIn(0, 3)) + "♡".repeat((3 - lives).coerceIn(0, 3)), w - 14 * d, ceilY + 104 * d, p)

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

        drawPraise(c, w, h, now)

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
            if (stars > 0) drawStars(c, w, h, now)
        }
    }
}
