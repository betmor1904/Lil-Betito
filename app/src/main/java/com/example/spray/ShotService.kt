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
import kotlin.math.sqrt
import kotlin.random.Random

private class Barrier(
    val view: BarrierView,
    val lp: WindowManager.LayoutParams,
    val coin: Boolean = false,
    var removed: Boolean = false,
    var coinSpawned: Boolean = false
)

private class Coin(val view: CoinView, val lp: WindowManager.LayoutParams, val x: Float, val y: Float)

/**
 * Lil Betito: launch the turtle, let him ricochet around the room, and use 3 nudges per
 * level to steer him into the gap in the ceiling before the floor rises and squishes him.
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
    private var parts: PartsView? = null
    private var mascot: MascotView? = null
    private var fx: FxView? = null
    private var sfx: Sfx? = null
    private lateinit var slp: WindowManager.LayoutParams
    private lateinit var tlp: WindowManager.LayoutParams
    private lateinit var mlp: WindowManager.LayoutParams
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
    private var speed = 0f
    private var lastHitMs = 0L
    private var levelStartMs = 0L
    private var dying = false
    private var stuckUntil = 0L
    private var winStart = 0L
    private var winMin = 0f
    private var winMax = 0f
    private var dyingUntil = 0L
    private var steering = false
    private var steerX = 0f
    private var steerY = 0f
    private var twoFinger = false
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

    // game state
    private var level = 1
    private var best = 0
    private var score = 0
    private var launched = false
    private var over = false
    private var combo = 0

    private val voices = arrayOf("wee", "wee2", "letsgo")
    private val pent = intArrayOf(0, 2, 4, 7, 9, 12)   // happy notes the ding climbs through

    private val kFloor = 0
    private val kWall = 1
    private val kObstacle = 2
    private val kCeil = 3
    private val kCoinBrick = 4
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
        // the room is half as tall as the screen, so levels stay short and tense
        val bottom = sh - 70 * d
        floorStartY = ceilY + 2 * r + (bottom - ceilY - 2 * r) * 0.5f
        floorY = floorStartY
        best = getSharedPreferences("betito", MODE_PRIVATE).getInt("best_score", 0)

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

        // full-screen layer for steering. One finger: he is pulled toward it. Two fingers:
        // finger 1 is the anchor and finger 2 sets the direction (anchor -> finger 2).
        val tv = View(this)
        tlp = params(sw, sh)
        tv.setOnTouchListener { _, e ->
            // raw coordinates for every pointer (getRawX(index) needs a newer Android)
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
        stuckUntil = 0L
        winStart = levelStartMs
        winMin = floorStartY - r
        winMax = floorStartY - r
        combo = 0
        cx = sw / 2f
        cy = floorY - r
        // he is moving the moment the room appears: straight up, at one constant speed
        speed = 8f * d
        vx = 0f
        vy = -speed
        normalizeVel()

        // the gap gets smaller and sits somewhere new every level
        val gapW = max(2 * r + 14f * d, 90f * d - (level - 1) * 5f * d)
        val margin = 20f * d
        val center = margin + gapW / 2f + Random.nextFloat() * (sw - 2 * margin - gapW)
        gapC = center
        gapW0 = gapW
        gapL = center - gapW / 2f
        gapR = center + gapW / 2f

        spawnObstacles()
        sv?.let { it.heading = -90f; it.radiusPx = r; it.invalidate() }
        pushHud()
        hud?.let { it.banner = "HOLD YOUR FINGER DOWN TO PULL HIM"; it.message = "LEVEL $level"; it.invalidate() }
        handler.postDelayed({ hud?.let { it.message = null; it.invalidate() } }, 1400)
        handler.postDelayed({ hud?.let { it.banner = null; it.invalidate() } }, 5000)
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
            it.invalidate()
        }
    }

    /**
     * Points for escaping right now. The higher the floor has risen, the riskier the escape
     * and the more it pays: 100 / 300 / 600, and 1000 right at the last moment.
     * Later rooms multiply it: 1-5 = 1x, 6-10 = 2x, 11-20 = 3x, 21+ = 5x.
     */
    private fun worth(): Int {
        val full = floorStartY - (ceilY + 2 * r)
        val risen = (1f - (floorY - (ceilY + 2 * r)) / full).coerceIn(0f, 1f)
        val tier = when {
            risen >= 0.9f -> 1000
            risen >= 2f / 3f -> 600
            risen >= 1f / 3f -> 300
            else -> 100
        }
        val mult = when {
            level <= 5 -> 1
            level <= 10 -> 2
            level <= 20 -> 3
            else -> 5
        }
        return tier * mult
    }

    /** How hard your finger pulls him. He keeps his speed, so this only changes his direction. */
    private fun steerForce(): Float = 1.5f * d

    private fun riseSpeed(): Float {
        val base = min(1.4f, 0.5f + 0.07f * (level - 1)) * d
        return if (SystemClock.uptimeMillis() < stuckUntil) base * 2.5f else base
    }

    /** No real up-and-down movement for 3 seconds: warn, rumble, and speed the floor up for a bit. */
    private fun triggerStuck(now: Long) {
        stuckUntil = now + 2500L
        combo = 0
        snd("rumble", 1f)
        mascot?.play(MascotView.Move.FACEPALM, "UH OH...")
    }

    private fun spawnObstacles() {
        val count = min(level + 1, 8)
        val topY = ceilY + 110 * d
        val botY = floorStartY - 90 * d
        val placed = ArrayList<FloatArray>()
        for (i in 0 until count) {
            val w = ((50 + Random.nextInt(0, 41)) * d).toInt()
            var x = 0
            var y = 0
            for (attempt in 0 until 30) {
                x = (Random.nextFloat() * (sw - w - 24 * d) + 12 * d).toInt()
                y = (topY + Random.nextFloat() * (botY - topY)).toInt()
                var clear = true
                for (o in placed) if (abs(o[0] - x) < 110 * d && abs(o[1] - y) < 50 * d) clear = false
                if (clear) break
            }
            placed.add(floatArrayOf(x.toFloat(), y.toFloat()))
            val isCoin = i % 3 == 1
            val bv = BarrierView(this)
            bv.coin = isCoin
            val lp = params(w, (24 * d).toInt(), touchable = false)
            lp.x = x
            lp.y = y
            wm.addView(bv, lp)
            obstacles.add(Barrier(bv, lp, isCoin))
        }
    }

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
                score += 25
                snd("coin", 1f, 40L)
                hud?.let { it.score = score; it.invalidate() }
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
    }

    private fun levelClear() {
        over = true
        stuckUntil = 0L
        vx = 0f
        vy = 0f
        steering = false
        val pts = worth()
        val secs = (SystemClock.uptimeMillis() - levelStartMs) / 1000f
        val bonus = when {
            secs < 5f -> 500
            secs < 8f -> 250
            secs < 12f -> 100
            else -> 0
        }
        score += pts + bonus
        if (score > best) {
            best = score
            getSharedPreferences("betito", MODE_PRIVATE).edit().putInt("best_score", best).apply()
        }
        snd("fanfare")
        mascot?.play(MascotView.Move.BACKFLIP, "ESCAPED!")
        showFx()
        hud?.let { it.stuck = false }
        hud?.let { it.banner = if (bonus > 0) "SPEED BONUS +$bonus" else null; it.message = "LEVEL $level CLEARED! +${pts + bonus}"; it.score = score; it.best = best; it.invalidate() }
        level++
        handler.postDelayed({ startLevel() }, 2200)
    }

    private fun squished() {
        over = true
        stuckUntil = 0L
        vx = 0f
        vy = 0f
        steering = false
        if (score > best) {
            best = score
            getSharedPreferences("betito", MODE_PRIVATE).edit().putInt("best_score", best).apply()
        }
        snd("squish")
        sv?.bump()
        dying = true
        dyingUntil = SystemClock.uptimeMillis() + 900L
        mascot?.play(MascotView.Move.FACEPALM, "SQUISHED!")
        hud?.let { it.stuck = false }
        hud?.let { it.banner = "FINAL SCORE $score  (LEVEL $level)"; it.message = "SQUISHED!"; it.invalidate() }
        handler.postDelayed({ newGame() }, 3000)
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

    // ---------- steering ----------

    /** Keep the speed constant. Steering decides the direction, including straight sideways. */
    private fun normalizeVel() {
        var sp = hypot(vx, vy)
        if (sp < 0.001f) {
            vx = 0f
            vy = -speed
            sp = speed
        }
        vx = vx / sp * speed
        vy = vy / sp * speed
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
     * Sounds go by rarity. Plain walls, the ceiling and the floor are a soft tick. Obstacles
     * are a bonk plus a chip clink. Then the combo adds rarer sounds on top: coin drop at 3-4,
     * slot ding at 5-7 and a jackpot siren at 8+.
     */
    private fun hit(speed: Float, x: Float, y: Float, nx: Float, ny: Float, kind: Int) {
        if (speed <= 2.5f * d) return
        lastHitMs = SystemClock.uptimeMillis()
        sv?.bump()
        val sp = (speed / (14f * d)).coerceIn(0.3f, 1f)
        if (kind != kObstacle && kind != kCoinBrick) snd("tick", 0.15f + 0.2f * sp, 40L)
        if (kind == kFloor) {
            parts?.burst(x, y, nx, ny, speed, false, 0)
            return
        }
        combo++
        if (kind == kObstacle) {
            snd("bonk", 0.7f + 0.3f * sp, 120L)
            sfx?.play("clink", 0.6f + 0.3f * sp, 60L)
        }
        if (kind == kCoinBrick) {
            snd("bonk", 0.5f + 0.2f * sp, 120L)
            sfx?.play("coin", 1f, 60L, 1.3f)
        }
        when {
            combo >= 8 -> sfx?.play("siren", 1f, 500L)
            combo >= 5 -> sfx?.play("ding", 0.9f, 80L, 1f + 0.1f * (combo - 5))
            combo >= 3 -> sfx?.play("coin", 0.9f, 80L)
        }
        score += if (kind == kCoinBrick) 50 else 10
        hud?.let { it.combo = combo; it.score = score; it.invalidate() }
        parts?.burst(x, y, nx, ny, speed, kind == kObstacle || kind == kCoinBrick, combo)
        if (combo == 3 || combo == 5 || combo == 8) {
            val dance = arrayOf(MascotView.Move.TWERK, MascotView.Move.FLOSS, MascotView.Move.SPIN).random()
            mascot?.play(dance, if (combo == 3) "WOO!" else if (combo == 5) "COMBO!" else "UNREAL!")
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
            val hdt = dt / 3f
            val rise = riseSpeed()
            val ct = ceilThick()
            val topBar = ceilY - ct
            for (step in 0 until 3) {
                floorY -= rise * hdt

                // the exit closes in toward its center by up to 40% as the floor climbs
                val prog = ((floorStartY - floorY) / (floorStartY - (ceilY + 2 * r))).coerceIn(0f, 1f)
                val gw = max(2 * r + 8f * d, gapW0 * (1f - 0.4f * prog))
                gapL = gapC - gw / 2f
                gapR = gapC + gw / 2f
                cx += vx * hdt
                cy += vy * hdt

                // side walls
                if (cx < r) {
                    hit(abs(vx), 0f, cy, 1f, 0f, kWall)
                    cx = r
                    vx = abs(vx)
                }
                if (cx > sw - r) {
                    hit(abs(vx), sw.toFloat(), cy, -1f, 0f, kWall)
                    cx = sw - r
                    vx = -abs(vx)
                }

                // the floor is moving up, so bounce relative to it
                if (cy > floorY - r) {
                    cy = floorY - r
                    combo = 0
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
                    val bounced = collide(l, t, l + b.lp.width, t + b.lp.height, if (b.coin) kCoinBrick else kObstacle)
                    if (bounced && b.coin && !b.coinSpawned) {
                        b.coinSpawned = true
                        spawnCoin()
                    }
                }
                if (steering) {
                    // two fingers: pull along anchor -> finger 2. One finger: pull toward it.
                    val sdx = if (twoFinger) p2x - p1x else steerX - cx
                    val sdy = if (twoFinger) p2y - p1y else steerY - cy
                    val sl = hypot(sdx, sdy)
                    if (sl > (if (twoFinger) 12f else 20f) * d) {
                        vx += sdx / sl * steerForce() * hdt
                        vy += sdy / sl * steerForce() * hdt
                    }
                }
                normalizeVel()

                val inGap = cx > gapL + 4 * d && cx < gapR - 4 * d
                if (cy < topBar && inGap) { levelClear(); break }
                if (floorY - 2 * r <= ceilY && !inGap) { squished(); break }
            }

            if (!over) {
                collectCoins()
                val nowMs = SystemClock.uptimeMillis()
                if (cy < winMin) winMin = cy
                if (cy > winMax) winMax = cy
                if (nowMs - winStart >= 3000L) {
                    if (winMax - winMin < 60 * d && nowMs >= stuckUntil) triggerStuck(nowMs)
                    winStart = nowMs
                    winMin = cy
                    winMax = cy
                }
                for (b in obstacles.toList()) {
                    if (!b.removed && floorY - 2 * r <= b.lp.y + b.lp.height) crushObstacle(b)
                }
                var hd = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
                if (steering) {
                    val tdx = if (twoFinger) p2x - p1x else steerX - cx
                    val tdy = if (twoFinger) p2y - p1y else steerY - cy
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
                    it.stuck = SystemClock.uptimeMillis() < stuckUntil
                    it.steerOn = steering
                    it.steerTwo = twoFinger
                    it.sx0 = if (twoFinger) p1x else cx
                    it.sy0 = if (twoFinger) p1y else cy
                    it.sx1 = if (twoFinger) p2x else steerX
                    it.sy1 = if (twoFinger) p2y else steerY
                    it.invalidate()
                }
                moveMascot()
            }
            applyPos()
            s.invalidate()
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
            parts?.let { wm.removeView(it) }
            mascot?.let { wm.removeView(it) }
            hud?.let { wm.removeView(it) }
        }
        sfx?.release()
        sfx = null
        sv = null
        tapLayer = null
        closeBtn = null
        parts = null
        mascot = null
        hud = null
        super.onDestroy()
    }
}
