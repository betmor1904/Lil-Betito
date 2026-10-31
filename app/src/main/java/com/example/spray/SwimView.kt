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
 * SWIM RUN: Flappy Bird with reef friends.
 * Tap = swim up. Swim through the gaps in the rocks, as far as you can.
 * Press a friend: a line comes out and pulls Betito to it. Grab friends for points and a speed kick.
 * Every 8 rocks you pass is a new LEVEL (faster, tighter gaps, sharks from level 3).
 * Touch a rock or the floor = run over. A shark squishes you (slow, but you live).
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
    private class Pillar(val x: Float, val w: Float, val gapTop: Float, val gapBot: Float) {
        var passed = false
    }
    private class Obj(val kind: Int, val x: Float, var y: Float, val r: Float, val baseY: Float, val amp: Float, val phase: Float) {
        var taken = false
        var hp = 3                 // sharks take 3 smashes
        var flashMs = 0f
        val hazard get() = kind == K_SHARK
    }
    private class Pop(val kind: Int, val wx: Float, val y: Float, val at: Long, val rot: Float, val size: Float = 90f)
    private class Word(val text: String, val wx: Float, val y: Float, val at: Long, val color: Int, val size: Float)
    private class Conf(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, val at: Long, val sz: Float)
    private class Bub(var x: Float, var y: Float, val r: Float, val v: Float)

    private companion object {
        const val K_JELLY = 0
        const val K_DOLPHIN = 1
        const val K_SEAHORSE = 2
        const val K_CLOWN = 3
        const val K_STAR = 4
        const val K_SHARK = 5
        // which crochet critter wears each kind (Yarn.drawPal index)
        val palOf = intArrayOf(6, 5, 1, 2, 4, 0)
        // speed kick each friend gives
        val power = floatArrayOf(0.9f, 1.7f, 1.1f, 0.6f, 0.35f)
        // points each friend is worth
        val ptsOf = intArrayOf(100, 200, 120, 80, 50)
        val wordOf = arrayOf("NICE!", "AWESOME!!", "SWEET!", "NICE!", "STAR!")
        val sizeOf = floatArrayOf(24f, 32f, 26f, 22f, 22f)
        val confOf = intArrayOf(10, 24, 14, 8, 6)
        val popOf = intArrayOf(Yarn.POP_BING, Yarn.POP_ZAP, Yarn.POP_POW, Yarn.POP_BING, Yarn.POP_BING)
        const val PER_LEVEL = 8          // rocks to pass for the next level
        const val READY = 0
        const val RUN = 1
        const val DEAD = 2
    }

    // ---------- tuning knobs (dp per 60 fps frame) ----------
    private val grav get() = 0.30f * d
    private val flapV get() = 6.3f * d
    private val maxFall get() = 9f * d
    private val pullAcc get() = 1.4f * d
    private val floorH get() = 32 * d
    private val br get() = 15 * d            // Betito's hit radius
    private val reachD get() = 230 * d       // you can only press a friend that is this close to Betito
    private val spriteH get() = 54 * d

    // ---------- run state ----------
    private var state = READY
    private var worldX = 0f
    private var bx = 0f
    private var by = 0f
    private var ovx = 0f
    private var vy = 0f
    private var extra = 0f           // speed on top of the base, from friends; fades
    private var slowMs = 0f
    private var invulnMs = 0f
    private var shakeMs = 0f
    private var tether: Obj? = null
    private var tetherMs = 0f
    private var score = 0
    private var passedCount = 0
    private var bannerAt = 0L
    private var lastTapAt = 0L
    private var diedAt = 0L
    private var newBest = false
    private var deathText = ""
    private var best = prefs.getInt("swim_best", 0)
    private var bestScore = prefs.getInt("swim_best_score", 0)
    private var flapPush = 0f
    private var flapAnimMs = 0f
    private var rotS = 0f
    private var hintMs = 0f
    private var hitMs = 0f          // after a shark hit: tumble, then curl up, then swim on
    private var happyMs = 0f        // a happy wave after grabbing a friend
    private var spin = 0f           // roll angle while tumbling / curled
    // thumb slide: touch, then slide right = forward and faster, left = back and slower
    private var steerId = -1
    private var steerX0 = 0f
    private var steerY0 = 0f
    private var steerX = 0f
    private var thumb = 0f          // -1..1 from the slide
    private var thumbS = 0f         // smoothed

    private val pillars = ArrayList<Pillar>()
    private val objs = ArrayList<Obj>()
    private val pops = ArrayList<Pop>()
    private val words = ArrayList<Word>()
    private val confs = ArrayList<Conf>()
    private val bubs = ArrayList<Bub>()
    private var genX = 0f
    private var genCount = 0
    private var lastGap = 0f
    private val trailX = FloatArray(16)
    private val trailY = FloatArray(16)
    private var trailN = 0
    private var trailHead = 0
    private val menuR = RectF()
    private var lastNs = 0L

    private val baseX get() = width * 0.28f
    private val camX get() = worldX - baseX
    private fun sxOf(wx: Float) = wx - camX

    private fun level(): Int = 1 + passedCount / PER_LEVEL
    private fun meters(): Int = max(0, ((worldX + (bx - baseX)) / (8f * d)).toInt())
    private fun baseSpeed(): Float = (2.6f + min(3.2f, 0.4f * (level() - 1))) * d
    private fun speedNow(): Float = (baseSpeed() + extra) * (if (slowMs > 0f) 0.55f else 1f) * (1f + 0.55f * thumbS)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        tintP.color = Color.argb(110, 3, 20, 40)
        if (w > 0 && h > 0) reset()
    }

    private fun reset() {
        state = READY
        worldX = 0f
        bx = baseX
        by = height * 0.5f
        ovx = 0f
        vy = 0f
        extra = 0f
        slowMs = 0f
        invulnMs = 0f
        shakeMs = 0f
        tether = null
        score = 0
        passedCount = 0
        bannerAt = 0L
        lastTapAt = 0L
        flapPush = 0f
        flapAnimMs = 0f
        rotS = 0f
        hintMs = 0f
        hitMs = 0f
        happyMs = 0f
        spin = 0f
        thumbS = 0f
        trailN = 0
        trailHead = 0
        pillars.clear()
        objs.clear()
        pops.clear()
        words.clear()
        confs.clear()
        bubs.clear()
        for (i in 0 until 18) {
            bubs.add(Bub(Random.nextFloat() * width, Random.nextFloat() * height, (2 + Random.nextFloat() * 4) * d, 0.3f + Random.nextFloat() * 0.5f))
        }
        // a friend right ahead so the "press a friend" idea is obvious
        objs.add(Obj(K_JELLY, width * 0.45f, height * 0.40f, 30 * d, height * 0.40f, 0f, 0f))
        genX = width * 1.0f
        genCount = 0
        lastGap = 0f
        genAhead()
    }

    // ============================================================
    // world generation
    // ============================================================
    private fun genAhead() {
        val floorY = height - floorH
        while (genX < camX + width + 500 * d) {
            val lvl = 1 + genCount / PER_LEVEL
            val gapH = (200f - min(70f, 8f * (lvl - 1))) * d
            val top = gapH / 2f + 14 * d
            val bot = floorY - gapH / 2f - 14 * d
            var cy = top + Random.nextFloat() * max(1f, bot - top)
            if (lastGap != 0f) cy = cy.coerceIn(lastGap - 150 * d, lastGap + 150 * d).coerceIn(top, bot)
            lastGap = cy
            pillars.add(Pillar(genX, 52 * d, cy - gapH / 2f, cy + gapH / 2f))
            val spacing = (430f - min(130f, 12f * (lvl - 1))) * d
            placeObjects(genX + spacing * 0.5f, lvl)
            genX += spacing
            genCount++
        }
    }

    private fun placeObjects(mid: Float, lvl: Int) {
        val floorY = height - floorH
        // a friend in the open water between the rocks
        if (Random.nextFloat() < 0.85f) {
            val dolphin = 0.06f + min(0.12f, 0.015f * lvl)
            val r = Random.nextFloat()
            val kind = when {
                r < dolphin -> K_DOLPHIN
                r < dolphin + 0.28f -> K_JELLY
                r < dolphin + 0.45f -> K_SEAHORSE
                r < dolphin + 0.58f -> K_CLOWN
                else -> K_STAR
            }
            val y = 40 * d + Random.nextFloat() * (floorY - 80 * d)
            objs.add(Obj(kind, mid + (Random.nextFloat() - 0.5f) * 140 * d, y, 30 * d, y, 0f, 0f))
        }
        // sharks from level 3
        if (lvl >= 3 && Random.nextFloat() < min(0.5f, 0.2f + 0.05f * lvl)) {
            val y = floorY * (0.25f + 0.5f * Random.nextFloat())
            objs.add(Obj(K_SHARK, mid + (Random.nextFloat() - 0.5f) * 200 * d, y, 30 * d, y, 70 * d, Random.nextFloat() * 6f))
        }
    }

    // ============================================================
    // input
    // ============================================================
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (steerId == -1) {
                    steerId = e.getPointerId(i)
                    steerX0 = x
                    steerY0 = y
                    steerX = x
                    thumb = 0f
                }
                onTap(x, y, SystemClock.uptimeMillis())
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(steerId)
                if (i >= 0) {
                    steerX = e.getX(i)
                    val dx = steerX - steerX0
                    val dz = 14 * ui
                    thumb = if (abs(dx) < dz) 0f else ((if (dx > 0f) dx - dz else dx + dz) / (100 * ui)).coerceIn(-1f, 1f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == steerId) {
                    steerId = -1
                    thumb = 0f
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                steerId = -1
                thumb = 0f
            }
        }
        return true
    }

    private fun onTap(x: Float, y: Float, now: Long) {
        when (state) {
            READY -> {
                state = RUN
                bannerAt = now
                flap()
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
        // press on or near a friend: a line comes out and pulls you to it
        val o = pick(x, y)
        if (o != null) {
            tether = o
            tetherMs = 0f
            sfx?.play("tick", 0.8f, 0L)
            return
        }
        tether = null
        flap()
        sfx?.play("tick", 0.4f, 60L)
    }

    private fun pick(x: Float, y: Float): Obj? {
        var best: Obj? = null
        var bd = Float.MAX_VALUE
        for (o in objs) {
            if (o.taken) continue
            val ox = sxOf(o.x)
            if (ox < bx - 30 * d || ox > width + 20 * d) continue
            if (hypot(ox - bx, o.y - by) > reachD) continue      // too far from the turtle: a tap is just a stroke
            val dd = hypot(ox - x, o.y - y)
            if (dd < o.r * 2.4f && dd < bd) {
                bd = dd
                best = o
            }
        }
        return best
    }

    /** A smooth stroke: a small kick now, then a push that fades over a few frames. */
    private fun flap() {
        vy = min(vy, -flapV * 0.55f)
        flapPush = 1f
        flapAnimMs = 380f
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
            by = height * 0.5f + sin(now / 380.0).toFloat() * 9 * d
            return
        }
        if (state == DEAD) {
            // after the hit he tumbles, curls up and drops to the floor
            val floorY = height - floorH
            vy = min(vy + grav * dt, maxFall)
            by += vy * dt
            bx += ovx * dt
            ovx *= 0.96f.pow(dt)
            if (by + br > floorY) {
                by = floorY - br
                vy = if (vy > 2 * d) -vy * 0.35f else 0f
            }
            if (vy != 0f || abs(ovx) > 0.3f * d) spin += 16f * dt
            return
        }

        val dtMs = dt * 16.667f
        hintMs += dtMs
        val base = baseSpeed()
        extra *= 0.992f.pow(dt)
        if (slowMs > 0f) slowMs -= dtMs
        if (invulnMs > 0f) invulnMs -= dtMs
        if (shakeMs > 0f) shakeMs -= dtMs
        if (flapAnimMs > 0f) flapAnimMs -= dtMs
        if (happyMs > 0f) happyMs -= dtMs
        if (hitMs > 0f) {
            hitMs -= dtMs
            spin += 14f * dt
        }
        thumbS += (thumb - thumbS) * min(1f, 0.15f * dt)
        val speed = speedNow()
        worldX += speed * dt

        // the line: pulls Betito toward the friend you pressed
        val t = tether
        if (t != null) {
            tetherMs += dtMs
            val ox = sxOf(t.x)
            val dx = ox - bx
            val dy = t.y - by
            val dist = max(1f, hypot(dx, dy))
            if (t.taken || ox < bx - 90 * d || tetherMs > 2600f) {
                tether = null
            } else {
                val dash = if (t.hazard) 1.4f else 1f          // curled up he dashes harder
                ovx += dx / dist * pullAcc * dash * dt
                vy += dy / dist * pullAcc * 0.8f * dash * dt
                if (t.hazard) spin += 22f * dt
                val damp = 0.94f.pow(dt)
                ovx *= damp
                vy *= damp
                ovx = ovx.coerceIn(-12 * d, 16 * d)
                vy = vy.coerceIn(-14 * d, 14 * d)
                if (dx > 0f) extra = min(extra + 0.012f * base * dt, base * 3f)   // being reeled in feels fast
            }
        }
        if (tether == null) {
            if (flapPush > 0.03f) {
                if (vy > -flapV * 1.15f) vy -= flapPush * 0.45f * d * dt
                flapPush *= 0.86f.pow(dt)
            } else {
                flapPush = 0f
            }
            vy = min(vy + grav * dt, maxFall)
            ovx += ((baseX + thumbS * width * 0.18f) - bx) * 0.02f * dt
            ovx *= 0.9f.pow(dt)
        }
        bx = (bx + ovx * dt).coerceIn(width * 0.10f, width * 0.70f)
        by += vy * dt
        if (by - br < 0f) {          // the top of the ocean just stops you
            by = br
            if (vy < 0f) vy = 0f
        }

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
            else if (o.kind == K_SHARK) {
                if (o.flashMs > 0f) o.flashMs -= dtMs
                o.y = o.baseY + sin(now * 0.0016 + o.phase).toFloat() * o.amp
            }
        }

        // friends and sharks
        val cur = camX
        val tucking = tether?.hazard == true
        for (o in objs) {
            if (o.taken) continue
            val ox = o.x - cur
            if (abs(ox - bx) > 80 * d) continue
            val dist = hypot(ox - bx, o.y - by)
            if (o.hazard) {
                if (tether === o && dist < br + o.r * 0.9f) hitShark(o, now)
                else if (dist < br + o.r * 0.8f && invulnMs <= 0f && !tucking) squish(o, now)
            } else if (dist < br + o.r * 0.9f) {
                collect(o, base, now)
            }
        }

        // floor and rocks
        if (by + br > height - floorH) die(now, "SPLASHED!")
        for (pl in pillars) {
            val px = sxOf(pl.x)
            if (bx + br * 0.8f > px && bx - br * 0.8f < px + pl.w) {
                if (by - br * 0.8f < pl.gapTop || by + br * 0.8f > pl.gapBot) {
                    die(now, "SQUISHED!")
                    break
                }
            }
            if (!pl.passed && px + pl.w < bx - br) {
                pl.passed = true
                passedCount++
                score += 10
                words.add(Word("+10", camX + bx, by - 44 * d, now, Color.WHITE, 15f))
                if (passedCount % PER_LEVEL == 0) levelUp(now)
            }
        }
        upkeepFx(dt, now)
    }

    private fun levelUp(now: Long) {
        val lv = level()
        score += 100 * lv
        bannerAt = now
        celebrate("LEVEL $lv!", Yarn.POP_BOOM, camX + bx + 70 * d, by - 20 * d, Color.parseColor("#FF80AB"), 36f, now, 40)
        words.add(Word("+${100 * lv}", camX + bx + 70 * d, by + 40 * d, now, Color.WHITE, 18f))
        words.add(Word("FASTER!", camX + bx + 70 * d, by + 90 * d, now, Color.parseColor("#80DEEA"), 22f))
        sfx?.play("fanfare", 1f, 0L)
        shakeMs = 250f
    }

    private fun collect(o: Obj, base: Float, now: Long) {
        o.taken = true
        happyMs = 650f
        if (tether === o) tether = null
        val pts = ptsOf[o.kind]
        score += pts
        extra = min(extra + base * 0.5f * power[o.kind], base * 3f)
        shakeMs = 120f
        celebrate(wordOf[o.kind], popOf[o.kind], o.x, o.y - 10 * d, Color.parseColor("#FFD54F"), sizeOf[o.kind], now, confOf[o.kind])
        words.add(Word("+$pts", o.x, o.y + 30 * d, now, Color.WHITE, 18f))
        sfx?.play(if (o.kind == K_DOLPHIN) "woo" else if (o.kind == K_STAR) "coin" else "ding", 0.9f, 0L)
    }

    /** Curled up in his shell, Betito smashes a shark. Three smashes beat it. */
    private fun hitShark(o: Obj, now: Long) {
        o.hp--
        o.flashMs = 500f
        tether = null
        ovx = -6f * d
        vy = -4f * d
        invulnMs = 700f
        shakeMs = 250f
        val gold = Color.parseColor("#FFD54F")
        if (o.hp <= 0) {
            o.taken = true
            val pts = 400 + 50 * level()
            score += pts
            extra = min(extra + baseSpeed(), baseSpeed() * 3f)
            celebrate("SHARK DEFEATED!!", Yarn.POP_BOOM, o.x, o.y - 20 * d, gold, 36f, now, 44)
            words.add(Word("+$pts", o.x, o.y + 40 * d, now, Color.WHITE, 20f))
            sfx?.play("fanfare", 1f, 0L)
        } else {
            score += 50
            pops.add(Pop(Yarn.POP_POW, o.x, o.y - 20 * d, now, 8f, 100f))
            words.add(Word("SMASH ${3 - o.hp}/3", o.x, o.y - 80 * d, now, Color.parseColor("#FF8A65"), 26f))
            words.add(Word("+50", o.x, o.y + 40 * d, now, Color.WHITE, 16f))
            sfx?.play("bonk", 1f, 0L)
        }
    }

    private fun squish(o: Obj, now: Long) {
        extra = 0f
        slowMs = 1300f
        invulnMs = 900f
        shakeMs = 300f
        hitMs = 1100f
        spin = 0f
        tether = null
        sfx?.play("squish", 1f, 0L)
        pops.add(Pop(Yarn.POP_BAM, o.x, o.y - 30 * d, now, -6f, 100f))
        words.add(Word("OUCH!", o.x, o.y - 80 * d, now, Color.parseColor("#FF7043"), 28f))
    }

    private fun die(now: Long, text: String) {
        if (state != RUN) return
        state = DEAD
        diedAt = now
        deathText = text
        shakeMs = 350f
        val m = meters()
        if (m > best) {
            best = m
            prefs.edit().putInt("swim_best", best).apply()
        }
        newBest = score > bestScore
        if (newBest) {
            bestScore = score
            prefs.edit().putInt("swim_best_score", bestScore).apply()
        }
        vy = -5f * d
        ovx = -4f * d
        spin = 0f
        tether = null
        sfx?.play("bonk", 1f, 0L)
        pops.add(Pop(Yarn.POP_WHAM, camX + bx, by - 30 * d, now, 5f, 110f))
    }

    // ---------- celebrations ----------
    private fun upkeepFx(dt: Float, now: Long) {
        val pi = pops.iterator()
        while (pi.hasNext()) if (now - pi.next().at > 800L) pi.remove()
        val wi = words.iterator()
        while (wi.hasNext()) if (now - wi.next().at > 1300L) wi.remove()
        val ci = confs.iterator()
        while (ci.hasNext()) {
            val q = ci.next()
            q.vy += 0.35f * ui * dt
            q.x += q.vx * dt
            q.y += q.vy * dt
            if (now - q.at > 1300L) ci.remove()
        }
    }

    private fun addConf(x: Float, y: Float, now: Long) {
        if (confs.size > 220) return
        val hue = Random.nextFloat() * 360f
        confs.add(Conf(x, y, (Random.nextFloat() - 0.5f) * 9 * ui, (-Random.nextFloat() * 8 - 1) * ui,
            Color.HSVToColor(floatArrayOf(hue, 0.65f, 1f)), now, (3 + Random.nextFloat() * 3) * ui))
    }

    /** A comic patch (POW!, BING!...) with a bouncy praise word and a burst of confetti. */
    private fun celebrate(word: String, kind: Int, wx: Float, y: Float, color: Int, size: Float, now: Long, confetti: Int) {
        pops.add(Pop(kind, wx, y, now, (Random.nextFloat() - 0.5f) * 24f, 80f + size * 1.4f))
        words.add(Word(word, wx, y - 56 * d, now, color, size))
        for (i in 0 until confetti) addConf(sxOf(wx), y, now)
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
        drawFloor(c, w, h)
        drawPillars(c, w, h)
        drawObjects(c, now)
        drawTether(c, now)
        drawBetito(c, now)
        for (pp in pops) drawPop(c, pp, now)
        c.restore()
        drawFx(c, w, h, now)
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

    private fun drawFloor(c: Canvas, w: Float, h: Float) {
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
            c.drawRoundRect(px, -30 * d, px + pl.w, pl.gapTop, 14 * d, 14 * d, rockP)
            c.drawRoundRect(px, pl.gapBot, px + pl.w, h + 30 * d, 14 * d, 14 * d, rockP)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = Color.argb(150, 20, 10, 5)
            c.drawRoundRect(px, -30 * d, px + pl.w, pl.gapTop, 14 * d, 14 * d, p)
            c.drawRoundRect(px, pl.gapBot, px + pl.w, h + 30 * d, 14 * d, 14 * d, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawObjects(c: Canvas, now: Long) {
        val pulse = 0.5f + 0.5f * sin(now / 220.0).toFloat()
        if (state == RUN) {
            // how close a friend has to be to press it
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = Color.argb(28, 255, 255, 255)
            c.drawCircle(bx, by, reachD, p)
        }
        for (o in objs) {
            if (o.taken) continue
            val ox = sxOf(o.x)
            if (ox < -80 * d || ox > width + 80 * d) continue
            val bob = if (o.hazard) 0f else sin(now / 500.0 + o.x * 0.01).toFloat() * 4 * d
            val oy = o.y + bob
            if (!o.hazard) {
                val near = hypot(ox - bx, oy - by) < reachD
                p.style = Paint.Style.STROKE
                if (near) {
                    // close enough: tap anywhere inside the faint ring to swim to it
                    p.strokeWidth = 2 * d
                    p.color = Color.argb((40 + 40 * pulse).toInt(), 255, 255, 255)
                    c.drawCircle(ox, oy, o.r * 2.4f, p)
                }
                p.strokeWidth = 2.5f * d
                p.color = Color.argb(if (near) (90 + 90 * pulse).toInt() else 35, 255, 244, 160)
                c.drawCircle(ox, oy, o.r + (6 + 4 * pulse) * d, p)
                p.style = Paint.Style.FILL
                p.color = Color.argb(if (near) 40 else 12, 255, 244, 160)
                c.drawCircle(ox, oy, o.r + 6 * d, p)
            }
            if (o.hazard) {
                val near = hypot(ox - bx, oy - by) < reachD
                if (near) {
                    // close enough to smash: press it to curl up and dash
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.5f * d
                    p.color = Color.argb((60 + 70 * pulse).toInt(), 255, 90, 70)
                    c.drawCircle(ox, oy, o.r * 2.4f, p)
                }
                // hit points
                for (i in 0 until 3) {
                    p.style = Paint.Style.FILL
                    p.color = if (i < o.hp) Color.argb(230, 255, 82, 82) else Color.argb(90, 255, 255, 255)
                    c.drawCircle(ox + (i - 1) * 14 * d, oy - o.r - 22 * d, 4.5f * d, p)
                }
                if (o.flashMs > 0f) {
                    p.style = Paint.Style.FILL
                    p.color = Color.argb(110, 255, 60, 40)
                    c.drawCircle(ox, oy, o.r * 1.5f, p)
                }
            }
            val shakeX = if (o.hazard && o.flashMs > 0f) sin(now / 25.0).toFloat() * 5 * d else 0f
            val ok = if (o.hazard) {
                c.save()
                c.translate(ox + shakeX, oy)
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
            p.color = Color.argb((120 + 120 * f).toInt(), 255, if (t.hazard) 110 else 241, if (t.hazard) 90 else 118)
            c.drawCircle(bx + dx * f, by + dy * f, (2 + 2.5f * f) * d, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.argb(150, 255, if (t.hazard) 110 else 241, if (t.hazard) 90 else 118)
        c.drawCircle(ox, oy, t.r + 12 * d, p)
        p.style = Paint.Style.FILL
    }

    private fun drawBetito(c: Canvas, now: Long) {
        // trail (rainbow while a friend's speed kick is on)
        val kicked = extra > baseSpeed() * 0.8f
        if (state != READY) {
            for (i in 0 until trailN) {
                val idx = (trailHead - 1 - i + trailX.size * 2) % trailX.size
                val f = 1f - i / trailX.size.toFloat()
                val sx = sxOf(trailX[idx])
                if (kicked) {
                    val hue = ((i * 28 + now / 8) % 360).toFloat()
                    p.color = Color.HSVToColor((200 * f).toInt(), floatArrayOf(hue, 0.85f, 1f))
                } else {
                    p.color = Color.argb((70 * f).toInt(), 200, 240, 255)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(sx, trailY[idx], (5 + 8 * f) * d * (if (kicked) 1.4f else 1f), p)
            }
        }
        // pick the picture: he swims, strokes, dives, tumbles when hit, then curls into his shell
        val target = (atan2(vy, max(1f, speedNow())) * 57.3f).coerceIn(-40f, 45f)
        rotS += (target - rotS) * 0.15f
        val amp = if (state == RUN) 1f else 0.5f
        val wob = sin(now / 160.0).toFloat() * amp
        val al = if (invulnMs > 0f && (now / 90L) % 2L == 0L) 130 else 255
        val bw = 100 * d
        val dive = ((vy - 2 * d) / (6 * d)).coerceIn(0f, 1f)
        val happy = (happyMs / 200f).coerceIn(0f, 1f)
        val hitActive = state == DEAD || hitMs > 0f
        val tucking = state == RUN && tether?.hazard == true
        val hitT = if (state == DEAD) (now - diedAt).toFloat() else 1100f - hitMs   // ms since he was hit
        c.save()
        c.translate(bx, by + wob * 1.5f * d)
        var ok: Boolean
        if (hitActive) {
            ok = if (hitT < 350f) {
                Yarn.drawBet(c, Yarn.B_TUMBLE, 0f, 0f, bw * 0.95f, spin, al)      // knocked about
            } else {
                Yarn.drawBet(c, Yarn.B_CURL, 0f, 0f, bw * 0.72f, spin, al)        // curled up, rolling
            }
        } else if (tucking) {
            ok = Yarn.drawBet(c, Yarn.B_CURL, 0f, 0f, bw * 0.72f, spin, al)           // tucked in, dashing at the shark
        } else {
            val rot = rotS * (1f - 0.6f * dive) + wob * 2f
            val sx = 1f + 0.03f * wob
            val sy = 1f - 0.03f * wob
            ok = Yarn.drawBet(c, Yarn.B_SWIM, 0f, 0f, bw, rot, al, sx, sy)
            if (ok) {
                // the poses melt into each other (the swim-up stroke stays the one swim picture)
                if (dive > 0.02f) Yarn.drawBet(c, Yarn.B_DIVE, 0f, 0f, bw, rot, (al * dive).toInt(), sx, sy)
                if (happy > 0.02f) Yarn.drawBet(c, Yarn.B_WAVE, 0f, 0f, bw, rot, (al * happy).toInt(), sx, sy)
            }
        }
        if (!ok && !Yarn.drawPose(c, if (flapAnimMs > 190f) 1 else 0, 0f, 0f, spriteH, rotS)) {
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#66BB6A")
            c.drawCircle(0f, 0f, br + 4 * d, p)
        }
        c.restore()
    }

    private fun drawPop(c: Canvas, pp: Pop, now: Long) {
        val age = now - pp.at
        val sc = if (age < 150L) 0.4f + 0.8f * (age / 150f) else 1.2f - 0.2f * ((age - 150L) / 650f)
        val a = if (age > 560L) (255 * (1f - (age - 560L) / 240f)).toInt().coerceIn(0, 255) else 255
        Yarn.drawPop(c, pp.kind, sxOf(pp.wx), pp.y, pp.size * d * sc, a, pp.rot)
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

    /** Praise words and confetti, drawn at normal size over the zoomed world. */
    private fun drawFx(c: Canvas, w: Float, h: Float, now: Long) {
        for (q in confs) {
            val age = (now - q.at) / 1300f
            p.style = Paint.Style.FILL
            p.color = q.color
            p.alpha = (255 * (1f - age)).toInt().coerceIn(0, 255)
            c.drawRect(q.x - q.sz / 2f, q.y - q.sz / 2f, q.x + q.sz / 2f, q.y + q.sz / 2f, p)
            p.alpha = 255
        }
        for (wd in words) {
            val age = now - wd.at
            val k = (age / 1300f).coerceIn(0f, 1f)
            val pop = if (age < 160L) 0.5f + 0.9f * (age / 160f) else 1.4f - 0.4f * min(1f, (age - 160L) / 200f)
            val a = if (k > 0.7f) (255 * (1f - (k - 0.7f) / 0.3f)).toInt().coerceIn(0, 255) else 255
            val y = wd.y - 30 * ui * k
            val x = sxOf(wd.wx).coerceIn(80 * ui, width - 80 * ui)
            text(c, wd.text, x, y, wd.size * pop, Color.argb(a, Color.red(wd.color), Color.green(wd.color), Color.blue(wd.color)))
        }
        // big LEVEL banner
        val ba = now - bannerAt
        if (state == RUN && bannerAt > 0L && ba < 1800L) {
            val pop = if (ba < 200L) 0.5f + 2.5f * (ba / 200f) * 0.4f else 1.0f
            val a = if (ba > 1300L) (255 * (1f - (ba - 1300L) / 500f)).toInt().coerceIn(0, 255) else 255
            text(c, "LEVEL ${level()}", w / 2f, h * 0.30f, 44f * pop, Color.argb(a, 255, 255, 255))
        }
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, now: Long) {
        text(c, "$score", 18 * ui, 36 * ui, 30f, Color.WHITE, Paint.Align.LEFT)
        text(c, "${meters()} m   BEST $bestScore", 18 * ui, 54 * ui, 12f, Color.argb(220, 255, 224, 130), Paint.Align.LEFT)
        if (state != RUN) return
        if (steerId != -1 && abs(thumb) > 0.01f) {
            // the slide: a line from where you touched to your thumb
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * ui
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.argb(150, 255, 255, 255)
            c.drawLine(steerX0, steerY0, steerX, steerY0, p)
            p.style = Paint.Style.FILL
            c.drawCircle(steerX, steerY0, 8 * ui, p)
            text(c, if (thumb > 0f) "FASTER >>" else "<< SLOWER", (steerX0 + steerX) / 2f, steerY0 - 18 * ui, 13f, Color.WHITE)
        }
        // level progress
        val k = (passedCount % PER_LEVEL) / PER_LEVEL.toFloat()
        val bw = 130 * ui
        val bxl = w - bw - 18 * ui
        val byt = 14 * ui
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 0, 0, 0)
        rect.set(bxl, byt, bxl + bw, byt + 11 * ui)
        c.drawRoundRect(rect, 6 * ui, 6 * ui, p)
        p.color = if (slowMs > 0f) Color.parseColor("#FF7043") else Color.parseColor("#4FC3F7")
        rect.set(bxl, byt, bxl + max(6 * ui, bw * k), byt + 11 * ui)
        c.drawRoundRect(rect, 6 * ui, 6 * ui, p)
        text(c, if (slowMs > 0f) "SQUISHED! SLOW" else "LEVEL ${level()}", bxl + bw / 2f, byt + 30 * ui, 11f, Color.WHITE)
        if (hintMs < 12000f) {
            text(c, "SLIDE YOUR THUMB RIGHT = FASTER, LEFT = SLOWER. PRESS A GLOWING FRIEND TO SWIM TO IT!", w / 2f, h - 14 * ui, 12f, Color.argb(210, 255, 255, 255))
        }
    }

    private fun drawReadyCard(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(120, 0, 0, 0)
        rect.set(w * 0.16f, h * 0.16f, w * 0.84f, h * 0.49f)
        c.drawRoundRect(rect, 18 * ui, 18 * ui, p)
        text(c, "SWIM RUN", w / 2f, h * 0.16f + 34 * ui, 28f, Color.WHITE)
        text(c, "TAP = SWIM UP. SLIDE THUMB RIGHT = FASTER + FORWARD, LEFT = SLOWER + BACK.", w / 2f, h * 0.16f + 58 * ui, 13f, Color.parseColor("#B3E5FC"))
        text(c, "PRESS A CLOSE, GLOWING FRIEND = A LINE PULLS YOU TO IT!", w / 2f, h * 0.16f + 76 * ui, 13f, Color.parseColor("#FFE082"))
        text(c, "PASS 8 ROCKS = NEXT LEVEL (FASTER EACH TIME). GO FAR!", w / 2f, h * 0.16f + 94 * ui, 13f, Color.parseColor("#FFE082"))
        text(c, "SHARK CLOSE? PRESS IT: CURL UP AND SMASH IT. 3 SMASHES = BIG POINTS!", w / 2f, h * 0.16f + 112 * ui, 13f, Color.parseColor("#FF8A65"))
        val pulse = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 250.0).toFloat()
        text(c, "TAP TO START", w / 2f, h * 0.66f, 18f + 2 * pulse, Color.WHITE)
    }

    private fun drawDeadCard(c: Canvas, w: Float, h: Float, now: Long) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        text(c, deathText, w / 2f, h * 0.26f, 32f, Color.parseColor("#FF8A65"))
        text(c, "$score", w / 2f, h * 0.26f + 50 * ui, 44f, Color.WHITE)
        text(c, "LEVEL ${level()}   ${meters()} m", w / 2f, h * 0.26f + 74 * ui, 16f, Color.parseColor("#B3E5FC"))
        text(c, if (newBest) "NEW BEST!" else "BEST $bestScore", w / 2f, h * 0.26f + 96 * ui, 16f, Color.parseColor("#FFD54F"))
        val pulse = 0.5f + 0.5f * sin(now / 250.0).toFloat()
        text(c, "TAP TO TRY AGAIN", w / 2f, h * 0.26f + 128 * ui, 18f + 2 * pulse, Color.WHITE)
        val bw = 120 * ui
        val bh = 38 * ui
        menuR.set(w / 2f - bw / 2f, h * 0.26f + 146 * ui, w / 2f + bw / 2f, h * 0.26f + 146 * ui + bh)
        p.color = Color.parseColor("#37474F")
        c.drawRoundRect(menuR, 12 * ui, 12 * ui, p)
        text(c, "MENU", w / 2f, menuR.centerY() + 6 * ui, 16f, Color.WHITE)
    }
}
