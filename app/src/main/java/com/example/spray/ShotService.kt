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

private class Barrier(val view: BarrierView, val lp: WindowManager.LayoutParams, var removed: Boolean = false)

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

    // room
    private var floorStartY = 0f
    private var floorY = 0f
    private var ceilY = 0f
    private var gapL = 0f
    private var gapR = 0f

    // game state
    private var level = 1
    private var best = 0
    private var score = 0
    private var nudgesLeft = 3
    private var launched = false
    private var over = false
    private var combo = 0

    private val voices = arrayOf("wee", "wee2", "letsgo")
    private val pent = intArrayOf(0, 2, 4, 7, 9, 12)   // happy notes the ding climbs through

    private val kFloor = 0
    private val kWall = 1
    private val kObstacle = 2
    private val kCeil = 3
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
        floorStartY = sh - 70 * d
        floorY = floorStartY
        ceilY = 64 * d
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

        // full-screen layer that catches nudge taps while the turtle is flying
        val tv = View(this)
        tlp = params(sw, sh, touchable = false)
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) nudge(e.rawX, e.rawY)
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
        slp = params((112 * d).toInt(), (112 * d).toInt())
        var dx0 = 0f
        var dy0 = 0f
        v.setOnTouchListener { _, e ->
            if (over) return@setOnTouchListener true
            if (launched) {
                if (e.action == MotionEvent.ACTION_DOWN) nudge(e.rawX, e.rawY)
                return@setOnTouchListener true
            }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dragX = dx0 - e.rawX
                    val dragY = dy0 - e.rawY
                    if (abs(dragX) > abs(dragY) && abs(dragY) < 40 * d) {
                        moveBeto(e.rawX)
                        dx0 = e.rawX
                        dy0 = e.rawY
                    } else {
                        aim(dragX, dragY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dragX = dx0 - e.rawX
                    val dragY = dy0 - e.rawY
                    if (abs(dragX) > abs(dragY) && abs(dragY) < 40 * d) {
                        moveBeto(e.rawX)
                    } else {
                        fire(dragX, dragY)
                    }
                }
                MotionEvent.ACTION_CANCEL -> clearAim()
            }
            true
        }
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
        launched = false
        over = false
        nudgesLeft = 3
        combo = 0
        cx = sw / 2f
        cy = floorY - r
        vx = 0f
        vy = 0f
        speed = 0f

        // the gap gets smaller and sits somewhere new every level
        val gapW = max(2 * r + 14f * d, 90f * d - (level - 1) * 5f * d)
        val margin = 20f * d
        val center = margin + gapW / 2f + Random.nextFloat() * (sw - 2 * margin - gapW)
        gapL = center - gapW / 2f
        gapR = center + gapW / 2f

        spawnObstacles()
        setTapActive(false)
        sv?.let { it.heading = -90f; it.radiusPx = r; it.invalidate() }
        pushHud()
        hud?.let { it.banner = "DRAG BACK AND LET GO. FARTHER = FASTER"; it.message = "LEVEL $level"; it.invalidate() }
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
            it.nudgesLeft = nudgesLeft
            it.combo = combo
            it.dots = FloatArray(0)
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

    private fun riseSpeed(): Float = min(0.6f, 0.14f + 0.025f * (level - 1)) * d

    private fun spawnObstacles() {
        val count = min(level / 2, 5)
        val topY = ceilY + 130 * d
        val botY = floorStartY - 160 * d
        val placed = ArrayList<FloatArray>()
        for (i in 0 until count) {
            val w = ((90 + Random.nextInt(0, 61)) * d).toInt()
            var x = 0
            var y = 0
            for (attempt in 0 until 30) {
                x = (Random.nextFloat() * (sw - w - 24 * d) + 12 * d).toInt()
                y = (topY + Random.nextFloat() * (botY - topY)).toInt()
                var clear = true
                for (o in placed) if (abs(o[0] - x) < 160 * d && abs(o[1] - y) < 90 * d) clear = false
                if (clear) break
            }
            placed.add(floatArrayOf(x.toFloat(), y.toFloat()))
            val bv = BarrierView(this)
            val lp = params(w, (40 * d).toInt(), touchable = false)
            lp.x = x
            lp.y = y
            wm.addView(bv, lp)
            obstacles.add(Barrier(bv, lp))
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

    private fun setTapActive(on: Boolean) {
        val t = tapLayer ?: return
        tlp.flags = if (on) {
            tlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            tlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm.updateViewLayout(t, tlp)
    }

    private fun levelClear() {
        over = true
        vx = 0f
        vy = 0f
        setTapActive(false)
        val pts = worth()
        score += pts
        if (score > best) {
            best = score
            getSharedPreferences("betito", MODE_PRIVATE).edit().putInt("best_score", best).apply()
        }
        snd("jackpot")
        mascot?.play(MascotView.Move.BACKFLIP, "ESCAPED!")
        showFx()
        hud?.let { it.banner = null; it.message = "LEVEL $level CLEARED! +$pts"; it.score = score; it.best = best; it.invalidate() }
        level++
        handler.postDelayed({ startLevel() }, 2200)
    }

    private fun squished() {
        over = true
        vx = 0f
        vy = 0f
        setTapActive(false)
        snd("lose")
        mascot?.play(MascotView.Move.FACEPALM, "SQUISHED!")
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

    // ---------- aiming, launching and nudging ----------

    private fun maxSpeed(): Float = 15f * d

    /** Drag farther = faster. Once launched he keeps that speed for the whole level. */
    private fun launchV(px: Float, py: Float): FloatArray? {
        val len = hypot(px, py)
        if (len < 20 * d) return null
        val sp = max(3f * d, min(len, 150f * d) / (150f * d) * maxSpeed())
        return floatArrayOf(px / len * sp, py / len * sp)
    }

    private fun clearAim() {
        hud?.let { it.dots = FloatArray(0); it.invalidate() }
        sv?.let { it.heading = -90f; it.invalidate() }
    }

    private fun aim(px: Float, py: Float) {
        val h = hud ?: return
        val s = sv
        val v = launchV(px, py)
        if (v == null) {
            h.dots = FloatArray(0)
            s?.heading = -90f
        } else {
            val sp = hypot(v[0], v[1])
            val ux = v[0] / sp
            val uy = v[1] / sp
            val n = 4 + (sp / maxSpeed() * 10f).toInt()
            val pts = ArrayList<Float>()
            for (i in 1..n) {
                pts.add(cx + ux * i * 18f * d)
                pts.add(cy + uy * i * 18f * d)
            }
            h.dots = pts.toFloatArray()
            s?.heading = Math.toDegrees(atan2(v[1].toDouble(), v[0].toDouble())).toFloat()
        }
        h.invalidate()
        s?.invalidate()
    }

    /** Keep the speed constant and make sure he always travels up and down the room. */
    private fun normalizeVel() {
        var sp = hypot(vx, vy)
        if (sp < 0.001f) {
            vx = 0f
            vy = -speed
            sp = speed
        }
        vx = vx / sp * speed
        vy = vy / sp * speed
        val minVy = 0.22f * speed
        if (abs(vy) < minVy) {
            vy = if (vy < 0f) -minVy else minVy
            val rest = sqrt(max(0f, speed * speed - vy * vy))
            vx = if (vx < 0f) -rest else rest
        }
    }

    private fun fire(px: Float, py: Float) {
        val v = launchV(px, py)
        hud?.let { it.dots = FloatArray(0); it.invalidate() }
        if (v == null) {
            clearAim()
            return
        }
        vx = v[0]
        vy = v[1]
        speed = hypot(vx, vy)
        normalizeVel()
        launched = true
        combo = 0
        setTapActive(true)
        snd("launch", 0.6f)
        val voice = voices.random()
        snd(voice, 1f)
        mascot?.play(MascotView.randomFunny(), if (voice == "letsgo") "LET'S GOOO!" else "WEEEE!")
        hud?.let { it.banner = "TAP TO NUDGE HIM INTO THE GAP"; it.invalidate() }
    }

    /** Tap anywhere: he gets pushed away from your tap, which turns him. His speed stays the same. */
    private fun nudge(tx: Float, ty: Float) {
        if (!launched || over || nudgesLeft <= 0) return
        nudgesLeft--
        var dx = cx - tx
        var dy = cy - ty
        val len = hypot(dx, dy)
        if (len < 6 * d) {
            dx = 0f
            dy = -1f
        } else {
            dx /= len
            dy /= len
        }
        vx += dx * speed * 1.3f
        vy += dy * speed * 1.3f
        normalizeVel()
        snd("pop", 0.9f)
        parts?.burst(cx - dx * r, cy - dy * r, -dx, -dy, 10f * d, false, 3)
        hud?.let { it.nudgesLeft = nudgesLeft; it.invalidate() }
    }

    private fun moveBeto(x: Float) {
        val minX = r + 20 * d
        val maxX = sw - r - 20 * d
        cx = x.coerceIn(minX, maxX)
        cy = floorY - r
        applyPos()
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
     * Every bump makes noise. The floor is a soft thud, walls and the ceiling are a bonk,
     * and the obstacles are loudest: bonk + a climbing casino ding + a coin.
     */
    private fun hit(speed: Float, x: Float, y: Float, nx: Float, ny: Float, kind: Int) {
        if (speed <= 2.5f * d) return
        lastHitMs = SystemClock.uptimeMillis()
        sv?.bump()
        val sp = (speed / (14f * d)).coerceIn(0.3f, 1f)
        if (kind == kFloor) {
            snd("thud", 0.25f + 0.25f * sp, 120L)
            parts?.burst(x, y, nx, ny, speed, false, 0)
            return
        }
        combo++
        val semis = pent[min(combo - 1, pent.size - 1)]
        val rate = Math.pow(2.0, semis / 12.0).toFloat()
        when (kind) {
            kObstacle -> {
                snd("bonk", 0.7f + 0.3f * sp, 120L)
                sfx?.play("ding", 0.8f + 0.2f * sp, 60L, rate)
                sfx?.play("coin", 0.8f, 100L)
            }
            kCeil -> {
                snd("bonk", 0.5f + 0.3f * sp, 120L)
                sfx?.play("ding", 0.4f + 0.3f * sp, 60L, rate)
            }
            else -> {
                snd("bonk", 0.4f + 0.35f * sp, 120L)
                if (combo >= 2) sfx?.play("ding", 0.5f * sp + 0.2f, 60L, rate)
            }
        }
        hud?.let { it.combo = combo; it.invalidate() }
        parts?.burst(x, y, nx, ny, speed, kind == kObstacle, combo)
        if (combo == 3 || combo == 5 || combo == 8) {
            snd("woo", 0.9f)
            val dance = arrayOf(MascotView.Move.TWERK, MascotView.Move.FLOSS, MascotView.Move.SPIN).random()
            mascot?.play(dance, if (combo == 3) "WOO!" else if (combo == 5) "COMBO!" else "UNREAL!")
        }
    }

    // ---------- physics loop ----------

    /** Circle-vs-rectangle bounce for the ceiling pieces and the obstacles. */
    private fun collide(l: Float, t: Float, rr: Float, bb: Float, kind: Int) {
        val qx = cx.coerceIn(l, rr)
        val qy = cy.coerceIn(t, bb)
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
            hit(-vn, qx, qy, nx, ny, kind)
            vx -= (1 + bounce) * vn * nx
            vy -= (1 + bounce) * vn * ny
        }
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
                    collide(l, t, l + b.lp.width, t + b.lp.height, kObstacle)
                }
                normalizeVel()

                val inGap = cx > gapL + 4 * d && cx < gapR - 4 * d
                if (cy < topBar && inGap) { levelClear(); break }
                if (floorY - 2 * r <= ceilY && !inGap) { squished(); break }
            }

            if (!over) {
                for (b in obstacles.toList()) {
                    if (!b.removed && floorY - 2 * r <= b.lp.y + b.lp.height) crushObstacle(b)
                }
                if (combo > 0 && SystemClock.uptimeMillis() - lastHitMs > 1500L) combo = 0
                s.heading = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
                hud?.let { it.floorY = floorY; it.worth = worth(); it.combo = combo; it.invalidate() }
                moveMascot()
            }
            applyPos()
            s.invalidate()
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
