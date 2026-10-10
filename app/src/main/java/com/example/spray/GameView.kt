package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the whole game in one view (sea, maze, currents, shark, turtle, effects, HUD, controls)
 * and turns touches into input. Slingshot: touch anywhere, pull back, let go.
 */
class GameView(ctx: Context, private val g: Game) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val water = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint()
    private val path = Path()
    private val starPath = Path()
    private val rect = RectF()
    private val src = Rect()
    private val clip = Path()
    private val photo: Bitmap? = try {
        BitmapFactory.decodeFile(File(ctx.filesDir, "beto.png").path)
    } catch (e: Exception) {
        null
    }
    private val neon = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF4081"), Color.parseColor("#00E5FF"),
        Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )

    // slingshot touch state: put a finger down anywhere, pull back, let go
    private var aimPtr = -1
    private var aimSX = 0f
    private var aimSY = 0f
    private var aimTX = 0f
    private var aimTY = 0f
    private val maxPull get() = 110 * d
    private val deadPull get() = 14 * d

    var onResize: ((Int, Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0A3D62"), Color.parseColor("#06274A"), Color.parseColor("#031428")),
            null, Shader.TileMode.CLAMP)
        onResize?.invoke(w, h)
    }

    // ============================================================
    // touch
    // ============================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (aimPtr == -1) {
                    aimPtr = id
                    aimSX = x
                    aimSY = y
                    aimTX = x
                    aimTY = y
                    updateAim()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(aimPtr)
                if (i >= 0) {
                    aimTX = e.getX(i)
                    aimTY = e.getY(i)
                    updateAim()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                val id = if (cancel) -2 else e.getPointerId(e.actionIndex)
                if (id == aimPtr || cancel) {
                    // let go = launch (dragging back inside the small circle cancels)
                    if (!cancel && g.aiming) g.launchQueued = true
                    aimPtr = -1
                    g.aiming = false
                }
            }
        }
        return true
    }

    /** Pull vector = where you touched minus where your finger is now. He launches the opposite way you drag. */
    private fun updateAim() {
        val dx = aimSX - aimTX
        val dy = aimSY - aimTY
        val len = hypot(dx, dy)
        if (len < deadPull) {
            g.aiming = false
            return
        }
        g.aiming = true
        g.aimDX = dx / len
        g.aimDY = dy / len
        g.aimPower = ((len - deadPull) / (maxPull - deadPull)).coerceIn(0f, 1f)
    }

    // ============================================================
    // drawing
    // ============================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        if (!g.started) return

        // screen shake on big moments
        val sAge = now - g.shakeAt
        val shaking = g.shakeAt != 0L && sAge < 320L
        if (shaking) {
            val a = g.shakeAmp * (1f - sAge / 320f)
            c.save()
            c.translate((Math.random().toFloat() - 0.5f) * 2 * a, (Math.random().toFloat() - 0.5f) * 2 * a)
        }
        drawLightRays(c, w, h, now)
        drawFish(c)
        drawBubbles(c)
        drawWater(c, now)
        drawWeeds(c, now)
        drawWalls(c, now)
        drawPickups(c, now)
        drawTargets(c, now)
        for (s in g.sharks) drawShark(c, s, now)
        drawAimPreview(c, now)
        drawTurtle(c, now)
        drawParticles(c)
        if (shaking) c.restore()
        drawHud(c, w, now)
        drawOcto(c, w, now)
        drawControls(c, now)
        drawPraise(c, w, h, now)
        drawMessages(c, w, h, now)
        if (g.flash > 0f) {
            val fc = if (g.flashColor != 0) g.flashColor else Color.WHITE
            p.style = Paint.Style.FILL
            p.color = Color.argb((180 * g.flash).toInt().coerceIn(0, 255), Color.red(fc), Color.green(fc), Color.blue(fc))
            c.drawRect(0f, 0f, w, h, p)
        }
        if (w < h) {
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = 16 * d
            c.drawText("Turn your phone sideways for more room!", w / 2, h * 0.55f, p)
        }
    }

    private fun drawLightRays(c: Canvas, w: Float, h: Float, now: Long) {
        p.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val sway = sin(now / 2400.0 + i * 1.7).toFloat() * 40 * d
            val x = w * (0.15f + 0.23f * i) + sway
            path.reset()
            path.moveTo(x - 20 * d, 0f)
            path.lineTo(x + 30 * d, 0f)
            path.lineTo(x + 140 * d, h)
            path.lineTo(x + 40 * d, h)
            path.close()
            p.color = Color.argb(18, 180, 230, 255)
            c.drawPath(path, p)
        }
    }

    private fun drawFish(c: Canvas) {
        p.style = Paint.Style.FILL
        for (f in g.fish) {
            val dir = if (f.vx >= 0) 1f else -1f
            p.color = Color.argb(150, Color.red(f.color), Color.green(f.color), Color.blue(f.color))
            c.drawOval(f.x - f.size, f.y - f.size * 0.5f, f.x + f.size, f.y + f.size * 0.5f, p)
            path.reset()
            path.moveTo(f.x - dir * f.size * 0.8f, f.y)
            path.lineTo(f.x - dir * f.size * 1.6f, f.y - f.size * 0.5f)
            path.lineTo(f.x - dir * f.size * 1.6f, f.y + f.size * 0.5f)
            path.close()
            c.drawPath(path, p)
            p.color = Color.argb(180, 0, 0, 0)
            c.drawCircle(f.x + dir * f.size * 0.5f, f.y - f.size * 0.1f, f.size * 0.12f, p)
        }
    }

    private fun drawBubbles(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = Color.argb(110, 200, 240, 255)
        for (b in g.bubbles) c.drawCircle(b.x, b.y, b.rad, p)
        p.style = Paint.Style.FILL
    }

    private fun zoneColor(kind: Int): IntArray = when (kind) {
        WaterZone.UP -> intArrayOf(0, 229, 255)
        WaterZone.SIDE -> intArrayOf(29, 233, 182)
        WaterZone.RIP -> intArrayOf(255, 82, 82)
        else -> intArrayOf(179, 136, 255)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val k = 9 * d
        val qx = -dy * k
        val qy = dx * k
        c.drawLine(x, y, x - dx * k + qx, y - dy * k + qy, water)
        c.drawLine(x, y, x - dx * k - qx, y - dy * k - qy, water)
    }

    /** Currents: tinted patches with streaks sliding along the flow and a big arrow. */
    private fun drawWater(c: Canvas, now: Long) {
        for (z in g.zones) {
            val pw = z.power
            if (pw < 0.03f) continue
            val col = zoneColor(z.kind)
            water.style = Paint.Style.FILL
            water.color = Color.argb((40 * pw).toInt(), col[0], col[1], col[2])
            if (z.kind == WaterZone.WHIRL) {
                c.drawCircle(z.cx, z.cy, z.rad, water)
                water.style = Paint.Style.STROKE
                water.strokeWidth = 3 * d
                water.strokeCap = Paint.Cap.ROUND
                val turn = (now % 100000L) / 1000f * 160f * z.spin
                for (i in 0 until 4) {
                    val rr = z.rad * (0.3f + 0.18f * i)
                    rect.set(z.cx - rr, z.cy - rr, z.cx + rr, z.cy + rr)
                    water.color = Color.argb((150 * pw).toInt(), col[0], col[1], col[2])
                    c.drawArc(rect, turn * (1.4f - 0.15f * i) + i * 72f, 70f, false, water)
                    c.drawArc(rect, turn * (1.4f - 0.15f * i) + i * 72f + 180f, 70f, false, water)
                }
                continue
            }
            c.drawRect(z.l, z.t, z.r, z.b, water)
            c.save()
            c.clipRect(z.l, z.t, z.r, z.b)
            water.style = Paint.Style.STROKE
            water.strokeWidth = 2.5f * d
            water.strokeCap = Paint.Cap.ROUND
            water.color = Color.argb((150 * pw).toInt(), col[0], col[1], col[2])
            val along = 44 * d
            val across = 20 * d
            val dash = 16 * d
            val shift = (now % 100000L) * (z.speed / 16.67f) % along
            if (z.dy != 0f) {
                var x = z.l + across / 2
                var lane = 0
                while (x < z.r) {
                    val st = if (lane % 2 == 0) 0f else along / 2
                    var y = if (z.dy > 0f) z.t - along + (shift + st) % along else z.t - along + (along - (shift + st) % along)
                    while (y < z.b + along) {
                        c.drawLine(x, y, x, y + dash, water)
                        y += along
                    }
                    x += across
                    lane++
                }
            } else {
                var y = z.t + across / 2
                var lane = 0
                while (y < z.b) {
                    val st = if (lane % 2 == 0) 0f else along / 2
                    var x = if (z.dx > 0f) z.l - along + (shift + st) % along else z.l - along + (along - (shift + st) % along)
                    while (x < z.r + along) {
                        c.drawLine(x, y, x + dash, y, water)
                        x += along
                    }
                    y += across
                    lane++
                }
            }
            c.restore()
            val ax = (z.l + z.r) / 2f
            val ay = (z.t + z.b) / 2f
            val len = 16 * d
            water.strokeWidth = 4 * d
            water.color = Color.argb((220 * pw).toInt(), 255, 255, 255)
            c.drawLine(ax - z.dx * len, ay - z.dy * len, ax + z.dx * len, ay + z.dy * len, water)
            arrowHead(c, ax + z.dx * len, ay + z.dy * len, z.dx, z.dy)
        }
    }

    /** Seaweed hiding spots: fronds sway, and part when Betito is inside. */
    private fun drawWeeds(c: Canvas, now: Long) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (wd in g.weeds) {
            val inside = g.cx > wd.l && g.cx < wd.r && g.cy > wd.t && g.cy < wd.b
            p.style = Paint.Style.FILL
            p.color = Color.argb(40, 76, 175, 80)
            rect.set(wd.l, wd.t, wd.r, wd.b)
            c.drawRoundRect(rect, 10 * d, 10 * d, p)
            p.style = Paint.Style.STROKE
            for (i in 0 until 6) {
                val bx = wd.l + (wd.r - wd.l) * (i + 0.5f) / 6f
                val part = if (inside) (if (bx < g.cx) -10 * d else 10 * d) else 0f
                val sway = sin(now / 500.0 + wd.seed + i).toFloat() * 8 * d + part
                p.strokeWidth = (5 - (i % 2)) * d
                p.color = if (i % 2 == 0) Color.argb(230, 46, 125, 50) else Color.argb(230, 102, 187, 106)
                path.reset()
                path.moveTo(bx, wd.b)
                path.quadTo(bx - sway, (wd.t + wd.b) / 2f, bx + sway, wd.t - 4 * d)
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    /** Walls are underwater rock. Pillars show Beto's photo if one was chosen. */
    private fun drawWalls(c: Canvas, now: Long) {
        for (w in g.blocks) {
            if (!w.alive) continue
            rect.set(w.l, w.t, w.r, w.b)
            p.style = Paint.Style.FILL
            p.color = if (w.pillar) Color.parseColor("#6D4C41") else Color.parseColor("#4A3B5C")
            c.drawRoundRect(rect, 5 * d, 5 * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = if (w.pillar) Color.parseColor("#A1887F") else Color.parseColor("#8E7CC3")
            c.drawRoundRect(rect, 5 * d, 5 * d, p)
            p.style = Paint.Style.FILL
            if (w.pillar && photo != null) {
                val size = min(w.r - w.l, w.b - w.t) - 6 * d
                val mx = (w.l + w.r) / 2f
                val my = (w.t + w.b) / 2f
                clip.reset()
                clip.addCircle(mx, my, size / 2, Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                src.set(0, 0, photo.width, photo.height)
                rect.set(mx - size / 2, my - size / 2, mx + size / 2, my + size / 2)
                c.drawBitmap(photo, src, rect, null)
                c.restore()
            }
        }
        // the exit: a glowing door in the right wall
        val pulse = 0.6f + 0.4f * sin(now / 220.0).toFloat()
        for (i in 0 until 4) {
            p.color = Color.argb(((90 * pulse).toInt() - i * 18).coerceIn(0, 255), 118, 255, 3)
            c.drawRect(g.exitX - (i + 1) * 14 * d, g.exitGapT, g.exitX + 30 * d, g.exitGapB, p)
        }
        p.color = Color.parseColor("#76FF03")
        val my = (g.exitGapT + g.exitGapB) / 2f
        path.reset()
        path.moveTo(g.exitX + 14 * d, my)
        path.lineTo(g.exitX - 2 * d, my - 10 * d)
        path.lineTo(g.exitX - 2 * d, my + 10 * d)
        path.close()
        c.drawPath(path, p)
    }

    private fun drawPickups(c: Canvas, now: Long) {
        for (pk in g.pickups) {
            val bob = sin(now / 300.0 + pk.seed).toFloat() * 3 * d
            val x = pk.x
            val y = pk.y + bob
            // soft glow so they pop against the water
            p.style = Paint.Style.FILL
            p.color = Color.argb(50, 255, 255, 255)
            c.drawCircle(x, y, 16 * d, p)
            when (pk.type) {
                Game.P_APPLE -> {
                    p.color = Color.parseColor("#E53935")
                    c.drawCircle(x, y + 1 * d, 9 * d, p)
                    p.color = Color.parseColor("#43A047")
                    c.drawOval(x, y - 12 * d, x + 7 * d, y - 6 * d, p)
                }
                else -> {
                    p.color = Color.parseColor("#FFEB3B")
                    c.drawCircle(x, y, 10 * d, p)
                    p.color = Color.parseColor("#F57F17")
                    path.reset()
                    path.moveTo(x + 2 * d, y - 8 * d)
                    path.lineTo(x - 5 * d, y + 1 * d)
                    path.lineTo(x, y + 1 * d)
                    path.lineTo(x - 2 * d, y + 8 * d)
                    path.lineTo(x + 5 * d, y - 1 * d)
                    path.lineTo(x, y - 1 * d)
                    path.close()
                    c.drawPath(path, p)
                }
            }
        }
    }

    /** Buoys: hit one to flip its current. A faint dashed line links each buoy to its current. */
    private fun drawTargets(c: Canvas, now: Long) {
        for (t in g.targets) {
            val z = t.zone
            val col = zoneColor(z.kind)
            val hitAge = now - t.hitAt
            // link line to the current it controls
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.pathEffect = dash
            p.color = Color.argb(if (g.aiming) 110 else 55, col[0], col[1], col[2])
            c.drawLine(t.x, t.y, z.cx, z.cy, p)
            p.pathEffect = null
            val bob = sin(now / 350.0 + t.x).toFloat() * 2 * d
            val y = t.y + bob
            val pop = if (t.hitAt != 0L && hitAge < 400L) 1f + 0.5f * (1f - hitAge / 400f) else 1f
            val rr = g.targetR * pop
            // glow
            p.style = Paint.Style.FILL
            val pulse = 0.5f + 0.5f * sin(now / 300.0 + t.y).toFloat()
            p.color = Color.argb((40 + 50 * pulse).toInt(), col[0], col[1], col[2])
            c.drawCircle(t.x, y, rr * 1.7f, p)
            // red and white buoy rings
            p.color = Color.WHITE
            c.drawCircle(t.x, y, rr, p)
            p.color = Color.rgb(col[0], col[1], col[2])
            c.drawCircle(t.x, y, rr * 0.72f, p)
            p.color = Color.WHITE
            c.drawCircle(t.x, y, rr * 0.42f, p)
            p.color = Color.rgb(col[0], col[1], col[2])
            c.drawCircle(t.x, y, rr * 0.18f, p)
            // flip arrows over the top
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb(220, 255, 255, 255)
            rect.set(t.x - rr * 1.35f, y - rr * 1.35f, t.x + rr * 1.35f, y + rr * 1.35f)
            val spin = (now % 4000L) / 4000f * 360f
            c.drawArc(rect, spin, 100f, false, p)
            c.drawArc(rect, spin + 180f, 100f, false, p)
            p.style = Paint.Style.FILL
        }
    }

    private val dash = DashPathEffect(floatArrayOf(6f, 6f), 0f)

    /** A patrolling shark. He bumps shots off course; he doesn't eat Betito. */
    private fun drawShark(c: Canvas, s: Shark, now: Long) {
        val u = 16 * d
        val bumped = now - s.bumpAt < 500L
        c.save()
        c.translate(s.x, s.y)
        c.rotate(s.heading)
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
        // a goofy grin instead of teeth: he's a nuisance, not a killer
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.08f * u
        rect.set(0.45f * u, 0.0f, 0.95f * u, 0.35f * u)
        c.drawArc(rect, 20f, 120f, false, p)
        p.style = Paint.Style.FILL
        c.restore()
        if (bumped) {
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 14 * d
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            p.color = Color.parseColor("#FFD54F")
            c.drawText("BONK", s.x, s.y - 22 * d, p)
            p.clearShadowLayer()
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Lil Betito seen from above (head and legs tuck in when he bumps something). */
    private fun drawTurtle(c: Canvas, now: Long) {
        val u = g.r * 1.1f
        val bt = now - g.bumpAt
        val tuck = when {
            g.bumpAt == 0L -> 0f
            bt < 120L -> 1f
            bt < 520L -> { val k = (bt - 120L) / 400f; 1f - k * k * (3f - 2f * k) }
            else -> 0f
        }
        val out = 1f - tuck
        val alpha = 255
        c.save()
        c.translate(g.cx, g.cy)
        // glow: yellow when boosted, cyan otherwise
        val boosted = now < g.speedUntil
        p.style = Paint.Style.FILL
        p.color = if (boosted) Color.argb(110, 255, 235, 59) else Color.argb(70, 0, 229, 255)
        c.drawCircle(0f, 0f, 1.9f * u, p)
        c.rotate(g.heading)
        val kt = now - g.kickAt
        val sq = if (g.kickAt == 0L || kt > 180L) 0f else 1f - kt / 180f
        c.scale(1f + 0.35f * sq, 1f - 0.25f * sq)
        val flap = sin(now / 90.0).toFloat() * 18f * out
        p.color = Color.argb(alpha, 124, 179, 66)
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            leg(c, lerp(0.15f * u, 0.34f * u, out), s * lerp(0.2f * u, 0.66f * u, out), s * (40f + flap), u)
            leg(c, lerp(-0.15f * u, -0.34f * u, out), s * lerp(0.2f * u, 0.60f * u, out), s * (-40f - flap), u)
        }
        path.reset()
        path.moveTo(-0.5f * u, -0.08f * u)
        path.lineTo(lerp(-0.5f * u, -0.82f * u, out), 0f)
        path.lineTo(-0.5f * u, 0.08f * u)
        path.close()
        c.drawPath(path, p)
        val hx = lerp(0.35f * u, 0.80f * u, out)
        p.color = Color.argb(alpha, 156, 204, 101)
        c.drawCircle(hx, 0f, 0.2f * u, p)
        if (out > 0.5f) {
            p.color = Color.argb(alpha, 0, 0, 0)
            c.drawCircle(hx + 0.07f * u, -0.08f * u, 0.035f * u, p)
            c.drawCircle(hx + 0.07f * u, 0.08f * u, 0.035f * u, p)
        }
        p.color = Color.argb(alpha, 46, 125, 50)
        c.drawOval(-0.62f * u, -0.52f * u, 0.62f * u, 0.52f * u, p)
        p.color = Color.argb(alpha, 102, 187, 106)
        c.drawOval(-0.52f * u, -0.43f * u, 0.52f * u, 0.43f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.06f * u
        p.color = Color.argb(alpha, 46, 125, 50)
        c.drawOval(-0.22f * u, -0.17f * u, 0.22f * u, 0.17f * u, p)
        c.drawLine(0.22f * u, 0f, 0.5f * u, 0f, p)
        c.drawLine(-0.22f * u, 0f, -0.5f * u, 0f, p)
        p.style = Paint.Style.FILL
        c.restore()
        // live combo counter over his head
        if (g.shotCombo >= 2 && !g.ready && !g.over) {
            val age = now - g.comboAt
            val sc = if (age < 250) 1f + 0.6f * (1f - age / 250f) else 1f
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
            p.textSize = 15 * d * sc
            p.setShadowLayer(4f, 1f, 1f, Color.BLACK)
            p.color = neon[((now / 90) % neon.size).toInt()]
            c.drawText("COMBO x${g.shotCombo}", g.cx, g.cy - 26 * d, p)
            p.clearShadowLayer()
            p.typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun leg(c: Canvas, x: Float, y: Float, angle: Float, u: Float) {
        c.save()
        c.rotate(angle, x, y)
        c.drawOval(x - 0.22f * u, y - 0.12f * u, x + 0.22f * u, y + 0.12f * u, p)
        c.restore()
    }

    private fun drawParticles(c: Canvas) {
        p.style = Paint.Style.FILL
        for (i in 0 until g.pMax) {
            val life = g.pLife[i]
            if (life <= 0f) continue
            val k = (life / g.pMaxLife[i]).coerceIn(0f, 1f)
            val col = g.pColor[i]
            p.color = Color.argb((Color.alpha(col) * k).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(g.px[i], g.py[i], g.pSize[i] * (0.5f + 0.5f * k), p)
        }
    }

    // ============================================================
    // HUD
    // ============================================================

    private fun drawHud(c: Canvas, w: Float, now: Long) {
        val top = g.topInset
        val barB = g.playT - 14 * d
        p.style = Paint.Style.FILL
        p.color = Color.argb(170, 2, 10, 30)
        c.drawRect(0f, 0f, w, barB, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        p.color = Color.WHITE
        p.textSize = 15 * d
        val lv = "HOLE ${g.level}"
        c.drawText(lv, 14 * d, top + 17 * d, p)
        var x = 14 * d + p.measureText(lv) + 12 * d
        // shots vs par, colored like a golf scorecard
        p.textSize = 13 * d
        p.color = when {
            g.shots < g.par -> Color.parseColor("#76FF03")
            g.shots == g.par -> Color.parseColor("#FFEB3B")
            else -> Color.parseColor("#FF9100")
        }
        val st = "SHOT ${g.shots}  PAR ${g.par}"
        c.drawText(st, x, top + 17 * d, p)
        x += p.measureText(st) + 10 * d
        p.textSize = 11 * d
        val end = if (g.levelEndMs != 0L) g.levelEndMs else now
        val secs = ((end - g.levelStartMs) / 1000f).coerceAtLeast(0f)
        p.color = if (secs < g.par * 3f) Color.parseColor("#80D8FF") else Color.WHITE
        val tm = "%.1fs".format(secs)
        c.drawText(tm, x, top + 17 * d, p)
        x += p.measureText(tm) + 10 * d
        if (g.bestCombo >= 2) {
            p.color = neon[((now / 150) % neon.size).toInt()]
            c.drawText("BEST COMBO x${g.bestCombo}", x, top + 17 * d, p)
        }

        p.textSize = 11 * d
        p.color = Color.parseColor("#FFEB3B")
        val sc = "SCORE ${g.score}   BEST ${g.best}"
        c.drawText(sc, 14 * d, top + 32 * d, p)
        p.color = if (g.shots >= g.maxShots - 2) Color.parseColor("#FF5252") else Color.argb(150, 255, 255, 255)
        p.textSize = 10 * d
        c.drawText("MAX ${g.maxShots}", 14 * d + p.measureText(sc) + 30 * d, top + 32 * d, p)
        p.clearShadowLayer()

        // slot reels, top right (the close button sits at the far right)
        val bs = 24 * d
        val gap = 4 * d
        val rx0 = w - 60 * d - 3 * bs - 2 * gap
        for (i in 0 until 3) {
            val x0 = rx0 + i * (bs + gap)
            rect.set(x0, top + 6 * d, x0 + bs, top + 6 * d + bs)
            p.style = Paint.Style.FILL
            p.color = Color.argb(220, 25, 10, 40)
            c.drawRoundRect(rect, 5 * d, 5 * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = if (g.reelResolving) {
                if ((now / 120) % 2 == 0L) Color.parseColor("#FFD54F") else Color.WHITE
            } else Color.parseColor("#B388FF")
            c.drawRoundRect(rect, 5 * d, 5 * d, p)
            p.style = Paint.Style.FILL
            val s = g.reels[i]
            if (s >= 0) symbol(c, s, rect.centerX(), rect.centerY(), bs)
            else {
                p.color = Color.argb(90, 255, 255, 255)
                p.textAlign = Paint.Align.CENTER
                p.textSize = 12 * d
                c.drawText("?", rect.centerX(), rect.centerY() + 4 * d, p)
            }
        }
    }

    /** One slot symbol. 0 cherry, 1 bell, 2 bar, 3 seven, 4 coin. */
    private fun symbol(c: Canvas, s: Int, x: Float, y: Float, size: Float) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        when (s) {
            0 -> {
                p.color = Color.parseColor("#E53935")
                c.drawCircle(x - size * 0.18f, y + size * 0.15f, size * 0.17f, p)
                c.drawCircle(x + size * 0.2f, y + size * 0.2f, size * 0.17f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Color.parseColor("#2E7D32")
                c.drawLine(x - size * 0.18f, y + size * 0.05f, x + size * 0.12f, y - size * 0.32f, p)
                c.drawLine(x + size * 0.2f, y + size * 0.1f, x + size * 0.12f, y - size * 0.32f, p)
                p.style = Paint.Style.FILL
            }
            1 -> {
                p.color = Color.parseColor("#FFD54F")
                path.reset()
                path.moveTo(x - size * 0.32f, y + size * 0.2f)
                path.quadTo(x - size * 0.28f, y - size * 0.35f, x, y - size * 0.35f)
                path.quadTo(x + size * 0.28f, y - size * 0.35f, x + size * 0.32f, y + size * 0.2f)
                path.close()
                c.drawPath(path, p)
            }
            2 -> {
                p.color = Color.WHITE
                c.drawRect(x - size * 0.38f, y - size * 0.16f, x + size * 0.38f, y + size * 0.16f, p)
                p.color = Color.BLACK
                p.textSize = size * 0.28f
                c.drawText("BAR", x, y + size * 0.1f, p)
            }
            3 -> {
                p.color = Color.parseColor("#FF1744")
                p.textSize = size * 0.8f
                c.drawText("7", x, y + size * 0.28f, p)
            }
            else -> {
                p.color = Color.parseColor("#FFC107")
                c.drawCircle(x, y, size * 0.3f, p)
            }
        }
    }

    /** Octo the octopus narrates from the top bar with a speech bubble. */
    private fun drawOcto(c: Canvas, w: Float, now: Long) {
        val ox = w * 0.47f
        val oy = g.topInset + 18 * d
        val bobY = sin(now / 400.0).toFloat() * 2 * d
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 3 * d
        p.color = Color.parseColor("#AB47BC")
        for (k in 0 until 4) {
            val bx = ox - 9 * d + k * 6 * d
            val wig = sin(now / 200.0 + k).toFloat() * 4 * d
            path.reset()
            path.moveTo(bx, oy + 6 * d + bobY)
            path.quadTo(bx + wig, oy + 14 * d + bobY, bx - wig, oy + 20 * d + bobY)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(ox, oy + bobY, 11 * d, p)
        p.color = Color.WHITE
        c.drawCircle(ox - 4 * d, oy - 2 * d + bobY, 3.2f * d, p)
        c.drawCircle(ox + 4 * d, oy - 2 * d + bobY, 3.2f * d, p)
        p.color = Color.BLACK
        c.drawCircle(ox - 3.5f * d, oy - 2 * d + bobY, 1.6f * d, p)
        c.drawCircle(ox + 4.5f * d, oy - 2 * d + bobY, 1.6f * d, p)

        val txt = g.octoText ?: return
        val age = now - g.octoAt
        if (age > 2600) return
        val a = if (age > 2200) ((2600 - age) / 400f) else 1f
        p.textSize = 11 * d
        p.typeface = Typeface.DEFAULT_BOLD
        val tw = p.measureText(txt)
        val bx = ox + 18 * d
        val maxW = w - 60 * d - 3 * 28 * d - bx - 10 * d
        val boxW = min(tw + 16 * d, maxW)
        rect.set(bx, oy - 12 * d, bx + boxW, oy + 12 * d)
        p.color = Color.argb((240 * a).toInt(), 255, 255, 255)
        c.drawRoundRect(rect, 10 * d, 10 * d, p)
        path.reset()
        path.moveTo(bx + 2 * d, oy - 4 * d)
        path.lineTo(bx - 7 * d, oy)
        path.lineTo(bx + 2 * d, oy + 4 * d)
        path.close()
        c.drawPath(path, p)
        p.color = Color.argb((255 * a).toInt(), 30, 20, 60)
        p.textAlign = Paint.Align.LEFT
        if (tw > boxW - 16 * d) p.textSize = 11 * d * (boxW - 16 * d) / tw
        c.drawText(txt, bx + 8 * d, oy + 4 * d, p)
    }

    /** Gauge color: green for soft shots, through yellow and orange, to red at full power. Grey while he's moving. */
    private fun powerColor(k: Float): Int {
        if (!g.ready) return Color.rgb(150, 160, 175)
        return when {
            k < 0.33f -> Color.rgb(118, 255, 3)
            k < 0.6f -> Color.rgb(255, 235, 59)
            k < 0.85f -> Color.rgb(255, 145, 0)
            else -> Color.rgb(255, 61, 0)
        }
    }

    private fun aimColor(): Int = powerColor(g.aimPower)

    /** The ready ring around Betito, and the dotted path preview while aiming. */
    private fun drawAimPreview(c: Canvas, now: Long) {
        if (g.over) return
        if (g.ready) {
            val pulse = 0.5f + 0.5f * sin(now / 180.0).toFloat()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb((90 + 90 * pulse).toInt(), 118, 255, 3)
            c.drawCircle(g.cx, g.cy, g.r * 1.7f + pulse * 3 * d, p)
            p.style = Paint.Style.FILL
        }
        if (!g.aiming) return
        // shot gauge: a ring around Betito that fills with power
        val gr = g.r * 2.4f
        rect.set(g.cx - gr, g.cy - gr, g.cx + gr, g.cy + gr)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = 6 * d
        p.color = Color.argb(90, 0, 0, 0)
        c.drawArc(rect, 135f, 270f, false, p)
        val segs = 12
        val filled = (g.aimPower * segs).toInt().coerceIn(0, segs)
        for (i in 0 until segs) {
            val k = (i + 1) / segs.toFloat()
            p.color = if (i < filled || (i == 0 && g.aimPower > 0f)) powerColor(k) else Color.argb(60, 255, 255, 255)
            c.drawArc(rect, 135f + i * 22.5f + 1.5f, 19.5f, false, p)
        }
        // distance ring: how far this power glides in still water (currents change it!)
        if (g.ready) {
            p.strokeWidth = 1.5f * d
            p.pathEffect = dash
            p.color = Color.argb(110, 255, 255, 255)
            c.drawCircle(g.cx, g.cy, g.stillDistance(now), p)
            p.pathEffect = null
        }
        p.style = Paint.Style.FILL
        if (g.previewN == 0) return
        val col = aimColor()
        p.style = Paint.Style.FILL
        for (i in 0 until g.previewN) {
            if (i % 2 == 1) continue
            // dots fade out: you only get to see the start of the shot
            val a = (230 * (1f - i / g.pvMax.toFloat())).toInt().coerceIn(0, 255)
            p.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(g.previewX[i], g.previewY[i], (3.2f - 1.4f * i / g.pvMax) * d, p)
        }
    }

    private fun drawControls(c: Canvas, now: Long) {
        // slingshot band: from where you touched to where your finger is
        if (aimPtr != -1) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(aimSX, aimSY, deadPull, p)
            if (g.aiming) {
                val col = aimColor()
                val pull = hypot(aimSX - aimTX, aimSY - aimTY).coerceAtMost(maxPull)
                val ex = aimSX - g.aimDX * pull
                val ey = aimSY - g.aimDY * pull
                p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = (5 - 2.5f * g.aimPower) * d   // the band thins as it stretches
                p.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col))
                c.drawLine(aimSX, aimSY, ex, ey, p)
                p.style = Paint.Style.FILL
                c.drawCircle(ex, ey, 10 * d, p)
                // power meter
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 12 * d
                p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                val label = if (!g.ready) "WAIT..." else "POWER ${(g.aimPower * 100).toInt()}%"
                c.drawText(label, aimSX, aimSY - deadPull - 8 * d, p)
                p.clearShadowLayer()
            }
            p.style = Paint.Style.FILL
        } else if (now - g.levelStartMs < 6000 && g.shots == 0) {
            // how-to hint: a little animated pull-back
            val k = ((now - g.levelStartMs) % 1400L) / 1400f
            val hx = width * 0.3f
            val hy = height - 70 * d
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.argb(140, 255, 255, 255)
            c.drawLine(hx, hy, hx - 60 * d * k, hy + 10 * d * k, p)
            p.style = Paint.Style.FILL
            p.color = Color.argb(170, 255, 255, 255)
            c.drawCircle(hx - 60 * d * k, hy + 10 * d * k, 9 * d, p)
            p.textAlign = Paint.Align.CENTER
            p.textSize = 11 * d
            c.drawText("TOUCH, PULL BACK, LET GO", hx, hy - 18 * d, p)
        }
    }

    // ============================================================
    // praise, stars, messages
    // ============================================================

    private fun bounce(age: Long, dur: Long): Float {
        val t = (age.toFloat() / dur).coerceIn(0f, 1f)
        return if (t < 0.6f) 0.3f + 1.0f * (t / 0.6f) else 1.3f - 0.3f * ((t - 0.6f) / 0.4f)
    }

    private fun fitText(c: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    private fun drawPraise(c: Canvas, w: Float, h: Float, now: Long) {
        val txt = g.praise ?: return
        val age = now - g.praiseAt
        val life = if (g.praiseBig) 1600L else 1100L
        if (age > life) return
        val sc = bounce(age, 280L)
        val alpha = if (age > life - 300) ((life - age) / 300f).coerceIn(0f, 1f) else 1f
        val y = h * 0.42f
        val size = (if (g.praiseBig) 40 else 28) * d
        c.save()
        c.scale(sc, sc, w / 2, y)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 7 * d
        p.color = Color.argb((255 * alpha).toInt(), 74, 20, 140)
        fitText(c, txt, w / 2, y, size, w * 0.8f)
        p.style = Paint.Style.FILL
        val col = neon[((now / 90) % neon.size).toInt()]
        p.color = Color.argb((255 * alpha).toInt(), Color.red(col), Color.green(col), Color.blue(col))
        fitText(c, txt, w / 2, y, size, w * 0.8f)
        c.restore()
        p.typeface = Typeface.DEFAULT_BOLD
        if (g.praiseBig) {
            val k = (age / 700f).coerceIn(0f, 1f)
            for (i in 0 until 12) {
                val a = i * 0.5236f
                val dist = (40 + 160 * k) * d
                val nc = neon[i % neon.size]
                p.color = Color.argb((255 * (1f - k) * alpha).toInt(), Color.red(nc), Color.green(nc), Color.blue(nc))
                star(c, w / 2 + cos(a) * dist, y - 12 * d + sin(a) * dist * 0.5f, (6 - 3 * k) * d)
            }
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, rad: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val a = -1.5708f + i * 0.6283f
            val rr = if (i % 2 == 0) rad else rad * 0.45f
            val sx = x + cos(a) * rr
            val sy = y + sin(a) * rr
            if (i == 0) starPath.moveTo(sx, sy) else starPath.lineTo(sx, sy)
        }
        starPath.close()
        c.drawPath(starPath, p)
    }

    private fun drawMessages(c: Canvas, w: Float, h: Float, now: Long) {
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        val b = g.banner
        if (b != null) {
            p.color = Color.WHITE
            p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
            fitText(c, b, w / 2, g.playT + 22 * d, 15 * d, w * 0.6f)
            p.clearShadowLayer()
        }
        val pu = g.popup
        if (pu != null) {
            val age = now - g.popupAt
            if (age < 900) {
                p.color = Color.argb((255 * (1f - age / 900f)).toInt().coerceIn(0, 255), 128, 216, 255)
                p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                fitText(c, pu, g.cx, g.cy - 30 * d - age / 900f * 20 * d, 13 * d, w * 0.5f)
                p.clearShadowLayer()
            }
        }
        val m = g.message
        if (m != null) {
            p.color = Color.WHITE
            p.setShadowLayer(8f, 3f, 3f, Color.BLACK)
            fitText(c, m, w / 2, h * 0.6f, 32 * d, w * 0.8f)
            p.clearShadowLayer()
            if (g.stars > 0) drawStars(c, w, h, now)
        }
    }

    private fun drawStars(c: Canvas, w: Float, h: Float, now: Long) {
        val y = h * 0.6f - 50 * d
        for (i in 0 until 3) {
            val age = now - g.starsAt - 300L * i
            if (age < 0) continue
            val sc = bounce(age, 320L)
            val x = w / 2 + (i - 1) * 58 * d
            val rad = (if (i == 1) 26 else 21) * d * sc
            val yy = y - (if (i == 1) 6 * d else 0f)
            p.style = Paint.Style.FILL
            p.color = if (i < g.stars) Color.parseColor("#FFD600") else Color.argb(120, 60, 60, 80)
            star(c, x, yy, rad)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = if (i < g.stars) Color.parseColor("#FF6F00") else Color.argb(160, 200, 200, 220)
            star(c, x, yy, rad)
            p.style = Paint.Style.FILL
        }
    }
}
