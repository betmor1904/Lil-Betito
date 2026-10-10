package com.example.spray

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A wall rectangle. Pillars can be blown up by bomb shells; everything else is permanent. */
class Block(val l: Float, val t: Float, val r: Float, val b: Float, val pillar: Boolean = false) {
    var alive = true
}

/** Something to pick up: an apple (spins a slot reel), a shell (ammo) or speed kelp. */
class Pickup(val x: Float, val y: Float, val type: Int, val seed: Float = Random.nextFloat() * 6f)

/** A fired shell. */
class Shell(var x: Float, var y: Float, var vx: Float, var vy: Float, val type: Int, var bounces: Int, var life: Float)

/** A seaweed patch: swim inside and the shark loses sight of you. */
class Weed(val l: Float, val t: Float, val r: Float, val b: Float, val seed: Float = Random.nextFloat() * 6f)

class Fish(var x: Float, var y: Float, var vx: Float, val size: Float, val color: Int)

class Bubble(var x: Float, var y: Float, val rad: Float, val speed: Float, val seed: Float)

/**
 * All the game rules for Lil Betito, independent of how it is drawn.
 *
 * Landscape strategy maze: Betito starts on the left and must reach the exit gap in the
 * right wall. A stinging tide creeps in from the left, and a shark hunts him through the
 * maze. He can hide in seaweed, eat speed kelp, and pick up shells to fire at the shark:
 * green flies straight, red bounces off walls, orange bomb shells explode.
 */
class Game(val d: Float, private val sfx: Sfx?, private val prefs: android.content.SharedPreferences) {

    companion object {
        const val P_APPLE = 0
        const val P_GREEN = 1
        const val P_RED = 2
        const val P_BOMB = 3
        const val P_SPEED = 4

        const val MODE_HUNT = 0
        const val MODE_SEARCH = 1
        const val MODE_WANDER = 2
    }

    // ---------- screen + layout ----------
    var W = 0f
    var H = 0f
    var topInset = 0f
    var playT = 0f
    var playB = 0f
    var exitX = 0f
    var exitGapT = 0f
    var exitGapB = 0f
    val wallThick get() = 14f * d
    var started = false
    private var wantNewGame = true
    private var pendingLevel = 1

    // ---------- the room ----------
    val blocks = ArrayList<Block>()
    val zones = ArrayList<WaterZone>()
    val weeds = ArrayList<Weed>()
    val pickups = ArrayList<Pickup>()
    val shells = ArrayList<Shell>()
    val fish = ArrayList<Fish>()
    val bubbles = ArrayList<Bubble>()
    var colX = FloatArray(0)
    var colGap = FloatArray(0)
    private var ripCol = -1
    private var narrowC = 0f
    private var narrowH = 0f
    private var nextCol = 0
    var tideX = 0f

    // ---------- the turtle ----------
    val r get() = 13f * d
    var cx = 0f
    var cy = 0f
    var vx = 0f
    var vy = 0f
    var faceX = 1f
    var faceY = 0f
    var heading = 0f
    private var ix = 0f
    private var iy = 0f
    var bumpAt = 0L
    var kickAt = 0L
    var speedUntil = 0L
    var hidden = false
    private var lastStrokeMs = 0L

    // ---------- input (set by the view) ----------
    var joyOn = false
    var joyX = 0f      // -1..1
    var joyY = 0f
    var brakeHeld = false
    var strokeQueued = false
    var fireQueued = false
    val ammo = ArrayList<Int>()
    val maxAmmo = 4

    // ---------- the shark ----------
    var chActive = false
    var chX = 0f
    var chY = 0f
    var chVx = 0f
    var chVy = 0f
    var chHeading = 0f
    var stunnedUntil = 0L
    private var chSpawnAt = 0L
    var chMode = MODE_WANDER
    private var lastSeenAt = 0L
    private var lastSeenX = 0f
    private var lastSeenY = 0f
    private var wanderX = 0f
    private var wanderY = 0f
    private var repathAt = 0L
    private var pathLen = 0
    private var pathIdx = 0
    private var pathBuf = IntArray(0)
    private val chR get() = 14f * d

    // pathfinding grid
    private val cell get() = 14f * d
    private var gCols = 0
    private var gRows = 0
    private var blocked = BooleanArray(0)
    private var parent = IntArray(0)
    private var queue = IntArray(0)

    // ---------- game state ----------
    var level = 1
    var score = 0
    var best = prefs.getInt("best_score", 0)
    var lives = 3
    var over = false
    private var resumeAt = 0L
    private var resumeNewGame = false
    var levelStartMs = 0L
    var dying = false

    // style bonuses
    var touchedWall = false
    private var touchedSinceCol = false
    var currentsVisited = 0
    private val visited = HashSet<Int>()

    // slot reels: 0 cherry, 1 bell, 2 bar, 3 seven, 4 coin
    val reels = intArrayOf(-1, -1, -1)
    private var reelCount = 0
    var reelResolving = false
    private var reelClearAt = 0L
    private var pendingFills = 0
    private val symbolWeights = intArrayOf(30, 20, 15, 7, 28)

    // ---------- presentation state (read by the view) ----------
    var message: String? = null
    var banner: String? = null
    var bannerUntil = 0L
    var popup: String? = null
    var popupAt = 0L
    var praise: String? = null
    var praiseAt = 0L
    var praiseBig = false
    var stars = 0
    var starsAt = 0L
    var flash = 0f
    var flashColor = 0
    var octoText: String? = null
    var octoAt = 0L
    private var octoNextAt = 0L
    private var cheerStreak = 0
    private var warnedTide = false
    private var lastCloseWarn = 0L

    // particles
    val pMax = 420
    val px = FloatArray(pMax)
    val py = FloatArray(pMax)
    val pvx = FloatArray(pMax)
    val pvy = FloatArray(pMax)
    val pLife = FloatArray(pMax)
    val pMaxLife = FloatArray(pMax)
    val pSize = FloatArray(pMax)
    val pColor = IntArray(pMax)
    private var pNext = 0

    private val neon = intArrayOf(
        0xFFFFEB3B.toInt(), 0xFFFF4081.toInt(), 0xFF00E5FF.toInt(), 0xFF76FF03.toInt(), 0xFFE040FB.toInt()
    )
    private val smooth = arrayOf("SMOOTH!", "SLICK!", "SHELL YEAH!", "SWIM-TASTIC!", "SO FRESH!")
    private val yum = arrayOf("SWEET!", "YUMMY!", "TASTY!", "DELICIOUS!")
    private val rides = arrayOf("NICE RIDE!", "SURF'S UP!", "WHEEE!")

    // ============================================================
    // setup
    // ============================================================

    fun resize(w: Float, h: Float, inset: Float) {
        val changed = w != W || h != H
        W = w
        H = h
        topInset = inset
        if (fish.isEmpty() || changed) makeScenery()
        if (wantNewGame) {
            wantNewGame = false
            newGame()
            if (pendingLevel != 1) {
                level = pendingLevel
                pendingLevel = 1
                startLevel()
            }
        } else if (changed && started) {
            startLevel()
        }
    }

    fun requestNewGame(startLevelNum: Int = 1) {
        if (W <= 0f) {
            wantNewGame = true
            pendingLevel = startLevelNum
            return
        }
        newGame()
        if (startLevelNum != 1) {
            level = startLevelNum
            startLevel()
        }
    }

    private fun newGame() {
        level = 1
        score = 0
        lives = 3
        ammo.clear()
        ammo.add(P_GREEN)   // one shell to start, so you can try the FIRE button
        startLevel()
    }

    private fun makeScenery() {
        fish.clear()
        bubbles.clear()
        val cols = intArrayOf(0xFFFFB74D.toInt(), 0xFF4FC3F7.toInt(), 0xFFF06292.toInt(), 0xFFFFF176.toInt(), 0xFF81C784.toInt())
        for (i in 0 until 9) {
            val dir = if (Random.nextBoolean()) 1f else -1f
            fish.add(Fish(Random.nextFloat() * W, H * (0.2f + 0.7f * Random.nextFloat()),
                dir * (0.4f + Random.nextFloat() * 0.7f) * d, (7 + Random.nextFloat() * 7) * d, cols[i % cols.size]))
        }
        for (i in 0 until 26) {
            bubbles.add(Bubble(Random.nextFloat() * W, H * Random.nextFloat(), (2 + Random.nextFloat() * 5) * d,
                (0.3f + Random.nextFloat() * 0.8f) * d, Random.nextFloat() * 6f))
        }
    }

    fun startLevel() {
        started = true
        over = false
        dying = false
        resumeAt = 0L
        val now = android.os.SystemClock.uptimeMillis()
        levelStartMs = now
        playT = topInset + 50 * d
        playB = H - 12 * d
        exitX = W - 18 * d
        message = "LEVEL $level"
        banner = "SWIM TO THE EXIT ON THE RIGHT"
        bannerUntil = now + 2500
        stars = 0
        praise = null
        cheerStreak = 0
        touchedWall = false
        touchedSinceCol = false
        currentsVisited = 0
        visited.clear()
        warnedTide = false
        resetReels()
        shells.clear()
        speedUntil = 0L
        hidden = false
        joyOn = false
        brakeHeld = false
        strokeQueued = false
        fireQueued = false

        buildMaze()
        buildGrid()

        cx = 48 * d
        cy = (playT + playB) / 2f
        vx = 0f
        vy = 0f
        ix = 0f
        iy = 0f
        faceX = 1f
        faceY = 0f
        heading = 0f
        tideX = -30 * d

        chActive = false
        chSpawnAt = now + (if (level == 1) 2500L else 1200L)
        chMode = MODE_WANDER
        stunnedUntil = 0L
        pathLen = 0

        say(if (level == 1) "Swim right to the exit! Use the left thumb to steer." else "Here we go! Watch for the shark!", true)
    }

    // ============================================================
    // the maze
    // ============================================================

    private fun pickGap(gapH: Float, prev: Float, minApart: Float): Float {
        val lo = playT + gapH / 2f + 8 * d
        val hi = playB - gapH / 2f - 8 * d
        var c = (lo + hi) / 2f
        for (k in 0 until 30) {
            c = lo + Random.nextFloat() * max(1f, hi - lo)
            if (abs(c - prev) >= minApart) break
        }
        return c
    }

    private fun addWall(l: Float, t: Float, rr: Float, b: Float, pillar: Boolean = false) {
        if (rr - l < 4 * d || b - t < 4 * d) return
        blocks.add(Block(l, t, rr, b, pillar))
    }

    fun laneL(i: Int) = if (i < 0) 0f else colX[i] + wallThick
    fun laneR(i: Int) = if (i + 1 < colX.size) colX[i + 1] else exitX

    private fun buildMaze() {
        blocks.clear()
        zones.clear()
        weeds.clear()
        pickups.clear()
        val playH = playB - playT
        val mid = (playT + playB) / 2f

        // borders: top, bottom, and the right wall with the exit gap
        addWall(0f, playT - 14 * d, W, playT)
        addWall(0f, playB, W, playB + 14 * d)
        val exitH = max(3.2f * r, 84 * d - (level - 1) * 3 * d)
        val ec = playT + exitH / 2f + 10 * d + Random.nextFloat() * max(1f, playH - exitH - 20 * d)
        exitGapT = ec - exitH / 2f
        exitGapB = ec + exitH / 2f
        addWall(exitX, playT, W + 40 * d, exitGapT)
        addWall(exitX, exitGapB, W + 40 * d, playB)

        // columns of walls, each with a gap, zigzagging up and down
        val n = min(3 + (level - 1) / 3, 6)
        val x0 = 96 * d + 30 * d
        val x1 = exitX - 80 * d
        colX = FloatArray(n) { x0 + (x1 - x0) * it / max(1, n - 1) }
        colGap = FloatArray(n)
        val gapH = max(3.6f * r, 80 * d - (level - 1) * 2 * d)
        val fwdCol = Random.nextInt(n)
        ripCol = if (level >= 4 && n > 1) (fwdCol + 1 + Random.nextInt(n - 1)) % n else -1
        narrowH = 0f
        var prev = mid
        for (i in 0 until n) {
            val g = pickGap(gapH, prev, playH * 0.35f)
            colGap[i] = g
            prev = g
            val x = colX[i]
            if (i == ripCol) {
                // the riptide gap, plus a narrow clean gap at the far edge
                val nh = 2.6f * r
                val nc = if (g < mid) playB - 10 * d - nh / 2f else playT + 10 * d + nh / 2f
                narrowC = nc
                narrowH = nh
                val a = min(g - gapH / 2f, nc - nh / 2f)
                val aB = min(g + gapH / 2f, nc + nh / 2f)
                val b = max(g - gapH / 2f, nc - nh / 2f)
                val bB = max(g + gapH / 2f, nc + nh / 2f)
                addWall(x, playT, x + wallThick, a)
                addWall(x, aB, x + wallThick, b)
                addWall(x, bB, x + wallThick, playB)
            } else {
                addWall(x, playT, x + wallThick, g - gapH / 2f)
                addWall(x, g + gapH / 2f, x + wallThick, playB)
            }
        }
        nextCol = 0
        val pulse = level >= 11

        // forward stream: carries you right through one gap like a conveyor belt
        run {
            val h = min(gapH - 8 * d, 56 * d)
            zones.add(WaterZone(WaterZone.UP, laneL(fwdCol - 1), colGap[fwdCol] - h / 2f, laneR(fwdCol),
                colGap[fwdCol] + h / 2f, 1f, 0f, 3.6f * d, pulse = pulse && Random.nextBoolean(),
                phaseMs = Random.nextLong(12000)))
        }

        // up/down stream in a lane, flowing toward the gap of the next column
        fun laneStream(lane: Int, towardGap: Boolean) {
            if (lane < 0 || lane + 1 >= n) return
            val target = colGap[lane + 1]
            val up = if (towardGap) target < mid else target >= mid
            val lw = laneR(lane) - laneL(lane)
            val bw = min(50 * d, lw * 0.55f)
            val cxL = (laneL(lane) + laneR(lane)) / 2f
            val t = if (up) min(target, mid) - 10 * d else playT
            val b = if (up) playB else max(target, mid) + 10 * d
            zones.add(WaterZone(WaterZone.SIDE, cxL - bw / 2f, max(playT, t), cxL + bw / 2f, min(playB, b),
                0f, if (up) -1f else 1f, 3f * d, pulse = pulse, phaseMs = Random.nextLong(12000)))
        }
        val streamLane = if (n > 1) (fwdCol + 1) % (n - 1) else 0
        laneStream(streamLane, true)

        // riptide: pushes you back left through the guarded gap
        if (ripCol >= 0) {
            val h = min(gapH - 6 * d, 60 * d)
            zones.add(WaterZone(WaterZone.RIP, colX[ripCol] - 50 * d, colGap[ripCol] - h / 2f, laneR(ripCol),
                colGap[ripCol] + h / 2f, -1f, 0f, (2.0f + min(1.0f, 0.1f * (level - 4))) * d,
                pulse = pulse, phaseMs = Random.nextLong(12000)))
        }

        // whirlpool in a lane
        if (level >= 7 && n >= 2) {
            val lane = Random.nextInt(n - 1)
            val lw = laneR(lane) - laneL(lane)
            val rad = min(56 * d, lw / 2f + 6 * d)
            val wcx = (laneL(lane) + laneR(lane)) / 2f
            val wcy = playT + rad + Random.nextFloat() * max(1f, playH - 2 * rad)
            zones.add(WaterZone(WaterZone.WHIRL, wcx - rad, wcy - rad, wcx + rad, wcy + rad, 0f, 0f, 3.2f * d,
                spin = if (Random.nextBoolean()) 1f else -1f))
        }

        // a second stream flowing the wrong way
        if (level >= 10 && n >= 3) laneStream((streamLane + 1) % (n - 1), false)

        // pillars in the lanes from level 3 (bomb shells can blow these up)
        val pillars = if (level >= 3) min((level - 1) / 2, 6) else 0
        var placed = 0
        var tries = 0
        while (placed < pillars && tries < 80 && n >= 2) {
            tries++
            val lane = Random.nextInt(n - 1)
            val size = 28 * d
            val lw = laneR(lane) - laneL(lane)
            if (lw < size + 2 * r + 16 * d) continue
            val pxx = (laneL(lane) + laneR(lane)) / 2f - size / 2f
            val pyy = playT + 12 * d + Random.nextFloat() * (playH - size - 24 * d)
            val pcy = pyy + size / 2f
            if (abs(pcy - colGap[lane]) < gapH + size) continue
            if (abs(pcy - colGap[lane + 1]) < gapH + size) continue
            if (blocks.any { it.pillar && abs(it.t - pyy) < size * 2 && abs(it.l - pxx) < 4 * d }) continue
            addWall(pxx, pyy, pxx + size, pyy + size, pillar = true)
            placed++
        }

        // seaweed hiding spots
        val weedCount = 1 + min(3, level / 3)
        tries = 0
        while (weeds.size < weedCount && tries < 80) {
            tries++
            val lane = Random.nextInt(n)
            val ww = 44 * d
            val wh = 60 * d
            val lw = laneR(lane) - laneL(lane)
            if (lw < ww + 8 * d) continue
            val wl = laneL(lane) + 4 * d + Random.nextFloat() * (lw - ww - 8 * d)
            val wt = playT + 6 * d + Random.nextFloat() * (playH - wh - 12 * d)
            if (rectHitsWall(wl, wt, wl + ww, wt + wh, 6 * d)) continue
            if (weeds.any { it.l < wl + ww + 10 * d && wl < it.r + 10 * d && it.t < wt + wh + 10 * d && wt < it.b + 10 * d }) continue
            weeds.add(Weed(wl, wt, wl + ww, wt + wh))
        }

        // things to pick up
        repeat(if (level >= 5) 3 else 2) { spawnPickup(P_APPLE) }
        spawnPickup(P_GREEN)
        spawnPickup(P_RED)
        spawnPickup(P_SPEED)
        if (level >= 3) spawnPickup(P_BOMB)
    }

    private fun rectHitsWall(l: Float, t: Float, rr: Float, b: Float, pad: Float): Boolean {
        for (w in blocks) {
            if (!w.alive) continue
            if (l < w.r + pad && rr > w.l - pad && t < w.b + pad && b > w.t - pad) return true
        }
        return false
    }

    private fun pointInWall(x: Float, y: Float, pad: Float): Boolean {
        for (w in blocks) {
            if (!w.alive) continue
            if (x > w.l - pad && x < w.r + pad && y > w.t - pad && y < w.b + pad) return true
        }
        return false
    }

    private fun spawnPickup(type: Int) {
        for (k in 0 until 60) {
            val x = 110 * d + Random.nextFloat() * (exitX - 140 * d)
            val y = playT + 20 * d + Random.nextFloat() * (playB - playT - 40 * d)
            if (x < tideX + 120 * d) continue
            if (pointInWall(x, y, 18 * d)) continue
            if (pickups.any { hypot(it.x - x, it.y - y) < 60 * d }) continue
            pickups.add(Pickup(x, y, type))
            return
        }
    }

    private fun randomPickupType(): Int {
        val roll = Random.nextInt(100)
        return when {
            roll < 35 -> P_GREEN
            roll < 60 -> P_RED
            roll < 75 && level >= 2 -> P_BOMB
            else -> P_SPEED
        }
    }

    // ============================================================
    // pathfinding for the shark
    // ============================================================

    private fun buildGrid() {
        gCols = max(1, (W / cell).toInt() + 1)
        gRows = max(1, (H / cell).toInt() + 1)
        val n = gCols * gRows
        blocked = BooleanArray(n)
        parent = IntArray(n)
        queue = IntArray(n)
        pathBuf = IntArray(n)
        refreshGrid()
    }

    private fun refreshGrid() {
        for (gy in 0 until gRows) {
            for (gx in 0 until gCols) {
                val x = (gx + 0.5f) * cell
                val y = (gy + 0.5f) * cell
                blocked[gy * gCols + gx] = y < playT + chR * 0.6f || y > playB - chR * 0.6f || x > exitX ||
                    pointInWall(x, y, chR * 0.85f)
            }
        }
        pathLen = 0
    }

    private fun cellOf(x: Float, y: Float): Int {
        val gx = (x / cell).toInt().coerceIn(0, gCols - 1)
        val gy = (y / cell).toInt().coerceIn(0, gRows - 1)
        return gy * gCols + gx
    }

    private fun nearestFree(c: Int): Int {
        if (!blocked[c]) return c
        val gx0 = c % gCols
        val gy0 = c / gCols
        for (rad in 1 until 8) {
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val gx = gx0 + dx
                val gy = gy0 + dy
                if (gx < 0 || gy < 0 || gx >= gCols || gy >= gRows) continue
                val k = gy * gCols + gx
                if (!blocked[k]) return k
            }
        }
        return c
    }

    /** Breadth-first search from the shark to (tx, ty). Fills pathBuf with cells, start to goal. */
    private fun findPath(tx: Float, ty: Float) {
        val start = nearestFree(cellOf(chX, chY))
        val goal = nearestFree(cellOf(tx, ty))
        pathLen = 0
        pathIdx = 0
        if (start == goal) return
        java.util.Arrays.fill(parent, -1)
        var head = 0
        var tail = 0
        queue[tail++] = start
        parent[start] = start
        var found = false
        while (head < tail) {
            val c = queue[head++]
            if (c == goal) { found = true; break }
            val gx = c % gCols
            val gy = c / gCols
            for (k in 0 until 4) {
                val nx = gx + (if (k == 0) 1 else if (k == 1) -1 else 0)
                val ny = gy + (if (k == 2) 1 else if (k == 3) -1 else 0)
                if (nx < 0 || ny < 0 || nx >= gCols || ny >= gRows) continue
                val nc = ny * gCols + nx
                if (blocked[nc] || parent[nc] != -1) continue
                parent[nc] = c
                queue[tail++] = nc
            }
        }
        if (!found) return
        var c = goal
        var len = 0
        while (c != start && len < pathBuf.size) {
            pathBuf[len++] = c
            c = parent[c]
        }
        // reverse into start -> goal order
        for (i in 0 until len / 2) {
            val t = pathBuf[i]
            pathBuf[i] = pathBuf[len - 1 - i]
            pathBuf[len - 1 - i] = t
        }
        pathLen = len
    }

    private fun clearLine(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val dist = hypot(x1 - x0, y1 - y0)
        val steps = max(1, (dist / (8 * d)).toInt())
        for (i in 1 until steps) {
            val t = i / steps.toFloat()
            if (pointInWall(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, chR * 0.7f)) return false
        }
        return true
    }

    // ============================================================
    // water
    // ============================================================

    private var wx = 0f
    private var wy = 0f

    private fun sampleWater(x: Float, y: Float, record: Boolean): WaterZone? {
        wx = 0f
        wy = 0f
        var whirl: WaterZone? = null
        for (z in zones) {
            if (z.power < 0.02f) continue
            var inside = false
            if (z.kind == WaterZone.WHIRL) {
                val ddx = x - z.cx
                val ddy = y - z.cy
                val dist = hypot(ddx, ddy)
                if (dist < z.rad && dist > 1f) {
                    val ux = ddx / dist
                    val uy = ddy / dist
                    val s = z.speed * z.power * (0.6f + 0.4f * dist / z.rad)
                    wx += -uy * z.spin * s - ux * s * 0.25f
                    wy += ux * z.spin * s - uy * s * 0.25f
                    whirl = z
                    inside = true
                }
            } else if (x >= z.l && x <= z.r && y >= z.t && y <= z.b) {
                wx += z.dx * z.speed * z.power
                wy += z.dy * z.speed * z.power
                inside = true
            }
            if (record && inside && z.power > 0.5f && visited.add(z.id)) {
                currentsVisited++
                if (currentsVisited >= zones.size) celebrate("EXPLORER!", true)
                else celebrate(rides.random())
            }
        }
        return whirl
    }

    private fun updatePulses(now: Long, dt: Float) {
        for (z in zones) {
            if (!z.pulse) { z.power = 1f; continue }
            val target = if (now < z.forcedUntil) 1f
                else if (((now - levelStartMs + z.phaseMs) / 6000L) % 2L == 0L) 1f else 0f
            z.power += (target - z.power) * min(1f, 0.04f * dt)
        }
    }

    // ============================================================
    // the frame
    // ============================================================

    fun update(now: Long, dt: Float) {
        if (W <= 0f || !started) return
        updateScenery(now, dt)
        updateParticles(dt)
        if (flash > 0f) flash = max(0f, flash - 0.04f * dt)
        if (reelResolving && now >= reelClearAt) finishReels()
        if (banner != null && bannerUntil in 1..now) banner = null

        if (over) {
            if (dying) tideX += 0.15f * riseSpeed() * dt
            if (resumeAt in 1..now) {
                resumeAt = 0L
                if (resumeNewGame) newGame() else startLevel()
            }
            return
        }
        if (now - levelStartMs > 1500) message = null

        updatePulses(now, dt)

        // stroke: a burst of speed the way he's facing (tap), brake while held
        if (strokeQueued) {
            strokeQueued = false
            if (now - lastStrokeMs > 450) {
                lastStrokeMs = now
                val p = (if (now < speedUntil) 5.5f else 4f) * d
                ix += faceX * p
                iy += faceY * p
                kickAt = now
                sfx?.play("bounce", 0.35f, 60L, 1.4f)
                for (k in 0 until 6) spawnParticle(cx - faceX * r, cy - faceY * r,
                    -faceX * 2 * d + (Random.nextFloat() - 0.5f) * 2 * d, -faceY * 2 * d + (Random.nextFloat() - 0.5f) * 2 * d,
                    0x99FFFFFF.toInt(), 3 * d, 25f)
            }
        }
        if (fireQueued) {
            fireQueued = false
            fire(now)
        }

        val boosted = now < speedUntil
        val maxSp = (if (boosted) 6f else 3.6f) * d
        val hdt = dt / 3f
        val rise = riseSpeed()
        for (step in 0 until 3) {
            tideX += rise * hdt

            val whirl = sampleWater(cx, cy, true)
            val inCurrent = wx != 0f || wy != 0f
            val grip = if (brakeHeld && inCurrent) 0.15f else 1f
            if (brakeHeld) {
                val k = 1f - min(1f, 0.15f * hdt)
                vx *= k
                vy *= k
            } else if (joyOn) {
                var a = 0.12f * hdt
                if (inCurrent) a *= 0.35f
                vx += (joyX * maxSp - vx) * min(1f, a)
                vy += (joyY * maxSp - vy) * min(1f, a)
            } else {
                val a = 0.035f * hdt
                vx -= vx * a
                vy -= vy * a
            }
            if (whirl != null && !joyOn) {
                val ddx = cx - whirl.cx
                val ddy = cy - whirl.cy
                val len = hypot(ddx, ddy).coerceAtLeast(1f)
                val sp = hypot(vx, vy)
                vx += (-ddy / len * whirl.spin * sp - vx) * 0.05f * hdt
                vy += (ddx / len * whirl.spin * sp - vy) * 0.05f * hdt
            }
            cx += (vx + wx * grip + ix) * hdt
            cy += (vy + wy * grip + iy) * hdt
            val fade = Math.pow(0.88, (hdt * 3f).toDouble()).toFloat()
            ix *= fade
            iy *= fade

            if (cx < r) { cx = r; vx = max(0f, vx); ix = max(0f, ix) }
            for (w in blocks) if (w.alive) collideTurtle(w.l, w.t, w.r, w.b, now)

            val sp = hypot(vx + ix, vy + iy)
            if (sp > 0.4f * d) {
                faceX = (vx + ix) / sp
                faceY = (vy + iy) / sp
            }

            // escaped through the exit gap
            if (cx > exitX + wallThick * 0.5f && cy > exitGapT && cy < exitGapB) { levelClear(now); return }
            // caught by the tide
            if (cx - r * 0.6f < tideX) { caught(now, "THE TIDE GOT YOU!"); return }
        }
        heading = Math.toDegrees(atan2(faceY.toDouble(), faceX.toDouble())).toFloat()

        updateShark(now, dt)
        if (over) return
        updateShells(now, dt)
        if (over) return
        collectPickups(now)
        checkColumns()

        // keep a few pickups around
        if (pickups.count { it.type != P_APPLE } < 3 && Random.nextFloat() < 0.004f * dt) spawnPickup(randomPickupType())

        if (!warnedTide && tideX > cx - 130 * d) {
            warnedTide = true
            say("The tide is coming! Swim!", true)
        }
    }

    private fun riseSpeed(): Float = min(0.75f, 0.22f + 0.04f * (level - 1)) * d

    private fun collideTurtle(l: Float, t: Float, rr: Float, b: Float, now: Long) {
        val qx = cx.coerceIn(l, rr)
        val qy = cy.coerceIn(t, b)
        val dx = cx - qx
        val dy = cy - qy
        val d2 = dx * dx + dy * dy
        if (d2 >= r * r) return
        var nx = 0f
        var ny = -1f
        if (d2 > 0.0001f) {
            val dist = sqrt(d2)
            nx = dx / dist
            ny = dy / dist
            cx += nx * (r - dist)
            cy += ny * (r - dist)
        } else {
            cy = t - r
        }
        val vn = vx * nx + vy * ny
        if (vn < 0f) {
            val vtx = vx - vn * nx
            val vty = vy - vn * ny
            vx = -vn * 0.15f * nx + vtx * 0.2f
            vy = -vn * 0.15f * ny + vty * 0.2f
            ix *= 0.3f
            iy *= 0.3f
            if (-vn > 1.2f * d) {
                bumpAt = now
                sfx?.play("tick", 0.4f, 80L)
                for (k in 0 until 4) spawnParticle(cx - nx * r, cy - ny * r, nx * 1.5f * d + (Random.nextFloat() - 0.5f) * 2 * d,
                    ny * 1.5f * d + (Random.nextFloat() - 0.5f) * 2 * d, 0xCCB39DDB.toInt(), 2.5f * d, 20f)
            }
            touchedSinceCol = true
            touchedWall = true
        }
    }

    // ============================================================
    // the shark
    // ============================================================

    private fun chaseSpeed(): Float = min(3.3f, 2.3f + 0.07f * (level - 1)) * d

    private fun updateShark(now: Long, dt: Float) {
        // seaweed hides him, unless the shark is right on top of him
        val inWeed = weeds.any { cx > it.l && cx < it.r && cy > it.t && cy < it.b }
        val distToShark = if (chActive) hypot(chX - cx, chY - cy) else 9999f
        val nowHidden = inWeed && distToShark > 60 * d
        if (nowHidden && !hidden) {
            sfx?.play("click", 0.8f, 200L)
            if (chActive) say("Shhh... he can't see you!", true)
        }
        hidden = nowHidden

        if (!chActive) {
            if (now >= chSpawnAt) {
                // the shark starts out patrolling somewhere in the middle of the maze
                chActive = true
                var sx = W * 0.55f
                var sy = (playT + playB) / 2f
                for (k in 0 until 60) {
                    val x = W * 0.35f + Random.nextFloat() * W * 0.4f
                    val y = playT + 20 * d + Random.nextFloat() * (playB - playT - 40 * d)
                    if (pointInWall(x, y, chR * 1.2f)) continue
                    if (hypot(x - cx, y - cy) < 250 * d) continue
                    sx = x
                    sy = y
                    break
                }
                chX = sx
                chY = sy
                chVx = 0f
                chVy = 0f
                chMode = MODE_WANDER
                pickWander()
                say("A shark patrols this maze. Don't let him see you!", true)
            }
            return
        }

        val stunned = now < stunnedUntil
        // can the shark see him? close enough, nothing in the way, not hiding in seaweed
        val sight = min(300f, 170f + 9f * (level - 1)) * d
        val sees = !stunned && !hidden && distToShark < sight && clearLine(chX, chY, cx, cy)
        if (sees) {
            if (chMode != MODE_HUNT) {
                sfx?.play("siren", 0.6f, 0L)
                say(arrayOf("He spotted you! RUN!", "SHARK! Swim, Betito!", "Uh oh... he sees you!").random(), true)
                pathLen = 0
            }
            chMode = MODE_HUNT
            lastSeenX = cx
            lastSeenY = cy
            lastSeenAt = now
        } else if (chMode == MODE_HUNT && now - lastSeenAt > 1500L) {
            // lost him: go look where he was last seen
            chMode = MODE_SEARCH
            pathLen = 0
        }

        var tx = if (chMode == MODE_HUNT && !sees) lastSeenX else cx
        var ty = if (chMode == MODE_HUNT && !sees) lastSeenY else cy
        when (chMode) {
            MODE_SEARCH -> {
                tx = lastSeenX
                ty = lastSeenY
                if (hypot(chX - tx, chY - ty) < 20 * d) {
                    chMode = MODE_WANDER
                    pickWander()
                    pathLen = 0
                    say("Phew... he lost you.", true)
                }
            }
            MODE_WANDER -> {
                tx = wanderX
                ty = wanderY
                if (hypot(chX - tx, chY - ty) < 24 * d) {
                    pickWander()
                    pathLen = 0
                }
            }
        }

        val hdt = dt / 3f
        val speed = when (chMode) {
            MODE_HUNT -> chaseSpeed()
            MODE_SEARCH -> 2.2f * d
            else -> 1.5f * d
        }
        for (step in 0 until 3) {
            if (stunned) {
                chVx *= 0.9f
                chVy *= 0.9f
            } else {
                // straight at the target when nothing is in the way, otherwise follow the path
                var gx = tx
                var gy = ty
                val direct = hypot(tx - chX, ty - chY) < 160 * d && clearLine(chX, chY, tx, ty)
                if (!direct) {
                    if (pathLen == 0 || now >= repathAt) {
                        findPath(tx, ty)
                        repathAt = now + 350L
                    }
                    while (pathIdx < pathLen) {
                        val c = pathBuf[pathIdx]
                        val wxp = (c % gCols + 0.5f) * cell
                        val wyp = (c / gCols + 0.5f) * cell
                        if (hypot(wxp - chX, wyp - chY) < 10 * d && pathIdx < pathLen - 1) pathIdx++ else {
                            gx = wxp
                            gy = wyp
                            break
                        }
                    }
                }
                val dx = gx - chX
                val dy = gy - chY
                val len = hypot(dx, dy)
                if (len > 0.5f) {
                    chVx += (dx / len * speed - chVx) * 0.2f * hdt * 3f
                    chVy += (dy / len * speed - chVy) * 0.2f * hdt * 3f
                }
            }
            sampleWater(chX, chY, false)
            chX += (chVx + wx * 0.6f) * hdt
            chY += (chVy + wy * 0.6f) * hdt
            // push out of walls
            for (w in blocks) {
                if (!w.alive) continue
                val qx = chX.coerceIn(w.l, w.r)
                val qy = chY.coerceIn(w.t, w.b)
                val ddx = chX - qx
                val ddy = chY - qy
                val d2 = ddx * ddx + ddy * ddy
                val rr = chR * 0.8f
                if (d2 < rr * rr && d2 > 0.0001f) {
                    val dist = sqrt(d2)
                    chX += ddx / dist * (rr - dist)
                    chY += ddy / dist * (rr - dist)
                }
            }
            chX = chX.coerceIn(chR, exitX - chR)
            chY = chY.coerceIn(playT + chR * 0.6f, playB - chR * 0.6f)
        }
        val chSp = hypot(chVx, chVy)
        if (chSp > 0.3f * d) chHeading = Math.toDegrees(atan2(chVy.toDouble(), chVx.toDouble())).toFloat()

        if (!stunned) {
            val dist = hypot(chX - cx, chY - cy)
            if (dist < r + chR * 0.75f) {
                caught(now, "CHOMPED!")
                return
            }
            if (chMode == MODE_HUNT && dist < 120 * d && now - lastCloseWarn > 5000) {
                lastCloseWarn = now
                say("It's right behind you!", true)
            }
        }
    }

    private fun pickWander() {
        val sniff = level >= 6 && Random.nextFloat() < 0.4f
        for (k in 0 until 40) {
            val x = if (sniff) (cx + (Random.nextFloat() - 0.5f) * 240 * d).coerceIn(40 * d, exitX - 40 * d)
                else 60 * d + Random.nextFloat() * (exitX - 100 * d)
            val y = playT + 20 * d + Random.nextFloat() * (playB - playT - 40 * d)
            if (!pointInWall(x, y, chR)) {
                wanderX = x
                wanderY = y
                return
            }
        }
        wanderX = cx
        wanderY = cy
    }

    // ============================================================
    // shells
    // ============================================================

    private fun fire(now: Long) {
        if (ammo.isEmpty()) {
            sfx?.play("wrong", 0.3f, 200L)
            popup = "NO SHELLS - GRAB SOME!"
            popupAt = now
            return
        }
        val type = ammo.removeAt(0)
        var dx = faceX
        var dy = faceY
        if (chActive) {
            val ddx = chX - cx
            val ddy = chY - cy
            val len = hypot(ddx, ddy)
            if (len > 1f) {
                dx = ddx / len
                dy = ddy / len
            }
        }
        val sp = 9f * d
        shells.add(Shell(cx + dx * (r + 6 * d), cy + dy * (r + 6 * d), dx * sp, dy * sp, type,
            if (type == P_RED) 5 else 0, 150f))
        sfx?.play("launch", 0.8f, 80L, 1.2f)
    }

    private fun shellColor(type: Int): Int = when (type) {
        P_GREEN -> 0xFF43A047.toInt()
        P_RED -> 0xFFE53935.toInt()
        else -> 0xFFFF9100.toInt()
    }

    private fun updateShells(now: Long, dt: Float) {
        val hdt = dt / 3f
        val sr = 6f * d
        val it = shells.iterator()
        while (it.hasNext()) {
            val s = it.next()
            var dead = false
            for (step in 0 until 3) {
                val ox = s.x
                val oy = s.y
                s.x += s.vx * hdt
                s.y += s.vy * hdt
                s.life -= hdt
                if (s.life <= 0f || s.x < -20 * d) { dead = true; break }
                // hit the shark
                if (chActive && hypot(s.x - chX, s.y - chY) < sr + chR) {
                    if (s.type == P_BOMB) explode(s.x, s.y, now) else {
                        stunnedUntil = now + 2500L
                        chVx += s.vx * 0.4f
                        chVy += s.vy * 0.4f
                        score += 150
                        sfx?.play("bonk", 1f, 0L)
                        celebrate("BONK!", false)
                        say("BONK! Ha! Got him!", true)
                        burst(s.x, s.y, shellColor(s.type), 18)
                    }
                    dead = true
                    break
                }
                // hit a wall
                if (pointInWall(s.x, s.y, sr)) {
                    when (s.type) {
                        P_RED -> {
                            if (s.bounces <= 0) { dead = true; burst(s.x, s.y, shellColor(s.type), 8); break }
                            s.bounces--
                            // reflect off whichever side we came through
                            val hitX = pointInWall(s.x, oy, sr)
                            val hitY = pointInWall(ox, s.y, sr)
                            if (hitX || !hitY) s.vx = -s.vx
                            if (hitY || !hitX) s.vy = -s.vy
                            s.x = ox
                            s.y = oy
                            sfx?.play("clink", 0.6f, 60L, 1.5f)
                        }
                        P_BOMB -> { explode(s.x, s.y, now); dead = true; break }
                        else -> { burst(s.x, s.y, shellColor(s.type), 8); sfx?.play("tick", 0.5f, 60L); dead = true; break }
                    }
                }
            }
            if (dead) it.remove()
        }
    }

    private fun explode(x: Float, y: Float, now: Long) {
        val rad = 95 * d
        flash = 0.5f
        flashColor = 0xFFFFAB40.toInt()
        sfx?.play("bust", 1f, 0L)
        sfx?.play("rumble", 0.8f, 0L)
        for (k in 0 until 50) {
            val a = Random.nextFloat() * 6.283f
            val sp = (1 + Random.nextFloat() * 6) * d
            spawnParticle(x, y, cos(a) * sp, sin(a) * sp,
                if (Random.nextBoolean()) 0xFFFF9100.toInt() else 0xFFFFEB3B.toInt(), (3 + Random.nextFloat() * 4) * d, 40f)
        }
        if (chActive && hypot(chX - x, chY - y) < rad) {
            stunnedUntil = now + 5000L
            val dd = hypot(chX - x, chY - y).coerceAtLeast(1f)
            chVx = (chX - x) / dd * 6 * d
            chVy = (chY - y) / dd * 6 * d
            score += 300
            say("KABOOM! He's seeing stars!", true)
        }
        var broke = false
        for (w in blocks) {
            if (!w.pillar || !w.alive) continue
            if (hypot((w.l + w.r) / 2f - x, (w.t + w.b) / 2f - y) < rad) {
                w.alive = false
                broke = true
                burst((w.l + w.r) / 2f, (w.t + w.b) / 2f, 0xFF8D6E63.toInt(), 24)
            }
        }
        if (broke) refreshGrid()
        celebrate("KABOOM!", true)
    }

    // ============================================================
    // pickups, columns, celebrations
    // ============================================================

    private fun collectPickups(now: Long) {
        var applesEaten = 0
        val it = pickups.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.x < tideX) { it.remove(); continue }
            if (hypot(cx - p.x, cy - p.y) > r + 13 * d) continue
            when (p.type) {
                P_APPLE -> {
                    score += 25
                    sfx?.play("pop", 1f, 40L)
                    celebrate(yum.random())
                    fillReel(now)
                }
                P_SPEED -> {
                    speedUntil = now + 5000L
                    sfx?.play("woo", 1f, 0L)
                    celebrate("ZOOOM!")
                    say("Speed kelp! ZOOOM!", true)
                }
                else -> {
                    if (ammo.size >= maxAmmo) {
                        popup = "SHELL POUCH FULL"
                        popupAt = now
                        continue
                    }
                    ammo.add(p.type)
                    sfx?.play("clink", 1f, 0L)
                    say(when (p.type) {
                        P_GREEN -> "Green shell! Tap the shell button to fire!"
                        P_RED -> "Red shells bounce off walls!"
                        else -> "BOMB shell! It blows up pillars too!"
                    }, true)
                }
            }
            burst(p.x, p.y, if (p.type == P_APPLE) 0xFFE53935.toInt() else if (p.type == P_SPEED) 0xFFFFEB3B.toInt() else shellColor(p.type), 12)
            it.remove()
            if (p.type == P_APPLE) applesEaten++
        }
        repeat(applesEaten) { spawnPickup(P_APPLE) }
    }

    private fun checkColumns() {
        while (nextCol < colX.size && cx - r > colX[nextCol] + wallThick) {
            val col = nextCol
            nextCol++
            when {
                col == ripCol && narrowH > 0f && abs(cy - narrowC) < narrowH -> celebrate("THREADED THE NEEDLE!", true)
                col == ripCol -> celebrate("BEAT THE RIPTIDE!", true)
                !touchedSinceCol -> celebrate(smooth.random())
                else -> {
                    sfx?.play("ding", 0.6f, 0L, min(2f, 0.9f + 0.1f * nextCol))
                    popup = "WALL $nextCol/${colX.size}"
                    popupAt = android.os.SystemClock.uptimeMillis()
                }
            }
            touchedSinceCol = false
        }
    }

    fun celebrate(text: String, big: Boolean = false) {
        cheerStreak++
        praise = text
        praiseAt = android.os.SystemClock.uptimeMillis()
        praiseBig = big
        sfx?.play("ding", 0.9f, 0L, min(2f, 0.9f + 0.1f * cheerStreak))
        if (big) {
            sfx?.play("coins", 1f, 200L)
            confetti(cx, cy)
            confetti(W * (0.2f + 0.6f * Random.nextFloat()), H * 0.35f)
        }
    }

    fun say(text: String, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now < octoNextAt) return
        octoText = text
        octoAt = now
        octoNextAt = now + 2500L
    }

    // ============================================================
    // slots
    // ============================================================

    private fun resetReels() {
        reels[0] = -1; reels[1] = -1; reels[2] = -1
        reelCount = 0
        reelResolving = false
        pendingFills = 0
    }

    private fun rollSymbol(): Int {
        var n = Random.nextInt(symbolWeights.sum())
        for (i in symbolWeights.indices) {
            if (n < symbolWeights[i]) return i
            n -= symbolWeights[i]
        }
        return 0
    }

    private fun fillReel(now: Long) {
        if (reelResolving || reelCount >= 3) {
            pendingFills++
            return
        }
        reels[reelCount] = rollSymbol()
        reelCount++
        when (reelCount) {
            1 -> sfx?.play("pop", 1f, 0L)
            2 -> sfx?.play("coins", 1f, 0L)
            else -> sfx?.play("slot", 1f, 0L)
        }
        if (reelCount == 3) {
            reelResolving = true
            reelClearAt = now + 1300L
            val a = reels[0]
            val b = reels[1]
            val c = reels[2]
            val base = tier()
            val pay: Int
            val text: String
            if (a == b && b == c) {
                if (a == 3) {
                    pay = base * 10
                    text = "777 JACKPOT! +$pay"
                    sfx?.play("jackpot", 1f, 0L)
                    celebrate("777!!!", true)
                } else {
                    pay = base * 5
                    text = "3 OF A KIND! +$pay"
                    celebrate("JACKPOT!", true)
                }
                sfx?.play("siren", 1f, 0L)
                flash = 0.6f
                flashColor = 0xFFFFF59D.toInt()
            } else if (a == b || b == c || a == c) {
                pay = base * 2
                text = "PAIR! +$pay"
                celebrate("NICE PAIR!")
            } else {
                pay = base / 2
                text = "NO MATCH +$pay"
                sfx?.play("wrong", 0.4f, 0L)
            }
            score += pay
            banner = text
            bannerUntil = now + 1600L
        }
    }

    private fun finishReels() {
        resetReelsKeepPending()
        val n = min(3, pendingFills)
        pendingFills -= n
        val now = android.os.SystemClock.uptimeMillis()
        repeat(n) { fillReel(now) }
    }

    private fun resetReelsKeepPending() {
        reels[0] = -1; reels[1] = -1; reels[2] = -1
        reelCount = 0
        reelResolving = false
    }

    /** Escape points: 100 / 300 / 600, and 1000 when the tide is almost on you. */
    fun tier(): Int {
        val frac = ((tideX - 0f) / exitX).coerceIn(0f, 1f)
        return when {
            frac >= 0.9f -> 1000
            frac >= 2f / 3f -> 600
            frac >= 1f / 3f -> 300
            else -> 100
        }
    }

    fun band(): Int = when {
        level <= 5 -> 1
        level <= 10 -> 2
        level <= 20 -> 3
        else -> 5
    }

    // ============================================================
    // end of level
    // ============================================================

    private fun saveBest() {
        if (score > best) {
            best = score
            prefs.edit().putInt("best_score", best).apply()
        }
    }

    private fun levelClear(now: Long) {
        over = true
        val bandM = band()
        val pts = tier() * bandM
        val secs = (now - levelStartMs) / 1000f
        val bonus = when {
            secs < 12f -> 500
            secs < 20f -> 250
            secs < 30f -> 100
            else -> 0
        }
        val clean = if (!touchedWall) 500 * bandM else 0
        val explorer = if (zones.isNotEmpty() && currentsVisited >= zones.size) 300 * bandM else 0
        val direct = if (currentsVisited == 0) 200 * bandM else 0
        score += pts + bonus + clean + explorer + direct
        saveBest()
        sfx?.play("fanfare", 1f, 0L)

        val styles = (if (clean > 0) 1 else 0) + (if (explorer > 0) 1 else 0) + (if (direct > 0) 1 else 0)
        val clutch = tier() >= 1000
        stars = 1 + (if (styles >= 1) 1 else 0) + (if (styles >= 2 || clutch || bonus >= 250) 1 else 0)
        starsAt = now
        if (stars == 3) sfx?.play("jackpot", 1f, 0L)
        celebrate(when {
            clutch -> "CLUTCH!!"
            clean > 0 && styles >= 2 -> "TURTLEY AWESOME!"
            clean > 0 -> "SHELLTASTIC!"
            direct > 0 -> "STRAIGHT SHOT!"
            explorer > 0 -> "EXPLORER!"
            else -> "ESCAPED!"
        }, true)
        for (k in 0 until 4) confetti(W * (0.15f + 0.7f * Random.nextFloat()), H * (0.25f + 0.4f * Random.nextFloat()))
        val lines = ArrayList<String>()
        lines.add("ESCAPE +${pts + bonus}")
        lines.add("%.1fs".format(secs))
        if (clean > 0) lines.add("CLEAN +$clean")
        if (explorer > 0) lines.add("EXPLORER +$explorer")
        if (direct > 0) lines.add("DIRECT +$direct")
        message = "LEVEL $level CLEARED!"
        banner = lines.joinToString("  ")
        bannerUntil = now + 3000L
        say(arrayOf("You made it!", "Woohoo! Next room!", "Betito is unstoppable!").random(), true)
        level++
        resumeNewGame = false
        resumeAt = now + 3400L
    }

    private fun caught(now: Long, why: String) {
        over = true
        dying = true
        saveBest()
        sfx?.play("squish", 1f, 0L)
        bumpAt = now
        val soClose = cx > exitX - 70 * d && cy > exitGapT - 30 * d && cy < exitGapB + 30 * d
        lives--
        say(if (why == "CHOMPED!") "Noooo! The shark got him!" else "Ouch! Too slow for the tide!", true)
        if (lives > 0) {
            message = if (soClose) "SO CLOSE!" else why
            banner = if (lives == 1) "LAST LIFE!" else "$lives LIVES LEFT"
            bannerUntil = now + 2500L
            resumeNewGame = false
            resumeAt = now + 2600L
        } else {
            sfx?.play("lose", 1f, 0L)
            message = "GAME OVER"
            banner = "FINAL SCORE $score  (LEVEL $level)"
            bannerUntil = now + 3400L
            resumeNewGame = true
            resumeAt = now + 3600L
        }
    }

    // ============================================================
    // scenery + particles
    // ============================================================

    private fun updateScenery(now: Long, dt: Float) {
        for (f in fish) {
            var fx = f.vx
            var fy = 0f
            val dd = hypot(f.x - cx, f.y - cy)
            if (dd < 80 * d && dd > 1f) {
                // scatter away from Betito
                fx += (f.x - cx) / dd * 3 * d
                fy += (f.y - cy) / dd * 3 * d
            }
            f.x += fx * dt
            f.y += fy * dt + sin((now / 600.0 + f.size).toFloat()) * 0.15f * d * dt
            if (f.x > W + 30 * d) f.x = -30 * d
            if (f.x < -30 * d) f.x = W + 30 * d
            f.y = f.y.coerceIn(topInset + 60 * d, H - 10 * d)
        }
        for (b in bubbles) {
            b.y -= b.speed * dt
            b.x += sin((now / 500.0 + b.seed).toFloat()) * 0.2f * d * dt
            if (b.y < topInset + 40 * d) {
                b.y = H + Random.nextFloat() * 40 * d
                b.x = Random.nextFloat() * W
            }
            if (!over && hypot(b.x - cx, b.y - cy) < r + b.rad) {
                sfx?.play("pop", 0.2f, 120L, 1.8f)
                for (k in 0 until 5) spawnParticle(b.x, b.y, (Random.nextFloat() - 0.5f) * 3 * d,
                    (Random.nextFloat() - 0.5f) * 3 * d, 0xCCE1F5FE.toInt(), 2 * d, 15f)
                b.y = H + Random.nextFloat() * 40 * d
                b.x = Random.nextFloat() * W
            }
        }
        // speed trail
        if (!over && now < speedUntil) spawnParticle(cx - faceX * r, cy - faceY * r, 0f, 0f, 0xAAFFEB3B.toInt(), 4 * d, 18f)
    }

    fun spawnParticle(x: Float, y: Float, vx0: Float, vy0: Float, color: Int, size: Float, life: Float) {
        val i = pNext
        pNext = (pNext + 1) % pMax
        px[i] = x
        py[i] = y
        pvx[i] = vx0
        pvy[i] = vy0
        pColor[i] = color
        pSize[i] = size
        pLife[i] = life
        pMaxLife[i] = life
    }

    private fun updateParticles(dt: Float) {
        for (i in 0 until pMax) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dt
            px[i] += pvx[i] * dt
            py[i] += pvy[i] * dt
            pvx[i] *= 0.95f
            pvy[i] *= 0.95f
        }
    }

    private fun burst(x: Float, y: Float, color: Int, n: Int) {
        for (k in 0 until n) {
            val a = Random.nextFloat() * 6.283f
            val sp = (1 + Random.nextFloat() * 4) * d
            spawnParticle(x, y, cos(a) * sp, sin(a) * sp, color, (2 + Random.nextFloat() * 3) * d, 30f)
        }
    }

    private fun confetti(x: Float, y: Float) {
        for (k in 0 until 36) {
            val a = Random.nextFloat() * 6.283f
            val sp = (2 + Random.nextFloat() * 7) * d
            spawnParticle(x, y, cos(a) * sp, sin(a) * sp, neon[Random.nextInt(neon.size)], (3 + Random.nextFloat() * 3) * d, 45f)
        }
    }
}
