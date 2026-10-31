package com.example.spray

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * REEF SORT: a calm little strategy puzzle. Shells sit on coral ledges, four to a ledge.
 * Move the top run of matching shells onto an empty ledge or onto a matching shell; four of a kind pops away.
 * The strategy: a move limit, a shark that locks a ledge every few moves (you can see where he'll go next),
 * and two special moves, ZAP (swap two tops) and WAVE (flip a ledge upside down).
 */
class ReefView(ctx: Context, private val prefs: SharedPreferences, private val sfx: Sfx?) : View(ctx) {
    var onQuit: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seaP = Paint()
    private val tintP = Paint()
    private val rect = RectF()

    private class Sh(val kind: Int) { var readyAt = 0L }
    private class Flight(val kind: Int, val fx: Float, val fy: Float, val tx: Float, val ty: Float, val at: Long, val dur: Long)
    private class Snap(val ledges: List<List<Int>>, val moves: Int, val since: Int, val shark: Int, val next: Int, val zap: Int, val wave: Int)
    private class Pending(val ledge: Int, val at: Long)
    private class PopFx(val kind: Int, val x: Float, val y: Float, val at: Long, val rot: Float, val size: Float)
    private class Btn(val id: String, val r: RectF)

    private val CAP = 4
    private var level = prefs.getInt("reef_level", 1).coerceAtLeast(1)
    private var ledges = ArrayList<ArrayList<Sh>>()
    private var deal: List<List<Int>> = emptyList()
    private var nLedges = 6
    private var movesLeft = 0
    private var maxMoves = 1
    private var since = 0
    private var shark = -1
    private var nextShark = -1
    private var sharkOn = false
    private var zapLeft = 0
    private var wave = 0
    private var undoLeft = 1
    private var sel = -1
    private var mode = 0          // 0 normal, 1 zap: pick first, 2 zap: pick second, 3 wave: pick ledge
    private var zapA = -1
    private var state = 0         // 0 playing, 1 won, 2 out of moves
    private var stateAt = 0L
    private var stars = 0
    private var happyAt = 0L
    private var shakeAt = 0L
    private var hint = ""
    private val history = ArrayList<Snap>()
    private val flights = ArrayList<Flight>()
    private val pending = ArrayList<Pending>()
    private val pops = ArrayList<PopFx>()
    private val btns = ArrayList<Btn>()
    private var slot = 0f
    private var baseY = 0f

    private var lastSteps = 10

    init {
        startLevel(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        seaP.shader = Yarn.sea
        tintP.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0E5A8A"), Color.parseColor("#0A3D62"), Color.parseColor("#031428")),
            null, android.graphics.Shader.TileMode.CLAMP)
        tintP.alpha = 100
    }

    // ============================================================
    // rules
    // ============================================================

    private fun colorsFor(l: Int) = min(5, 3 + (l - 1) / 3)

    private fun startLevel(retry: Boolean) {
        val k = colorsFor(level)
        nLedges = k + 2
        if (!retry || deal.isEmpty()) deal = generate(k)
        ledges = ArrayList()
        for (row in deal) ledges.add(ArrayList(row.map { Sh(it) }))
        // anything that starts already complete just pops away
        for (l in ledges) if (l.size == CAP && l.all { it.kind == l[0].kind }) l.clear()
        val steps = lastSteps
        maxMoves = steps + 4 + steps / 3
        movesLeft = maxMoves
        since = 0
        sharkOn = level >= 3
        shark = -1
        nextShark = if (sharkOn) Random.nextInt(nLedges) else -1
        zapLeft = if (level >= 2) 1 else 0
        wave = if (level >= 4) 1 else 0
        undoLeft = 1
        sel = -1
        mode = 0
        zapA = -1
        state = 0
        history.clear()
        flights.clear()
        pending.clear()
        pops.clear()
        hint = when {
            level == 1 -> "Stack 4 matching shells on one ledge."
            level == 2 -> "New: ZAP swaps the top shells of two ledges."
            level == 3 -> "A shark locks a ledge every 5 moves. His ghost shows where next."
            level == 4 -> "New: WAVE flips a ledge upside down."
            else -> "Plan ahead. Fewer moves means more stars."
        }
    }

    /** Builds a puzzle by scrambling a solved board with moves that exactly undo legal forward moves, so it is always solvable. */
    private fun generate(k: Int): List<List<Int>> {
        val kinds = (0 until ShellKinds.COUNT).shuffled().take(k)
        val n = k + 2
        val L = ArrayList<ArrayList<Int>>()
        for (i in 0 until n) {
            val row = ArrayList<Int>()
            if (i < k) repeat(CAP) { row.add(kinds[i]) }
            L.add(row)
        }
        val target = min(60, 12 + level * 4)
        var steps = 0
        var tries = 0
        while (steps < target && tries < 4000) {
            tries++
            val src = Random.nextInt(n)
            val s = L[src]
            if (s.isEmpty()) continue
            val c = s.last()
            var run = 1
            while (run < s.size && s[s.size - 1 - run] == c) run++
            val cnt = if (run == s.size) 1 + Random.nextInt(run) else if (run >= 2) 1 + Random.nextInt(run - 1) else continue
            val dst = Random.nextInt(n)
            if (dst == src) continue
            val t = L[dst]
            if (CAP - t.size < cnt) continue
            if (t.isNotEmpty() && t.last() == c) continue
            repeat(cnt) { t.add(s.removeAt(s.size - 1)) }
            steps++
        }
        lastSteps = steps.coerceAtLeast(4)
        return L.map { it.toList() }
    }

    private fun snap() {
        history.add(Snap(ledges.map { l -> l.map { it.kind } }, movesLeft, since, shark, nextShark, zapLeft, wave))
        if (history.size > 1) history.removeAt(0)
    }

    private fun undo(now: Long) {
        if (undoLeft <= 0 || history.isEmpty() || state != 0) return
        val s = history.removeAt(history.size - 1)
        undoLeft--
        ledges = ArrayList(s.ledges.map { row -> ArrayList(row.map { Sh(it) }) })
        movesLeft = s.moves; since = s.since; shark = s.shark; nextShark = s.next; zapLeft = s.zap; wave = s.wave
        sel = -1; mode = 0; zapA = -1
        flights.clear(); pending.clear()
        sfx?.play("pop", 0.6f, 0L, 0.8f)
    }

    private fun lx(i: Int) = width * 0.06f + width * 0.88f * (i + 0.5f) / nLedges
    private fun slotY(j: Int) = baseY - slot * (j + 0.55f)

    private fun topRun(l: List<Sh>): Int {
        if (l.isEmpty()) return 0
        val c = l.last().kind
        var r = 1
        while (r < l.size && l[l.size - 1 - r].kind == c) r++
        return r
    }

    private fun tap(i: Int, now: Long) {
        if (state != 0) return
        when (mode) {
            0 -> {
                if (sel == -1) {
                    if (i == shark || ledges[i].isEmpty()) { no(now); return }
                    sel = i
                    sfx?.play("tick", 0.5f, 0L, 1.4f)
                } else if (sel == i) {
                    sel = -1
                } else {
                    val a = sel
                    sel = -1
                    tryMove(a, i, now)
                }
            }
            1 -> {
                if (i == shark || ledges[i].isEmpty()) { no(now); return }
                zapA = i
                mode = 2
            }
            2 -> {
                if (i == zapA) { mode = 1; zapA = -1; return }
                if (i == shark || ledges[i].isEmpty()) { no(now); return }
                doZap(zapA, i, now)
                mode = 0
                zapA = -1
            }
            3 -> {
                if (i == shark || ledges[i].size < 2) { no(now); return }
                doWave(i, now)
                mode = 0
            }
        }
    }

    private fun no(now: Long) {
        shakeAt = now
        sfx?.play("wrong", 0.4f, 120L)
    }

    private fun tryMove(a: Int, b: Int, now: Long) {
        if (a == shark || b == shark) { no(now); return }
        val s = ledges[a]
        val t = ledges[b]
        if (s.isEmpty()) { no(now); return }
        val c = s.last().kind
        if (t.isNotEmpty() && t.last().kind != c) { no(now); return }
        val space = CAP - t.size
        val n = min(topRun(s), space)
        if (n <= 0) { no(now); return }
        snap()
        var lastReady = now
        for (k in 0 until n) {
            val fromJ = s.size - 1
            val sh = s.removeAt(s.size - 1)
            val toJ = t.size
            val at = now + k * 80L
            val dur = 280L
            sh.readyAt = at + dur
            lastReady = sh.readyAt
            flights.add(Flight(sh.kind, lx(a), slotY(fromJ), lx(b), slotY(toJ), at, dur))
            t.add(sh)
        }
        sfx?.play("pop", 0.6f, 0L, 1.2f)
        afterAction(now, b, lastReady)
    }

    private fun doZap(a: Int, b: Int, now: Long) {
        snap()
        val sa = ledges[a]
        val sb = ledges[b]
        val ka = sa.removeAt(sa.size - 1)
        val kb = sb.removeAt(sb.size - 1)
        val ja = sa.size
        val jb = sb.size
        val na = Sh(kb.kind)
        val nb = Sh(ka.kind)
        na.readyAt = now + 380L
        nb.readyAt = now + 380L
        sa.add(na)
        sb.add(nb)
        flights.add(Flight(kb.kind, lx(b), slotY(jb), lx(a), slotY(ja), now, 380L))
        flights.add(Flight(ka.kind, lx(a), slotY(ja), lx(b), slotY(jb), now, 380L))
        zapLeft--
        addPop(Yarn.POP_ZAP, lx(a), slotY(ja) - slot * 0.6f, now)
        addPop(Yarn.POP_ZAP, lx(b), slotY(jb) - slot * 0.6f, now)
        sfx?.play("wee", 0.6f, 0L)
        afterAction(now, b, now + 380L, a)
    }

    private fun doWave(i: Int, now: Long) {
        snap()
        val l = ledges[i]
        val bottom = l.removeAt(0)
        val nb = Sh(bottom.kind)
        nb.readyAt = now + 380L
        l.add(nb)
        flights.add(Flight(bottom.kind, lx(i), slotY(0), lx(i), slotY(l.size - 1), now, 380L))
        wave--
        addPop(Yarn.POP_CLOUD, lx(i), slotY(1), now)
        sfx?.play("wee", 0.6f, 0L, 1.4f)
        afterAction(now, i, now + 380L)
    }

    private fun afterAction(now: Long, touched: Int, ready: Long, touched2: Int = -1) {
        movesLeft--
        since++
        for (i in intArrayOf(touched, touched2)) {
            if (i < 0) continue
            val l = ledges[i]
            if (l.size == CAP && l.all { it.kind == l[0].kind }) pending.add(Pending(i, ready + 160L))
        }
        if (sharkOn && since >= 5) {
            shark = nextShark
            var n = Random.nextInt(nLedges)
            var guard = 0
            while ((n == shark || ledges[n].isEmpty() && guard < 6) && guard < 12) { n = Random.nextInt(nLedges); guard++ }
            nextShark = if (n == shark) (shark + 1) % nLedges else n
            since = 0
            if (sel == shark) sel = -1
            addPop(Yarn.POP_BAM, lx(shark), baseY - slot * 4.2f, now)
            sfx?.play("bonk", 0.8f, 0L)
        }
        checkEnd(now)
    }

    private fun checkEnd(now: Long) {
        if (state != 0 || pending.isNotEmpty()) return
        if (ledges.all { it.isEmpty() }) {
            val f = movesLeft.toFloat() / maxMoves
            stars = if (f >= 0.45f) 3 else if (f >= 0.2f) 2 else 1
            state = 1
            stateAt = now
            happyAt = now
            val best = prefs.getInt("reef_best", 1)
            prefs.edit().putInt("reef_level", level + 1).putInt("reef_best", maxOf(best, level + 1)).apply()
            sfx?.play("jackpot", 1f, 0L)
        } else if (movesLeft <= 0) {
            state = 2
            stateAt = now
            sfx?.play("lose", 1f, 0L)
        }
    }

    private fun addPop(kind: Int, x: Float, y: Float, now: Long, w: Float = 62f) {
        pops.removeAll { now - it.at > 900L }
        pops.add(PopFx(kind, x, y, now, (Random.nextFloat() - 0.5f) * 18f, w * d))
    }

    private fun processPending(now: Long) {
        val it = pending.iterator()
        var cleared = false
        while (it.hasNext()) {
            val pd = it.next()
            if (now < pd.at) continue
            it.remove()
            val l = ledges[pd.ledge]
            if (l.size == CAP && l.all { s -> s.kind == l[0].kind }) {
                l.clear()
                cleared = true
                happyAt = now
                addPop(if (Random.nextBoolean()) Yarn.POP_BING else Yarn.POP_POW, lx(pd.ledge), baseY - slot * 2.2f, now, 80f)
                sfx?.play("ding", 1f, 0L, 1.3f)
                sfx?.play("coins", 0.7f, 150L)
            }
        }
        if (cleared) checkEnd(now)
    }

    // ============================================================
    // drawing
    // ============================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        slot = min(h * 0.125f, w * 0.88f / nLedges * 0.78f)
        baseY = h * 0.86f
        if (Yarn.loaded) {
            if (Yarn.drawReef(c, w, h)) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(150, 6, 40, 70)
                c.drawRect(0f, 0f, w, h, p)
            } else {
                c.drawRect(0f, 0f, w, h, seaP)
                c.drawRect(0f, 0f, w, h, tintP)
            }
            for (i in 0 until 4) Yarn.drawDecal(c, intArrayOf(0, 3, 5, 4)[i], w * (0.2f + 0.2f * i), h * (0.4f + 0.12f * (i % 2)), 100 * d, 36)
        } else {
            p.color = Color.parseColor("#0A3D62")
            c.drawRect(0f, 0f, w, h, p)
        }
        processPending(now)
        btns.clear()

        val shaking = now - shakeAt < 260L
        if (shaking) {
            c.save()
            c.translate(sin(now / 25.0).toFloat() * 4 * d, 0f)
        }
        for (i in 0 until nLedges) drawLedge(c, i, now)
        drawFlights(c, now)
        if (shaking) c.restore()

        drawHud(c, w, h, now)
        for (pp in pops) {
            val age = now - pp.at
            if (age < 0 || age > 800L) continue
            val sc = when {
                age < 140L -> 0.3f + 1.0f * (age / 140f)
                age < 260L -> 1.3f - 0.3f * ((age - 140L) / 120f)
                age < 600L -> 1f
                else -> 1f - 0.25f * ((age - 600L) / 200f)
            }
            val a = if (age < 600L) 255 else (255 * (1f - (age - 600L) / 200f)).toInt().coerceIn(0, 255)
            Yarn.drawPop(c, pp.kind, pp.x, pp.y - age / 800f * 12 * d, pp.size * sc, a, pp.rot + sin(age / 55.0).toFloat() * 3f)
        }
        if (state != 0) drawEnd(c, w, h, now)
        postInvalidateOnAnimation()
    }

    private fun drawLedge(c: Canvas, i: Int, now: Long) {
        val x = lx(i)
        val l = ledges[i]
        val locked = i == shark
        val tw = slot * 0.58f
        // glass tube
        rect.set(x - tw, baseY - slot * 4.1f, x + tw, baseY + slot * 0.05f)
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (i == sel) 70 else 34, 255, 255, 255)
        c.drawRoundRect(rect, 14 * d, 14 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (i == sel || i == zapA) 3.5f else 2f) * d
        p.color = when {
            locked -> Color.parseColor("#FF5252")
            i == sel || i == zapA -> Color.parseColor("#FFEB3B")
            mode == 3 || mode == 1 || mode == 2 -> Color.argb(200, 128, 216, 255)
            else -> Color.argb(110, 255, 255, 255)
        }
        c.drawRoundRect(rect, 14 * d, 14 * d, p)
        p.style = Paint.Style.FILL
        // coral base
        rect.set(x - tw * 1.25f, baseY, x + tw * 1.25f, baseY + slot * 0.5f)
        val sh = Yarn.rock
        if (sh != null) p.shader = sh else p.color = Color.parseColor("#8D6E63")
        c.drawRoundRect(rect, 10 * d, 10 * d, p)
        p.shader = null
        // shells
        val run = if (i == sel) topRun(l) else 0
        for (j in l.indices) {
            val s = l[j]
            if (now < s.readyAt) continue
            var y = slotY(j)
            if (run > 0 && j >= l.size - run) y -= slot * 0.35f + sin(now / 150.0 + j).toFloat() * 2 * d
            drawShell(c, s.kind, x, y)
        }
        // shark lock
        if (locked) {
            p.color = Color.argb(90, 0, 0, 0)
            rect.set(x - tw, baseY - slot * 4.1f, x + tw, baseY + slot * 0.05f)
            c.drawRoundRect(rect, 14 * d, 14 * d, p)
            c.save()
            c.translate(x + sin(now / 400.0).toFloat() * 3 * d, baseY - slot * 2.1f)
            c.rotate(-90f)
            Yarn.drawShark(c, slot * 3.4f)
            c.restore()
            label(c, "LOCKED", x, baseY - slot * 4.25f, 11f, Color.parseColor("#FF8A80"))
        } else if (sharkOn && i == nextShark) {
            // the ghost shows where he goes next
            c.save()
            c.translate(x, baseY - slot * 4.7f)
            Yarn.drawShark(c, slot * 1.3f)
            c.restore()
            label(c, "in ${5 - since}", x, baseY - slot * 4.2f, 11f, Color.argb(220, 255, 255, 255))
        }
    }

    private fun drawShell(c: Canvas, kind: Int, x: Float, y: Float) {
        val rad = slot * 0.40f
        if (!Yarn.drawShell(c, kind, x, y, rad, 255)) {
            p.color = ShellKinds.colors[kind.coerceIn(0, ShellKinds.COUNT - 1)]
            c.drawCircle(x, y, rad, p)
        }
    }

    private fun drawFlights(c: Canvas, now: Long) {
        val it = flights.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (now > f.at + f.dur + 40L) { it.remove(); continue }
            val t = ((now - f.at).toFloat() / f.dur).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)
            val x = f.fx + (f.tx - f.fx) * e
            val y = f.fy + (f.ty - f.fy) * e - sin(t * 3.1416f) * slot * 0.9f
            drawShell(c, f.kind, x, y)
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = size * d
        p.color = color
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
    }

    private fun button(c: Canvas, id: String, l: Float, t: Float, r: Float, b: Float, text: String, color: Int, on: Boolean = true, lit: Boolean = false) {
        val rr = RectF(l, t, r, b)
        if (on) btns.add(Btn(id, rr))
        p.style = Paint.Style.FILL
        p.color = if (on) color else Color.argb(120, 80, 90, 100)
        c.drawRoundRect(rr, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (lit) 4 else 2) * d
        p.color = if (lit) Color.parseColor("#FFEB3B") else Color.argb(220, 255, 255, 255)
        c.drawRoundRect(rr, 12 * d, 12 * d, p)
        p.style = Paint.Style.FILL
        label(c, text, (l + r) / 2, (t + b) / 2 + 5 * d, 13f, if (on) Color.WHITE else Color.argb(160, 255, 255, 255))
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, now: Long) {
        val bh = 38 * d
        val top = 8 * d
        button(c, "quit", 10 * d, top, 10 * d + 44 * d, top + bh, "X", Color.parseColor("#37474F"))
        label(c, "REEF SORT  ·  LEVEL $level", w / 2, top + 16 * d, 15f, Color.parseColor("#FFEB3B"))
        label(c, "Moves left: $movesLeft", w / 2, top + 36 * d, 14f,
            if (movesLeft <= 4) Color.parseColor("#FF8A80") else Color.WHITE)
        val bw = 62 * d
        var x = w - 10 * d - bw
        button(c, "wave", x, top, x + bw, top + bh, "WAVE $wave", Color.parseColor("#1565C0"), wave > 0 && state == 0, mode == 3)
        x -= bw + 8 * d
        button(c, "zap", x, top, x + bw, top + bh, "ZAP $zapLeft", Color.parseColor("#F9A825"), zapLeft > 0 && state == 0, mode == 1 || mode == 2)
        x -= bw + 8 * d
        button(c, "undo", x, top, x + bw, top + bh, "UNDO $undoLeft", Color.parseColor("#6A1B9A"), undoLeft > 0 && history.isNotEmpty() && state == 0)
        val msg = when (mode) {
            1 -> "ZAP: tap the first ledge"
            2 -> "ZAP: now tap the second ledge"
            3 -> "WAVE: tap the ledge to flip"
            else -> hint
        }
        label(c, msg, w / 2, top + bh + 24 * d, 13f, Color.argb(230, 255, 255, 255))
        // Betito watches and cheers
        val happy = now - happyAt < 1300L
        Yarn.drawPose(c, if (happy) Yarn.POSE_HAPPY else 0, 52 * d, top + bh + 66 * d + sin(now / 400.0).toFloat() * 3 * d, 56 * d,
            if (happy) sin(now / 90.0).toFloat() * 6f else 0f)
    }

    private fun drawEnd(c: Canvas, w: Float, h: Float, now: Long) {
        val age = now - stateAt
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 0, 10, 30)
        c.drawRect(0f, 0f, w, h, p)
        if (state == 1) {
            Yarn.drawParty(c, w, h, now, stateAt, 3400L)
            // Betito zooms across the screen
            val t = ((age % 3200L) / 3200f)
            val size = h * 0.55f
            val x = -size * 0.6f + (w + size * 1.2f) * t
            val y = h * 0.30f - sin(t * 3.1416f) * h * 0.08f
            Yarn.drawPose(c, if (t in 0.35f..0.6f) 5 else 0, x, y, size * 0.7f, if (t in 0.35f..0.6f) (t - 0.35f) / 0.25f * 360f else 0f)
            label(c, "REEF CLEARED!", w / 2, h * 0.52f, 34f, Color.WHITE)
            for (i in 0 until 3) {
                val a = now - stateAt - 300L * i
                if (a < 0) continue
                val sc = (min(1f, a / 300f) * 1.15f).coerceAtMost(1.15f)
                p.color = if (i < stars) Color.parseColor("#FFD600") else Color.argb(120, 60, 60, 80)
                drawStar(c, w / 2 + (i - 1) * 62 * d, h * 0.64f - (if (i == 1) 8 * d else 0f), 24 * d * sc)
            }
            if (age > 900L) label(c, "TAP FOR LEVEL ${level + 1}", w / 2, h * 0.84f, 18f, Color.parseColor("#76FF03"))
        } else {
            label(c, "OUT OF MOVES!", w / 2, h * 0.5f, 34f, Color.parseColor("#FF8A80"))
            if (age > 600L) label(c, "TAP TO TRY AGAIN", w / 2, h * 0.64f, 18f, Color.WHITE)
        }
    }

    private val starPath = android.graphics.Path()
    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.45f
            val a = Math.PI / 5 * i - Math.PI / 2
            val sx = x + Math.cos(a).toFloat() * rr
            val sy = y + Math.sin(a).toFloat() * rr
            if (i == 0) starPath.moveTo(sx, sy) else starPath.lineTo(sx, sy)
        }
        starPath.close()
        c.drawPath(starPath, p)
    }

    // ============================================================
    // touch
    // ============================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        val now = SystemClock.uptimeMillis()
        val x = e.x
        val y = e.y
        if (state != 0) {
            if (now - stateAt < 700L) return true
            if (state == 1) { level++; deal = emptyList(); startLevel(false) } else startLevel(true)
            sfx?.play("letsgo", 0.7f, 0L)
            return true
        }
        for (b in btns) {
            if (!b.r.contains(x, y)) continue
            sfx?.play("click", 0.6f, 0L)
            when (b.id) {
                "quit" -> onQuit?.invoke()
                "undo" -> undo(now)
                "zap" -> { mode = if (mode == 1 || mode == 2) 0 else 1; sel = -1; zapA = -1 }
                "wave" -> { mode = if (mode == 3) 0 else 3; sel = -1; zapA = -1 }
            }
            return true
        }
        // ledge hit: nearest column, tall hit zone
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in 0 until nLedges) {
            val dx = abs(x - lx(i))
            if (dx < bd) { bd = dx; best = i }
        }
        if (best >= 0 && bd < slot * 0.9f && y > baseY - slot * 4.6f && y < baseY + slot * 0.8f) tap(best, now)
        return true
    }
}
