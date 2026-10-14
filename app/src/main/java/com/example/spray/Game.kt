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
class Block(val l: Float, val t: Float, val r: Float, val b: Float, val pillar: Boolean = false, val gate: Boolean = false, val plug: Boolean = false) {
    var alive = true
    var openAt = 0L
}

/** Something to pick up: an apple (spins a slot reel) or speed kelp (stronger shots). */
class Pickup(val x: Float, val y: Float, val type: Int, val seed: Float = Random.nextFloat() * 6f)

/** A seaweed patch: a sticky landing spot that soaks up speed. */
class Weed(val l: Float, val t: Float, val r: Float, val b: Float, val seed: Float = Random.nextFloat() * 6f)

/**
 * Something to shoot. FLIP buoys reverse their current, GATE switches blow open a shortcut
 * in a wall, BONUS stars are just for points.
 */
class Target(val x: Float, val y: Float, val kind: Int, val zone: WaterZone? = null, val gate: Block? = null) {
    var hitAt = 0L
    var done = false
}

/** A bounce shell the player has shot out: it settles and Betito can bank off it. */
class ShellObj(var x: Float, var y: Float, var vx: Float, var vy: Float, val bomb: Boolean = false) {
    var explodeAt = 0L   // bombs only: when it goes off
    var settled = false
    var hitAt = 0L
    val seed = Random.nextFloat() * 6f
}

/** A kelp slide: shoot into either end and it carries you around a corner. */
class Rail(val xs: FloatArray, val ys: FloatArray, val id: Int) {
    val cum = FloatArray(xs.size)
    val len: Float
    var coolUntil = 0L
    init {
        var acc = 0f
        for (i in 1 until xs.size) {
            acc += kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            cum[i] = acc
        }
        len = acc
    }
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
 * All the game rules for Turtle Shot, independent of how it is drawn.
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
        const val P_BOMB = 5

        const val T_FLIP = 0
        const val T_GATE = 1
        const val T_BONUS = 2
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
    val flippable = ArrayList<WaterZone>()   // currents you can tap to reverse
    var flipsLeft = 0
    var flipAt = 0L
    val weeds = ArrayList<Weed>()
    val pickups = ArrayList<Pickup>()
    val targets = ArrayList<Target>()
    val sharks = ArrayList<Shark>()
    val rails = ArrayList<Rail>()
    val fish = ArrayList<Fish>()
    val bubbles = ArrayList<Bubble>()
    var colX = FloatArray(0)
    var colGap = FloatArray(0)
    private var ripCol = -1
    private var narrowC = 0f
    private var narrowH = 0f
    private var nextCol = 0
    val targetR get() = 13f * d
    val suckR get() = 125f * d
    var suckFromX = 0f
    val holeX get() = exitX + wallThick
    val holeY get() = (exitGapT + exitGapB) / 2f
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

    // ---------- bounce shells: free, they don't cost a shot ----------
    val shells = ArrayList<ShellObj>()
    var shellsLeft = 3
    var bombsLeft = 1
    private var plugCount = 0
    var bombMode = false            // tapped BOMB: the next pull throws a bomb
    val blastR get() = 52f * d
    var shellMode = false           // tapped the SHELL button: the next pull fires a shell
    val shellR get() = 15f * d
    var shellLandX = 0f             // where an aimed shell would come to rest (for the preview)
    var shellLandY = 0f
    private var shellHitThisShot = false

    // ---------- quick turn: one mid-flight direction change per shot ----------
    var turnUsed = false
    var turnGrab = false          // finger is on the turtle: time is frozen while you pick a direction
    var turnGrabAt = 0L
    var turnDX = 1f
    var turnDY = 0f
    val canTurn get() = !over && !paused && !ready && riding == null && !turnUsed && hypot(vx, vy) > 1.6f * d
    var turnAt = 0L

    // ---------- shot rules ----------
    val readySpeed get() = 0.9f * d     // he can only shoot again once he has (nearly) stopped
    private val waterDrag = 0.025f
    private val rideDrag = 0.006f       // riding a current barely slows you
    private val weedDrag = 0.16f
    private val bounceK = 0.65f         // how much speed a bounce keeps (into the wall)
    private val shellBounce = 1.2f        // shells are springy: they kick Betito faster
    private val shellSlide = 1.0f
    private val slideK = 0.85f          // how much speed is kept sliding along the wall
    private val maxSpeed get() = 17.5f * d
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
    var lastShotAt = 0L

    // riding a kelp slide
    var riding: Rail? = null
    private var railS = 0f
    private var railDir = 1f
    private var railSpeed = 0f

    // aim preview: the first part of the path, simulated with real physics
    val pvMax = 30
    val previewX = FloatArray(pvMax)
    val previewY = FloatArray(pvMax)
    var previewN = 0

    // ---------- game state ----------
    /** The game's own clock in ms. It stops while paused or while Octo is explaining something. */
    var clock = 1_000_000L
        private set
    var paused = false
    var level = 1
    var score = 0
    private var holeStartScore = 0
    var best = prefs.getInt("best_score", 0)
    var over = false
    private var resumeAt = 0L
    var levelStartMs = 0L
    var levelEndMs = 0L
    var introUntil = 0L      // the "HOLE n · PAR p" card shows until this time

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
            level = pendingLevel
            score = pendingScore
            holeStartScore = score
            startLevel()
        } else if (changed && started) {
            // the screen changed size: rebuild this hole from scratch
            score = holeStartScore
            startLevel()
        }
    }

    private var pendingScore = 0

    /** Starts at a hole with a score (hole 1 and 0 for a new game, the saved ones to continue). */
    fun requestNewGame(startLevelNum: Int = 1, startScore: Int = 0) {
        if (W <= 0f) {
            wantNewGame = true
            pendingLevel = startLevelNum
            pendingScore = startScore
            return
        }
        level = startLevelNum
        score = startScore
        holeStartScore = score
        startLevel()
    }

    /** Pause menu: replay this hole from the start, with the score you had when it began. */
    fun restartHole() {
        if (!started) return
        score = holeStartScore
        paused = false
        startLevel()
    }

    /** Called every frame by the activity. Real time only moves the game while it isn't paused. */
    fun tick(realMs: Long, dt: Float) {
        if (W <= 0f || !started || paused || tip != null) return
        clock += realMs.coerceIn(0L, 50L)
        update(clock, dt)
    }

    fun setSound(on: Boolean) {
        sfx?.enabled = on
        prefs.edit().putBoolean("sound", on).apply()
    }

    val soundOn get() = sfx?.enabled ?: false

    fun sfxClick() {
        sfx?.play("click", 0.7f, 0L)
    }

    // ============================================================
    // Octo's tips: the first time something shows up, he stops the game and explains it
    // ============================================================

    class Tip(val key: String, val x: Float, val y: Float, val rad: Float)

    var tip: Tip? = null
        private set
    var tipShownReal = 0L
    private val tipQueue = ArrayList<String>()
    private val seenTips = HashSet<String>(prefs.getStringSet("tips_seen", emptySet()) ?: emptySet())
    var tipsOn = prefs.getBoolean("tips", true)
    private var startTipsQueued = false
    private var tipLookedThisRest = false

    /** Where an item of this kind is in the current hole (null if there isn't one). */
    private fun locate(key: String): Tip? {
        fun zoneTip(kind: Int): Tip? {
            val z = zones.firstOrNull { it.kind == kind && it.power > 0.3f } ?: return null
            val rad = if (kind == WaterZone.WHIRL) z.rad + 8 * d
                else (max(z.r - z.l, min(z.b - z.t, 140 * d)) / 2f + 10 * d).coerceAtMost(110 * d)
            val y = if (kind == WaterZone.SIDE) cy.coerceIn(z.t + rad * 0.5f, z.b - rad * 0.5f) else z.cy
            return Tip(key, z.cx, y, rad)
        }
        return when (key) {
            "shoot" -> Tip(key, cx, cy, 44 * d)
            "goal" -> Tip(key, holeX - 10 * d, holeY, 56 * d)
            "blue" -> zoneTip(WaterZone.UP)
            "green" -> zoneTip(WaterZone.SIDE)
            "red" -> zoneTip(WaterZone.RIP)
            "whirl" -> zoneTip(WaterZone.WHIRL)
            "buoy" -> flippable.firstOrNull()?.let { Tip(key, it.cx, it.cy, 40 * d) }
            "lock" -> targets.firstOrNull { it.kind == T_GATE && !it.done }?.let { Tip(key, it.x, it.y, 36 * d) }
            "star" -> targets.firstOrNull { it.kind == T_BONUS && !it.done }?.let { Tip(key, it.x, it.y, 34 * d) }
            "apple" -> pickups.firstOrNull { it.type == P_APPLE }?.let { Tip(key, it.x, it.y, 30 * d) }
            "speed" -> pickups.firstOrNull { it.type == P_SPEED }?.let { Tip(key, it.x, it.y, 30 * d) }
            "slide" -> rails.firstOrNull()?.let { val m = it.xs.size / 2; Tip(key, it.xs[m], it.ys[m], 70 * d) }
            "weed" -> weeds.firstOrNull()?.let { Tip(key, (it.l + it.r) / 2f, (it.t + it.b) / 2f, 44 * d) }
            "shark" -> sharks.firstOrNull()?.let { Tip(key, it.x, it.y, 40 * d) }
            "combo" -> Tip(key, cx, cy, 44 * d)
            "shell" -> Tip(key, W - 42 * d, H - 42 * d, 40 * d)
            "bomb" -> Tip(key, W - 42 * d, H - 106 * d, 40 * d)
            "turn" -> Tip(key, cx, cy, 44 * d)
            "score" -> Tip(key, 150 * d, topInset + 22 * d, 70 * d)
            else -> null
        }
    }

    // the order Octo introduces things in when a hole starts
    private val tipOrder = listOf("blue", "green", "red", "buoy", "lock", "slide", "shark", "whirl", "star", "apple", "speed", "weed")

    private fun queueStartTips() {
        startTipsQueued = true
        if (!tipsOn) return
        for (k in listOf("shoot", "goal", "shell", "bomb", "turn", "score")) if (k !in seenTips) tipQueue.add(k)
        var extra = 0
        for (k in tipOrder) {
            if (extra >= 2) break
            if (k in seenTips || k in tipQueue) continue
            if (locate(k) != null) { tipQueue.add(k); extra++ }
        }
    }

    /** Once he comes to rest, Octo explains the closest thing nearby he hasn't explained yet. */
    private fun lookForNearbyTip() {
        if (!tipsOn || tipLookedThisRest) return
        tipLookedThisRest = true
        var bestKey: String? = null
        var bestD = 240 * d
        for (k in tipOrder) {
            if (k in seenTips) continue
            val t = locate(k) ?: continue
            val dd = hypot(t.x - cx, t.y - cy) - t.rad * 0.5f
            if (dd < bestD) { bestD = dd; bestKey = k }
        }
        bestKey?.let { tipQueue.add(it) }
    }

    private fun queueTip(key: String) {
        if (tipsOn && key !in seenTips && key !in tipQueue) tipQueue.add(key)
    }

    private fun showNextTip() {
        while (tipQueue.isNotEmpty()) {
            val k = tipQueue.removeAt(0)
            if (k in seenTips) continue
            val t = locate(k) ?: continue
            tip = t
            tipShownReal = android.os.SystemClock.uptimeMillis()
            sfx?.play("ding", 0.7f, 0L, 1.3f)
            return
        }
    }

    /** Tap to close Octo's tip (ignored for a moment so a stray tap doesn't skip it). */
    fun dismissTip(): Boolean {
        val t = tip ?: return false
        if (android.os.SystemClock.uptimeMillis() - tipShownReal < 450L) return false
        seenTips.add(t.key)
        prefs.edit().putStringSet("tips_seen", HashSet(seenTips)).apply()
        tip = null
        sfx?.play("pop", 0.6f, 0L)
        return true
    }

    fun skipAllTips() {
        tip?.let { seenTips.add(it.key) }
        tip = null
        tipQueue.clear()
    }

    private fun saveProgress() {
        val maxL = max(prefs.getInt("max_level", 1), level)
        prefs.edit().putInt("save_level", level).putInt("save_score", score).putInt("max_level", maxL).apply()
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
        paused = false
        resumeAt = 0L
        holeStartScore = score
        tip = null
        tipQueue.clear()
        startTipsQueued = false
        tipLookedThisRest = true
        val now = clock
        introUntil = now + 2600L
        levelStartMs = introUntil
        levelEndMs = 0L
        playT = topInset + 50 * d
        playB = H - 12 * d
        exitX = W - 18 * d
        message = null
        riding = null
        lastShotAt = 0L
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
        shells.clear()
        shellsLeft = 3
        bombsLeft = 1
        shellMode = false
        bombMode = false
        shellHitThisShot = false
        turnUsed = false

        buildMaze()
        bombsLeft = plugCount + 1   // one per plug plus a spare
        par = colX.size + 2
        maxShots = par   // go over par and the hole must be replayed
        banner = null
        sfx?.play("letsgo", 0.8f, 0L)

        cx = 48 * d
        cy = (playT + playB) / 2f
        vx = 0f
        vy = 0f
        faceX = 1f
        faceY = 0f
        heading = 0f

        placeSharks()

        say(when (level) {
            1 -> "Pull back and let go! Tap SHELL to bank around corners. Par or better to advance!"
            2 -> "Coral blocks the gap! Tap BOMB, pull and throw it to blast through!"
            4 -> "Hit the gold lock to blow open a shortcut in the wall!"
            3 -> "TAP a current's flip icon to reverse it. You get a few flips per hole!"
            else -> "Par $par. Chain currents and kelp for a DEADLY COMBO!"
        }, true)
    }

    /** Tap during the par card to start right away. */
    fun skipIntro(now: Long) {
        if (now < introUntil) {
            introUntil = now
            levelStartMs = now
        }
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

    private fun addWall(l: Float, t: Float, rr: Float, b: Float, pillar: Boolean = false, gate: Boolean = false, plug: Boolean = false): Block? {
        if (rr - l < 4 * d || b - t < 4 * d) return null
        val bl = Block(l, t, rr, b, pillar, gate, plug)
        blocks.add(bl)
        return bl
    }

    fun laneL(i: Int) = if (i < 0) 0f else colX[i] + wallThick
    fun laneR(i: Int) = if (i + 1 < colX.size) colX[i + 1] else exitX

    private fun buildMaze() {
        blocks.clear()
        zones.clear()
        flippable.clear()
        weeds.clear()
        pickups.clear()
        targets.clear()
        rails.clear()
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
        val baseGap = max(3.6f * r, 80 * d - (level - 1) * 2 * d)
        val gapH = baseGap * 1.45f   // wide-open gaps: much easier to get through
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
        // coral plugs: a gap that is blocked solid. Only a BOMB clears it (shells help you aim it).
        plugCount = 0
        val plugWant = if (level < 2) 0 else min(1 + (level - 2) / 4, 2)
        for (i in (0 until n).shuffled()) {
            if (plugCount >= plugWant) break
            if (i == ripCol || i == fwdCol) continue
            addWall(colX[i], colGap[i] - gapH / 2f, colX[i] + wallThick, colGap[i] + gapH / 2f, plug = true)
            plugCount++
        }
        nextCol = 0
        suckFromX = colX[n - 1] + wallThick
        val pulse = level >= 11

        // shortcut gates: a chunk of wall that a gold switch blows open
        val gates = ArrayList<Pair<Block, Int>>()
        val gateWant = if (level >= 7) 2 else 1
        for (i in (1 until n).shuffled()) {
            if (gates.size >= gateWant) break
            if (i == ripCol) continue
            makeGate(i, baseGap)?.let { gates.add(Pair(it, i)) }
        }

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

        // kelp slides around corners
        val railWant = 0 // kelp slides removed; shells and bombs get you around corners
        for (i in (0 until n).shuffled()) {
            if (rails.size >= railWant) break
            if (i == ripCol) continue
            tryRail(i)
        }

        // currents you can tap to flip. A few spare flips beyond the number of currents so every
        // hole can be set up right, but you still have to choose when and which.
        lane1?.let { flippable.add(it) }
        rip?.let { flippable.add(it) }
        if (level >= 5) flippable.add(fwd)    // careful: flipping this one sends the boost lane backward
        whirl?.let { if (level >= 8) flippable.add(it) }
        lane2?.let { flippable.add(it) }
        flipsLeft = flippable.size + 1
        // gold switches for the gates, in the lane just before each gate
        for ((gb, i) in gates) placeTarget(T_GATE, null, gb, laneL(i - 1) + 16 * d, colX[i] - 16 * d)
        // bonus stars for sharpshooters
        repeat(min(3, 1 + level / 4)) { placeTarget(T_BONUS, null, null, 110 * d, exitX - 40 * d) }

        // things to pick up
        repeat(if (level >= 5) 3 else 2) { spawnPickup(P_APPLE) }
        spawnPickup(P_SPEED)
        if (level >= 2) spawnPickup(P_BOMB)
        if (level >= 6) spawnPickup(P_BOMB)
    }

    /** Where a current's flip icon sits (the view draws it here, taps near it flip while Betito is flying). */
    fun flipIconY(z: WaterZone): Float =
        if (z.kind == WaterZone.WHIRL) z.cy else z.cy.coerceIn(playT + 24 * d, playB - 24 * d)

    /** Touch the turtle in flight: time freezes and he flips toward your finger until you let go. */
    fun beginTurnGrab(x: Float, y: Float, now: Long): Boolean {
        if (!canTurn || turnGrab) return false
        if (hypot(x - cx, y - cy) > r * 2.8f) return false
        turnGrab = true
        turnGrabAt = now
        val sp = hypot(vx, vy)
        turnDX = vx / sp
        turnDY = vy / sp
        sfx?.play("pop", 0.5f, 0L, 1.2f)
        return true
    }

    fun setTurnDir(x: Float, y: Float) {
        if (!turnGrab) return
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist < r * 0.9f) return
        turnDX = dx / dist
        turnDY = dy / dist
        faceX = turnDX
        faceY = turnDY
    }

    /** Let go: he shoots off the way he's facing. */
    fun endTurnGrab(now: Long) {
        if (!turnGrab) return
        turnGrab = false
        tapTurn(cx + turnDX * 100f * d, cy + turnDY * 100f * d, now)
    }

    /** Quick turn: while Betito is still swimming, one quick tap sends him toward the tap (same speed). */
    fun tapTurn(x: Float, y: Float, now: Long): Boolean {
        if (!canTurn) return false
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        if (dist < r * 1.5f) return false
        val sp = hypot(vx, vy)
        vx = dx / dist * sp * 0.95f
        vy = dy / dist * sp * 0.95f
        faceX = dx / dist
        faceY = dy / dist
        turnUsed = true
        turnAt = now
        sfx?.play("pop", 0.9f, 0L, 1.5f)
        for (k in 0 until 8) spawnParticle(cx, cy, (Random.nextFloat() - 0.5f) * 4 * d, (Random.nextFloat() - 0.5f) * 4 * d,
            0xFF80DEEA.toInt(), 3 * d, 22f)
        celebrate("QUICK TURN!")
        return true
    }

    /** A tap on the water: if it lands on a flippable current, reverse it (costs one flip, not a shot). */
    fun tapFlip(x: Float, y: Float, now: Long): Boolean {
        if (over || paused || !started || now < introUntil) return false
        var best: WaterZone? = null
        var bestArea = Float.MAX_VALUE
        for (z in flippable) {
            // while Betito is flying, only the flip icon counts (any other tap is a quick turn)
            val inside = if (!ready) hypot(x - z.cx, y - flipIconY(z)) < 30 * d
                else if (z.kind == WaterZone.WHIRL) hypot(x - z.cx, y - z.cy) < z.rad + 10 * d
                else x > z.l - 10 * d && x < z.r + 10 * d && y > z.t - 10 * d && y < z.b + 10 * d
            if (!inside) continue
            val area = (z.r - z.l) * (z.b - z.t)
            if (area < bestArea) { best = z; bestArea = area }
        }
        val z = best ?: return false
        if (flipsLeft <= 0) {
            sfx?.play("tick", 0.3f, 150L)
            popup = "NO FLIPS LEFT"
            popupAt = now
            return true
        }
        flipsLeft--
        z.flip()
        flipAt = now
        sfx?.play("ding", 0.9f, 0L, 1.3f)
        burst(z.cx, z.cy, 0xFFFFFFFF.toInt(), 14)
        say(when (z.kind) {
            WaterZone.UP -> "Flipped! It pushes you FORWARD now!"
            WaterZone.RIP -> "Flipped! That current flows BACKWARD now!"
            WaterZone.WHIRL -> "The whirlpool spins the other way!"
            else -> "Flipped! It flows the other way now!"
        }, true)
        return true
    }

    /** Puts a target in open water between x0..x1 (and y0..y1), away from walls, currents, kelp and other targets. */
    private fun placeTarget(kind: Int, z: WaterZone?, gate: Block?, x0: Float, x1: Float,
                            y0: Float = playT, y1: Float = playB, tries: Int = 80): Boolean {
        val pad = targetR + 10 * d
        val lo = max(x0, 100 * d)
        val hi = min(x1, exitX - 30 * d)
        if (hi <= lo) return false
        for (k in 0 until tries) {
            val x = lo + Random.nextFloat() * (hi - lo)
            val y = max(y0, playT + pad) + Random.nextFloat() * max(1f, min(y1, playB - pad) - max(y0, playT + pad))
            if (y < playT + pad || y > playB - pad) continue
            if (pointInWall(x, y, pad)) continue
            if (zones.any { insideZone(it, x, y, targetR) }) continue
            if (weeds.any { x > it.l - pad && x < it.r + pad && y > it.t - pad && y < it.b + pad }) continue
            if (targets.any { hypot(it.x - x, it.y - y) < 55 * d }) continue
            if (rails.any { rl -> (rl.xs.indices).any { hypot(rl.xs[it] - x, rl.ys[it] - y) < 28 * d } }) continue
            if (hypot(x - holeX, y - holeY) < suckR) continue
            targets.add(Target(x, y, kind, z, gate))
            return true
        }
        return false
    }

    /** Splits one wall piece of column i so a middle chunk becomes a gate, away from the column's real gap. */
    private fun makeGate(i: Int, gapH: Float): Block? {
        val gh = gapH * 0.9f
        val minPiece = 10 * d
        val x = colX[i]
        val cands = blocks.filter { !it.pillar && !it.gate && !it.plug && abs(it.l - x) < 1f && it.b - it.t >= gh + 2 * minPiece && !it.plug }
        if (cands.isEmpty()) return null
        val w = cands[Random.nextInt(cands.size)]
        for (k in 0 until 20) {
            val gc = w.t + minPiece + gh / 2f + Random.nextFloat() * (w.b - w.t - 2 * minPiece - gh)
            if (abs(gc - colGap[i]) < gapH * 1.3f) continue
            blocks.remove(w)
            addWall(w.l, w.t, w.r, gc - gh / 2f)
            addWall(w.l, gc + gh / 2f, w.r, w.b)
            return addWall(w.l, gc - gh / 2f, w.r, gc + gh / 2f, gate = true)
        }
        return null
    }

    /** A kelp slide that curves into column i's gap from above/below, through it, and out around the corner. */
    private fun tryRail(i: Int): Boolean {
        val g = colGap[i]
        val wl = colX[i]
        val wr = colX[i] + wallThick
        val leftL = laneL(i - 1)
        val rightR = laneR(i)
        for (k in 0 until 14) {
            val sgn1 = if (Random.nextBoolean()) 1f else -1f
            val sgn2 = if (Random.nextBoolean()) 1f else -1f
            val sxp = max(leftL + r + 8 * d, wl - (45 + Random.nextFloat() * 30) * d)
            val exp = min(rightR - r - 8 * d, wr + (45 + Random.nextFloat() * 30) * d)
            if (sxp > wl - 26 * d || exp < wr + 26 * d) return false
            val syp = (g + sgn1 * (60 + Random.nextFloat() * 60) * d).coerceIn(playT + r + 8 * d, playB - r - 8 * d)
            val eyp = (g + sgn2 * (60 + Random.nextFloat() * 60) * d).coerceIn(playT + r + 8 * d, playB - r - 8 * d)
            if (abs(syp - g) < 40 * d || abs(eyp - g) < 40 * d) continue
            val ax = wl - 14 * d
            val bx = wr + 14 * d
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            // corner in: from (sx, sy) heading up/down, bending to head right at (ax, g)
            for (s in 0..8) {
                val t = s / 8f
                val u = 1f - t
                xs.add(u * u * sxp + 2 * u * t * sxp + t * t * ax)
                ys.add(u * u * syp + 2 * u * t * g + t * t * g)
            }
            // through the gap
            xs.add(bx); ys.add(g)
            // corner out: heading right, bending to (ex, ey)
            for (s in 1..8) {
                val t = s / 8f
                val u = 1f - t
                xs.add(u * u * bx + 2 * u * t * exp + t * t * exp)
                ys.add(u * u * g + 2 * u * t * g + t * t * eyp)
            }
            var ok = true
            for (j in 1 until xs.size) if (!clearLine(xs[j - 1], ys[j - 1], xs[j], ys[j], r * 0.7f)) { ok = false; break }
            if (!ok) continue
            if (rails.any { rl -> hypot(rl.xs[0] - sxp, rl.ys[0] - syp) < 60 * d }) continue
            rails.add(Rail(xs.toFloatArray(), ys.toFloatArray(), 100000 + rails.size))
            return true
        }
        return false
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
            if (t.done || now - t.hitAt < 700L) continue
            if (hypot(cx - t.x, cy - t.y) > r + targetR + 6 * d) continue
            t.hitAt = now
            targetsThisShot++
            if (t.kind != T_BONUS) {
                vx *= 0.85f
                vy *= 0.85f
            }
            val b = band()
            sfx?.play("ding", 1f, 0L, 1.6f)
            sfx?.play("kaching", 0.9f, 0L)
            burst(t.x, t.y, 0xFFFFFFFF.toInt(), 16)
            confetti(t.x, t.y)
            when (t.kind) {
                T_FLIP -> {
                    val z = t.zone ?: continue
                    z.flip()
                    score += 150 * b
                    celebrate("BULLSEYE!")
                    say(when (z.kind) {
                        WaterZone.UP -> "Current flipped! It's pushing you FORWARD now!"
                        WaterZone.RIP -> "Uh oh, that current flips backward now!"
                        WaterZone.WHIRL -> "The whirlpool spins the other way!"
                        else -> "Current flipped! It flows the other way now!"
                    }, true)
                }
                T_GATE -> {
                    t.done = true
                    val gb = t.gate
                    if (gb != null && gb.alive) {
                        gb.alive = false
                        gb.openAt = now
                        val gx = (gb.l + gb.r) / 2f
                        val gy = (gb.t + gb.b) / 2f
                        for (k in 0 until 40) {
                            val a = Random.nextFloat() * 6.283f
                            val v = (1 + Random.nextFloat() * 5) * d
                            spawnParticle(gx, gy + (Random.nextFloat() - 0.5f) * (gb.b - gb.t), cos(a) * v, sin(a) * v,
                                if (Random.nextBoolean()) 0xFFFFC107.toInt() else 0xFF8E7CC3.toInt(), (2 + Random.nextFloat() * 3) * d, 40f)
                        }
                    }
                    score += 250 * b
                    sfx?.play("bust", 1f, 0L)
                    sfx?.play("rumble", 0.8f, 0L)
                    shake(8f, now)
                    celebrate("WALL OPENED!!", true)
                    say("BOOM! A shortcut opened in the wall!", true)
                }
                else -> {
                    t.done = true
                    score += 250 * b
                    fillReel(now)
                    celebrate("SHARPSHOOTER!")
                }
            }
            if (bouncesThisShot > 0) {
                score += 150 * b
                celebrate("TRICK SHOT!!", true)
            }
            when {
                targetsThisShot == 2 -> { score += 300 * b; celebrate("DOUBLE TAP!!", true) }
                targetsThisShot >= 3 -> { score += 600 * b; celebrate("TRIPLE THREAT!!", true); sfx?.play("siren", 1f, 0L) }
            }
        }
    }

    // ============================================================
    // kelp slides
    // ============================================================

    private var rpx = 0f
    private var rpy = 0f

    private fun railPoint(rl: Rail, s0: Float) {
        val s = s0.coerceIn(0f, rl.len)
        var i = 1
        while (i < rl.xs.size - 1 && rl.cum[i] < s) i++
        val seg = (rl.cum[i] - rl.cum[i - 1]).coerceAtLeast(0.001f)
        val t = ((s - rl.cum[i - 1]) / seg).coerceIn(0f, 1f)
        rpx = rl.xs[i - 1] + (rl.xs[i] - rl.xs[i - 1]) * t
        rpy = rl.ys[i - 1] + (rl.ys[i] - rl.ys[i - 1]) * t
    }

    private fun tryGrabRail(now: Long) {
        if (ready || riding != null) return
        for (rl in rails) {
            if (now < rl.coolUntil) continue
            val last = rl.xs.size - 1
            val grab = r + 12 * d
            val dir = when {
                hypot(cx - rl.xs[0], cy - rl.ys[0]) < grab -> 1f
                hypot(cx - rl.xs[last], cy - rl.ys[last]) < grab -> -1f
                else -> 0f
            }
            if (dir == 0f) continue
            riding = rl
            railDir = dir
            railS = if (dir > 0f) 0f else rl.len
            railSpeed = max(hypot(vx, vy), 7f * d)
            sfx?.play("wee2", 0.8f, 0L)
            if (shots > 0 && shotZones.add(rl.id)) comboStep(now)
            if (shotCombo < 2) {
                popup = "KELP SLIDE!"
                popupAt = now
            }
            return
        }
    }

    private fun advanceRail(rl: Rail, hdt: Float, now: Long) {
        railS += railDir * railSpeed * hdt
        val done = railS <= 0f || railS >= rl.len
        railPoint(rl, railS)
        val px0 = rpx
        val py0 = rpy
        railPoint(rl, railS + railDir * 3 * d)
        var tx = rpx - px0
        var ty = rpy - py0
        if (done) {
            // tangent at the very end
            railPoint(rl, railS - railDir * 3 * d)
            tx = px0 - rpx
            ty = py0 - rpy
        }
        val tl = hypot(tx, ty).coerceAtLeast(0.001f)
        cx = px0
        cy = py0
        vx = tx / tl * railSpeed
        vy = ty / tl * railSpeed
        if (now % 3L == 0L) spawnParticle(cx, cy, 0f, 0f, 0xAA66BB6A.toInt(), 4 * d, 22f)
        if (done) {
            riding = null
            rl.coolUntil = now + 900L
            vx *= 1.05f
            vy *= 1.05f
            sfx?.play("launch", 0.5f, 0L, 1.4f)
        }
    }

    // ============================================================
    // the frame
    // ============================================================

    val trX = FloatArray(90)
    val trY = FloatArray(90)
    var trN = 0
    private var trHead = 0
    fun trailPoint(i: Int): Int = (trHead - trN + i + trX.size * 2) % trX.size
    private fun tracerStep() {
        if (!over && !ready) {
            trX[trHead] = cx; trY[trHead] = cy
            trHead = (trHead + 1) % trX.size
            if (trN < trX.size) trN++
        } else if (ready) {
            trN = 0
        }
    }

    fun update(now: Long, dt: Float) {
        tracerStep()
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

        // frozen while you pick a turn direction (gives up after 3 seconds, turn not used)
        if (turnGrab) {
            if (!canTurn || now - turnGrabAt > 3000L) turnGrab = false else return
        }

        updatePulses(now, dt)

        // he can shoot again once he has (nearly) stopped
        val wasMoving = !ready
        ready = hypot(vx, vy) < readySpeed
        if (wasMoving && ready) endShot(now)

        // Octo's tips: a few when the hole starts, then one for anything new he's resting near
        if (now >= introUntil && !aiming && ready && riding == null) {
            if (!startTipsQueued) queueStartTips()
            if (tipQueue.isEmpty() && shots > 0) lookForNearbyTip()
            if (tipQueue.isNotEmpty()) {
                showNextTip()
                if (tip != null) return
            }
        }
        if (launchQueued) {
            launchQueued = false
            if (now < introUntil) skipIntro(now)
            else if ((shellMode || bombMode) && ready) fireThrown(now, bombMode)
            else if (ready) launch(now) else {
                sfx?.play("tick", 0.3f, 150L)
                popup = "WAIT TILL HE STOPS..."
                popupAt = now
            }
        }
        if (ready && shots >= maxShots) { outOfShots(now); return }

        val hdt = dt / 3f
        for (step in 0 until 3) {
            val rl = riding
            if (rl != null) {
                advanceRail(rl, hdt, now)
                checkTargets(now)
                if (cx > exitX + wallThick * 0.5f && cy > exitGapT && cy < exitGapB) { levelClear(now); return }
                continue
            }
            tryGrabRail(now)
            if (riding != null) continue
            val whirl = sampleWater(cx, cy, true, now)
            // the hole sucks you in once you're in the last stretch
            var sucked = false
            if (cx > suckFromX && cx < holeX) {
                val sdx = holeX - cx
                val sdy = holeY - cy
                val sd = hypot(sdx, sdy)
                if (sd < suckR && sd > 1f) {
                    val pull = 0.55f * d * (1f - sd / suckR) + 0.08f * d
                    vx += sdx / sd * pull * hdt
                    vy += sdy / sd * pull * hdt
                    sucked = true
                }
            }
            val sticky = inWeed(cx, cy)
            val inFlow = (wx != 0f || wy != 0f) && !sticky
            val grip = if (sticky) 0.25f else 1f
            val drag = if (sucked) rideDrag else if (sticky) weedDrag else if (inFlow && !ready) rideDrag else waterDrag
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
            for (sh in shells) collideShell(sh, now)
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
        updateShells(dt, now)
        if ((shellMode || bombMode) && aiming && ready) computeShellPreview(now)
        else if (aiming) computePreview(now) else previewN = 0

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

    // ============================================================
    // bounce shells
    // ============================================================

    /** The SHELL button: arm (or disarm) a shell. Your next pull-and-release fires it instead of Betito. */
    fun toggleShellMode(now: Long) {
        if (over || now < introUntil) return
        if (shellsLeft <= 0) {
            sfx?.play("tick", 0.3f, 150L)
            popup = "NO SHELLS LEFT"
            popupAt = now
            return
        }
        shellMode = !shellMode
        bombMode = false
        sfx?.play(if (shellMode) "pop" else "tick", 0.6f, 60L)
    }

    /** The BOMB button: arm (or disarm) a bomb. Your next pull-and-release throws it. */
    fun toggleBombMode(now: Long) {
        if (over || now < introUntil) return
        if (bombsLeft <= 0) {
            sfx?.play("tick", 0.3f, 150L)
            popup = "NO BOMBS - FIND ONE!"
            popupAt = now
            return
        }
        bombMode = !bombMode
        shellMode = false
        sfx?.play(if (bombMode) "pop" else "tick", 0.6f, 60L)
    }

    private fun shellHitsWall(x: Float, y: Float): Boolean {
        val sr = shellR
        if (x < sr || y < playT + sr || y > playB - sr) return true
        for (w in blocks) {
            if (!w.alive) continue
            val qx = x.coerceIn(w.l, w.r)
            val qy = y.coerceIn(w.t, w.b)
            if ((x - qx) * (x - qx) + (y - qy) * (y - qy) < sr * sr) return true
        }
        return false
    }

    /** Where a shell fired along the aim would stop: it glides with water drag and stops dead at a wall. */
    private fun shellPath(now: Long, power: Float, out: FloatArray) {
        var x = cx + aimDX * (r + shellR + 3 * d)
        var y = cy + aimDY * (r + shellR + 3 * d)
        if (shellHitsWall(x, y)) { out[0] = Float.NaN; return }
        var svx = aimDX * launchSpeed(now) * power
        var svy = aimDY * launchSpeed(now) * power
        for (i in 0 until 600) {
            val nx = x + svx / 3f
            val ny = y + svy / 3f
            if (shellHitsWall(nx, ny)) break
            x = nx
            y = ny
            val k = 1f - waterDrag / 3f
            svx *= k
            svy *= k
            if (hypot(svx, svy) < 0.15f * d) break
        }
        out[0] = x
        out[1] = y
    }

    private val shellTmp = FloatArray(2)

    private fun computeShellPreview(now: Long) {
        previewN = 0
        shellPath(now, aimPower.coerceIn(0.12f, 1f), shellTmp)
        if (shellTmp[0].isNaN()) { shellLandX = cx; shellLandY = cy; return }
        shellLandX = shellTmp[0]
        shellLandY = shellTmp[1]
    }

    private fun fireThrown(now: Long, bomb: Boolean) {
        val p = aimPower.coerceIn(0.12f, 1f)
        shellPath(now, p, shellTmp)
        if (shellTmp[0].isNaN()) {
            sfx?.play("tick", 0.3f, 150L)
            popup = "NO ROOM HERE"
            popupAt = now
            return
        }
        val sh = ShellObj(cx + aimDX * (r + shellR + 3 * d), cy + aimDY * (r + shellR + 3 * d),
            aimDX * launchSpeed(now) * p, aimDY * launchSpeed(now) * p, bomb)
        shells.add(sh)
        kickAt = now
        if (bomb) {
            bombsLeft--
            bombMode = false
            sh.explodeAt = now + 3500L
            sfx?.play("launch", 0.5f + 0.4f * p, 0L, 1.2f)
            say(if (bombsLeft > 0) "Bomb away! Stand back, it blows soon!" else "Bomb away! Stand clear!", true)
        } else {
            shellsLeft--
            shellMode = false
            sfx?.play("launch", 0.35f + 0.4f * p, 0L, 2.0f)
            say(if (shellsLeft > 0) "Shell out! Bank Betito off it. It's free!" else "That's your last shell!", true)
        }
    }

    private fun updateShells(dt: Float, now: Long) {
        for (sh in shells) {
            if (sh.settled) continue
            for (i in 0 until 3) {
                val nx = sh.x + sh.vx * dt / 3f
                val ny = sh.y + sh.vy * dt / 3f
                if (shellHitsWall(nx, ny)) { sh.vx = 0f; sh.vy = 0f; sh.settled = true; break }
                sh.x = nx
                sh.y = ny
                val k = 1f - min(1f, waterDrag * dt / 3f)
                sh.vx *= k
                sh.vy *= k
            }
            if (hypot(sh.vx, sh.vy) < 0.15f * d) { sh.vx = 0f; sh.vy = 0f; sh.settled = true }
        }
        // bombs: once one stops, a short fuse; then it goes off
        var blown: ArrayList<ShellObj>? = null
        for (sh in shells) {
            if (!sh.bomb) continue
            if (sh.settled && sh.explodeAt > now + 1200L) sh.explodeAt = now + 1200L
            if (now >= sh.explodeAt) { if (blown == null) blown = ArrayList(); blown.add(sh) }
        }
        blown?.forEach { explodeBomb(it, now) }
    }

    /** Blows a hole in the walls around a bomb: each wall piece in the blast keeps only the parts above and below it. */
    private fun explodeBomb(sh: ShellObj, now: Long) {
        shells.remove(sh)
        val br = blastR
        var carved = 0
        for (w in ArrayList(blocks)) {
            if (!w.alive) continue
            val qx = sh.x.coerceIn(w.l, w.r)
            val qy = sh.y.coerceIn(w.t, w.b)
            if (hypot(sh.x - qx, sh.y - qy) > br) continue
            w.alive = false
            w.openAt = now
            carved++
            if (w.gate || w.plug) continue
            val y0 = sh.y - br
            val y1 = sh.y + br
            if (y0 > w.t) addWall(w.l, w.t, w.r, min(w.b, y0), w.pillar)
            if (y1 < w.b) addWall(w.l, max(w.t, y1), w.r, w.b, w.pillar)
        }
        sfx?.play("bust", 1f, 0L)
        sfx?.play("rumble", 0.8f, 0L)
        shake(12f, now)
        flash = 0.45f
        flashColor = 0xFFFFE0B2.toInt()
        for (k in 0 until 46) {
            val a = Random.nextFloat() * 6.283f
            val v = (1 + Random.nextFloat() * 6) * d
            spawnParticle(sh.x, sh.y, cos(a) * v, sin(a) * v,
                if (k % 3 == 0) 0xFF8E7CC3.toInt() else if (k % 3 == 1) 0xFFFF9800.toInt() else 0xFFFFEB3B.toInt(), (2 + Random.nextFloat() * 4) * d, 40f)
        }
        // the blast shoves Betito if he's close
        val dx = cx - sh.x
        val dy = cy - sh.y
        val dd = hypot(dx, dy)
        if (dd < br * 1.6f && dd > 1f) {
            val k = (1f - dd / (br * 1.6f)) * 6f * d
            vx += dx / dd * k
            vy += dy / dd * k
        }
        if (carved > 0) {
            score += 50 * carved * band()
            celebrate("BOOM!", true)
            say("Wall blasted! Path cleared!", true)
        } else {
            celebrate("FIZZLE...")
        }
    }

    /** Betito bounces off a shell like off a round rock. It costs no shot and keeps combos going. */
    private fun collideShell(sh: ShellObj, now: Long) {
        val dx = cx - sh.x
        val dy = cy - sh.y
        val dist = hypot(dx, dy)
        val minD = r + shellR
        if (dist >= minD || dist < 0.0001f) return
        val nx = dx / dist
        val ny = dy / dist
        cx += nx * (minD - dist)
        cy += ny * (minD - dist)
        val vn = vx * nx + vy * ny
        if (vn < 0f) {
            val vtx = vx - vn * nx
            val vty = vy - vn * ny
            vx = -vn * shellBounce * nx + vtx * shellSlide
            vy = -vn * shellBounce * ny + vty * shellSlide
            if (-vn > 1.0f * d) {
                bumpAt = now
                sh.hitAt = now
                bouncesThisShot++
                sfx?.play("clink", 0.4f + min(0.5f, -vn / (12 * d)), 60L, 1.8f)
                if (!shellHitThisShot && shots > 0) {
                    shellHitThisShot = true
                    celebrate("SHELL BANK!")
                }
                for (k in 0 until 5) spawnParticle(sh.x + nx * shellR, sh.y + ny * shellR, nx * 1.5f * d + (Random.nextFloat() - 0.5f) * 2 * d,
                    ny * 1.5f * d + (Random.nextFloat() - 0.5f) * 2 * d, 0xFFFFCC80.toInt(), 2.5f * d, 20f)
            }
        }
    }

    private fun inWeed(x: Float, y: Float): Boolean =
        weeds.any { x > it.l && x < it.r && y > it.t && y < it.b }

    private fun launchSpeed(now: Long): Float = (if (now < speedUntil) 14f else 10.5f) * d

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
        lastShotAt = now
        bouncesThisShot = 0
        shotZones.clear()
        shotCombo = 0
        targetsThisShot = 0
        sharkThisShot = false
        shellHitThisShot = false
        turnUsed = false
        tipLookedThisRest = false
        kickAt = now
        sfx?.play("launch", 0.4f + 0.5f * p, 0L, 1.6f - 0.5f * p)
        for (k in 0 until 8) spawnParticle(cx - aimDX * r, cy - aimDY * r,
            -aimDX * 2 * d + (Random.nextFloat() - 0.5f) * 2 * d, -aimDY * 2 * d + (Random.nextFloat() - 0.5f) * 2 * d,
            0x99FFFFFF.toInt(), 3 * d, 25f)
        when (shots) {
            par -> say("LAST SHOT! Get in the hole or replay the hole!", true)
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
        if (shotCombo >= 1) queueTip("combo")
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
                for (sh in shells) {
                    val dx = sx - sh.x
                    val dy = sy - sh.y
                    val dist = hypot(dx, dy)
                    val minD = r + shellR
                    if (dist >= minD || dist < 0.0001f) continue
                    val nx = dx / dist
                    val ny = dy / dist
                    sx += nx * (minD - dist)
                    sy += ny * (minD - dist)
                    val vn = svx * nx + svy * ny
                    if (vn < 0f) {
                        val vtx = svx - vn * nx
                        val vty = svy - vn * ny
                        svx = -vn * shellBounce * nx + vtx * shellSlide
                        svy = -vn * shellBounce * ny + vty * shellSlide
                    }
                }
            }
            previewX[previewN] = sx
            previewY[previewN] = sy
            previewN++
            if (sx > exitX || hypot(svx, svy) < 0.3f * d) break
            if (rails.any { rl -> hypot(sx - rl.xs[0], sy - rl.ys[0]) < r + 12 * d ||
                    hypot(sx - rl.xs[rl.xs.size - 1], sy - rl.ys[rl.ys.size - 1]) < r + 12 * d }) break
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
            } else if (p.type == P_BOMB) {
                bombsLeft++
                sfx?.play("pop", 1f, 40L)
                celebrate("BOMB!")
                say("Got a bomb! Tap BOMB, aim, and blow a wall!", true)
            } else {
                speedUntil = now + 8000L
                sfx?.play("woo", 1f, 0L)
                celebrate("POWER UP!")
                say("Speed kelp! Your shots go further for 8 seconds!", true)
            }
            burst(p.x, p.y, if (p.type == P_APPLE) 0xFFE53935.toInt() else if (p.type == P_BOMB) 0xFF90A4AE.toInt() else 0xFFFFEB3B.toInt(), 12)
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
                    popupAt = clock
                }
            }
        }
    }

    fun celebrate(text: String, big: Boolean = false) {
        cheerStreak++
        praise = text
        praiseAt = clock
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

    /** Octo's face for what he's saying: surprised for trouble, happy for big wins, a wink for tips. */
    var octoFace = 0
    private fun pickFace(text: String): Int {
        val t = text.lowercase()
        return when {
            listOf("uh oh", "wait", "over par", "last shot", "last shell", "replay", "backward", "no room", "no flips", "bump", "shark").any { it in t } -> Sprites.FACE_SURPRISE
            listOf("hole in one", "king of the sea", "incredible", "birdie", "eagle", "jackpot", "perfect", "deadly", "smooth", "right on par", "made it", "wall blasted", "cleared").any { it in t } -> Sprites.FACE_HAPPY
            listOf("flipped", "turn", "shell", "pull back", "tap", "gold lock", "buoy", "try ", "bomb").any { it in t } -> Sprites.FACE_WINK
            else -> Sprites.FACE_SMILE
        }
    }

    fun say(text: String, force: Boolean = false) {
        val now = clock
        if (!force && now < octoNextAt) return
        octoText = text
        octoAt = now
        octoNextAt = now + 2500L
        octoFace = pickFace(text)
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
        val now = clock
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
        if (shots > par) {
            // over par: no credit, the hole has to be replayed
            score = holeStartScore
            sfx?.play("lose", 1f, 0L)
            stars = 0
            message = "OVER PAR"
            banner = "$shots SHOTS · PAR $par  ·  REPLAY HOLE $level"
            bannerUntil = now + 2800L
            riding = null
            say("You need par or better to move on. Try again!", true)
            resumeAt = now + 3000L
            return
        }
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
        riding = null
        banner = lines.joinToString("  ")
        bannerUntil = now + 3200L
        say(when {
            shots == 1 -> "A HOLE IN ONE?! You're the KING OF THE SEA!"
            under >= 2 -> "Incredible! The currents obey you!"
            under == 1 -> "Birdie! Smooth swimming!"
            under == 0 -> "Right on par. Try chaining currents for a birdie!"
            else -> "Made it! Use the currents, shells and flips to save shots."
        }, true)
        level++
        saveProgress()
        resumeAt = now + 3600L
    }

    private fun outOfShots(now: Long) {
        over = true
        levelEndMs = now
        saveBest()
        sfx?.play("lose", 1f, 0L)
        message = "OUT OF SHOTS"
        banner = "NEED PAR OR BETTER  ·  REPLAY HOLE $level"
        bannerUntil = now + 2500L
        say("Ride the currents to go further! Tap a current to flip it, or fire a shell to bank around a corner.", true)
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
