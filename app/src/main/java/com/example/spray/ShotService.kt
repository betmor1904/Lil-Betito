package com.example.spray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A wall. Walls never break: you route around them. */
private class Wall(val view: BarrierView, val lp: WindowManager.LayoutParams, var removed: Boolean = false)

private class Food(val view: FoodView, val x: Float, val y: Float)

/**
 * Lil Betito, strategy maze: swim up through a flooded room of walls, using currents as tools
 * and hazards, and escape through the gap in the ceiling before the rising floor catches him.
 * Casino layer: bet part of your score on each level; apples spin a 3-reel slot.
 */
class ShotService : Service(), Choreographer.FrameCallback {
    private lateinit var wm: WindowManager
    private var d = 1f
    private var sw = 0
    private var sh = 0

    private var sv: ShotView? = null
    private var hud: HudView? = null
    private var tapLayer: View? = null
    private var closeBtn: CloseView? = null
    private var spinBtn: SpinButtonView? = null
    private var parts: PartsView? = null
    private var mascot: MascotView? = null
    private var fx: FxView? = null
    private var sfx: Sfx? = null
    private lateinit var slp: WindowManager.LayoutParams
    private lateinit var tlp: WindowManager.LayoutParams
    private lateinit var mlp: WindowManager.LayoutParams
    private lateinit var spinLp: WindowManager.LayoutParams
    private val walls = ArrayList<Wall>()
    private val foods = ArrayList<Food>()
    private val handler = Handler(Looper.getMainLooper())

    // turtle physics (velocities are pixels per 60fps frame)
    private var cx = 0f
    private var cy = 0f
    private var vx = 0f
    private var vy = 0f
    private var r = 0f
    private var lastNs = 0L
    private var levelStartMs = 0L
    private var dying = false
    private var flash = 0f
    private var dyingUntil = 0L

    // a swimmer, not a pinball: slow, and walls soak up speed
    private val speedBase get() = 3.5f * d
    private val speedMin get() = 2f * d
    private val speedMax get() = 5f * d
    private val climbForce get() = 1.5f * d
    private val brakeForce get() = 1.5f * d
    private val dragK = 0.02f
    private val bounce = 0.15f

    // touch
    private var steering = false
    private var steerX = 0f
    private var steerY = 0f
    private var twoFinger = false
    private var thrust = 0          // 1 climbing, -1 braking, 0 none
    private var ptr1 = -1
    private var ptr2 = -1
    private var p1x = 0f
    private var p1y = 0f
    private var p2x = 0f
    private var p2y = 0f

    // room
    private var floorStartY = 0f
    private var floorY = 0f
    private var ceilY = 0f
    private var gapL = 0f
    private var gapR = 0f
    private var gapC = 0f
    private var gapW0 = 0f
    private var gapBase = 0f
    private var floorDrop = 0f
    private var mazeTop = 0f
    private var mazeBot = 0f

    // game state
    private var level = 1
    private var best = 0
    private var score = 0
    private var launched = false
    private var over = false
    private var lives = 3

    // style bonuses
    private var touchedWallThisLevel = false
    private var currentsVisited = 0
    private var visitedAllCurrents = false
    private val visitedZoneIds = HashSet<Int>()

    // celebrations: rows passed, chime ladder, maze facts for the fancy moves
    private var rowYs = FloatArray(0)
    private var nextRow = -1
    private var touchedSinceRow = false
    private var ripRowIdx = -1
    private var narrowC = 0f
    private var narrowW = 0f
    private var cheerStreak = 0
    private val smooth = arrayOf("SMOOTH!", "SLICK!", "SHELL YEAH!", "SWIM-TASTIC!", "SO FRESH!")
    private val yum = arrayOf("SWEET!", "YUMMY!", "TASTY!", "DELICIOUS!")
    private val rides = arrayOf("NICE RIDE!", "SURF'S UP!", "WHEEE!")

    // spin meter: fuel for two-finger control, tap SPIN when full
    private var energy = 0f
    private var spinShown = false

    // slot reels: 0 cherry, 1 bell, 2 bar, 3 seven, 4 coin. Sevens are the rarest.
    private val reels = intArrayOf(-1, -1, -1)
    private var reelCount = 0
    private var reelResolving = false
    private var pendingFills = 0
    private val symbolWeights = intArrayOf(30, 20, 15, 7, 28)

    // water: currents push him only while he is in them
    private val zones = ArrayList<WaterZone>()
    private var wx = 0f
    private var wy = 0f
    private var mx = 0f
    private var my = 0f

    private val kFloor = 0
    private val kWall = 1
    private val kCeil = 3
    private val kSolid = 5

    private val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun params(w: Int, h: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var f = flags
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun ceilThick() = 16f * d

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startAsForeground()
        val a = i?.action
        if (a == "SHOT_STOP") { stopSelf(); return START_NOT_STICKY }
        val fresh = sv == null
        if (fresh) setup()
        sfx?.enabled = getSharedPreferences("betito", MODE_PRIVATE).getBoolean("sound", true)
        when (a) {
            "SHOT_SOLO", "SHOT_RESET" -> newGame()
            "SHOT_TEST7" -> { newGame(); level = 7; startLevel() }
            else -> { if (fresh) newGame() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shot", "Lil Betito", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String, label: String, icon: Int, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, code, Intent(this, ShotService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }
        val n = Notification.Builder(this, "shot")
            .setContentTitle("Lil Betito is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(action("SHOT_RESET", "Restart", android.R.drawable.ic_menu_revert, 12))
            .addAction(action("SHOT_STOP", "Stop", android.R.drawable.ic_delete, 13))
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, n)
    }

    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sfx = Sfx(this)
        d = resources.displayMetrics.density
        sw = resources.displayMetrics.widthPixels
        sh = resources.displayMetrics.heightPixels
        r = 14 * d
        ceilY = 64 * d
        floorStartY = sh - 70 * d
        floorY = floorStartY
        val prefs = getSharedPreferences("betito", MODE_PRIVATE)
        best = prefs.getInt("best_score", 0)

        val h = HudView(this)
        h.ceilY = ceilY
        h.ceilThick = ceilThick()
        wm.addView(h, params(sw, sh, touchable = false))
        hud = h

        val mv = MascotView(this)
        mlp = params((150 * d).toInt(), (150 * d).toInt(), touchable = false)
        mlp.x = 0
        mlp.y = (floorY - 130 * d).toInt()
        wm.addView(mv, mlp)
        mascot = mv

        val pv = PartsView(this)
        wm.addView(pv, params(sw, sh, touchable = false))
        parts = pv

        // Full-screen layer for steering. One finger: he turns toward it.
        // Two fingers: finger 2 above finger 1 climbs, below brakes, sideways steers.
        val tv = View(this)
        tlp = params(sw, sh)
        tv.setOnTouchListener { _, e ->
            val ox = e.rawX - e.getX(0)
            val oy = e.rawY - e.getY(0)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ptr1 = e.getPointerId(0)
                    ptr2 = -1
                    p1x = e.getX(0) + ox
                    p1y = e.getY(0) + oy
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val id = e.getPointerId(i)
                    if (ptr2 == -1 && id != ptr1) {
                        ptr2 = id
                        p2x = e.getX(i) + ox
                        p2y = e.getY(i) + oy
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val i1 = e.findPointerIndex(ptr1)
                    if (i1 >= 0) { p1x = e.getX(i1) + ox; p1y = e.getY(i1) + oy }
                    val i2 = e.findPointerIndex(ptr2)
                    if (i2 >= 0) { p2x = e.getX(i2) + ox; p2y = e.getY(i2) + oy }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(e.actionIndex)
                    if (id == ptr2) {
                        ptr2 = -1
                    } else if (id == ptr1) {
                        // the anchor lifted: the other finger becomes the anchor
                        ptr1 = ptr2; p1x = p2x; p1y = p2y; ptr2 = -1
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ptr1 = -1; ptr2 = -1
                }
            }
            steering = ptr1 != -1 && !over
            twoFinger = steering && ptr2 != -1
            steerX = p1x
            steerY = p1y
            true
        }
        wm.addView(tv, tlp)
        tapLayer = tv

        val xv = CloseView(this)
        val xlp = params((40 * d).toInt(), (40 * d).toInt())
        xlp.x = sw - (50 * d).toInt()
        xlp.y = (ceilY + 40 * d).toInt()
        xv.setOnClickListener { stopSelf() }
        wm.addView(xv, xlp)
        closeBtn = xv

        // SPIN button: only shows (and only takes touches) when the meter is full
        val bv = SpinButtonView(this)
        spinLp = params((160 * d).toInt(), (56 * d).toInt(), touchable = false)
        spinLp.x = (sw / 2f - 80 * d).toInt()
        spinLp.y = (sh - 66 * d).toInt()
        bv.visibility = View.GONE
        bv.setOnClickListener { spendSpin() }
        wm.addView(bv, spinLp)
        spinBtn = bv

        val v = ShotView(this)
        v.radiusPx = r
        slp = params((112 * d).toInt(), (112 * d).toInt(), touchable = false)
        cx = sw / 2f
        cy = floorY - r
        slp.x = (cx - 56 * d).toInt()
        slp.y = (cy - 56 * d).toInt()
        wm.addView(v, slp)
        sv = v
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------- levels ----------

    private fun newGame() {
        level = 1
        score = 0
        lives = 3
        startLevel()
    }

    private fun startLevel() {
        handler.removeCallbacksAndMessages(null)
        stopFx()
        clearRoom()
        floorY = floorStartY
        launched = true
        over = false
        steering = false
        levelStartMs = SystemClock.uptimeMillis()
        dying = false
        floorDrop = 0f
        thrust = 0
        cx = sw / 2f
        cy = floorY - r
        vx = 0f
        vy = -speedBase
        mx = 0f
        my = 0f
        wx = 0f
        wy = 0f

        touchedWallThisLevel = false
        currentsVisited = 0
        visitedAllCurrents = false
        visitedZoneIds.clear()
        touchedSinceRow = false
        cheerStreak = 0
        hud?.let { it.stars = 0; it.praise = null }

        resetReels()

        // the exit gap: fixed width for the whole level, somewhere new every level
        val gapW = max(2 * r + 14f * d, 90f * d - (level - 1) * 4f * d)
        val margin = 20f * d
        val center = margin + gapW / 2f + Random.nextFloat() * (sw - 2 * margin - gapW)
        gapC = center
        gapBase = center
        gapW0 = gapW
        gapL = center - gapW / 2f
        gapR = center + gapW / 2f

        energy = 30f
        flash = 0f
        updateSpinReady()
        buildMaze()
        for (k in 0 until (if (level >= 5) 3 else 2)) spawnFood()
        sv?.let { it.heading = -90f; it.radiusPx = r; it.charged = false; it.invalidate() }
        pushHud()
        hud?.let {
            it.message = "LEVEL $level"
            it.banner = "READ THE ROOM"
            it.invalidate()
        }
        handler.postDelayed({ hud?.let { it.message = null; it.invalidate() } }, 1400)
        handler.postDelayed({ hud?.let { if (it.banner == "READ THE ROOM") { it.banner = null; it.invalidate() } } }, 2500)
        applyPos()
        moveMascot()
    }

    private fun pushHud() {
        hud?.let {
            it.floorY = floorY
            it.gapL = gapL
            it.gapR = gapR
            it.level = level
            it.best = best
            it.score = score
            it.worth = worth()
            it.lives = lives
            it.energy = energy
            it.spinReady = energy >= 100f
            it.flash = 0f
            it.reels = reels
            it.reelResolving = reelResolving
            it.zones = zones
            it.cleanRun = !touchedWallThisLevel
            it.currentsVisited = currentsVisited
            it.currentsTotal = zones.size
            it.invalidate()
        }
    }

    /** Escape points before the level band: 100 / 300 / 600, and 1000 right at the last moment. */
    private fun tier(): Int {
        val full = floorStartY - (ceilY + 2 * r)
        val risen = (1f - (floorY - (ceilY + 2 * r)) / full).coerceIn(0f, 1f)
        return when {
            risen >= 0.9f -> 1000
            risen >= 2f / 3f -> 600
            risen >= 1f / 3f -> 300
            else -> 100
        }
    }

    /** Level band multiplier: 1-5 = 1x, 6-10 = 2x, 11-20 = 3x, 21+ = 5x. */
    private fun band(): Int = when {
        level <= 5 -> 1
        level <= 10 -> 2
        level <= 20 -> 3
        else -> 5
    }

    private fun worth(): Int = tier() * band()

    private fun steerForce(): Float = 0.7f * d

    private fun riseSpeed(): Float = min(0.9f, 0.25f + 0.04f * (level - 1)) * d

    // ---------- the maze ----------

    private fun placeWall(l: Float, t: Float, w: Float, h: Float) {
        if (w < 6 * d || h < 6 * d) return
        val bv = BarrierView(this)
        bv.value = 0
        bv.solid = true
        val lp = params(w.toInt(), h.toInt(), touchable = false)
        lp.x = l.toInt()
        lp.y = t.toInt()
        wm.addView(bv, lp)
        walls.add(Wall(bv, lp))
    }

    /** A random gap center at least minApart away from the last one, so you have to zigzag. */
    private fun pickGap(gapW: Float, prev: Float, minApart: Float): Float {
        val lo = 16 * d + gapW / 2f
        val hi = sw - 16 * d - gapW / 2f
        var best = lo + Random.nextFloat() * (hi - lo)
        for (k in 0 until 30) {
            val c = lo + Random.nextFloat() * (hi - lo)
            best = c
            if (abs(c - prev) >= minApart) break
        }
        return best
    }

    /**
     * Rows of walls across the room, each with a gap, zigzagging so you have to cross every row.
     * Currents are placed on purpose:
     *  - an updraft carries you up through one gap like an elevator
     *  - a side stream in a lane flows toward the gap above it
     *  - level 4+: a riptide guards one gap; that row also gets a second, narrow gap
     *  - level 7+: a whirlpool spins in one lane
     *  - level 10+: a second side stream, flowing the wrong way
     *  - level 11+: some currents pulse (6s on, 6s off)
     */
    private fun buildMaze() {
        zones.clear()
        val wallH = 14 * d
        mazeTop = ceilY + 160 * d
        mazeBot = floorStartY - 110 * d
        val rows = min(3 + (level - 1) / 3, 6)
        val ys = FloatArray(rows) { mazeTop + (mazeBot - mazeTop) * it / max(1, rows - 1) }
        rowYs = ys
        nextRow = rows - 1
        val gapW = max(3.6f * r, 78 * d - (level - 1) * 2f * d)
        val gaps = FloatArray(rows)

        // which rows get special currents
        val upRow = Random.nextInt(rows)
        val ripRow = if (level >= 4 && rows > 1) (upRow + 1 + Random.nextInt(rows - 1)) % rows else -1
        ripRowIdx = ripRow

        var prev = sw / 2f
        for (i in rows - 1 downTo 0) {
            val gc = pickGap(gapW, prev, sw * 0.35f)
            gaps[i] = gc
            prev = gc
            val y = ys[i]
            if (i == ripRow) {
                // the riptide gap, plus a narrow clean gap on the far side
                val narrow = 2.6f * r
                val nc = if (gc < sw / 2f) sw - 20 * d - narrow / 2f else 20 * d + narrow / 2f
                narrowC = nc
                narrowW = narrow
                val a = min(gc - gapW / 2f, nc - narrow / 2f)
                val aR = min(gc + gapW / 2f, nc + narrow / 2f)
                val b = max(gc - gapW / 2f, nc - narrow / 2f)
                val bR = max(gc + gapW / 2f, nc + narrow / 2f)
                placeWall(0f, y, a, wallH)
                placeWall(aR, y, b - aR, wallH)
                placeWall(bR, y, sw - bR, wallH)
            } else {
                placeWall(0f, y, gc - gapW / 2f, wallH)
                placeWall(gc + gapW / 2f, y, sw - (gc + gapW / 2f), wallH)
            }
        }

        // lane i is the open water just below row i (down to the next row or the start area)
        fun laneTop(i: Int) = ys[i] + wallH
        fun laneBot(i: Int) = if (i + 1 < rows) ys[i + 1] else floorStartY
        val pulse = level >= 11

        // updraft: an elevator through the gap of upRow
        run {
            val colW = min(gapW - 8 * d, 60 * d)
            val t = if (upRow > 0) ys[upRow - 1] + wallH else ceilY + 110 * d
            zones.add(WaterZone(WaterZone.UP, gaps[upRow] - colW / 2f, t, gaps[upRow] + colW / 2f, laneBot(upRow),
                0f, -1f, 4f * d, pulse = pulse && Random.nextBoolean(), phaseMs = Random.nextLong(12000)))
        }

        // side stream: in a lane, flowing toward the gap in the row above it
        fun sideStream(row: Int, towardGap: Boolean) {
            val lt = laneTop(row)
            val lb = laneBot(row)
            if (row + 1 >= rows && lb - lt > 120 * d) return
            val target = if (row >= 1) gaps[row] else gaps[0]
            val flowLeft = if (towardGap) target < sw / 2f else target >= sw / 2f
            val bandH = min(54 * d, (lb - lt) * 0.7f)
            val mid = (lt + lb) / 2f
            val l = if (flowLeft) min(target, sw * 0.5f) - 10 * d else 0f
            val rr = if (flowLeft) sw.toFloat() else max(target, sw * 0.5f) + 10 * d
            zones.add(WaterZone(WaterZone.SIDE, l.coerceAtLeast(0f), mid - bandH / 2f, rr.coerceAtMost(sw.toFloat()),
                mid + bandH / 2f, if (flowLeft) -1f else 1f, 0f, 3.2f * d,
                pulse = pulse, phaseMs = Random.nextLong(12000)))
        }
        // pick a lane that isn't the updraft's lane when possible
        val sideRow = if (rows > 1) (upRow + 1) % rows else 0
        sideStream(if (sideRow == rows - 1 && rows > 1) sideRow - 1 else sideRow, towardGap = true)

        // riptide: pushes down through the guarded gap
        if (ripRow >= 0) {
            val colW = min(gapW - 6 * d, 64 * d)
            val t = if (ripRow > 0) ys[ripRow - 1] + wallH else ceilY + 110 * d
            zones.add(WaterZone(WaterZone.RIP, gaps[ripRow] - colW / 2f, t, gaps[ripRow] + colW / 2f,
                laneTop(ripRow) + 40 * d, 0f, 1f, (2.0f + min(1.0f, 0.1f * (level - 4))) * d,
                pulse = pulse, phaseMs = Random.nextLong(12000)))
        }

        // whirlpool: spins in a lane, turns you to a new heading
        if (level >= 7 && rows >= 2) {
            val lane = Random.nextInt(rows - 1)
            val lt = laneTop(lane)
            val lb = laneBot(lane)
            val rad = min(64 * d, (lb - lt) / 2f + 10 * d)
            val wcx = rad + 20 * d + Random.nextFloat() * (sw - 2 * rad - 40 * d)
            val wcy = (lt + lb) / 2f
            zones.add(WaterZone(WaterZone.WHIRL, wcx - rad, wcy - rad, wcx + rad, wcy + rad, 0f, 0f, 3.5f * d,
                spin = if (Random.nextBoolean()) 1f else -1f))
        }

        // a second stream flowing the wrong way
        if (level >= 10 && rows >= 3) sideStream((sideRow + 1) % (rows - 1), towardGap = false)

        // pillars in the lanes from level 3: extra walls to route around, never blocking a gap
        val pillars = if (level >= 3) min((level - 1) / 2, 6) else 0
        var placed = 0
        var tries = 0
        while (placed < pillars && tries < 60) {
            tries++
            val lane = Random.nextInt(rows - 1).coerceAtLeast(0)
            if (rows < 2) break
            val lt = laneTop(lane)
            val lb = laneBot(lane)
            val size = 30 * d
            if (lb - lt < size + 2 * r + 20 * d) continue
            val px = 20 * d + Random.nextFloat() * (sw - 40 * d - size)
            val py = lt + (lb - lt - size) / 2f
            val pcx = px + size / 2f
            // keep clear of the gaps above and below this lane
            if (abs(pcx - gaps[lane]) < gapW + size) continue
            if (lane + 1 < rows && abs(pcx - gaps[lane + 1]) < gapW + size) continue
            placeWall(px, py, size, size)
            placed++
        }
    }

    private fun overlapsWall(x: Float, y: Float, pad: Float): Boolean {
        for (w in walls) {
            val l = w.lp.x - pad
            val t = w.lp.y - pad
            if (x > l && x < w.lp.x + w.lp.width + pad && y > t && y < w.lp.y + w.lp.height + pad) return true
        }
        return false
    }

    /** Apples sit somewhere in the maze, often out of your way: is a slot pull worth the detour? */
    private fun spawnFood() {
        if (over) return
        val size = (24 * d).toInt()
        for (k in 0 until 40) {
            val x = 30 * d + Random.nextFloat() * (sw - 60 * d)
            val y = mazeTop - 40 * d + Random.nextFloat() * (mazeBot - mazeTop + 40 * d)
            if (y > floorY - 60 * d) continue
            if (overlapsWall(x, y, 20 * d)) continue
            if (foods.any { hypot(it.x - x, it.y - y) < 80 * d }) continue
            val fv = FoodView(this)
            val lp = params(size, size, touchable = false)
            lp.x = (x - size / 2f).toInt()
            lp.y = (y - size / 2f).toInt()
            wm.addView(fv, lp)
            foods.add(Food(fv, x, y))
            return
        }
    }

    private fun collectFood() {
        for (f in foods.toList()) {
            if (hypot(cx - f.x, cy - f.y) < r + 13 * d) {
                wm.removeView(f.view)
                foods.remove(f)
                snd("pop", 1f, 40L)
                sfx?.play("coin", 0.8f, 40L, 1.5f)
                addEnergy(15f)
                addPoints(25)
                celebrate(yum.random())
                fillReel()
                handler.postDelayed({ spawnFood() }, 2500)
            } else if (floorY <= f.y + 12 * d) {
                wm.removeView(f.view)
                foods.remove(f)
                handler.postDelayed({ spawnFood() }, 1500)
            }
        }
    }

    private fun clearRoom() {
        for (w in walls) {
            if (!w.removed) {
                w.removed = true
                wm.removeView(w.view)
            }
        }
        walls.clear()
        for (f in foods) wm.removeView(f.view)
        foods.clear()
    }

    /** The rising floor swallows any wall it reaches, so he can never get pinned under one. */
    private fun swallowWall(w: Wall) {
        w.removed = true
        wm.removeView(w.view)
        walls.remove(w)
        snd("bust", 0.4f, 300L)
        parts?.smash(w.lp.x + w.lp.width / 2f, w.lp.y + w.lp.height / 2f)
    }

    // ---------- currents ----------

    /** Pulsing currents: 6s on, 6s off, fading in slowly so you can see them coming. */
    private fun updatePulses(now: Long, dt: Float) {
        for (z in zones) {
            if (!z.pulse) { z.power = 1f; continue }
            val target = if (now < z.forcedUntil) 1f
                else if (((now - levelStartMs + z.phaseMs) / 6000L) % 2L == 0L) 1f else 0f
            z.power += (target - z.power) * min(1f, 0.04f * dt)
        }
    }

    /** Water speed at (x, y), into wx/wy. Records which currents he has visited. */
    private fun sampleWater(x: Float, y: Float): WaterZone? {
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
            if (inside && z.power > 0.5f && visitedZoneIds.add(z.id)) {
                currentsVisited++
                if (currentsVisited >= zones.size) {
                    visitedAllCurrents = true
                    celebrate("EXPLORER!", big = true)
                } else celebrate(rides.random())
            }
        }
        return whirl
    }

    // ---------- celebrations ----------

    /**
     * Candy-crush style praise: big bouncy word, a chime that climbs higher each time this level,
     * and for big moments confetti, casino lights and a mascot dance.
     */
    private fun celebrate(text: String, big: Boolean = false) {
        cheerStreak++
        hud?.let {
            it.praise = text
            it.praiseAt = SystemClock.uptimeMillis()
            it.praiseBig = big
            it.invalidate()
        }
        sfx?.play("ding", 0.9f, 0L, min(2f, 0.9f + 0.1f * cheerStreak))
        if (big) {
            sfx?.play("coins", 1f, 200L)
            parts?.confetti(cx, cy)
            parts?.confetti(sw * (0.2f + 0.6f * Random.nextFloat()), sh * 0.3f)
            showFx()
            val dance = arrayOf(MascotView.Move.TWERK, MascotView.Move.FLOSS, MascotView.Move.SPIN, MascotView.Move.CHEER).random()
            mascot?.play(dance, text)
        }
    }

    /** Called every frame: did he just swim up past the next row of walls? */
    private fun checkRows() {
        while (nextRow >= 0 && nextRow < rowYs.size && cy + r < rowYs[nextRow]) {
            val row = nextRow
            nextRow--
            val passed = rowYs.size - row
            when {
                row == ripRowIdx && narrowW > 0f && abs(cx - narrowC) < narrowW -> celebrate("THREADED THE NEEDLE!", big = true)
                row == ripRowIdx -> celebrate("BEAT THE RIPTIDE!", big = true)
                !touchedSinceRow -> celebrate(smooth.random())
                else -> {
                    sfx?.play("ding", 0.6f, 0L, min(2f, 0.9f + 0.1f * passed))
                    hud?.let { it.popup = "ROW $passed/${rowYs.size}"; it.popupAt = SystemClock.uptimeMillis() }
                }
            }
            touchedSinceRow = false
        }
    }

    // ---------- spin meter: fill it, then tap SPIN to cash it in ----------

    private fun addEnergy(v: Float) {
        if (over) return
        energy = (energy + v).coerceIn(0f, 100f)
        updateSpinReady()
    }

    private fun updateSpinReady() {
        val ready = energy >= 100f && !over
        if (ready == spinShown) return
        spinShown = ready
        sv?.let { it.charged = ready; it.invalidate() }
        spinBtn?.let { b ->
            b.visibility = if (ready) View.VISIBLE else View.GONE
            spinLp.flags = if (ready) flags else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(b, spinLp)
        }
        if (ready) {
            snd("rumble", 0.8f, 500L)
            mascot?.play(MascotView.Move.SPIN, "SPIN READY!")
        }
    }

    /** Tap SPIN: free slot spin, every pulsing current forced on for 5s, and the floor drops. */
    private fun spendSpin() {
        if (over || energy < 100f) return
        energy = 0f
        updateSpinReady()
        blast()
        celebrate("SPIN TIME!", big = true)
        freeSpin()
    }

    private fun blast() {
        flash = 1f
        snd("siren", 1f)
        snd("bust", 1f)
        showFx()
        parts?.smash(cx, cy)
        val until = SystemClock.uptimeMillis() + 5000L
        for (z in zones) if (z.pulse) z.forcedUntil = until
        floorDrop += 0.30f * (floorStartY - (ceilY + 2 * r))
        hud?.let { it.banner = "CURRENTS OPEN! FLOOR DROPS"; it.invalidate() }
        handler.postDelayed({
            hud?.let { if (it.banner?.startsWith("CURRENTS") == true) { it.banner = null; it.invalidate() } }
        }, 1800)
    }

    // ---------- slot reels ----------

    private fun resetReels() {
        reels[0] = -1
        reels[1] = -1
        reels[2] = -1
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

    /** An apple was eaten: the next reel lands on a symbol. */
    private fun fillReel() {
        if (over) return
        if (reelResolving || reelCount >= 3) {
            pendingFills++
            return
        }
        reels[reelCount] = rollSymbol()
        reelCount++
        when (reelCount) {
            1 -> snd("pop", 1f, 0L)
            2 -> sfx?.play("coins", 1f, 0L)
            else -> sfx?.play("slot", 1f, 0L)
        }
        hud?.let { it.reels = reels; it.invalidate() }
        if (reelCount == 3) resolveReels()
    }

    /** Free spin from the SPIN button: fills whatever reels are left. */
    private fun freeSpin() {
        if (reelResolving) {
            pendingFills += 3
            return
        }
        while (reelCount < 3 && !reelResolving) fillReel()
    }

    /** 3 of a kind = 5x the escape tier (777 = 10x), a pair = 2x, nothing = half. Not band-multiplied. */
    private fun resolveReels() {
        reelResolving = true
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
                celebrate("777!!!", big = true)
            } else {
                pay = base * 5
                text = "3 OF A KIND! +$pay"
                celebrate("JACKPOT!", big = true)
            }
            sfx?.play("siren", 1f, 0L)
            snd("fanfare")
            mascot?.play(MascotView.Move.BACKFLIP, "JACKPOT!")
            flash = 0.6f
        } else if (a == b || b == c || a == c) {
            pay = base * 2
            text = "PAIR! +$pay"
            celebrate("NICE PAIR!")
        } else {
            pay = base / 2
            text = "NO MATCH +$pay"
            snd("wrong", 0.4f)
        }
        addPoints(pay)
        hud?.let { it.reelResolving = true; it.banner = text; it.invalidate() }
        handler.postDelayed({
            hud?.let { if (it.banner == text) it.banner = null }
            reels[0] = -1
            reels[1] = -1
            reels[2] = -1
            reelCount = 0
            reelResolving = false
            hud?.let { it.reels = reels; it.reelResolving = false; it.invalidate() }
            val n = min(3, pendingFills)
            pendingFills -= n
            repeat(n) { fillReel() }
        }, 1300)
    }

    private fun addPoints(v: Int) {
        score += v
        hud?.let { it.score = score; it.invalidate() }
    }

    // ---------- end of a level ----------

    private fun saveBest() {
        if (score > best) {
            best = score
            getSharedPreferences("betito", MODE_PRIVATE).edit().putInt("best_score", best).apply()
        }
    }

    private fun levelClear() {
        over = true
        updateSpinReady()
        vx = 0f
        vy = 0f
        steering = false
        val bandM = band()
        val pts = worth()
        val secs = (SystemClock.uptimeMillis() - levelStartMs) / 1000f
        val bonus = when {
            secs < 8f -> 500
            secs < 14f -> 250
            secs < 20f -> 100
            else -> 0
        }

        // style bonuses: three ways to solve a room
        val cleanBonus = if (!touchedWallThisLevel) 500 * bandM else 0
        val explorerBonus = if (visitedAllCurrents) 300 * bandM else 0
        val directBonus = if (currentsVisited == 0) 200 * bandM else 0

        score += pts + bonus + cleanBonus + explorerBonus + directBonus
        saveBest()
        snd("fanfare")

        // 1 star for escaping, +1 for any style bonus, +1 for two bonuses / a clutch or fast escape
        val styles = (if (cleanBonus > 0) 1 else 0) + (if (explorerBonus > 0) 1 else 0) + (if (directBonus > 0) 1 else 0)
        val clutch = tier() >= 1000
        val stars = 1 + (if (styles >= 1) 1 else 0) + (if (styles >= 2 || clutch || bonus >= 250) 1 else 0)
        val cheer = when {
            clutch -> "CLUTCH!!"
            cleanBonus > 0 && styles >= 2 -> "TURTLEY AWESOME!"
            cleanBonus > 0 -> "SHELLTASTIC!"
            directBonus > 0 -> "STRAIGHT SHOT!"
            explorerBonus > 0 -> "EXPLORER!"
            else -> "ESCAPED!"
        }
        celebrate(cheer, big = true)
        if (stars == 3) sfx?.play("jackpot", 1f, 0L)
        for (k in 0 until 4) {
            handler.postDelayed({ parts?.confetti(sw * (0.15f + 0.7f * Random.nextFloat()), sh * (0.2f + 0.4f * Random.nextFloat())) }, 250L * k)
        }
        hud?.let { it.stars = stars; it.starsAt = SystemClock.uptimeMillis() }
        mascot?.play(MascotView.Move.BACKFLIP, "ESCAPED!")
        showFx()
        val lines = ArrayList<String>()
        lines.add("ESCAPE +${pts + bonus}")
        lines.add("%.1fs".format(secs))
        if (cleanBonus > 0) lines.add("CLEAN +$cleanBonus")
        if (explorerBonus > 0) lines.add("EXPLORER +$explorerBonus")
        if (directBonus > 0) lines.add("DIRECT +$directBonus")
        hud?.let {
            it.message = "LEVEL $level CLEARED!"
            it.banner = lines.joinToString("  ")
            it.invalidate()
        }
        level++
        pushHud()
        handler.postDelayed({ startLevel() }, 3200)
    }

    private fun squished() {
        over = true
        updateSpinReady()
        vx = 0f
        vy = 0f
        steering = false
        saveBest()
        snd("squish")
        sv?.bump()
        dying = true
        dyingUntil = SystemClock.uptimeMillis() + 900L
        val soClose = abs(cx - gapC) < 70 * d
        mascot?.play(MascotView.Move.FACEPALM, if (soClose) "SO CLOSE!" else "SQUISHED!")
        lives--
        if (lives > 0) {
            pushHud()
            hud?.let {
                it.banner = if (lives == 1) "LAST LIFE!" else "$lives LIVES LEFT"
                it.message = if (soClose) "SO CLOSE!" else "SQUISHED!"
                it.invalidate()
            }
            handler.postDelayed({ startLevel() }, 2600)
        } else {
            snd("lose")
            pushHud()
            hud?.let {
                it.banner = "FINAL SCORE $score  (LEVEL $level)"
                it.message = "GAME OVER"
                it.invalidate()
            }
            handler.postDelayed({ newGame() }, 3500)
        }
    }

    private fun showFx() {
        stopFx()
        val f = FxView(this)
        f.durationMs = 2000L
        f.onFinished = { stopFx() }
        wm.addView(f, params(sw, sh, touchable = false))
        fx = f
    }

    private fun stopFx() {
        fx?.let { wm.removeView(it) }
        fx = null
    }

    // ---------- movement ----------

    /** Turn the velocity toward (dx, dy) without changing his speed. */
    private fun steerToward(dx: Float, dy: Float, hdt: Float) {
        val sl = hypot(dx, dy)
        if (sl < 0.001f) return
        val mag = hypot(vx, vy)
        vx += dx / sl * steerForce() * hdt
        vy += dy / sl * steerForce() * hdt
        val nm = hypot(vx, vy)
        if (nm > 0.001f) {
            vx = vx / nm * mag
            vy = vy / nm * mag
        }
    }

    /** Speed drifts back toward base (unless climbing/braking), and stays between min and max. */
    private fun settleSpeed() {
        var mag = hypot(vx, vy)
        if (mag < 0.001f) {
            vx = 0f
            vy = -speedMin
            mag = speedMin
        }
        var target = if (thrust == 0) mag + (speedBase - mag) * dragK else mag
        target = target.coerceIn(speedMin, speedMax)
        vx = vx / mag * target
        vy = vy / mag * target
    }

    private fun applyPos() {
        val s = sv ?: return
        slp.x = (cx - 56 * d).toInt()
        slp.y = (cy - 56 * d).toInt()
        wm.updateViewLayout(s, slp)
    }

    private fun moveMascot() {
        val m = mascot ?: return
        val ly = max(ceilY, floorY - 130 * d).toInt()
        if (ly != mlp.y) {
            mlp.y = ly
            wm.updateViewLayout(m, mlp)
        }
    }

    // ---------- sound + effects ----------

    private fun snd(name: String, vol: Float = 1f, gap: Long = 0L) {
        sfx?.play(name, vol, gap)
    }

    /** Every surface is the same soft thud. Touching a wall or the ceiling breaks a CLEAN run. */
    private fun hit(speed: Float, x: Float, y: Float, nx: Float, ny: Float, kind: Int) {
        if (speed <= 1.5f * d) return
        sv?.bump()
        val sp = (speed / (6f * d)).coerceIn(0.2f, 1f)
        snd("tick", 0.3f + 0.3f * sp, 60L)
        parts?.burst(x, y, nx, ny, speed, false, 0)
        if (kind != kFloor) touchedSinceRow = true
        if (kind != kFloor && !touchedWallThisLevel) {
            touchedWallThisLevel = true
            hud?.let { it.cleanRun = false; it.invalidate() }
        }
    }

    // ---------- physics loop ----------

    /** Circle-vs-rectangle. Walls soak up the hit: you stop, then push off. */
    private fun collide(l: Float, t: Float, rr: Float, bb: Float, kind: Int): Boolean {
        val qx = cx.coerceIn(l, rr)
        val qy = cy.coerceIn(t, bb)
        val dx = cx - qx
        val dy = cy - qy
        val d2 = dx * dx + dy * dy
        if (d2 >= r * r) return false
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
            val newVn = -vn * bounce
            vx = newVn * nx + vtx * 0.2f
            vy = newVn * ny + vty * 0.2f
            mx *= 0.3f
            my *= 0.3f
            hit(-vn, cx - nx * r, cy - ny * r, nx, ny, kind)
            return true
        }
        return false
    }

    override fun doFrame(ns: Long) {
        val s = sv ?: return
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceAtMost(2f)
        lastNs = ns

        if (launched && !over) {
            val now = SystemClock.uptimeMillis()

            // two fingers only work while there is spin in the meter; it burns 15 per second
            val twoActive = twoFinger && energy > 0f
            thrust = 0
            if (twoActive) {
                val vdy = p2y - p1y
                thrust = when {
                    vdy < -20f * d -> 1
                    vdy > 20f * d -> -1
                    else -> 0
                }
                energy = max(0f, energy - 15f * dt / 60f)
                updateSpinReady()
            }

            updatePulses(now, dt)

            // flow bonus: swimming with a current refills spin, swimming against it drains a little
            if (wx != 0f || wy != 0f) {
                val ws = hypot(wx, wy)
                val vs = hypot(vx, vy)
                if (ws > 0.5f * d && vs > 0.01f) {
                    val dot = (vx * wx + vy * wy) / (ws * vs)
                    if (dot > 0.5f) addEnergy(6f * dt / 60f)
                    else if (dot < -0.5f && thrust == 0) energy = max(0f, energy - 3f * dt / 60f)
                }
                updateSpinReady()
            }

            val hdt = dt / 3f
            val rise = riseSpeed()
            val topBar = ceilY - ceilThick()
            // the gap is fixed for the level; from level 10 it drifts slowly
            if (level >= 10) {
                val gm = 20f * d + gapW0 / 2f
                gapC = (gapBase + sin((now - levelStartMs) / 3000.0).toFloat() * 30f * d).coerceIn(gm, sw - gm)
            }
            gapL = gapC - gapW0 / 2f
            gapR = gapC + gapW0 / 2f

            for (step in 0 until 3) {
                floorY -= rise * hdt
                if (floorDrop > 0f) {
                    val dd = min(floorDrop, 10f * d * hdt)
                    floorDrop -= dd
                    floorY += dd
                }
                if (floorY > floorStartY) floorY = floorStartY

                // water pushes him only while he is in it
                val whirl = sampleWater(cx, cy)
                var grip = 1f
                if (thrust == -1 && (wx != 0f || wy != 0f)) grip = 0.15f   // braking = dig in and hold
                if (whirl != null && (!steering || twoActive)) {
                    // the whirlpool drags his heading around with it
                    val ddx = cx - whirl.cx
                    val ddy = cy - whirl.cy
                    steerToward(-ddy * whirl.spin, ddx * whirl.spin, hdt * 0.9f)
                }
                cx += (vx + wx * grip + mx) * hdt
                cy += (vy + wy * grip + my) * hdt
                val fade = Math.pow(0.90, (hdt * 3f).toDouble()).toFloat()
                mx *= fade
                my *= fade

                // side walls of the screen
                if (cx < r) {
                    hit(abs(vx), 0f, cy, 1f, 0f, kWall)
                    cx = r
                    vx = abs(vx) * bounce
                    vy *= 0.2f
                    mx = 0f
                }
                if (cx > sw - r) {
                    hit(abs(vx), sw.toFloat(), cy, -1f, 0f, kWall)
                    cx = sw - r
                    vx = -abs(vx) * bounce
                    vy *= 0.2f
                    mx = 0f
                }

                // the floor is moving up: it pushes him, it doesn't count against a clean run
                if (cy > floorY - r) {
                    cy = floorY - r
                    val rel = vy + rise
                    if (rel > 0f) {
                        hit(rel, cx, floorY, 0f, -1f, kFloor)
                        vy = -rise - rel * bounce
                    }
                    if (my > 0f) my = 0f
                }

                // ceiling pieces on each side of the gap, then the walls
                collide(-50f * d, topBar, gapL, ceilY, kCeil)
                collide(gapR, topBar, sw + 50f * d, ceilY, kCeil)
                for (w in walls) {
                    val l = w.lp.x.toFloat()
                    val t = w.lp.y.toFloat()
                    collide(l, t, l + w.lp.width, t + w.lp.height, kSolid)
                }

                if (steering) {
                    // inside a current you go where the water goes: your finger only trims
                    val inCurrent = (wx != 0f || wy != 0f)
                    val authority = if (inCurrent) 0.2f else 1f
                    if (twoActive) {
                        val hdx = p2x - p1x
                        if (abs(hdx) > 12f * d) steerToward(sign(hdx), 0f, hdt * authority)
                        if (thrust == 1) {
                            vy -= climbForce * hdt * authority
                        } else if (thrust == -1) {
                            // braking stays full strength: it's how you get out of a current
                            val nvy = max(0f, abs(vy) - brakeForce * hdt)
                            vy = sign(vy) * nvy
                        }
                    } else {
                        val sdx = steerX - cx
                        val sdy = steerY - cy
                        if (hypot(sdx, sdy) > 20f * d) steerToward(sdx, sdy, hdt * authority)
                    }
                }
                settleSpeed()

                val inGap = cx > gapL + 4 * d && cx < gapR - 4 * d
                if (cy < topBar && inGap) { levelClear(); break }
                if (floorY - 2 * r <= ceilY && !inGap) { squished(); break }
            }

            if (!over) {
                checkRows()
                collectFood()
                for (w in walls.toList()) {
                    if (!w.removed && floorY - 2 * r <= w.lp.y + w.lp.height) swallowWall(w)
                }
                var hd = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
                if (steering && !twoActive) {
                    val tdx = steerX - cx
                    val tdy = steerY - cy
                    var diff = Math.toDegrees(atan2(tdy.toDouble(), tdx.toDouble())).toFloat() - hd
                    while (diff > 180f) diff -= 360f
                    while (diff < -180f) diff += 360f
                    hd += (diff * 0.3f).coerceIn(-15f, 15f)
                }
                s.heading = hd
                hud?.let {
                    it.floorY = floorY
                    it.gapL = gapL
                    it.gapR = gapR
                    it.worth = worth()
                    it.energy = energy
                    it.spinReady = energy >= 100f
                    it.cleanRun = !touchedWallThisLevel
                    it.currentsVisited = currentsVisited
                    it.currentsTotal = zones.size
                    it.steerOn = steering
                    it.steerTwo = twoActive
                    it.steerMode = if (twoActive) thrust else 0
                    it.sx0 = if (twoActive) p1x else cx
                    it.sy0 = if (twoActive) p1y else cy
                    it.sx1 = if (twoActive) p2x else steerX
                    it.sy1 = if (twoActive) p2y else steerY
                    it.invalidate()
                }
                moveMascot()
            }
            applyPos()
            s.invalidate()
        }
        // keep the celebration animations moving between levels
        if (over) hud?.invalidate()
        if (flash > 0f) {
            flash = max(0f, flash - 0.04f * dt)
            hud?.let { it.flash = flash; it.invalidate() }
        }
        if (over && dying) {
            // death in slow motion: the floor crawls up over him
            if (SystemClock.uptimeMillis() > dyingUntil) {
                dying = false
            } else {
                floorY -= riseSpeed() * 0.15f * dt
                hud?.let { it.floorY = floorY; it.invalidate() }
                moveMascot()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this)
        handler.removeCallbacksAndMessages(null)
        if (::wm.isInitialized) {
            stopFx()
            clearRoom()
            sv?.let { wm.removeView(it) }
            tapLayer?.let { wm.removeView(it) }
            closeBtn?.let { wm.removeView(it) }
            spinBtn?.let { wm.removeView(it) }
            parts?.let { wm.removeView(it) }
            mascot?.let { wm.removeView(it) }
            hud?.let { wm.removeView(it) }
        }
        sfx?.release()
        sfx = null
        sv = null
        tapLayer = null
        closeBtn = null
        spinBtn = null
        parts = null
        mascot = null
        hud = null
        super.onDestroy()
    }
}
