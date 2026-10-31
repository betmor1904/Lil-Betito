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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * REEF CHESS: a small turn-based strategy game. Your four kinds of crochet turtles move like chess pieces,
 * the sharks chase them one step at a time, and red squares show exactly where a shark can strike next turn.
 * Reach the right edge (the exit) with any turtle, or eat every shark, to win.
 *
 *  LIGHTNING slides straight (like a rook)     WAVES slides diagonally (like a bishop)
 *  FLAMES jumps like a knight                  SPONGE steps one square and can't be eaten
 */
class ChessView(ctx: Context, private val prefs: SharedPreferences, private val sfx: Sfx?) : View(ctx) {
    var onQuit: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seaP = Paint()
    private val tintP = Paint()
    private val rect = RectF()

    private val cols = 6
    private val rows = 6
    private val kLight = 0
    private val kFlame = 2
    private val kSponge = 3
    private val kWave = 4

    private class Pc(val shark: Boolean, val kind: Int, var c: Int, var r: Int) {
        var alive = true
        var fc = 0
        var fr = 0
        var animAt = 0L
        var animDur = 0L
        var dieAt = 0L
        var faceLeft = false
    }

    private class PopFx(val kind: Int, val x: Float, val y: Float, val at: Long, val rot: Float, val size: Float)
    private class Btn(val id: String, val r: RectF)
    private class Snap(val pcs: List<IntArray>, val turns: Int)
    private class Lvl(val turtles: IntArray, val sharks: IntArray, val rocks: IntArray)

    private val levels = listOf(
        Lvl(intArrayOf(0, 1, 0, 0, 4, 3), intArrayOf(4, 2, 0, 3, 4, 0), intArrayOf(2, 2, 2, 3)),
        Lvl(intArrayOf(0, 0, 0, 0, 3, 4, 0, 5, 3), intArrayOf(3, 1, 0, 4, 3, 0, 4, 5, 0), intArrayOf(2, 1, 2, 4)),
        Lvl(intArrayOf(0, 2, 0, 0, 4, 2, 0, 0, 4, 1, 5, 3), intArrayOf(3, 0, 0, 3, 3, 0, 5, 2, 1), intArrayOf(2, 2, 3, 4, 1, 1)),
        Lvl(intArrayOf(0, 1, 0, 0, 3, 2, 0, 5, 4, 1, 3, 3), intArrayOf(4, 0, 0, 3, 2, 0, 4, 4, 0, 5, 3, 1), intArrayOf(2, 1, 2, 4, 3, 3)),
        Lvl(intArrayOf(0, 0, 2, 0, 2, 0, 0, 4, 4, 1, 5, 3), intArrayOf(3, 1, 0, 4, 2, 0, 3, 4, 0, 5, 5, 1, 4, 0, 0), intArrayOf(2, 2, 2, 3, 3, 5))
    )

    private var level = prefs.getInt("chess_level", 1).coerceAtLeast(1)
    private var pcs = ArrayList<Pc>()
    private val rocks = ArrayList<IntArray>()
    private var startTurtles = 0
    private var turns = 0
    private var sel: Pc? = null
    private var busyUntil = 0L
    private var pendingEnd = 0
    private var endAt = 0L
    private var state = 0           // 0 playing, 1 won, 2 lost
    private var stateAt = 0L
    private var stars = 0
    private var undoLeft = 3
    private var shakeAt = 0L
    private var happyAt = 0L
    private val history = ArrayList<Snap>()
    private val pops = ArrayList<PopFx>()
    private val btns = ArrayList<Btn>()
    private var cell = 0f
    private var x0 = 0f
    private var y0 = 0f
    private val starPath = android.graphics.Path()

    init {
        startLevel()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        seaP.shader = Yarn.sea
        tintP.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0E5A8A"), Color.parseColor("#0A3D62"), Color.parseColor("#031428")),
            null, android.graphics.Shader.TileMode.CLAMP)
        tintP.alpha = 100
    }

    // ============================================================
    // setup and rules
    // ============================================================

    private fun startLevel() {
        val lv = levels[(level - 1) % levels.size]
        pcs = ArrayList()
        rocks.clear()
        var i = 0
        while (i + 2 < lv.turtles.size) { pcs.add(Pc(false, lv.turtles[i + 2], lv.turtles[i], lv.turtles[i + 1])); i += 3 }
        i = 0
        while (i + 2 < lv.sharks.size) { pcs.add(Pc(true, lv.sharks[i + 2], lv.sharks[i], lv.sharks[i + 1])); i += 3 }
        i = 0
        while (i + 1 < lv.rocks.size) { rocks.add(intArrayOf(lv.rocks[i], lv.rocks[i + 1])); i += 2 }
        startTurtles = pcs.count { !it.shark }
        turns = 0
        sel = null
        busyUntil = 0L
        pendingEnd = 0
        state = 0
        undoLeft = 3
        history.clear()
        pops.clear()
    }

    private fun inB(c: Int, r: Int) = c in 0 until cols && r in 0 until rows
    private fun isRock(c: Int, r: Int) = rocks.any { it[0] == c && it[1] == r }
    private fun pieceAt(c: Int, r: Int): Pc? = pcs.firstOrNull { it.alive && it.c == c && it.r == r }

    private val orth = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val diag = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
    private val knight = arrayOf(intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(-2, 1), intArrayOf(-2, -1),
        intArrayOf(1, 2), intArrayOf(1, -2), intArrayOf(-1, 2), intArrayOf(-1, -2))

    private fun slide(pc: Pc, dirs: Array<IntArray>, out: ArrayList<IntArray>) {
        for (dir in dirs) {
            var n = 1
            while (true) {
                val nc = pc.c + dir[0] * n
                val nr = pc.r + dir[1] * n
                if (!inB(nc, nr) || isRock(nc, nr)) break
                val o = pieceAt(nc, nr)
                if (o == null) out.add(intArrayOf(nc, nr))
                else {
                    if (o.shark) out.add(intArrayOf(nc, nr))
                    break
                }
                n++
            }
        }
    }

    private fun step(pc: Pc, dirs: Array<IntArray>, out: ArrayList<IntArray>) {
        for (dir in dirs) {
            val nc = pc.c + dir[0]
            val nr = pc.r + dir[1]
            if (!inB(nc, nr) || isRock(nc, nr)) continue
            val o = pieceAt(nc, nr)
            if (o == null || o.shark) out.add(intArrayOf(nc, nr))
        }
    }

    private fun turtleMoves(pc: Pc): ArrayList<IntArray> {
        val out = ArrayList<IntArray>()
        when (pc.kind) {
            kLight -> slide(pc, orth, out)
            kWave -> slide(pc, diag, out)
            kFlame -> step(pc, knight, out)
            else -> step(pc, orth + diag, out)
        }
        return out
    }

    /** Squares a shark can move to next turn. Other sharks and the sponge block him; any other turtle there gets eaten. */
    private fun sharkMoves(s: Pc): ArrayList<IntArray> {
        val out = ArrayList<IntArray>()
        val dirs = if (s.kind == 1) orth + diag else orth
        for (dir in dirs) {
            val nc = s.c + dir[0]
            val nr = s.r + dir[1]
            if (!inB(nc, nr) || isRock(nc, nr)) continue
            val o = pieceAt(nc, nr)
            if (o == null || (!o.shark && o.kind != kSponge)) out.add(intArrayOf(nc, nr))
        }
        return out
    }

    private fun threatened(): HashSet<Int> {
        val set = HashSet<Int>()
        for (s in pcs) if (s.alive && s.shark) for (m in sharkMoves(s)) set.add(m[1] * cols + m[0])
        return set
    }

    private fun snap() {
        history.add(Snap(pcs.filter { it.alive }.map { intArrayOf(if (it.shark) 1 else 0, it.kind, it.c, it.r) }, turns))
        if (history.size > 3) history.removeAt(0)
    }

    private fun undo() {
        if (undoLeft <= 0 || history.isEmpty() || state != 0 || SystemClock.uptimeMillis() < busyUntil) return
        val s = history.removeAt(history.size - 1)
        undoLeft--
        pcs = ArrayList(s.pcs.map { Pc(it[0] == 1, it[1], it[2], it[3]) })
        turns = s.turns
        sel = null
        pendingEnd = 0
        sfx?.play("pop", 0.6f, 0L, 0.8f)
    }

    private fun addPop(kind: Int, x: Float, y: Float, at: Long, w: Float = 62f) {
        pops.removeAll { SystemClock.uptimeMillis() - it.at > 1200L }
        pops.add(PopFx(kind, x, y, at, (Random.nextFloat() - 0.5f) * 18f, w * d))
    }

    private fun later(ms: Long, name: String, vol: Float = 0.8f, rate: Float = 1f) {
        postDelayed({ sfx?.play(name, vol, 0L, rate) }, ms)
    }

    private fun moveTo(pc: Pc, c: Int, r: Int, at: Long, dur: Long) {
        pc.fc = pc.c
        pc.fr = pc.r
        pc.faceLeft = if (c != pc.c) c < pc.c else pc.faceLeft
        pc.c = c
        pc.r = r
        pc.animAt = at
        pc.animDur = dur
    }

    private fun playerMove(pc: Pc, c: Int, r: Int, now: Long) {
        snap()
        val dur = 240L
        val victim = pieceAt(c, r)
        moveTo(pc, c, r, now, dur)
        if (victim != null) {
            victim.alive = false
            victim.dieAt = now + dur
            addPop(if (pc.kind == kLight) Yarn.POP_ZAP else if (pc.kind == kFlame) Yarn.POP_BOOM else Yarn.POP_POW,
                cx(c), cy(r) - cell * 0.5f, now + dur, 70f)
            later(dur, "bonk")
            happyAt = now + dur
        } else {
            later(dur, "pop", 0.6f, 1.2f)
        }
        sel = null
        turns++
        var t = now + dur + 60L
        if (c == cols - 1) {
            addPop(Yarn.POP_BING, cx(c), cy(r) - cell * 0.5f, now + dur, 80f)
            finish(1, now + dur + 250L)
            busyUntil = now + dur + 250L
            return
        }
        if (pcs.none { it.alive && it.shark }) {
            finish(1, now + dur + 250L)
            busyUntil = now + dur + 250L
            return
        }
        t = sharksTurn(t)
        busyUntil = t
        if (pcs.none { it.alive && !it.shark }) { finish(2, t + 200L); return }
        // stuck: no turtle can move
        if (pcs.none { it.alive && !it.shark && turtleMoves(it).isNotEmpty() }) finish(2, t + 200L)
    }

    private fun dist(s: Pc, c: Int, r: Int, tc: Int, tr: Int): Int {
        val dx = abs(c - tc)
        val dy = abs(r - tr)
        return if (s.kind == 1) max(dx, dy) else dx + dy
    }

    private fun sharksTurn(start: Long): Long {
        var t = start
        val dur = 260L
        for (s in pcs.filter { it.alive && it.shark }) {
            val moves = sharkMoves(s)
            if (moves.isEmpty()) continue
            val targets = pcs.filter { it.alive && !it.shark && it.kind != kSponge }
            var pick: IntArray? = moves.firstOrNull { pieceAt(it[0], it[1]) != null }
            if (pick == null) {
                val tg = if (targets.isNotEmpty()) targets else pcs.filter { it.alive && !it.shark }
                if (tg.isEmpty()) continue
                var best = Int.MAX_VALUE
                for (m in moves) {
                    val dd = tg.minOf { dist(s, m[0], m[1], it.c, it.r) }
                    if (dd < best) { best = dd; pick = m }
                }
            }
            val m = pick ?: continue
            val victim = pieceAt(m[0], m[1])
            moveTo(s, m[0], m[1], t, dur)
            if (victim != null) {
                victim.alive = false
                victim.dieAt = t + dur
                addPop(Yarn.POP_BAM, cx(m[0]), cy(m[1]) - cell * 0.5f, t + dur, 70f)
                later(t + dur - SystemClock.uptimeMillis(), "bonk", 0.9f)
            } else {
                later(t + dur - SystemClock.uptimeMillis(), "tick", 0.4f, 0.8f)
            }
            t += dur + 90L
        }
        return t
    }

    private fun finish(kind: Int, at: Long) {
        pendingEnd = kind
        endAt = at
    }

    // ============================================================
    // drawing
    // ============================================================

    private fun cx(c: Int) = x0 + cell * (c + 0.5f)
    private fun cy(r: Int) = y0 + cell * (r + 0.5f)

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        cell = min(h * 0.80f / rows, w * 0.62f / cols)
        x0 = (w - cell * cols) / 2f
        y0 = h * 0.17f
        if (Yarn.loaded) {
            if (Yarn.drawReef(c, w, h)) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(150, 6, 40, 70)
                c.drawRect(0f, 0f, w, h, p)
            } else {
                c.drawRect(0f, 0f, w, h, seaP)
                c.drawRect(0f, 0f, w, h, tintP)
            }
        } else {
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#0A3D62")
            c.drawRect(0f, 0f, w, h, p)
        }
        if (pendingEnd != 0 && now >= endAt) {
            state = pendingEnd
            pendingEnd = 0
            stateAt = now
            if (state == 1) {
                val lost = startTurtles - pcs.count { it.alive && !it.shark }
                stars = if (lost == 0) 3 else if (lost == 1) 2 else 1
                prefs.edit().putInt("chess_level", level + 1).apply()
                sfx?.play("jackpot", 1f, 0L)
            } else sfx?.play("lose", 1f, 0L)
        }
        btns.clear()
        val busy = now < busyUntil
        drawBoard(c, now, busy)
        drawPieces(c, now)
        drawHud(c, w, h, now, busy)
        drawLegend(c, w, h)
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

    private fun drawBoard(c: Canvas, now: Long, busy: Boolean) {
        val danger = if (!busy && state == 0) threatened() else HashSet<Int>()
        val moves = if (!busy && state == 0) sel?.let { turtleMoves(it) } else null
        for (r in 0 until rows) for (col in 0 until cols) {
            rect.set(x0 + col * cell, y0 + r * cell, x0 + (col + 1) * cell, y0 + (r + 1) * cell)
            p.style = Paint.Style.FILL
            p.color = if ((col + r) % 2 == 0) Color.argb(46, 255, 255, 255) else Color.argb(18, 255, 255, 255)
            c.drawRect(rect, p)
            if (col == cols - 1) {
                val hs = Yarn.hole
                if (hs != null) { p.shader = hs; p.alpha = 150; c.drawRect(rect, p); p.shader = null } else {
                    p.color = Color.argb(120, 118, 255, 3)
                    c.drawRect(rect, p)
                }
            }
            if (danger.contains(r * cols + col)) {
                p.color = Color.argb(95, 255, 60, 60)
                c.drawRect(rect, p)
            }
            if (isRock(col, r)) {
                rect.inset(cell * 0.07f, cell * 0.07f)
                val rs = Yarn.rock
                if (rs != null) p.shader = rs else p.color = Color.parseColor("#8D6E63")
                c.drawRoundRect(rect, 10 * d, 10 * d, p)
                p.shader = null
            }
        }
        // board edge
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.argb(200, 255, 255, 255)
        rect.set(x0, y0, x0 + cell * cols, y0 + cell * rows)
        c.drawRect(rect, p)
        p.style = Paint.Style.FILL
        // legal moves for the selected turtle
        if (moves != null) {
            for (m in moves) {
                val victim = pieceAt(m[0], m[1])
                if (victim != null) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 4 * d
                    p.color = Color.parseColor("#FFEB3B")
                    c.drawCircle(cx(m[0]), cy(m[1]), cell * 0.46f, p)
                } else {
                    p.style = Paint.Style.FILL
                    p.color = Color.argb(200, 118, 255, 3)
                    c.drawCircle(cx(m[0]), cy(m[1]), cell * (0.13f + 0.02f * sin(now / 160.0).toFloat()), p)
                }
            }
            p.style = Paint.Style.FILL
        }
    }

    private fun drawPieces(c: Canvas, now: Long) {
        for (pc in pcs.sortedBy { it.r }) {
            if (!pc.alive && now >= pc.dieAt) continue
            var x = cx(pc.c)
            var y = cy(pc.r)
            if (pc.animDur > 0 && now < pc.animAt + pc.animDur) {
                val t = ((now - pc.animAt).toFloat() / pc.animDur).coerceIn(0f, 1f)
                val e = t * t * (3f - 2f * t)
                x = cx(pc.fc) + (cx(pc.c) - cx(pc.fc)) * e
                y = cy(pc.fr) + (cy(pc.r) - cy(pc.fr)) * e - sin(t * 3.1416f) * cell * 0.22f
            }
            if (pc.shark) {
                c.save()
                c.translate(x, y)
                if (pc.faceLeft) c.scale(-1f, 1f)
                val big = pc.kind == 1
                c.rotate(sin(now / 300.0 + pc.r).toFloat() * 3f)
                if (!Yarn.drawShark(c, cell * (if (big) 1.45f else 1.05f))) {
                    p.color = Color.parseColor("#607D8B")
                    c.drawCircle(0f, 0f, cell * 0.35f, p)
                }
                c.restore()
                if (big) label(c, "BIG", x, y - cell * 0.5f, 10f, Color.parseColor("#FF8A80"))
            } else {
                val lift = if (pc === sel) cell * 0.1f + sin(now / 140.0).toFloat() * 2 * d else 0f
                if (pc === sel) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3.5f * d
                    p.color = Color.parseColor("#FFEB3B")
                    c.drawCircle(x, y, cell * 0.46f, p)
                    p.style = Paint.Style.FILL
                }
                val rad = cell * 0.40f
                if (!Yarn.drawShell(c, pc.kind, x, y - lift, rad, 255)) {
                    p.color = ShellKinds.colors[pc.kind.coerceIn(0, ShellKinds.COUNT - 1)]
                    c.drawCircle(x, y - lift, rad, p)
                }
            }
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        p.style = Paint.Style.FILL
        p.textAlign = align
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = size * d
        p.color = color
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
    }

    private fun button(c: Canvas, id: String, l: Float, t: Float, r: Float, b: Float, text: String, color: Int, on: Boolean = true) {
        val rr = RectF(l, t, r, b)
        if (on) btns.add(Btn(id, rr))
        p.style = Paint.Style.FILL
        p.color = if (on) color else Color.argb(120, 80, 90, 100)
        c.drawRoundRect(rr, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.argb(220, 255, 255, 255)
        c.drawRoundRect(rr, 12 * d, 12 * d, p)
        p.style = Paint.Style.FILL
        label(c, text, (l + r) / 2, (t + b) / 2 + 5 * d, 13f, if (on) Color.WHITE else Color.argb(160, 255, 255, 255))
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, now: Long, busy: Boolean) {
        val bh = 38 * d
        val top = 8 * d
        button(c, "quit", 10 * d, top, 54 * d, top + bh, "X", Color.parseColor("#37474F"))
        label(c, "REEF CHESS  ·  LEVEL $level", w / 2, top + 16 * d, 15f, Color.parseColor("#FFEB3B"))
        val msg = when {
            state != 0 -> ""
            busy -> "The sharks are moving..."
            sel != null -> "Tap a green dot to move. Yellow ring = eat a shark."
            else -> "Your move. Tap a turtle. Red squares are where sharks can bite."
        }
        label(c, msg, w / 2, top + 36 * d, 12f, Color.argb(235, 255, 255, 255))
        val bw = 78 * d
        button(c, "undo", w - 10 * d - bw, top, w - 10 * d, top + bh, "UNDO $undoLeft", Color.parseColor("#6A1B9A"),
            undoLeft > 0 && history.isNotEmpty() && state == 0 && !busy)
        label(c, "Turn $turns", w - 10 * d - bw / 2, top + bh + 18 * d, 12f, Color.WHITE)
        // Betito cheers when you eat a shark
        val happy = now - happyAt in 0..1200L
        Yarn.drawPose(c, if (happy) Yarn.POSE_HAPPY else 0, w - 56 * d, h * 0.45f + sin(now / 400.0).toFloat() * 3 * d, 70 * d,
            if (happy) sin(now / 90.0).toFloat() * 6f else 0f)
    }

    private fun drawLegend(c: Canvas, w: Float, h: Float) {
        val lw = x0 - 14 * d
        if (lw < 120 * d) return
        val items = arrayOf(
            Triple(kLight, "LIGHTNING", "slides straight"),
            Triple(kWave, "WAVES", "slides diagonal"),
            Triple(kFlame, "FLAMES", "jumps like a knight"),
            Triple(kSponge, "SPONGE", "1 step, can't be eaten")
        )
        var y = y0 + 18 * d
        for (it in items) {
            Yarn.drawShell(c, it.first, 30 * d, y, 13 * d, 255)
            label(c, it.second, 52 * d, y - 2 * d, 11f, Color.WHITE, Paint.Align.LEFT)
            label(c, it.third, 52 * d, y + 12 * d, 10f, Color.argb(210, 200, 230, 255), Paint.Align.LEFT)
            y += 40 * d
        }
        label(c, "SHARKS: 1 step toward you.", 14 * d, y + 8 * d, 10f, Color.parseColor("#FF8A80"), Paint.Align.LEFT)
        label(c, "GOAL: reach the green edge", 14 * d, y + 24 * d, 10f, Color.parseColor("#76FF03"), Paint.Align.LEFT)
        label(c, "or eat every shark.", 14 * d, y + 38 * d, 10f, Color.parseColor("#76FF03"), Paint.Align.LEFT)
    }

    private fun drawEnd(c: Canvas, w: Float, h: Float, now: Long) {
        val age = now - stateAt
        p.style = Paint.Style.FILL
        p.color = Color.argb(120, 0, 10, 30)
        c.drawRect(0f, 0f, w, h, p)
        if (state == 1) {
            Yarn.drawParty(c, w, h, now, stateAt, 3400L)
            val t = ((age % 3200L) / 3200f)
            val size = h * 0.55f
            val x = -size * 0.6f + (w + size * 1.2f) * t
            val y = h * 0.30f - sin(t * 3.1416f) * h * 0.08f
            val spin = t in 0.35f..0.6f
            Yarn.drawPose(c, if (spin) 5 else 0, x, y, size * 0.7f, if (spin) (t - 0.35f) / 0.25f * 360f else 0f)
            label(c, "YOU ESCAPED THE SHARKS!", w / 2, h * 0.52f, 30f, Color.WHITE)
            for (i in 0 until 3) {
                val a = age - 300L * i
                if (a < 0) continue
                val sc = (min(1f, a / 300f) * 1.15f).coerceAtMost(1.15f)
                p.color = if (i < stars) Color.parseColor("#FFD600") else Color.argb(120, 60, 60, 80)
                drawStar(c, w / 2 + (i - 1) * 62 * d, h * 0.66f - (if (i == 1) 8 * d else 0f), 24 * d * sc)
            }
            if (age > 900L) label(c, "TAP FOR LEVEL ${level + 1}", w / 2, h * 0.86f, 18f, Color.parseColor("#76FF03"))
        } else {
            label(c, "THE SHARKS GOT YOU!", w / 2, h * 0.5f, 32f, Color.parseColor("#FF8A80"))
            if (age > 600L) label(c, "TAP TO TRY AGAIN", w / 2, h * 0.64f, 18f, Color.WHITE)
        }
    }

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
            if (state == 1) level++
            startLevel()
            sfx?.play("letsgo", 0.7f, 0L)
            return true
        }
        for (b in btns) {
            if (!b.r.contains(x, y)) continue
            sfx?.play("click", 0.6f, 0L)
            when (b.id) {
                "quit" -> onQuit?.invoke()
                "undo" -> undo()
            }
            return true
        }
        if (now < busyUntil || pendingEnd != 0) return true
        val col = floor((x - x0) / cell).toInt()
        val row = floor((y - y0) / cell).toInt()
        if (!inB(col, row)) { sel = null; return true }
        val s = sel
        if (s != null) {
            val legal = turtleMoves(s).any { it[0] == col && it[1] == row }
            if (legal) { playerMove(s, col, row, now); return true }
        }
        val o = pieceAt(col, row)
        if (o != null && !o.shark) {
            sel = if (o === sel) null else o
            sfx?.play("tick", 0.5f, 0L, 1.4f)
        } else {
            if (s != null) { shakeAt = now; sfx?.play("wrong", 0.3f, 120L) }
            sel = null
        }
        return true
    }
}
