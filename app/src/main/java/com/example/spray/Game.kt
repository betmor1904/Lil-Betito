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

/** A wall rectangle. Pillars are small rocks in the lanes. */
class Block(val l: Float, val t: Float, val r: Float, val b: Float, val pillar: Boolean = false) {
    var alive = true
}

/** Something to pick up: an apple (spins a slot reel) or speed kelp (stronger shots). */
class Pickup(val x: Float, val y: Float, val type: Int, val seed: Float = Random.nextFloat() * 6f)

/** A seaweed patch: a sticky landing spot that soaks up speed. */
class Weed(val l: Float, val t: Float, val r: Float, val b: Float, val seed: Float = Random.nextFloat() * 6f)

/** A buoy target. Hit it with a shot and its linked current flips direction. */
class Target(val x: Float, val y: Float, val zone: WaterZone) {
    var hitAt = 0L
}

/** A shark on patrol between two points. He never eats Betito, he just knocks his shots off course. */
class Shark(var x: Float, var y: Float, val ax: Float, val ay: Float, val bx: Float, val by: Float, val speed: Float) {
    var toB = true
    var vx = 0f
    var vy = 0f
    var heading = 0f
    var bumpAt = 0L
}

class Fish(var x: Float, var y: Float, var vx: Float, val size: Float, val color: Int)

class Bubble(var x: Float, var y: Float, val rad: Float, val speed: Float, val seed: Float)

/**
 * All the game rules for Lil Betito, independent of how it is drawn.
 *
 * Underwater golf: Betito is the ball. Pull back and let go to shoot him toward the exit gap
 * in the right wall in as few shots as possible (par). Currents are boost lanes: ride several
 * in one shot for combos. Hit buoys to flip a current's direction. Sharks patrol and knock
 * your shots off course.
 */
class Game(val d: Float, private val sfx: Sfx?, private val prefs: android.content.SharedPreferences) {

    companion object {
        const val P_APPLE = 0
        const val P_SPEED = 4
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
    val targets = ArrayList<Target>()
    val sharks = ArrayList<Shark>()
    val fish = ArrayList<Fish>()
    val bubbles = ArrayList<Bubble>()
    var colX = FloatArray(0)
    var colGap = FloatArray(0)
    private var ripCol = -1
    private var narrowC = 0f
    private var narrowH = 0f
    private var nextCol = 0
    val targetR get() = 13f * d
    val sharkR get() = 15f * d

    // ---------- the turtle ----------
    val r get() = 13f * d
    var cx = 0f
    var cy = 0f
    var vx = 0f
    var vy = 0f
    var faceX = 1f
    var faceY = 0f
    var heading = 0f
    var bumpAt = 0L
    var kickAt = 0L
    var speedUntil = 0L

    // ---------- slingshot input (set by the view) ----------
    var aiming = false
    var aimDX = 1f        // launch direction (unit vector)
    var aimDY = 0f
    var aimPower = 0f     // 0..1, how far you pulled back
    var launchQueued = false

    // ---------- shot rules ----------
    val readySpeed get() = 0.9f * d     // he can only shoot again once he has (nearly) stopped
    private val waterDrag = 0.025f
    private val rideDrag = 0.006f       // riding a current barely slows you
    private val weedDrag = 0.16f
    private val bounceK = 0.65f         // how much speed a bounce keeps (into the wall)
    private val slideK = 0.85f          // how much speed is kept sliding along the wall
    private val maxSpeed get() = 15f * d
    var ready = true
    var shots = 0
    var par = 5
    var maxShots = 10
    private var bouncesThisShot = 0
    private val shotZones = HashSet<Int>()
    var shotCombo = 0                   // currents ridden in the current shot
    var comboAt = 0L
    private var targetsThisShot = 0
    private var sharkThisShot = false
    var bestCombo = 0

    // aim preview: the first part of the path, simulated with real physics
    val pvMax = 30
    val previewX = FloatArray(pvMax)
    val previewY = FloatArray(pvMax)
    var previewN = 0

    // ---------- game state ----------
    var level = 1
    var score = 0
    var best = prefs.getInt("best_score", 0)
    var over = false
    private var resumeAt = 0L
    var levelStartMs = 0L
    var levelEndMs = 0L

    // explorer bonus
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
    var shakeAt = 0L
    var shakeAmp = 0f
    var octoText: String? = null
    var octoAt = 0L
    private var octoNextAt = 0L
    private var cheerStreak = 0

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
    private val smooth = arrayOf("SMOOTH!", "SLICK!", "SHELL YEAH!", "NICE!!", "SO FRESH!")
    private val yum = arrayOf("SWEET!", "YUMMY!", "TASTY!")

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
        resumeAt = 0L
        val now = android.os.SystemClock.uptimeMillis()
        levelStartMs = now
        levelEndMs = 0L
        playT = topInset + 50 * d
        playB = H - 12 * d
        exitX = W - 18 * d
        message = "HOLE $level"
        stars = 0
        praise = null
        cheerStreak = 0
        shots = 0
        bouncesThisShot = 0
        shotZones.clear()
        shotCombo = 0
        targetsThisShot = 0
        sharkThisShot = false
        bestCombo = 0
        ready = true
        aiming = false
        launchQueued = false
        previewN = 0
        currentsVisited = 0
        visited.clear()
        resetReels()
        speedUntil = 0L

        buildMaze()
        par = colX.size + 2
        maxShots = par * 2
        banner = "PAR $par  ·  HIT BUOYS TO FLIP CURRENTS"
        bannerUntil = now + 3000

        cx = 48 * d
        cy = (playT + playB) / 2f
        vx = 0f
        vy = 0f
        faceX = 1f
        faceY = 0f
        heading = 0f

        placeSharks()

        say(when (level) {
            1 -> "Pull back and let go! Ride currents for combos. Par is $par."
            2 -> "Hit a buoy to flip its current. Sharks bump your shots!"
            else -> "Par $par. Chain currents for a DEADLY COMBO!"
        }, true)
    }

    // ============================================================
    // the course
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
        targets.clear()
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
        ripCol = if (level >= 3 && n > 1) (fwdCol + 1 + Random.nextInt(n - 1)) % n else -1
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

        // forward stream: a boost lane carrying you right through one gap
        val fwd = run {
            val h = min(gapH - 8 * d, 56 * d)
            val z = WaterZone(WaterZone.UP, laneL(fwdCol - 1), colGap[fwdCol] - h / 2f, laneR(fwdCol),
                colGap[fwdCol] + h / 2f, 1f, 0f, 3.6f * d, pulse = pulse && Random.nextBoolean(),
                phaseMs = Random.nextLong(12000))
            zones.add(z)
            z
        }

        // up/down stream in a lane. Sometimes it starts the wrong way: hit its buoy to flip it.
        fun laneStream(lane: Int, towardGap: Boolean): WaterZone? {
            if (lane < 0 || lane + 1 >= n) return null
            val target = colGap[lane + 1]
            val up = if (towardGap) target < mid else target >= mid
            val lw = laneR(lane) - laneL(lane)
            val bw = min(50 * d, lw * 0.55f)
            val cxL = (laneL(lane) + laneR(lane)) / 2f
            // the stream covers the whole lane height so it can be flipped and still be useful
            val z = WaterZone(WaterZone.SIDE, cxL - bw / 2f, playT + 4 * d, cxL + bw / 2f, playB - 4 * d,
                0f, if (up) -1f else 1f, 3f * d, pulse = pulse, phaseMs = Random.nextLong(12000))
            zones.add(z)
            return z
        }
        val streamLane = if (n > 1) (fwdCol + 1) % (n - 1) else 0
        val lane1 = laneStream(streamLane, Random.nextFloat() < 0.5f)

        // riptide: pushes you back left through the guarded gap (flip it with its buoy!)
        var rip: WaterZone? = null
        if (ripCol >= 0) {
            val h = min(gapH - 6 * d, 60 * d)
            rip = WaterZone(WaterZone.RIP, colX[ripCol] - 50 * d, colGap[ripCol] - h / 2f, laneR(ripCol),
                colGap[ripCol] + h / 2f, -1f, 0f, (2.4f + min(1.0f, 0.1f * (level - 3))) * d,
                pulse = pulse, phaseMs = Random.nextLong(12000))
            zones.add(rip)
        }

        // whirlpool in a lane
        var whirl: WaterZone? = null
        if (level >= 6 && n >= 2) {
            val lane = Random.nextInt(n - 1)
            val lw = laneR(lane) - laneL(lane)
            val rad = min(56 * d, lw / 2f + 6 * d)
            val wcx = (laneL(lane) + laneR(lane)) / 2f
            val wcy = playT + rad + Random.nextFloat() * max(1f, playH - 2 * rad)
            whirl = WaterZone(WaterZone.WHIRL, wcx - rad, wcy - rad, wcx + rad, wcy + rad, 0f, 0f, 3.2f * d,
                spin = if (Random.nextBoolean()) 1f else -1f)
            zones.add(whirl)
        }

        // a second lane stream from level 9
        val lane2 = if (level >= 9 && n >= 3) laneStream((streamLane + 1) % (n - 1), Random.nextBoolean()) else null

        // pillars in the lanes from level 4
        val pillars = if (level >= 4) min((level - 2) / 2, 5) else 0
        var placed = 0
        var tries = 0
        while (placed < pillars && tries < 80 && n >= 2) {
            tries++
            val lane = Random.nextInt(n - 1)
            val size = 28 * d
            val lw = laneR(lane) - laneL(lane)
            if (lw < size + 2 * r + 16 * d) continue
            val pxx = laneL(lane) + 10 * d + Random.nextFloat() * (lw - size - 20 * d)
            val pyy = playT + 12 * d + Random.nextFloat() * (playH - size - 24 * d)
            val pcy = pyy + size / 2f
            if (abs(pcy - colGap[lane]) < gapH + size) continue
            if (abs(pcy - colGap[lane + 1]) < gapH + size) continue
            if (blocks.any { it.pillar && abs(it.t - pyy) < size * 2 && abs(it.l - pxx) < size * 2 }) continue
            addWall(pxx, pyy, pxx + size, pyy + size, pillar = true)
            placed++
        }

        // seaweed landing pads
        val weedCount = 1 + min(2, level / 4)
        tries = 0
        while (weeds.size < weedCount && tries < 80) {
            tries++
            val lane = Random.nextInt(n)
            val ww = 44 * d
            val wh = 54 * d
            val lw = laneR(lane) - laneL(lane)
            if (lw < ww + 8 * d) continue
            val wl = laneL(lane) + 4 * d + Random.nextFloat() * (lw - ww - 8 * d)
            val wt = playT + 6 * d + Random.nextFloat() * (playH - wh - 12 * d)
            if (rectHitsWall(wl, wt, wl + ww, wt + wh, 6 * d)) continue
            if (zones.any { it.kind != WaterZone.WHIRL && wl < it.r && wl + ww > it.l && wt < it.b && wt + wh > it.t }) continue
            if (weeds.any { it.l < wl + ww + 10 * d && wl < it.r + 10 * d && it.t < wt + wh + 10 * d && wt < it.b + 10 * d }) continue
            weeds.add(Weed(wl, wt, wl + ww, wt + wh))
        }

        // buoys that flip currents
        lane1?.let { placeTarget(it) }
        rip?.let { placeTarget(it) }
        if (level >= 5) placeTarget(fwd)        // careful: hitting this one sends the boost lane backward
        whirl?.let { if (level >= 8) placeTarget(it) }
        lane2?.let { placeTarget(it) }

        // things to pick up
        repeat(if (level >= 5) 3 else 2) { spawnPickup(P_APPLE) }
        spawnPickup(P_SPEED)
    }

    private fun placeTarget(z: WaterZone) {
        val pad = targetR + 10 * d
        for (k in 0 until 80) {
            val near = k < 50
            val x = if (near) z.cx + (Random.nextFloat() - 0.5f) * 300 * d else 110 * d + Random.nextFloat() * (exitX - 150 * d)
            val y = if (near) z.cy + (Random.nextFloat() - 0.5f) * 240 * d else playT + pad + Random.nextFloat() * (playB - playT - 2 * pad)
            if (x < 100 * d || x > exitX - 30 * d || y < playT + pad || y > playB - pad) continue
            if (pointInWall(x, y, pad)) continue
            if (zones.any { insideZone(it, x, y, targetR) }) continue
            if (weeds.any { x > it.l - pad && x < it.r + pad && y > it.t - pad && y < it.b + pad }) continue
            if (targets.any { hypot(it.x - x, it.y - y) < 60 * d }) continue
            targets.add(Target(x, y, z))
            return
        }
    }

    private fun insideZone(z: WaterZone, x: Float, y: Float, pad: Float): Boolean =
        if (z.kind == WaterZone.WHIRL) hypot(x - z.cx, y - z.cy) < z.rad + pad
        else x > z.l - pad && x < z.r + pad && y > z.t - pad && y < z.b + pad

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

    private fun clearLine(x0: Float, y0: Float, x1: Float, y1: Float, pad: Float): Boolean {
        val dist = hypot(x1 - x0, y1 - y0)
        val steps = max(1, (dist / (8 * d)).toInt())
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            if (pointInWall(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, pad)) return false
        }
        return true
    }

    private fun spawnPickup(type: Int) {
        for (k in 0 until 60) {
            val x = 110 * d + Random.nextFloat() * (exitX - 140 * d)
            val y = playT + 20 * d + Random.nextFloat() * (playB - playT - 40 * d)
            if (pointInWall(x, y, 18 * d)) continue
            if (pickups.any { hypot(it.x - x, it.y - y) < 60 * d }) continue
            if (targets.any { hypot(it.x - x, it.y - y) < 40 * d }) continue
            pickups.add(Pickup(x, y, type))
            return
        }
    }

    // ============================================================
    // sharks: patrol only
    // ============================================================

    private fun placeSharks() {
        sharks.clear()
        val count = when {
            level < 2 -> 0
            level < 8 -> 1
            else -> 2
        }
        val sp = min(2.2f, 1.1f + 0.06f * level) * d
        val n = colX.size
        var tries = 0
        while (sharks.size < count && tries < 40 && n >= 2) {
            tries++
            val ax: Float
            val ay: Float
            val bx: Float
            val by: Float
            if (Random.nextBoolean()) {
                // guard a gap: swim back and forth through it
                val i = 1 + Random.nextInt(n - 1)
                val g = colGap[i]
                ax = (laneL(i - 1) + laneR(i - 1)) / 2f
                bx = (laneL(i) + laneR(i)) / 2f
                ay = g
                by = g
            } else {
                // sweep up and down a lane
                val lane = Random.nextInt(n - 1)
                val lw = laneR(lane) - laneL(lane)
                val x = laneL(lane) + lw * (if (Random.nextBoolean()) 0.3f else 0.7f)
                ax = x
                bx = x
                ay = playT + sharkR + 6 * d
                by = playB - sharkR - 6 * d
            }
            if (!clearLine(ax, ay, bx, by, sharkR * 0.9f)) continue
            if (hypot(ax - cx, ay - cy) < 120 * d) continue
            if (sharks.any { abs(it.ax - ax) < 40 * d && abs(it.bx - bx) < 40 * d }) continue
            val k = Random.nextFloat()
            sharks.add(Shark(ax + (bx - ax) * k, ay + (by - ay) * k, ax, ay, bx, by, sp))
        }
        if (sharks.isNotEmpty() && level == 2) say("A shark! He won't bite, but he'll wreck your shot.", true)
    }

    private fun updateSharks(now: Long, dt: Float) {
        for (s in sharks) {
            val tx = if (s.toB) s.bx else s.ax
            val ty = if (s.toB) s.by else s.ay
            val dx = tx - s.x
            val dy = ty - s.y
            val len = hypot(dx, dy)
            if (len < 4 * d) {
                s.toB = !s.toB
                continue
            }
            s.vx += (dx / len * s.speed - s.vx) * min(1f, 0.08f * dt)
            s.vy += (dy / len * s.speed - s.vy) * min(1f, 0.08f * dt)
            s.x += s.vx * dt
            s.y += s.vy * dt
            if (hypot(s.vx, s.vy) > 0.2f * d) s.heading = Math.toDegrees(atan2(s.vy.toDouble(), s.vx.toDouble())).toFloat()
        }
    }

    /** Betito bounces off a shark like a moving rock, but it soaks up more of his speed. */
    private fun collideSharks(now: Long) {
        for (s in sharks) {
            val dx = cx - s.x
            val dy = cy - s.y
            val dist = hypot(dx, dy)
            val minD = r + sharkR * 0.85f
            if (dist >= minD || dist < 0.01f) continue
            val nx = dx / dist
            val ny = dy / dist
            cx += nx * (minD - dist)
            cy += ny * (minD - dist)
            val rvx = vx - s.vx
            val rvy = vy - s.vy
            val vn = rvx * nx + rvy * ny
            if (vn < 0f) {
                val tx = rvx - vn * nx
                val ty = rvy - vn * ny
                vx = -vn * 0.45f * nx + tx * 0.55f + s.vx
                vy = -vn * 0.45f * ny + ty * 0.55f + s.vy
                if (now - s.bumpAt > 600L) {
                    s.bumpAt = now
                    bumpAt = now
                    sharkThisShot = true
                    sfx?.play("bonk", 0.9f, 0L)
                    popup = "SHARK BUMP!"
                    popupAt = now
                    shake(5f, now)
                    say(arrayOf("Hey! Watch where you swim, shark!", "The shark knocked you off course!", "Bonk! Rude shark!").random())
                }
            }
        }
    }

    // ============================================================
    // water
    // ============================================================

    private var wx = 0f
    private var wy = 0f

    /** Adds up the water push at (x, y). When record is true, counts currents for combos and the explorer bonus. */
    private fun sampleWater(x: Float, y: Float, record: Boolean, now: Long): WaterZone? {
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
            if (record && inside && z.power > 0.5f) {
                if (visited.add(z.id)) {
                    currentsVisited++
                    if (currentsVisited >= zones.size && zones.size >= 2) celebrate("EXPLORER!", true)
                }
                if (shots > 0 && !ready && shotZones.add(z.id)) comboStep(now)
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
    // combos + target hits
    // ============================================================

    private fun comboStep(now: Long) {
        shotCombo = shotZones.size
        comboAt = now
        if (shotCombo > bestCombo) bestCombo = shotCombo
        val b = band()
        when (shotCombo) {
            1 -> sfx?.play("wee", 0.6f, 200L)
            2 -> {
                score += 100 * b
                celebrate("NICE!!")
                sfx?.play("coins", 0.8f, 0L)
            }
            3 -> {
                score += 300 * b
                celebrate("DEADLY COMBO!!", true)
                sfx?.play("slot", 1f, 0L)
                fillReel(now)
                shake(6f, now)
            }
            else -> {
                score += 600 * b * (shotCombo - 3)
                celebrate("KING OF THE SEA!!", true)
                sfx?.play("siren", 1f, 0L)
                sfx?.play("jackpot", 1f, 0L)
                flash = 0.5f
                flashColor = 0xFFFFF59D.toInt()
                fillReel(now)
                shake(10f, now)
            }
        }
    }

    private fun checkTargets(now: Long) {
        val sp = hypot(vx, vy)
        if (sp < readySpeed) return
        for (t in targets) {
            if (now - t.hitAt < 700L) continue
            if (hypot(cx - t.x, cy - t.y) > r + targetR) continue
            t.hitAt = now
            t.zone.flip()
            targetsThisShot++
            vx *= 0.85f
            vy *= 0.85f
            val b = band()
            sfx?.play("ding", 1f, 0L, 1.6f)
            sfx?.play("kaching", 0.9f, 0L)
            burst(t.x, t.y, 0xFFFFFFFF.toInt(), 16)
            confetti(t.x, t.y)
            if (bouncesThisShot > 0) {
                score += 300 * b
                celebrate("TRICK SHOT!!", true)
            } else {
                score += 150 * b
                celebrate("BULLSEYE!")
            }
            say(when (t.zone.kind) {
                WaterZone.UP -> "Current flipped! It's pushing you FORWARD now!"
                WaterZone.RIP -> "Uh oh, that current flips backward now!"
                WaterZone.WHIRL -> "The whirlpool spins the other way!"
                else -> "Current flipped! It flows the other way now!"
            }, true)
        }
    }

    // ============================================================
    // the frame
    // ============================================================

    fun update(now: Long, dt: Float) {
        if (W <= 0f || !started) return
        updateScenery(now, dt)
        updateParticles(dt)
        updateSharks(now, dt)
        if (flash > 0f) flash = max(0f, flash - 0.04f * dt)
        if (reelResolving && now >= reelClearAt) finishReels()
        if (banner != null && bannerUntil in 1..now) banner = null

        if (over) {
            if (resumeAt in 1..now) {
                resumeAt = 0L
                startLevel()
            }
            return
        }
        if (now - levelStartMs > 1500) message = null

        updatePulses(now, dt)

        // he can shoot again once he has (nearly) stopped
        val wasMoving = !ready
        ready = hypot(vx, vy) < readySpeed
        if (wasMoving && ready) endShot(now)
        if (launchQueued) {
            launchQueued = false
            if (ready) launch(now) else {
                sfx?.play("tick", 0.3f, 150L)
                popup = "WAIT TILL HE STOPS..."
                popupAt = now
            }
        }
        if (ready && shots >= maxShots) { outOfShots(now); return }

        val hdt = dt / 3f
        for (step in 0 until 3) {
            val whirl = sampleWater(cx, cy, true, now)
            val sticky = inWeed(cx, cy)
            val inFlow = (wx != 0f || wy != 0f) && !sticky
            val grip = if (sticky) 0.25f else 1f
            val drag = if (sticky) weedDrag else if (inFlow && !ready) rideDrag else waterDrag
            val k = 1f - min(1f, drag * hdt)
            vx *= k
            vy *= k
            if (inFlow && whirl == null && !ready) {
                // ride the current: speed builds up along the flow (not across it)
                val wl = hypot(wx, wy)
                val fx = wx / wl
                val fy = wy / wl
                val along = vx * fx + vy * fy
                val want = wl * 2.6f
                if (along < want) {
                    val add = (want - along) * min(1f, 0.035f * hdt)
                    vx += fx * add
                    vy += fy * add
                }
            }
            if (whirl != null) {
                // a whirlpool bends your glide around its center
                val ddx = cx - whirl.cx
                val ddy = cy - whirl.cy
                val len = hypot(ddx, ddy).coerceAtLeast(1f)
                val sp = hypot(vx, vy)
                vx += (-ddy / len * whirl.spin * sp - vx) * 0.04f * hdt
                vy += (ddx / len * whirl.spin * sp - vy) * 0.04f * hdt
            }
            val sp0 = hypot(vx, vy)
            if (sp0 > maxSpeed) {
                vx = vx / sp0 * maxSpeed
                vy = vy / sp0 * maxSpeed
            }
            cx += (vx + wx * grip) * hdt
            cy += (vy + wy * grip) * hdt

            if (cx < r) { cx = r; vx = abs(vx) * 0.6f }
            for (w in blocks) if (w.alive) collideTurtle(w.l, w.t, w.r, w.b, now)
            collideSharks(now)
            checkTargets(now)

            val sp = hypot(vx, vy)
            if (aiming && sp < readySpeed) {
                // pivot to face where you're aiming
                faceX = aimDX
                faceY = aimDY
            } else if (sp > 0.4f * d) {
                faceX = vx / sp
                faceY = vy / sp
            }

            // in the hole: through the exit gap
            if (cx > exitX + wallThick * 0.5f && cy > exitGapT && cy < exitGapB) { levelClear(now); return }
        }
        heading = Math.toDegrees(atan2(faceY.toDouble(), faceX.toDouble())).toFloat()
        if (aiming) computePreview(now) else previewN = 0

        collectPickups(now)
        checkColumns()
    }

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
            // rocks bounce you: this is what makes bank shots work
            val vtx = vx - vn * nx
            val vty = vy - vn * ny
            vx = -vn * bounceK * nx + vtx * slideK
            vy = -vn * bounceK * ny + vty * slideK
            if (-vn > 1.2f * d) {
                bumpAt = now
                bouncesThisShot++
                sfx?.play("clink", 0.25f + min(0.5f, -vn / (12 * d)), 60L, 1.3f)
                for (k in 0 until 4) spawnParticle(cx - nx * r, cy - ny * r, nx * 1.5f * d + (Random.nextFloat() - 0.5f) * 2 * d,
                    ny * 1.5f * d + (Random.nextFloat() - 0.5f) * 2 * d, 0xCCB39DDB.toInt(), 2.5f * d, 20f)
            }
        }
    }

    private fun inWeed(x: Float, y: Float): Boolean =
        weeds.any { x > it.l && x < it.r && y > it.t && y < it.b }

    private fun launchSpeed(now: Long): Float = (if (now < speedUntil) 12f else 9f) * d

    /** How far a shot of this power glides in still water (for the distance ring). */
    fun stillDistance(now: Long): Float {
        val v = launchSpeed(now) * aimPower.coerceIn(0.12f, 1f)
        val k = 1f - waterDrag / 3f
        return v / 3f * k / (1f - k)
    }

    private fun launch(now: Long) {
        val p = aimPower.coerceIn(0.12f, 1f)
        val sp = launchSpeed(now) * p
        vx = aimDX * sp
        vy = aimDY * sp
        ready = false
        shots++
        bouncesThisShot = 0
        shotZones.clear()
        shotCombo = 0
        targetsThisShot = 0
        sharkThisShot = false
        kickAt = now
        sfx?.play("launch", 0.4f + 0.5f * p, 0L, 1.6f - 0.5f * p)
        for (k in 0 until 8) spawnParticle(cx - aimDX * r, cy - aimDY * r,
            -aimDX * 2 * d + (Random.nextFloat() - 0.5f) * 2 * d, -aimDY * 2 * d + (Random.nextFloat() - 0.5f) * 2 * d,
            0x99FFFFFF.toInt(), 3 * d, 25f)
        when (shots) {
            par -> say("This shot is for PAR!", true)
            par + 1 -> say("Over par now... make it count!", true)
            maxShots -> say("Last shot! Make it count!", true)
        }
    }

    /** A shot has come to rest: the big combo-plus-buoy shots get the top praise. */
    private fun endShot(now: Long) {
        if (shots == 0) return
        val b = band()
        if (shotCombo >= 2 && targetsThisShot > 0 && !sharkThisShot) {
            score += 500 * b
            celebrate("PERFECT!!", true)
            sfx?.play("jackpot", 1f, 0L)
            fillReel(now)
        } else if (inWeed(cx, cy) && shotCombo == 0) {
            score += 50 * b
            celebrate("STUCK THE LANDING!")
        }
        shotCombo = 0
    }

    // simulation state for the aim preview
    private var sx = 0f
    private var sy = 0f
    private var svx = 0f
    private var svy = 0f

    /** Runs the real swim physics forward a short way so the dotted aim line bends with currents and banks off walls. */
    private fun computePreview(now: Long) {
        val sp = launchSpeed(now) * aimPower.coerceIn(0.12f, 1f)
        sx = cx
        sy = cy
        svx = aimDX * sp
        svy = aimDY * sp
        previewN = 0
        // only part of the path is shown: reading the rest is the skill
        for (f in 0 until pvMax) {
            for (s in 0 until 3) {
                val hdt = 1f / 3f
                sampleWater(sx, sy, false, now)
                val sticky = inWeed(sx, sy)
                val inFlow = (wx != 0f || wy != 0f) && !sticky
                val grip = if (sticky) 0.25f else 1f
                val k = 1f - (if (sticky) weedDrag else if (inFlow) rideDrag else waterDrag) * hdt
                svx *= k
                svy *= k
                sx += (svx + wx * grip) * hdt
                sy += (svy + wy * grip) * hdt
                if (sx < r) { sx = r; svx = abs(svx) * 0.6f }
                for (w in blocks) {
                    if (!w.alive) continue
                    val qx = sx.coerceIn(w.l, w.r)
                    val qy = sy.coerceIn(w.t, w.b)
                    val dx = sx - qx
                    val dy = sy - qy
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r * r || d2 < 0.0001f) continue
                    val dist = sqrt(d2)
                    val nx = dx / dist
                    val ny = dy / dist
                    sx += nx * (r - dist)
                    sy += ny * (r - dist)
                    val vn = svx * nx + svy * ny
                    if (vn < 0f) {
                        val vtx = svx - vn * nx
                        val vty = svy - vn * ny
                        svx = -vn * bounceK * nx + vtx * slideK
                        svy = -vn * bounceK * ny + vty * slideK
                    }
                }
            }
            previewX[previewN] = sx
            previewY[previewN] = sy
            previewN++
            if (sx > exitX || hypot(svx, svy) < 0.3f * d) break
        }
    }

    // ============================================================
    // pickups, columns, celebrations
    // ============================================================

    private fun collectPickups(now: Long) {
        var applesEaten = 0
        val it = pickups.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (hypot(cx - p.x, cy - p.y) > r + 13 * d) continue
            if (p.type == P_APPLE) {
                score += 25
                sfx?.play("pop", 1f, 40L)
                celebrate(yum.random())
                fillReel(now)
                applesEaten++
            } else {
                speedUntil = now + 8000L
                sfx?.play("woo", 1f, 0L)
                celebrate("POWER UP!")
                say("Speed kelp! Your shots go further for 8 seconds!", true)
            }
            burst(p.x, p.y, if (p.type == P_APPLE) 0xFFE53935.toInt() else 0xFFFFEB3B.toInt(), 12)
            it.remove()
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
                bouncesThisShot > 0 && !ready -> celebrate("BANK SHOT!")
                else -> {
                    sfx?.play("ding", 0.6f, 0L, min(2f, 0.9f + 0.1f * nextCol))
                    popup = "WALL $nextCol/${colX.size}"
                    popupAt = android.os.SystemClock.uptimeMillis()
                }
            }
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
            shake(4f, praiseAt)
        }
    }

    private fun shake(amp: Float, now: Long) {
        if (now - shakeAt < 250L && shakeAmp > amp) return
        shakeAt = now
        shakeAmp = amp * d
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
            val base = 200 * band()
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
        reels[0] = -1; reels[1] = -1; reels[2] = -1
        reelCount = 0
        reelResolving = false
        val n = min(3, pendingFills)
        pendingFills -= n
        val now = android.os.SystemClock.uptimeMillis()
        repeat(n) { fillReel(now) }
    }

    fun band(): Int = when {
        level <= 5 -> 1
        level <= 10 -> 2
        level <= 20 -> 3
        else -> 5
    }

    // ============================================================
    // end of a hole
    // ============================================================

    private fun saveBest() {
        if (score > best) {
            best = score
            prefs.edit().putInt("best_score", best).apply()
        }
    }

    fun golfName(shotsTaken: Int, parN: Int): String {
        val under = parN - shotsTaken
        return when {
            shotsTaken == 1 -> "HOLE IN ONE!!"
            under >= 3 -> "ALBATROSS!!!"
            under == 2 -> "EAGLE!!"
            under == 1 -> "BIRDIE!"
            under == 0 -> "PAR"
            under == -1 -> "BOGEY"
            under == -2 -> "DOUBLE BOGEY"
            else -> "+${-under} OVER PAR"
        }
    }

    private fun levelClear(now: Long) {
        over = true
        levelEndMs = now
        endShot(now)
        val b = band()
        val secs = (now - levelStartMs) / 1000f
        val under = par - shots
        val name = golfName(shots, par)
        val parPts = when {
            shots == 1 -> 3000 * b
            under >= 0 -> (300 + 400 * under) * b
            else -> 0
        }
        val speed = when {
            secs < par * 3f -> 500 * b
            secs < par * 5f -> 250 * b
            secs < par * 8f -> 100 * b
            else -> 0
        }
        val explorer = if (zones.size >= 2 && currentsVisited >= zones.size) 300 * b else 0
        score += 200 * b + parPts + speed + explorer
        saveBest()
        sfx?.play("fanfare", 1f, 0L)

        stars = when {
            under >= 1 -> 3
            under == 0 -> 2
            else -> 1
        }
        starsAt = now
        if (under >= 1) {
            sfx?.play("jackpot", 1f, 0L)
            sfx?.play("siren", 0.8f, 0L)
            flash = 0.5f
            flashColor = 0xFFFFF59D.toInt()
            shake(10f, now)
        }
        celebrate(when {
            under >= 0 -> name
            else -> "IN THE HOLE!"
        }, under >= 0)
        for (k in 0 until (if (under >= 1) 7 else 3)) confetti(W * (0.15f + 0.7f * Random.nextFloat()), H * (0.25f + 0.4f * Random.nextFloat()))
        val lines = ArrayList<String>()
        lines.add("$shots SHOTS · PAR $par")
        if (parPts > 0) lines.add("${if (shots == 1) "ACE" else name.trimEnd('!')} +$parPts")
        if (speed > 0) lines.add("SPEED +$speed")
        if (explorer > 0) lines.add("EXPLORER +$explorer")
        if (bestCombo >= 2) lines.add("BEST COMBO x$bestCombo")
        lines.add("%.1fs".format(secs))
        message = "HOLE $level: $name"
        banner = lines.joinToString("  ")
        bannerUntil = now + 3200L
        say(when {
            shots == 1 -> "A HOLE IN ONE?! You're the KING OF THE SEA!"
            under >= 2 -> "Incredible! The currents obey you!"
            under == 1 -> "Birdie! Smooth swimming!"
            under == 0 -> "Right on par. Try chaining currents for a birdie!"
            else -> "Made it! Use the currents and buoys to save shots."
        }, true)
        level++
        resumeAt = now + 3600L
    }

    private fun outOfShots(now: Long) {
        over = true
        levelEndMs = now
        saveBest()
        sfx?.play("lose", 1f, 0L)
        message = "OUT OF SHOTS"
        banner = "TRY HOLE $level AGAIN  ·  PAR $par"
        bannerUntil = now + 2500L
        say("Ride the currents to go further! Flip the red ones with buoys.", true)
        resumeAt = now + 2700L
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
        // trail while riding a combo or boosted
        if (!over && !ready) {
            if (shotCombo >= 2) spawnParticle(cx - faceX * r, cy - faceY * r, 0f, 0f, neon[((now / 80) % neon.size).toInt()], 4 * d, 20f)
            else if (now < speedUntil) spawnParticle(cx - faceX * r, cy - faceY * r, 0f, 0f, 0xAAFFEB3B.toInt(), 4 * d, 18f)
        }
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
