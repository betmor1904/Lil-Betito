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

private class Barrier(
    val view: BarrierView,
    val lp: WindowManager.LayoutParams,
    val coin: Boolean = false,
    var removed: Boolean = false,
    var coinSpawned: Boolean = false,
    val value: Int = 10,
    val solid: Boolean = false,
    val cell: Int = -1,
    // close-call tracking for black boxes
    var near: Boolean = false,
    var nearHit: Boolean = false,
    var closeCall: Boolean = false
)

private class Food(val view: FoodView, val lp: WindowManager.LayoutParams, val x: Float, val y: Float, val cell: Int)

private class Coin(val view: CoinView, val lp: WindowManager.LayoutParams, val x: Float, val y: Float)

/**
 * Lil Betito, Gauntlet rules: the gap in the ceiling is always open and is the only way out.
 * Get through it before it shrinks and before the rising floor squishes him.
 * Casino layer: bet part of your score on each level, and coin bricks spin a 3-reel slot.
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
    private var wagerBtn: WagerView? = null
    private var spinBtn: SpinButtonView? = null
    private var parts: PartsView? = null
    private var mascot: MascotView? = null
    private var fx: FxView? = null
    private var sfx: Sfx? = null
    private lateinit var slp: WindowManager.LayoutParams
    private lateinit var tlp: WindowManager.LayoutParams
    private lateinit var mlp: WindowManager.LayoutParams
    private lateinit var spinLp: WindowManager.LayoutParams
    private val obstacles = ArrayList<Barrier>()
    private val coins = ArrayList<Coin>()
    private var lastNx = 0f
    private var lastNy = -1f
    private val handler = Handler(Looper.getMainLooper())

    // turtle physics (velocities are pixels per 60fps frame)
    private var cx = 0f
    private var cy = 0f
    private var vx = 0f
    private var vy = 0f
    private var r = 0f
    private var lastNs = 0L
    private var lastHitMs = 0L
    private var levelStartMs = 0L
    private var dying = false
    private val toCrush = ArrayList<Barrier>()
    private val foods = ArrayList<Food>()
    private var flash = 0f
    private var maxBricks = 14
    private var gCols = 5
    private var gRows = 6
    private var gMargin = 0f
    private var gCellW = 0f
    private var gCellH = 0f
    private var gTopY = 0f
    private var dyingUntil = 0L

    // speed: drifts back to base; two-finger climb/brake pushes it between min and max
    private val speedBase get() = 8f * d
    private val speedMin get() = 4f * d
    private val speedMax get() = 10f * d
    private val climbForce get() = 3f * d
    private val brakeForce get() = 3f * d
    private val dragK = 0.02f

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
    private var respawns = 0
    private var floorDrop = 0f

    // game state
    private var level = 1
    private var best = 0
    private var score = 0
    private var launched = false
    private var over = false
    private var combo = 0
    private var lastComboMs = 0L
    private val comboWindow = 1500L
    private var lives = 3

    // spin meter (was energy): fuel for two-finger control, tap SPIN when full
    private var energy = 0f
    private var spinShown = false

    // wager
    private var wagerPct = 10
    private var wager = 0
    private var wagerLocked = false
    private var betsCloseMs = 0L
    private val betWindow = 4000L

    // slot reels: 0 cherry, 1 bell, 2 bar, 3 seven, 4 coin. Sevens are the rarest.
    private val reels = intArrayOf(-1, -1, -1)
    private var reelCount = 0
    private var reelResolving = false
    private var pendingFills = 0
    private val symbolWeights = intArrayOf(30, 20, 15, 7, 28)

    // water: currents push him around. (wx, wy) is the water speed where he is right now,
    // (mx, my) is momentum he carries out of a current, a slingshot or a tail kick.
    private val zones = ArrayList<WaterZone>()
    private var wx = 0f
    private var wy = 0f
    private var mx = 0f
    private var my = 0f
    private var whirlMs = 0L          // how long he has been circling in a whirlpool
    private var inWhirl = false
    private var lastWx = 0f
    private var lastWy = 0f
    private val kickCost = 8f

    // tail kick: a quick swipe with any finger
    private val downT = LongArray(10)
    private val downX = FloatArray(10)
    private val downY = FloatArray(10)

    private val kFloor = 0
    private val kWall = 1
    private val kObstacle = 2
    private val kCeil = 3
    private val kCoinBrick = 4
    private val kSolid = 5
    private val bounce = 1f

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
        // the ground starts at the bottom of the screen, so there is a lot of room
        val bottom = sh - 70 * d
        floorStartY = bottom
        floorY = floorStartY
        val prefs = getSharedPreferences("betito", MODE_PRIVATE)
        best = prefs.getInt("best_score", 0)
        wagerPct = prefs.getInt("wager_pct", 10)

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
            // raw coordinates for every pointer (getRawX(index) needs a newer Android)
            val ox = e.rawX - e.getX(0)
            val oy = e.rawY - e.getY(0)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    noteDown(e.getPointerId(0), e.getX(0) + ox, e.getY(0) + oy)
                    ptr1 = e.getPointerId(0)
                    ptr2 = -1
                    p1x = e.getX(0) + ox
                    p1y = e.getY(0) + oy
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val id = e.getPointerId(i)
                    noteDown(id, e.getX(i) + ox, e.getY(i) + oy)
                    if (ptr2 == -1 && id != ptr1) {
                        ptr2 = id
                        p2x = e.getX(i) + ox
                        p2y = e.getY(i) + oy
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val i1 = e.findPointerIndex(ptr1)
                    if (i1 >= 0) {
                        p1x = e.getX(i1) + ox
                        p1y = e.getY(i1) + oy
                    }
                    val i2 = e.findPointerIndex(ptr2)
                    if (i2 >= 0) {
                        p2x = e.getX(i2) + ox
                        p2y = e.getY(i2) + oy
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(e.actionIndex)
                    noteUp(id, e.getX(e.actionIndex) + ox, e.getY(e.actionIndex) + oy)
                    if (id == ptr2) {
                        ptr2 = -1
                    } else if (id == ptr1) {
                        // the anchor lifted: the other finger becomes the anchor
                        ptr1 = ptr2
                        p1x = p2x
                        p1y = p2y
                        ptr2 = -1
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP)
                        noteUp(e.getPointerId(0), e.getX(0) + ox, e.getY(0) + oy)
                    ptr1 = -1
                    ptr2 = -1
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

        // wager - / + buttons, just left of the close button
        val wv = WagerView(this)
        val wlp = params((124 * d).toInt(), (40 * d).toInt())
        wlp.x = sw - ((58 + 124) * d).toInt()
        wlp.y = (ceilY + 40 * d).toInt()
        wv.onMinus = { changeWager(-10) }
        wv.onPlus = { changeWager(10) }
        wm.addView(wv, wlp)
        wagerBtn = wv

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
        clearObstacles()
        floorY = floorStartY
        launched = true
        over = false
        steering = false
        levelStartMs = SystemClock.uptimeMillis()
        dying = false
        respawns = 0
        floorDrop = 0f
        combo = 0
        thrust = 0
        cx = sw / 2f
        cy = floorY - r
        // he is moving the moment the room appears: straight up at base speed
        vx = 0f
        vy = -speedBase
        mx = 0f
        my = 0f
        wx = 0f
        wy = 0f
        inWhirl = false
        whirlMs = 0L

        // betting is open for the first few seconds of every level
        wagerLocked = false
        wager = 0
        betsCloseMs = levelStartMs + betWindow

        // fresh reels each level
        resetReels()

        // the gap starts wide and sits somewhere new every level
        val gapW = max(2 * r + 14f * d, 90f * d - (level - 1) * 5f * d)
        val margin = 20f * d
        val center = margin + gapW / 2f + Random.nextFloat() * (sw - 2 * margin - gapW)
        gapC = center
        gapBase = center
        gapW0 = gapW
        gapL = center - gapW / 2f
        gapR = center + gapW / 2f

        energy = 30f      // a little spin to start, so the tail kick works right away
        flash = 0f
        updateSpinReady()
        buildCurrents()
        buildRoom()
        sv?.let { it.heading = -90f; it.radiusPx = r; it.charged = false; it.invalidate() }
        pushHud()
        hud?.let {
            it.message = "LEVEL $level"
            it.banner = "PLACE YOUR BET   - / +"
            it.invalidate()
        }
        handler.postDelayed({ hud?.let { it.message = null; it.invalidate() } }, 1400)
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
            it.combo = combo
            it.comboMult = comboMult()
            it.comboLeft = 0f
            it.lives = lives
            it.energy = energy
            it.spinReady = energy >= 100f
            it.flash = 0f
            it.reels = reels
            it.reelResolving = reelResolving
            it.zones = zones
            it.wagerLocked = wagerLocked
            it.wager = if (wagerLocked) wager else pendingWager()
            it.wagerPct = wagerPct
            it.betSecs = 0
            it.invalidate()
        }
        wagerBtn?.let { it.pct = wagerPct; it.locked = wagerLocked; it.invalidate() }
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

    /** Points for escaping right now. */
    private fun worth(): Int = tier() * band()

    /** How hard your finger turns him. Steering only changes direction, never speed. */
    private fun steerForce(): Float = 1.5f * d

    private fun riseSpeed(): Float = min(1.2f, 0.30f + 0.05f * (level - 1)) * d

    // ---------- wager ----------

    private fun pendingWager(): Int = score * wagerPct / 100

    private fun changeWager(step: Int) {
        if (wagerLocked) {
            snd("wrong", 0.3f, 200L)
            return
        }
        val np = (wagerPct + step).coerceIn(0, 100)
        if (np == wagerPct) return
        wagerPct = np
        getSharedPreferences("betito", MODE_PRIVATE).edit().putInt("wager_pct", wagerPct).apply()
        if (step > 0) sfx?.play("coin", 0.7f, 60L, 1.4f) else snd("tick", 0.8f, 60L)
        pushHud()
    }

    private fun lockWager() {
        if (wagerLocked) return
        wagerLocked = true
        wager = pendingWager()
        snd("clink", 1f)
        if (!over) {
            hud?.let { it.banner = if (wager > 0) "BETS CLOSED - WAGER $wager" else "BETS CLOSED"; it.invalidate() }
            handler.postDelayed({ hud?.let { if (it.banner?.startsWith("BETS") == true) { it.banner = null; it.invalidate() } } }, 1400)
        }
        pushHud()
    }

    // ---------- currents ----------

    /**
     * Lay out this level's water. Levels 1-3: an updraft and a side stream. 4+: a riptide
     * pulling down toward the floor. 7+: a whirlpool you can slingshot out of.
     * 10+: a second side stream. 11+: some currents pulse on and off.
     */
    private fun buildCurrents() {
        zones.clear()
        val top = ceilY
        val bot = floorStartY
        val hh = bot - top
        val pulse = level >= 11
        val upW = 76 * d
        val upLeft = Random.nextBoolean()
        val upX = if (upLeft) 20 * d + Random.nextFloat() * (sw * 0.35f - upW)
                  else sw * 0.65f + Random.nextFloat() * (sw * 0.35f - upW - 20 * d)
        zones.add(WaterZone(WaterZone.UP, upX, top + 0.22f * hh, upX + upW, bot, 0f, -1f, 5.5f * d,
            pulse = pulse && Random.nextBoolean(), phaseMs = Random.nextLong(5000)))

        // a band of water flowing left or right across most of the room
        fun side(yFrac: Float, flowLeft: Boolean) {
            val bandH = 58 * d
            val y = top + yFrac * hh
            val l = if (flowLeft) sw * 0.2f else 0f
            val r = if (flowLeft) sw.toFloat() else sw * 0.8f
            zones.add(WaterZone(WaterZone.SIDE, l, y, r, y + bandH, if (flowLeft) -1f else 1f, 0f, 4.5f * d,
                pulse = pulse, phaseMs = Random.nextLong(5000)))
        }
        // the main side stream flows toward the updraft, so you can surf one into the other
        side(0.42f + Random.nextFloat() * 0.18f, flowLeft = upLeft)
        // later, a lower stream flows the other way, toward the riptide side
        if (level >= 10) side(0.70f + Random.nextFloat() * 0.08f, flowLeft = !upLeft)

        if (level >= 4) {
            // riptide on the other side of the room, low down: the danger current
            val ripW = 66 * d
            val ripX = if (upLeft) sw * 0.55f + Random.nextFloat() * (sw * 0.4f - ripW)
                       else 20 * d + Random.nextFloat() * (sw * 0.4f - ripW)
            zones.add(WaterZone(WaterZone.RIP, ripX, top + 0.35f * hh, ripX + ripW, bot, 0f, 1f,
                (3.0f + min(1.5f, 0.15f * (level - 4))) * d,
                pulse = pulse, phaseMs = Random.nextLong(5000)))
        }
        if (level >= 7) {
            val rad = 72 * d
            val wcx = rad + 30 * d + Random.nextFloat() * (sw - 2 * rad - 60 * d)
            val wcy = top + (0.30f + Random.nextFloat() * 0.25f) * hh
            zones.add(WaterZone(WaterZone.WHIRL, wcx - rad, wcy - rad, wcx + rad, wcy + rad, 0f, 0f, 5.5f * d,
                spin = if (Random.nextBoolean()) 1f else -1f))
        }
    }

    /** Fade pulsing currents on and off: 2.5s on, 2.5s off. */
    private fun updatePulses(now: Long, dt: Float) {
        for (z in zones) {
            if (!z.pulse) { z.power = 1f; continue }
            val on = ((now - levelStartMs + z.phaseMs) / 2500L) % 2L == 0L
            val target = if (on) 1f else 0f
            z.power += (target - z.power) * min(1f, 0.08f * dt)
        }
    }

    /** Water speed at (x, y), into wx/wy. Returns the whirlpool he is in, if any. */
    private fun sampleWater(x: Float, y: Float): WaterZone? {
        wx = 0f
        wy = 0f
        var whirl: WaterZone? = null
        for (z in zones) {
            if (z.power < 0.02f) continue
            if (z.kind == WaterZone.WHIRL) {
                val ddx = x - z.cx
                val ddy = y - z.cy
                val dist = hypot(ddx, ddy)
                if (dist < z.rad && dist > 1f) {
                    val ux = ddx / dist
                    val uy = ddy / dist
                    // spin around the middle, with a gentle pull inward so he keeps circling
                    val s = z.speed * z.power * (0.6f + 0.4f * dist / z.rad)
                    wx += -uy * z.spin * s - ux * s * 0.25f
                    wy += ux * z.spin * s - uy * s * 0.25f
                    whirl = z
                }
            } else if (x >= z.l && x <= z.r && y >= z.t && y <= z.b) {
                wx += z.dx * z.speed * z.power
                wy += z.dy * z.speed * z.power
            }
        }
        return whirl
    }

    private fun noteDown(id: Int, x: Float, y: Float) {
        if (id !in 0..9) return
        downT[id] = SystemClock.uptimeMillis()
        downX[id] = x
        downY[id] = y
    }

    /** A quick swipe (under a quarter second, at least 35dp) is a tail kick that way. */
    private fun noteUp(id: Int, x: Float, y: Float) {
        if (id !in 0..9 || downT[id] == 0L) return
        val held = SystemClock.uptimeMillis() - downT[id]
        downT[id] = 0L
        val ddx = x - downX[id]
        val ddy = y - downY[id]
        val dist = hypot(ddx, ddy)
        if (held < 250L && dist > 35f * d) tailKick(ddx / dist, ddy / dist)
    }

    private fun tailKick(dirX: Float, dirY: Float) {
        if (over || !launched) return
        if (energy < kickCost) {
            snd("wrong", 0.3f, 200L)
            hud?.let { it.popup = "NEED SPIN TO KICK"; it.popupAt = SystemClock.uptimeMillis(); it.invalidate() }
            return
        }
        energy -= kickCost
        updateSpinReady()
        val mag = hypot(vx, vy).coerceAtLeast(speedBase)
        vx = dirX * mag
        vy = dirY * mag
        mx += dirX * 10f * d
        my += dirY * 10f * d
        sv?.kick()
        sfx?.play("woo", 0.8f, 150L, 1.2f)
        parts?.burst(cx - dirX * r, cy - dirY * r, -dirX, -dirY, 8f * d, false, 0)
    }

    // ---------- the room: a grid of cells holding bricks, black boxes and food ----------

    private fun layoutGrid() {
        gMargin = 14 * d
        gCellW = (sw - 2 * gMargin) / gCols
        gTopY = ceilY + 150 * d
        val botY = floorStartY - 90 * d
        gRows = max(3, ((botY - gTopY) / (52 * d)).toInt())
        gCellH = (botY - gTopY) / gRows
    }

    private fun cellFree(cell: Int): Boolean {
        for (b in obstacles) if (b.cell == cell) return false
        for (f in foods) if (f.cell == cell) return false
        return true
    }

    /** Empty cells that are comfortably above the rising floor, in random order. */
    private fun freeCells(): MutableList<Int> {
        val out = ArrayList<Int>()
        for (cell in 0 until gCols * gRows) {
            val bottom = gTopY + (cell / gCols + 1) * gCellH
            if (bottom < floorY - 50 * d && cellFree(cell)) out.add(cell)
        }
        out.shuffle()
        return out
    }

    private fun brickCount(): Int = obstacles.count { !it.solid }

    private fun placeBrick(cell: Int, wDp: Float, hDp: Float, value: Int, solid: Boolean, bar: Boolean, coin: Boolean = false) {
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        val jx = (Random.nextFloat() - 0.5f) * max(0f, gCellW - w - 4 * d) * 0.8f
        val jy = (Random.nextFloat() - 0.5f) * max(0f, gCellH - h - 4 * d) * 0.8f
        val bv = BarrierView(this)
        bv.value = value
        bv.coin = coin
        bv.solid = solid
        bv.bar = bar
        val lp = params(w, h, touchable = false)
        lp.x = (gMargin + (cell % gCols) * gCellW + (gCellW - w) / 2f + jx).toInt()
        lp.y = (gTopY + (cell / gCols) * gCellH + (gCellH - h) / 2f + jy).toInt()
        wm.addView(bv, lp)
        obstacles.add(Barrier(bv, lp, coin, value = value, solid = solid, cell = cell))
    }

    /** Smaller bricks are worth more. Long flat bars are worth extra. Gold bricks spin the slot. */
    private fun spawnBrick(cell: Int) {
        val roll = Random.nextInt(100)
        when {
            roll < 28 -> placeBrick(cell, 44f, 18f, 10, false, false)
            roll < 42 -> placeBrick(cell, 18f, 44f, 10, false, false)
            roll < 56 -> placeBrick(cell, 30f, 14f, 25, false, false)
            roll < 66 -> placeBrick(cell, 14f, 30f, 25, false, false)
            roll < 76 -> placeBrick(cell, 20f, 10f, 50, false, false, coin = true)
            roll < 86 -> placeBrick(cell, 26f, 26f, 30, false, false, coin = true)   // gold slot brick
            else -> placeBrick(cell, 64f, 10f, 40, false, true)
        }
    }

    private fun buildRoom() {
        layoutGrid()
        // permanent black boxes: they never pop, they bounce him around and get in the way
        val blacks = min(3 + level / 2, 9)
        var cells = freeCells()
        for (k in 0 until min(blacks, cells.size)) {
            if (Random.nextBoolean()) placeBrick(cells[k], 36f, 36f, 0, true, false)
            else placeBrick(cells[k], 62f, 16f, 0, true, false)
        }
        maxBricks = min(14 + 2 * (level - 1), 26)
        cells = freeCells()
        for (k in 0 until min(maxBricks, cells.size)) spawnBrick(cells[k])
        for (k in 0 until (if (level >= 5) 3 else 2)) spawnFood()
    }

    /** Apples go in tight spots right next to the black boxes, so you have to steer in for them. */
    private fun spawnFood() {
        if (over) return
        val free = freeCells()
        if (free.isEmpty()) return
        var cell = free[0]
        for (fc in free) {
            val col = fc % gCols
            val row = fc / gCols
            var near = false
            for (b in obstacles) {
                if (b.solid && abs(b.cell % gCols - col) + abs(b.cell / gCols - row) == 1) near = true
            }
            if (near) {
                cell = fc
                break
            }
        }
        val size = (24 * d).toInt()
        val x = gMargin + (cell % gCols) * gCellW + gCellW / 2f
        val y = gTopY + (cell / gCols) * gCellH + gCellH / 2f
        val fv = FoodView(this)
        val lp = params(size, size, touchable = false)
        lp.x = (x - size / 2f).toInt()
        lp.y = (y - size / 2f).toInt()
        wm.addView(fv, lp)
        foods.add(Food(fv, lp, x, y, cell))
    }

    private fun collectFood() {
        for (f in foods.toList()) {
            if (hypot(cx - f.x, cy - f.y) < r + 13 * d) {
                wm.removeView(f.view)
                foods.remove(f)
                snd("pop", 1f, 40L)
                sfx?.play("coin", 0.8f, 40L, 1.5f)
                addEnergy(25f)
                addPoints(25)
                handler.postDelayed({ spawnFood() }, 2500)
            } else if (floorY <= f.y + 12 * d) {
                wm.removeView(f.view)
                foods.remove(f)
                handler.postDelayed({ spawnFood() }, 1500)
            }
        }
    }

    /** Above this line (the top 40% of the room) no more bricks come back, so you can rush the gap. */
    private fun commitLine(): Float = ceilY + 0.4f * (floorStartY - ceilY)

    private fun respawnOne() {
        if (over || brickCount() >= maxBricks || respawns >= maxBricks || cy < commitLine()) return
        respawns++
        val cells = freeCells()
        if (cells.isEmpty()) return
        spawnBrick(cells[0])
        snd("pop", 0.6f, 150L)
    }

    // ---------- spin meter: fill it, then tap SPIN to cash it in ----------

    private fun addEnergy(v: Float) {
        if (over) return
        energy = (energy + v).coerceIn(0f, 100f)
        updateSpinReady()
    }

    /** Shows or hides the SPIN button (and his glow) depending on whether the meter is full. */
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

    /** Tap SPIN: free slot spin, every brick blows up for double points, and the floor drops. */
    private fun spendSpin() {
        if (over || energy < 100f) return
        energy = 0f
        updateSpinReady()
        blast()
        freeSpin()
    }

    private fun blast() {
        flash = 1f
        snd("siren", 1f)
        snd("bust", 1f)
        showFx()
        parts?.smash(cx, cy)
        // every brick explodes for double points (the black boxes stay)
        for (b in obstacles.toList()) {
            if (b.solid || b.removed) continue
            val mx = b.lp.x + b.lp.width / 2f
            val my = b.lp.y + b.lp.height / 2f
            b.removed = true
            wm.removeView(b.view)
            obstacles.remove(b)
            parts?.smash(mx, my)
            addPoints(b.value * 2)
        }
        addPoints(100)
        // the ground drops, which buys a lot of time
        floorDrop += 0.35f * (floorStartY - (ceilY + 2 * r))
        hud?.let { it.banner = "SPIN! BRICKS BLOWN, FLOOR DROPS"; it.invalidate() }
        handler.postDelayed({ hud?.let { if (it.banner?.startsWith("SPIN!") == true) { it.banner = null; it.invalidate() } } }, 1800)
        // bricks come back only if he is still low in the room
        handler.postDelayed({
            if (!over && cy > commitLine()) {
                val cells = freeCells()
                for (k in 0 until min(maxBricks - brickCount(), cells.size)) spawnBrick(cells[k])
                snd("pop", 1f, 0L)
            }
        }, 900)
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

    /** A coin brick was crushed: the next reel lands on a symbol. */
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

    /** Free spin from the SPIN button: fills whatever reels are left, no coin bricks needed. */
    private fun freeSpin() {
        if (reelResolving) {
            pendingFills += 3
            return
        }
        while (reelCount < 3 && !reelResolving) fillReel()
    }

    /**
     * Three reels are full. 3 of a kind = 5x the escape tier (three sevens = 10x jackpot),
     * a pair = 2x, nothing = half. Slot wins are NOT multiplied by the level band.
     */
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
            } else {
                pay = base * 5
                text = "3 OF A KIND! +$pay"
            }
            sfx?.play("siren", 1f, 0L)
            snd("fanfare")
            mascot?.play(MascotView.Move.BACKFLIP, "JACKPOT!")
            flash = 0.6f
        } else if (a == b || b == c || a == c) {
            pay = base * 2
            text = "PAIR! +$pay"
            snd("ding", 1f)
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

    // ---------- bricks, coins, points ----------

    /** He crushes a brick on contact: score + spin meter. Coin bricks also spin a reel. */
    private fun crushBrick(b: Barrier) {
        if (b.removed) return
        val mx = b.lp.x + b.lp.width / 2f
        val my = b.lp.y + b.lp.height / 2f
        b.removed = true
        wm.removeView(b.view)
        obstacles.remove(b)
        parts?.smash(mx, my)
        snd("bust", 0.7f, 100L)
        addPoints(b.value * comboMult())
        addEnergy(5f)
        if (b.coin) fillReel()
        handler.postDelayed({ respawnOne() }, 900)
    }

    private fun addPoints(v: Int) {
        score += v
        hud?.let { it.score = score; it.invalidate() }
    }

    /** Fast brick chains pay more: x2 at a combo of 5, x3 at 10, x4 at 15. */
    private fun comboMult(): Int = min(4, 1 + combo / 5)

    private fun clearObstacles() {
        for (b in obstacles) {
            if (!b.removed) {
                b.removed = true
                wm.removeView(b.view)
            }
        }
        obstacles.clear()
        for (c in coins) wm.removeView(c.view)
        coins.clear()
        for (f in foods) wm.removeView(f.view)
        foods.clear()
    }

    /** A coin pops out of a coin brick, in the direction he bounced. */
    private fun spawnCoin() {
        val x = (cx + lastNx * 60 * d).coerceIn(r + 10 * d, sw - r - 10 * d)
        val y = (cy + lastNy * 60 * d).coerceIn(ceilY + 20 * d, max(ceilY + 21 * d, floorY - 30 * d))
        val size = (22 * d).toInt()
        val cv = CoinView(this)
        val lp = params(size, size, touchable = false)
        lp.x = (x - size / 2f).toInt()
        lp.y = (y - size / 2f).toInt()
        wm.addView(cv, lp)
        coins.add(Coin(cv, lp, x, y))
        snd("pop", 0.7f, 100L)
    }

    private fun collectCoins() {
        for (c in coins.toList()) {
            if (hypot(cx - c.x, cy - c.y) < r + 12 * d) {
                wm.removeView(c.view)
                coins.remove(c)
                addPoints(25)
                addEnergy(15f)
                snd("coin", 1f, 40L)
            } else if (floorY <= c.y + 12 * d) {
                wm.removeView(c.view)
                coins.remove(c)
            }
        }
    }

    /** The rising floor smashes any obstacle it reaches. */
    private fun crushObstacle(b: Barrier) {
        val mx = b.lp.x + b.lp.width / 2f
        val my = b.lp.y + b.lp.height / 2f
        b.removed = true
        wm.removeView(b.view)
        obstacles.remove(b)
        snd("bust", 0.6f, 250L)
        parts?.smash(mx, my)
        if (!b.solid) handler.postDelayed({ respawnOne() }, 900)
    }

    /** Skimming past a black box without touching it: +10 spin, once per box per level. */
    private fun checkCloseCalls() {
        val zone = 10f * d
        for (b in obstacles) {
            if (!b.solid || b.closeCall) continue
            val l = b.lp.x.toFloat()
            val t = b.lp.y.toFloat()
            val qx = cx.coerceIn(l, l + b.lp.width)
            val qy = cy.coerceIn(t, t + b.lp.height)
            val gap = hypot(cx - qx, cy - qy) - r
            if (gap < zone) {
                if (!b.near) {
                    b.near = true
                    b.nearHit = false
                }
            } else if (b.near) {
                b.near = false
                if (!b.nearHit) {
                    b.closeCall = true
                    addEnergy(10f)
                    sfx?.play("woo", 0.7f, 300L)
                    parts?.burst(qx, qy, 0f, -1f, 6f * d, true, 3)
                    hud?.let { it.popup = "CLOSE CALL +10 SPIN"; it.popupAt = SystemClock.uptimeMillis(); it.invalidate() }
                }
            }
        }
    }

    // ---------- end of a level ----------

    private fun saveBest() {
        if (score > best) {
            best = score
            getSharedPreferences("betito", MODE_PRIVATE).edit().putInt("best_score", best).apply()
        }
    }

    private fun levelClear() {
        lockWager()
        over = true
        updateSpinReady()
        vx = 0f
        vy = 0f
        steering = false
        val bandM = band()
        val pts = worth()
        val secs = (SystemClock.uptimeMillis() - levelStartMs) / 1000f
        val bonus = when {
            secs < 5f -> 500
            secs < 8f -> 250
            secs < 12f -> 100
            else -> 0
        }
        val wagerWin = wager * bandM
        score += pts + bonus + wagerWin
        saveBest()
        snd("fanfare")
        if (wagerWin > 0) sfx?.play("kaching", 1f, 0L)
        mascot?.play(MascotView.Move.BACKFLIP, "ESCAPED!")
        showFx()
        val lines = ArrayList<String>()
        lines.add("ESCAPE +${pts + bonus}")
        lines.add("TIME %.1fs".format(secs))
        if (wager > 0) lines.add("WAGER +$wagerWin")
        hud?.let {
            it.message = "LEVEL $level CLEARED!"
            it.banner = lines.joinToString("   ")
            it.invalidate()
        }
        level++
        // bets for the next level open now
        wagerLocked = false
        wager = 0
        pushHud()
        handler.postDelayed({ hud?.let { it.banner = "NEXT LEVEL - SET YOUR BET   - / +"; it.invalidate() } }, 1600)
        handler.postDelayed({ startLevel() }, 3000)
    }

    private fun squished() {
        lockWager()
        over = true
        updateSpinReady()
        vx = 0f
        vy = 0f
        steering = false
        val lost = min(wager, score)
        score -= lost
        saveBest()
        snd("squish")
        sv?.bump()
        dying = true
        dyingUntil = SystemClock.uptimeMillis() + 900L
        mascot?.play(MascotView.Move.FACEPALM, "SQUISHED!")
        lives--
        wagerLocked = false
        wager = 0
        val lostTxt = if (lost > 0) "LOST $lost.  " else ""
        if (lives > 0) {
            pushHud()
            hud?.let {
                it.banner = lostTxt + (if (lives == 1) "LAST LIFE!" else "$lives LIVES LEFT")
                it.message = "SQUISHED!"
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

    /**
     * Walls, ceiling and floor are a soft tick. Bricks are a bonk plus a chip clink and build
     * the combo; the combo adds rarer sounds on top: coin drop at 3-4, slot ding at 5-7, siren at 8+.
     */
    private fun hit(speed: Float, x: Float, y: Float, nx: Float, ny: Float, kind: Int) {
        if (speed <= 2.5f * d) return
        lastHitMs = SystemClock.uptimeMillis()
        sv?.bump()
        val sp = (speed / (14f * d)).coerceIn(0.3f, 1f)
        if (kind == kSolid) {
            // black boxes: a heavy thunk, no points, no combo
            snd("bonk", 0.9f, 120L)
            parts?.burst(x, y, nx, ny, speed, false, 0)
            return
        }
        if (kind != kObstacle && kind != kCoinBrick) {
            // walls, ceiling, floor: just a tick, no points
            snd("tick", 0.45f + 0.35f * sp, 40L)
            parts?.burst(x, y, nx, ny, speed, false, 0)
            return
        }
        combo++
        lastComboMs = SystemClock.uptimeMillis()
        if (kind == kObstacle) {
            snd("bonk", 0.8f + 0.2f * sp, 120L)
            sfx?.play("clink", 1f, 60L)
        } else {
            snd("bonk", 0.6f, 120L)
            sfx?.play("kaching", 1f, 100L)
        }
        when {
            combo >= 8 -> sfx?.play("siren", 1f, 900L)
            combo >= 5 -> sfx?.play("slot", 1f, 250L)
            combo >= 3 -> sfx?.play("coins", 1f, 250L)
        }
        hud?.let { it.combo = combo; it.invalidate() }
        parts?.burst(x, y, nx, ny, speed, true, combo)
        if (combo == 3 || combo == 5 || combo == 10) {
            val dance = arrayOf(MascotView.Move.TWERK, MascotView.Move.FLOSS, MascotView.Move.SPIN).random()
            mascot?.play(dance, if (combo == 3) "WOO!" else if (combo == 5) "x2 POINTS!" else "x3 POINTS!")
        }
    }

    // ---------- physics loop ----------

    /** Circle-vs-rectangle bounce for the ceiling pieces and the obstacles. */
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
        val mn = mx * nx + my * ny
        if (mn < 0f) {
            mx = (mx - 2 * mn * nx) * 0.6f
            my = (my - 2 * mn * ny) * 0.6f
        }
        val vn = vx * nx + vy * ny
        if (vn < 0f) {
            hit(-vn, cx - nx * r, cy - ny * r, nx, ny, kind)
            vx -= (1 + bounce) * vn * nx
            vy -= (1 + bounce) * vn * ny
            lastNx = nx
            lastNy = ny
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
            if (!wagerLocked && now >= betsCloseMs) lockWager()

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
            val ct = ceilThick()
            val topBar = ceilY - ct
            for (step in 0 until 3) {
                floorY -= rise * hdt
                if (floorDrop > 0f) {
                    val dd = min(floorDrop, 10f * d * hdt)
                    floorDrop -= dd
                    floorY += dd
                }
                if (floorY > floorStartY) floorY = floorStartY

                // the gap shrinks to half its size over 45 seconds; after level 5 it drifts
                val elapsed = now - levelStartMs
                val prog = (elapsed / 45000f).coerceIn(0f, 1f)
                val gw = max(2 * r + 8f * d, gapW0 * (1f - 0.5f * prog))
                if (level > 5) {
                    val gm = 20f * d + gw / 2f
                    gapC = (gapBase + sin(elapsed / 1400.0).toFloat() * 50f * d).coerceIn(gm, sw - gm)
                }
                gapL = gapC - gw / 2f
                gapR = gapC + gw / 2f

                // water: currents push him on top of his own swimming
                val whirl = sampleWater(cx, cy)
                var grip = 1f
                if (thrust == -1 && (wx != 0f || wy != 0f)) grip = 0.15f   // braking = dig in and hold
                if (whirl != null) {
                    // the whirlpool drags his heading around with it
                    if (!steering || twoActive) {
                        val ddx = cx - whirl.cx
                        val ddy = cy - whirl.cy
                        steerToward(-ddy * whirl.spin, ddx * whirl.spin, hdt * 0.9f)
                    }
                    whirlMs += (hdt * 16.67f).toLong()
                    inWhirl = true
                } else if (inWhirl) {
                    // flung out of a whirlpool after circling: SLINGSHOT
                    inWhirl = false
                    if (whirlMs > 600L) {
                        mx += lastWx * 1.8f + vx * 0.5f
                        my += lastWy * 1.8f + vy * 0.5f
                        sfx?.play("launch", 1f, 300L)
                        sv?.kick()
                        hud?.let { it.popup = "SLINGSHOT!"; it.popupAt = SystemClock.uptimeMillis(); it.invalidate() }
                    }
                    whirlMs = 0L
                } else if ((lastWx != 0f || lastWy != 0f) && wx == 0f && wy == 0f) {
                    // shooting out the end of a current: keep some of that speed
                    mx += lastWx * 0.7f
                    my += lastWy * 0.7f
                }
                lastWx = wx
                lastWy = wy
                cx += (vx + wx * grip + mx) * hdt
                cy += (vy + wy * grip + my) * hdt
                val fade = Math.pow(0.985, (hdt * 3f).toDouble()).toFloat()
                mx *= fade
                my *= fade

                // side walls
                if (cx < r) {
                    hit(abs(vx), 0f, cy, 1f, 0f, kWall)
                    cx = r
                    vx = abs(vx)
                    mx = abs(mx) * 0.6f
                }
                if (cx > sw - r) {
                    hit(abs(vx), sw.toFloat(), cy, -1f, 0f, kWall)
                    cx = sw - r
                    vx = -abs(vx)
                    mx = -abs(mx) * 0.6f
                }

                // the floor is moving up, so bounce relative to it
                if (cy > floorY - r) {
                    cy = floorY - r
                    combo = 0
                    if (my > 0f) my = -my * 0.5f
                    val rel = vy + rise
                    if (rel > 0f) {
                        hit(rel, cx, floorY, 0f, -1f, kFloor)
                        vy = -rise - rel
                    }
                }

                // ceiling pieces on each side of the gap, then the obstacles
                collide(-50f * d, topBar, gapL, ceilY, kCeil)
                collide(gapR, topBar, sw + 50f * d, ceilY, kCeil)
                for (b in obstacles) {
                    val l = b.lp.x.toFloat()
                    val t = b.lp.y.toFloat()
                    val bounced = collide(l, t, l + b.lp.width, t + b.lp.height, if (b.solid) kSolid else if (b.coin) kCoinBrick else kObstacle)
                    if (bounced && b.solid) {
                        b.near = true
                        b.nearHit = true
                    }
                    if (bounced && !b.solid) {
                        if (b.coin && !b.coinSpawned) {
                            b.coinSpawned = true
                            spawnCoin()
                        }
                        toCrush.add(b)
                    }
                }
                if (toCrush.isNotEmpty()) {
                    for (b in toCrush) crushBrick(b)
                    toCrush.clear()
                }

                if (steering) {
                    if (twoActive) {
                        // sideways offset of finger 2 steers, vertical offset climbs or brakes
                        val hdx = p2x - p1x
                        if (abs(hdx) > 12f * d) steerToward(sign(hdx), 0f, hdt)
                        if (thrust == 1) {
                            vy -= climbForce * hdt
                        } else if (thrust == -1) {
                            val nvy = max(0f, abs(vy) - brakeForce * hdt)
                            vy = sign(vy) * nvy
                        }
                    } else {
                        // one finger: turn toward it
                        val sdx = steerX - cx
                        val sdy = steerY - cy
                        if (hypot(sdx, sdy) > 20f * d) steerToward(sdx, sdy, hdt)
                    }
                }
                settleSpeed()

                val inGap = cx > gapL + 4 * d && cx < gapR - 4 * d
                if (cy < topBar && inGap) { levelClear(); break }
                if (floorY - 2 * r <= ceilY && !inGap) { squished(); break }
            }

            if (!over) {
                collectCoins()
                collectFood()
                checkCloseCalls()
                val sinceCombo = now - lastComboMs
                if (combo > 0 && sinceCombo > comboWindow) combo = 0
                for (b in obstacles.toList()) {
                    if (!b.removed && floorY - 2 * r <= b.lp.y + b.lp.height) crushObstacle(b)
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
                    it.combo = combo
                    it.comboMult = comboMult()
                    it.comboLeft = if (combo > 0) (1f - sinceCombo / comboWindow.toFloat()).coerceIn(0f, 1f) else 0f
                    it.energy = energy
                    it.spinReady = energy >= 100f
                    it.wagerLocked = wagerLocked
                    it.wager = if (wagerLocked) wager else pendingWager()
                    it.wagerPct = wagerPct
                    it.betSecs = if (wagerLocked) 0 else ((betsCloseMs - now + 999) / 1000).toInt()
                    it.steerOn = steering
                    it.steerTwo = twoActive
                    it.steerMode = if (twoActive) thrust else 0
                    it.sx0 = if (twoActive) p1x else cx
                    it.sy0 = if (twoActive) p1y else cy
                    it.sx1 = if (twoActive) p2x else steerX
                    it.sy1 = if (twoActive) p2y else steerY
                    it.invalidate()
                }
                wagerBtn?.let {
                    if (it.locked != wagerLocked) {
                        it.locked = wagerLocked
                        it.invalidate()
                    }
                }
                moveMascot()
            }
            applyPos()
            s.invalidate()
        }
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
            clearObstacles()
            sv?.let { wm.removeView(it) }
            tapLayer?.let { wm.removeView(it) }
            closeBtn?.let { wm.removeView(it) }
            wagerBtn?.let { wm.removeView(it) }
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
        wagerBtn = null
        spinBtn = null
        parts = null
        mascot = null
        hud = null
        super.onDestroy()
    }
}
