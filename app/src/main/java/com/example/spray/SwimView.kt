package com.example.spray

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * SWIM RUN: a Flappy-Bird x Burrito-Bison side scroller.
 * Tap empty water = flap. Tap a reef friend = a line pulls Betito toward it; reach it to store a boost
 * (bigger when you reach it fast). Double tap = fire the stored boost. Touch rock, floor or ceiling = run over.
 * Crabs and sharks squish you (you slow down), unless you are mid-boost.
 */
class SwimView(ctx: Context, private val prefs: SharedPreferences, private val sfx: Sfx?) : View(ctx) {
    var onQuit: (() -> Unit)? = null

    private val ui = resources.displayMetrics.density   // real screen density (text, touch sizes)
    private val zoom = 0.62f                              // below 1 = zoomed out: more of the world on screen
    private val d = ui * zoom                             // world unit
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rockP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wallP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintP = Paint()
    private val rect = RectF()
    private val shaderM = Matrix()

    // ---------- data ----------
    private class Pillar(val x: Float, val w: Float, val gapTop: Float, val gapBot: Float)
    private class Obj(val kind: Int, val x: Float, var y: Float, val r: Float, val baseY: Float, val amp: Float, val phase: Float) {
        var taken = false
        val hazard get() = kind == K_CRAB || kind == K_SHARK
    }
    private class Pop(val kind: Int, val wx: Float, val y: Float, val at: Long, val rot: Float)
    private class Bub(var x: Float, var y: Float, val r: Float, val v: Float)

    private companion object {
        const val K_JELLY = 0
        const val K_DOLPHIN = 1
        const val K_SEAHORSE = 2
        const val K_CLOWN = 3
        const val K_STAR = 4
        const val K_CRAB = 5
        const val K_SHARK = 6
        // which crochet critter wears each kind (Yarn.drawPal index)
        val palOf = intArrayOf(6, 5, 1, 2, 4, 3, 0)
        // boost power each good friend stores
        val power = floatArrayOf(0.9f, 1.7f, 1.1f, 0.6f, 0.35f)
        val names = arrayOf("JELLYFISH", "DOLPHIN", "SEAHORSE", "CLOWNFISH", "STARFISH")
        const val NONE_TOP = -100000f      // pillar with no top part
        const val NONE_BOT = 100000f       // pillar with no bottom part
        // how hard each friend bounces you off the floor
        val launchMult = floatArrayOf(1.0f, 1.2f, 1.1f, 0.9f, 0f)
        const val READY = 0
        const val RUN = 1
        const val DEAD = 2
    }

    // ---------- tuning knobs (dp per 60 fps frame) ----------
    private val grav get() = 0.30f * d
    private val flapV get() = 6.3f * d
    private val maxFall get() = 9f * d
    private val pullAcc get() = 1.15f * d
    private val ceilH get() = 0f            // the sky is open; only the floor is deadly
    private val floorH get() = 32 * d
    private val br get() = 14 * d            // Betito's hit radius
    private val spriteH get() = 54 * d

    // ---------- run state ----------
    private var state = READY
    private var worldX = 0f
    private var bx = 0f
    private var by = 0f
    private var ovx = 0f
    private var vy = 0f
    private var extra = 0f           // speed on top of the base, from boosts; decays
    private var slowMs = 0f
    private var boostMs = 0f
    private var invulnMs = 0f
    private var shakeMs = 0f
    private var stored = 0f          // stored boost strength (0 = none)
    private var storedFrom = -1
    private var tether: Obj? = null
    private var tetherMs = 0f
    private var bonusM = 0
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var diedAt = 0L
    private var newBest = false
    private var deathText = ""
    private var best = prefs.getInt("swim_best", 0)

    private val pillars = ArrayList<Pillar>()
    private val objs = ArrayList<Obj>()
    private val pops = ArrayList<Pop>()
    private val bubs = ArrayList<Bub>()
    private var genX = 0f
    private var lastGap = 0f
    private val trailX = FloatArray(16)
    private val trailY = FloatArray(16)
    private var trailN = 0
    private var trailHead = 0
    private val menuR = RectF()
    private var lastNs = 0L
    private var hintMs = 0f

    private val baseX get() = width * 0.28f
    private val camX get() = worldX - baseX
    private fun sxOf(wx: Float) = wx - camX

    private fun meters(): Int = max(0, ((worldX + (bx - baseX)) / (8f * d)).toInt() + bonusM)
    private fun baseSpeed(): Float = (2.6f + 1.8f * min(1f, meters() / 600f)) * d
    private fun speedNow(): Float = (baseSpeed() + extra) * (if (slowMs > 0f) 0.55f else 1f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        tintP.color = Color.argb(110, 3, 20, 40)
        if (w > 0 && h > 0) reset()
    }

    private fun reset() {
        state = READY
        worldX = 0f
        bx = baseX
        by = height * 0.38f
        ovx = 0f
        vy = 0f
        extra = 0f
        slowMs = 0f
        boostMs = 0f
        invulnMs = 0f
        shakeMs = 0f
        stored = 0f
        storedFrom = -1
        tether = null
        bonusM = 0
        lastTapAt = 0L
        hintMs = 0f
        trailN = 0
        trailHead = 0
        pillars.clear()
        objs.clear()
        pops.clear()
        bubs.clear()
        for (i in 0 until 18) {
            bubs.add(Bub(Random.nextFloat() * width, Random.nextFloat() * height, (2 + Random.nextFloat() * 4) * d, 0.3f + Random.nextFloat() * 0.5f))
        }
        // a jellyfish pad waiting on the floor so the "dive at it" idea is obvious
        val fy = height - floorH - 28 * d * 0.6f
        objs.add(Obj(K_JELLY, width * 0.50f, fy, 28 * d, fy, 0f, 0f))
        genX = width * 0.95f
        lastGap = 0f
        genAhead()
    }

    // ============================================================
    // world generation
    // ============================================================
    private fun genAhead() {
        val floorY = height - floorH
        while (genX < camX + width + 500 * d) {
            val m = max(0f, genX / (8f * d))
            val seg = (330f + Random.nextFloat() * 90f + min(120f, m / 4f)) * d
            // a landing pad on the floor: dive at it, bounce up
            val dolphinChance = 0.08f + min(0.12f, m / 2000f)
            val r = Random.nextFloat()
            val kind = when {
                r < dolphinChance -> K_DOLPHIN
                r < dolphinChance + 0.40f -> K_JELLY
                r < dolphinChance + 0.65f -> K_SEAHORSE
                else -> K_CLOWN
            }
            val pr = 28 * d
            val py = floorY - pr * 0.6f
            objs.add(Obj(kind, genX, py, pr, py, 0f, 0f))
            // a rock rising from the floor (jump it)
            if (m > 40f && Random.nextFloat() < min(0.75f, 0.35f + m / 600f)) {
                val rockH = floorY * (0.14f + 0.30f * min(1f, m / 500f) * Random.nextFloat())
                pillars.add(Pillar(genX + seg * 0.55f, 52 * d, NONE_TOP, floorY - rockH))
            }
            // a hanging rock (stay under it)
            if (m > 150f && Random.nextFloat() < 0.35f) {
                pillars.add(Pillar(genX + seg * 0.82f, 52 * d, floorY * (0.12f + 0.25f * Random.nextFloat()), NONE_BOT))
            }
            // a star up in the sky: the reward for staying high
            if (Random.nextFloat() < 0.5f) {
                val sy = 30 * d + Random.nextFloat() * floorY * 0.35f
                objs.add(Obj(K_STAR, genX + seg * 0.3f, sy, 24 * d, sy, 0f, 0f))
            }
            // trouble: crabs by the pads, sharks in the air
            if (m > 120f && Random.nextFloat() < 0.30f) {
                val cy = floorY - 20 * d
                objs.add(Obj(K_CRAB, genX + seg * 0.25f, cy, 22 * d, cy, 0f, 0f))
            }
            if (m > 300f && Random.nextFloat() < 0.30f) {
                val sy = floorY * (0.25f + 0.30f * Random.nextFloat())
                objs.add(Obj(K_SHARK, genX + seg * 0.5f, sy, 30 * d, sy, 70 * d, Random.nextFloat() * 6f))
            }
            genX += seg
        }
    }

    // ============================================================
    // input
    // ============================================================
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            onTap(e.getX(i), e.getY(i), SystemClock.uptimeMillis())
        }
        return true
    }

    private fun onTap(x: Float, y: Float, now: Long) {
        when (state) {
            READY -> {
                state = RUN
                vy = -flapV
                lastTapAt = now
                lastTapX = x
                lastTapY = y
                sfx?.play("launch", 0.6f, 0L)
                return
            }
            DEAD -> {
                if (now - diedAt > 500L) {
                    if (menuR.contains(x, y)) onQuit?.invoke() else reset()
                }
                return
            }
        }
        // double tap (two quick taps near each other) fires a stored boost
        if (stored > 0f && now - lastTapAt < 260L && hypot(x - lastTapX, y - lastTapY) < 90 * ui) {
            fireBoost(now)
            lastTapAt = 0L
            return
        }
        lastTapAt = now
        lastTapX = x
        lastTapY = y
        val o = pick(x, y)
        if (o != null) {
            tether = o
            tetherMs = 0f
            sfx?.play("tick", 0.8f, 0L)
            return
        }
        tether = null
        vy = -flapV
        sfx?.play("tick", 0.4f, 60L)
    }

    private fun pick(x: Float, y: Float): Obj? {
        var best: Obj? = null
        var bd = 70 * ui
        for (o in objs) {
            if (o.hazard || o.taken) continue
            val ox = sxOf(o.x)
            if (ox < bx - 30 * d || ox > width + 20 * d) continue
            val dd = hypot(ox - x, o.y - y)
            if (dd < bd) {
                bd = dd
                best = o
            }
        }
        return best
    }

    private fun fireBoost(now: Long) {
        val base = baseSpeed()
        extra = min(extra + stored * base, base * 3.5f)
        boostMs = 1600f
        slowMs = 0f
        shakeMs = 220f
        stored = 0f
        storedFrom = -1
        sfx?.play("wee", 0.9f, 0L)
        pops.add(Pop(Yarn.POP_POW, camX + bx + 40 * d, by - 40 * d, now, -8f))
    }

    // ============================================================
    // update
    // ============================================================
    private fun update(dt: Float, now: Long) {
        // background bubbles always drift
        val sp = if (state == RUN) speedNow() else 1f * d
        for (b in bubs) {
            b.x -= (sp * 0.5f + b.v * d) * dt
            b.y -= b.v * 0.4f * d * dt
            if (b.x < -10 * d) {
                b.x = width + 10 * d
                b.y = Random.nextFloat() * height
            }
            if (b.y < -10 * d) b.y = height + 10 * d
        }
        if (state == READY) {
            by = height * 0.38f + sin(now / 380.0).toFloat() * 9 * d
            return
        }
        if (state == DEAD) return

        val dtMs = dt * 16.667f
        hintMs += dtMs
        val base = baseSpeed()
        extra *= 0.992f.pow(dt)
        if (slowMs > 0f) slowMs -= dtMs
        if (boostMs > 0f) boostMs -= dtMs
        if (invulnMs > 0f) invulnMs -= dtMs
        if (shakeMs > 0f) shakeMs -= dtMs
        val speed = speedNow()
        worldX += speed * dt

        // tether: a pulling line toward the friend you pressed
        val t = tether
        if (t != null) {
            tetherMs += dtMs
            val ox = sxOf(t.x)
            val dx = ox - bx
            val dy = t.y - by
            val dist = max(1f, hypot(dx, dy))
            if (t.taken || ox < bx - 90 * d || tetherMs > 2400f) {
                tether = null
            } else {
                ovx += dx / dist * pullAcc * dt
                vy += dy / dist * pullAcc * dt
                val damp = 0.93f.pow(dt)
                ovx *= damp
                vy *= damp
                ovx = ovx.coerceIn(-10 * d, 12 * d)
                vy = vy.coerceIn(-14 * d, 14 * d)
                if (dx > 0f) extra = min(extra + 0.012f * base * dt, base * 3.5f)   // being reeled in feels fast
            }
        }
        if (tether == null) {
            vy = min(vy + grav * dt, maxFall)
            ovx += (baseX - bx) * 0.02f * dt
            ovx *= 0.9f.pow(dt)
        }
        bx = (bx + ovx * dt).coerceIn(width * 0.10f, width * 0.70f)
        by += vy * dt

        // trail
        trailX[trailHead] = camX + bx
        trailY[trailHead] = by
        trailHead = (trailHead + 1) % trailX.size
        trailN = min(trailN + 1, trailX.size)

        genAhead()
        while (pillars.isNotEmpty() && sxOf(pillars[0].x + pillars[0].w) < -200 * d) pillars.removeAt(0)
        val it2 = objs.iterator()
        while (it2.hasNext()) {
            val o = it2.next()
            if (sxOf(o.x) < -200 * d) it2.remove()
            else if (o.kind == K_SHARK) o.y = o.baseY + sin(now * 0.0016 + o.phase).toFloat() * o.amp
        }

        // friends and hazards
        val cur = camX
        for (o in objs) {
            if (o.taken) continue
            val ox = o.x - cur
            if (abs(ox - bx) > 80 * d) continue
            val dist = hypot(ox - bx, o.y - by)
            if (o.hazard) {
                if (dist < br + o.r * 0.8f && invulnMs <= 0f && boostMs <= 0f) squish(o, now)
            } else if (dist < br + o.r * 0.9f) {
                collect(o, speed, base, now)
            }
        }

        // rock, ceiling, floor
        if (by - br < 0f) {
            by = br
            if (vy < 0f) vy = 0f
        }
        if (by + br > height - floorH) die(now, "SPLASHED!")
        for (pl in pillars) {
            val px = sxOf(pl.x)
            if (bx + br * 0.8f > px && bx - br * 0.8f < px + pl.w) {
                if (by - br * 0.8f < pl.gapTop || by + br * 0.8f > pl.gapBot) {
                    die(now, "SQUISHED!")
                    break
                }
            }
        }
        // popups
        val pi = pops.iterator()
        while (pi.hasNext()) if (now - pi.next().at > 800L) pi.remove()
    }

    private fun collect(o: Obj, speed: Float, base: Float, now: Long) {
        o.taken = true
        if (tether === o) tether = null
        val pw = power[o.kind]
        val ratio = (speed / base).coerceIn(1f, 3f)
        val gain = pw * (0.7f + 0.3f * ratio)          // faster arrival = bigger boost
        if (gain > stored) {
            stored = gain
            storedFrom = o.kind
        }
        extra = min(extra + base * 0.25f * pw, base * 3.5f)
        if (o.kind != K_STAR) {
            // landing on a floor friend: the harder you dove in, the higher you fly
            val down = max(vy, 0f)
            val lv = ((5f * d + 0.7f * down + 0.12f * speed) * launchMult[o.kind]).coerceIn(7f * d, 16f * d)
            vy = -lv
            shakeMs = 140f
        }
        if (o.kind == K_STAR) bonusM += 25
        sfx?.play(if (o.kind == K_DOLPHIN) "woo" else "coin", 0.9f, 0L)
        pops.add(Pop(Yarn.POP_BING, o.x, o.y - 30 * d, now, 6f))
    }

    private fun squish(o: Obj, now: Long) {
        extra = 0f
        stored = 0f
        storedFrom = -1
        slowMs = 1300f
        invulnMs = 900f
        shakeMs = 300f
        tether = null
        sfx?.play("squish", 1f, 0L)
        pops.add(Pop(Yarn.POP_BAM, o.x, o.y - 30 * d, now, -6f))
    }

    private fun die(now: Long, text: String) {
        if (state != RUN) return
        state = DEAD
        diedAt = now
        deathText = text
        shakeMs = 350f
        val m = meters()
        newBest = m > best
        if (newBest) {
            best = m
            prefs.edit().putInt("swim_best", best).apply()
        }
        sfx?.play("bonk", 1f, 0L)
        pops.add(Pop(Yarn.POP_WHAM, camX + bx, by - 30 * d, now, 5f))
    }

    // ============================================================
    // drawing
    // ============================================================
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ns = System.nanoTime()
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceIn(0f, 2.5f)
        lastNs = ns
        val now = SystemClock.uptimeMillis()
        update(dt, now)

        c.save()
        if (shakeMs > 0f) c.translate((Random.nextFloat() - 0.5f) * 6 * d, (Random.nextFloat() - 0.5f) * 6 * d)
        drawBackground(c, w, h)
        drawStrips(c, w, h)
        drawPillars(c, w, h)
        drawObjects(c, now)
        drawTether(c, now)
        drawBetito(c, now)
        for (pp in pops) drawPop(c, pp, now)
        c.restore()
        drawHud(c, w, h, now)
        if (state == READY) drawReadyCard(c, w, h)
        if (state == DEAD) drawDeadCard(c, w, h, now)
        postInvalidateOnAnimation()
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.parseColor("#06274A"))
        // slow parallax: the reef painting, mirrored every other copy so it tiles without a seam
        val off = (camX * 0.25f).mod(2f * w)
        for (k in 0..2) {
            val x0 = -off + k * w
            if (x0 > w || x0 + w < 0f) continue
            c.save()
            if (k % 2 == 0) {
                c.translate(x0, 0f)
            } else {
                c.translate(x0 + w, 0f)
                c.scale(-1f, 1f)
            }
            Yarn.drawReef(c, w, h)
            c.restore()
        }
        c.drawRect(0f, 0f, w, h, tintP)
        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 255, 255, 255)
        for (b in bubs) c.drawCircle(b.x, b.y, b.r, p)
        // speed lines when you are flying
        val base = baseSpeed()
        val sp = speedNow()
        if (state == RUN && sp > base * 1.5f) {
            p.color = Color.argb(((sp / base - 1.5f) * 70f).toInt().coerceIn(0, 140), 255, 255, 255)
            p.strokeWidth = 2 * d
            for (i in 0 until 9) {
                val ly = (i * 97 + 31) % max(1, h.toInt()).toFloat()
                val lx = ((i * 211f - camX * 2.2f).mod(w + 200 * d)) - 100 * d
                c.drawLine(lx, ly, lx + sp * 7f, ly, p)
            }
        }
    }

    private fun drawStrips(c: Canvas, w: Float, h: Float) {
        val sh = Yarn.wall
        if (sh != null) {
            shaderM.reset()
            shaderM.setTranslate(-camX, 0f)
            sh.setLocalMatrix(shaderM)
            wallP.shader = sh
        } else {
            wallP.shader = null
            wallP.color = Color.parseColor("#4E342E")
        }
        c.drawRect(0f, h - floorH, w, h, wallP)
        p.style = Paint.Style.FILL
        p.color = Color.argb(120, 0, 0, 0)
        c.drawRect(0f, h - floorH - 3 * d, w, h - floorH, p)
    }

    private fun drawPillars(c: Canvas, w: Float, h: Float) {
        val sh = Yarn.rock
        if (sh != null) {
            shaderM.reset()
            shaderM.setTranslate(-camX, 0f)
            sh.setLocalMatrix(shaderM)
            rockP.shader = sh
        } else {
            rockP.shader = null
            rockP.color = Color.parseColor("#6D4C41")
        }
        for (pl in pillars) {
            val px = sxOf(pl.x)
            if (px > w + 10 * d || px + pl.w < -10 * d) continue
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            if (pl.gapTop > 0f) {
                c.drawRoundRect(px, -30 * d, px + pl.w, pl.gapTop, 14 * d, 14 * d, rockP)
                p.color = Color.argb(150, 20, 10, 5)
                c.drawRoundRect(px, -30 * d, px + pl.w, pl.gapTop, 14 * d, 14 * d, p)
            }
            if (pl.gapBot < h) {
                c.drawRoundRect(px, pl.gapBot, px + pl.w, h + 30 * d, 14 * d, 14 * d, rockP)
                p.color = Color.argb(150, 20, 10, 5)
                c.drawRoundRect(px, pl.gapBot, px + pl.w, h + 30 * d, 14 * d, 14 * d, p)
            }
            p.style = Paint.Style.FILL
        }
    }

    private fun drawObjects(c: Canvas, now: Long) {
        val pulse = 0.5f + 0.5f * sin(now / 220.0).toFloat()
        for (o in objs) {
            if (o.taken) continue
            val ox = sxOf(o.x)
            if (ox < -80 * d || ox > width + 80 * d) continue
            val bob = sin(now / 500.0 + o.x * 0.01).toFloat() * 4 * d
            val oy = o.y + (if (o.kind == K_SHARK || o.kind == K_CRAB) 0f else bob)
            if (!o.hazard) {
                // soft halo: this one can be pressed
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = Color.argb((90 + 90 * pulse).toInt(), 255, 244, 160)
                c.drawCircle(ox, oy, o.r + (6 + 4 * pulse) * d, p)
                p.style = Paint.Style.FILL
                p.color = Color.argb(40, 255, 244, 160)
                c.drawCircle(ox, oy, o.r + 6 * d, p)
            }
            val ok = if (o.kind == K_SHARK) {
                c.save()
                c.translate(ox, oy)
                c.scale(-1f, 1f)          // it swims at you
                val r = Yarn.drawShark(c, 110 * d)
                c.restore()
                r
            } else {
                Yarn.drawPal(c, palOf[o.kind], ox, oy, o.r * 2.3f)
            }
            if (!ok) {
                p.style = Paint.Style.FILL
                p.color = if (o.hazard) Color.parseColor("#E53935") else Color.parseColor("#FFC107")
                c.drawCircle(ox, oy, o.r, p)
            }
        }
    }

    private fun drawTether(c: Canvas, now: Long) {
        val t = tether ?: return
        val ox = sxOf(t.x)
        val oy = t.y
        val dx = ox - bx
        val dy = oy - by
        val dist = max(1f, hypot(dx, dy))
        // dotted line that marches toward the friend, like the pull-back line in the golf holes
        val n = (dist / (14 * d)).toInt().coerceIn(2, 40)
        val march = (now % 400L) / 400f
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val f = ((i + march) / n).coerceIn(0f, 1f)
            p.color = Color.argb((120 + 120 * f).toInt(), 255, 241, 118)
            c.drawCircle(bx + dx * f, by + dy * f, (2 + 2.5f * f) * d, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.argb(150, 255, 241, 118)
        c.drawCircle(ox, oy, t.r + 12 * d, p)
        p.style = Paint.Style.FILL
    }

    private fun drawBetito(c: Canvas, now: Long) {
        // trail (rainbow while boosting)
        if (state != READY) {
            for (i in 0 until trailN) {
                val idx = (trailHead - 1 - i + trailX.size * 2) % trailX.size
                val f = 1f - i / trailX.size.toFloat()
                val sx = sxOf(trailX[idx])
                if (boostMs > 0f) {
                    val hue = ((i * 28 + now / 8) % 360).toFloat()
                    p.color = Color.HSVToColor((200 * f).toInt(), floatArrayOf(hue, 0.85f, 1f))
                } else {
                    p.color = Color.argb((70 * f).toInt(), 200, 240, 255)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(sx, trailY[idx], (5 + 8 * f) * d * (if (boostMs > 0f) 1.4f else 1f), p)
            }
        }
        // glow when a boost is stored
        if (stored > 0f) {
            val pulse = 0.5f + 0.5f * sin(now / 140.0).toFloat()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = Color.argb((140 + 100 * pulse).toInt(), 255, 213, 79)
            c.drawCircle(bx, by, 34 * d + 5 * d * pulse, p)
            p.style = Paint.Style.FILL
        }
        // blink while protected after a squish
        if (invulnMs > 0f && (now / 90L) % 2L == 0L) return
        val rot = (atan2(vy, max(1f, speedNow())) * 57.3f).coerceIn(-35f, 40f)
        val pose = if (vy < -2 * d) 1 else 0
        if (!Yarn.drawPose(c, pose, bx, by, spriteH, rot)) {
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#66BB6A")
            c.drawCircle(bx, by, br + 4 * d, p)
        }
    }

    private fun drawPop(c: Canvas, pp: Pop, now: Long) {
        val age = now - pp.at
        val sc = if (age < 150L) 0.4f + 0.8f * (age / 150f) else 1.2f - 0.2f * ((age - 150L) / 650f)
        val a = if (age > 560L) (255 * (1f - (age - 560L) / 240f)).toInt().coerceIn(0, 255) else 255
        Yarn.drawPop(c, pp.kind, sxOf(pp.wx), pp.y, 86 * d * sc, a, pp.rot)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = align
        p.textSize = size * ui
        p.color = color
        p.setShadowLayer(4f, 1.5f, 1.5f, Color.BLACK)
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, now: Long) {
        text(c, "${meters()} m", 18 * ui, ceilH + 30 * ui, 26f, Color.WHITE, Paint.Align.LEFT)
        text(c, "BEST $best m", 18 * ui, ceilH + 48 * ui, 12f, Color.argb(220, 255, 224, 130), Paint.Align.LEFT)
        if (state != RUN) return
        // speed bar
        val base = baseSpeed()
        val k = (speedNow() / (base * 4.2f)).coerceIn(0f, 1f)
        val bw = 130 * ui
        val bxl = w - bw - 18 * ui
        val byt = ceilH + 14 * ui
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 0, 0, 0)
        rect.set(bxl, byt, bxl + bw, byt + 11 * ui)
        c.drawRoundRect(rect, 6 * ui, 6 * ui, p)
        p.color = if (slowMs > 0f) Color.parseColor("#FF7043") else if (boostMs > 0f) Color.parseColor("#E040FB") else Color.parseColor("#4FC3F7")
        rect.set(bxl, byt, bxl + bw * k, byt + 11 * ui)
        c.drawRoundRect(rect, 6 * ui, 6 * ui, p)
        text(c, if (slowMs > 0f) "SQUISHED! SLOW" else if (boostMs > 0f) "BOOST!!" else "SPEED", bxl + bw / 2f, byt + 30 * ui, 11f, Color.WHITE)
        // stored boost
        if (stored > 0f) {
            val pulse = 0.5f + 0.5f * sin(now / 140.0).toFloat()
            val nm = if (storedFrom in names.indices) names[storedFrom] else "BOOST"
            text(c, "$nm BOOST x${"%.1f".format(stored)}   DOUBLE TAP!", w / 2f, h - floorH - 14 * ui, (14 + 2 * pulse), Color.parseColor("#FFD54F"))
        } else if (hintMs < 12000f) {
            text(c, "PRESS A FRIEND ON THE FLOOR: DIVE, THEN BOUNCE HIGH", w / 2f, h - floorH - 14 * ui, 12f, Color.argb(210, 255, 255, 255))
        }
    }

    private fun drawReadyCard(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(120, 0, 0, 0)
        rect.set(w * 0.2f, h * 0.18f, w * 0.8f, h * 0.44f)
        c.drawRoundRect(rect, 18 * ui, 18 * ui, p)
        text(c, "SWIM RUN", w / 2f, h * 0.18f + 34 * ui, 28f, Color.WHITE)
        text(c, "TAP = SWIM UP.   PRESS A FLOOR FRIEND = DIVE AT IT.", w / 2f, h * 0.18f + 58 * ui, 13f, Color.parseColor("#B3E5FC"))
        text(c, "DIVE FASTER = BOUNCE HIGHER. TOUCH THE FLOOR = SPLASH!", w / 2f, h * 0.18f + 76 * ui, 13f, Color.parseColor("#FFE082"))
        val pulse = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 250.0).toFloat()
        text(c, "TAP TO START", w / 2f, h * 0.62f, 18f + 2 * pulse, Color.WHITE)
    }

    private fun drawDeadCard(c: Canvas, w: Float, h: Float, now: Long) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        text(c, deathText, w / 2f, h * 0.30f, 32f, Color.parseColor("#FF8A65"))
        text(c, "${meters()} m", w / 2f, h * 0.30f + 46 * ui, 40f, Color.WHITE)
        text(c, if (newBest) "NEW BEST!" else "BEST $best m", w / 2f, h * 0.30f + 70 * ui, 16f, Color.parseColor("#FFD54F"))
        val pulse = 0.5f + 0.5f * sin(now / 250.0).toFloat()
        text(c, "TAP TO TRY AGAIN", w / 2f, h * 0.30f + 106 * ui, 18f + 2 * pulse, Color.WHITE)
        val bw = 120 * ui
        val bh = 38 * ui
        menuR.set(w / 2f - bw / 2f, h * 0.30f + 126 * ui, w / 2f + bw / 2f, h * 0.30f + 126 * ui + bh)
        p.color = Color.parseColor("#37474F")
        c.drawRoundRect(menuR, 12 * ui, 12 * ui, p)
        text(c, "MENU", w / 2f, menuR.centerY() + 6 * ui, 16f, Color.WHITE)
    }
}
