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
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

private class Barrier(val view: BarrierView, val lp: WindowManager.LayoutParams, var removed: Boolean = false)

class ShotService : Service(), Choreographer.FrameCallback {
    private lateinit var wm: WindowManager
    private var d = 1f
    private var sw = 0
    private var sh = 0

    private var sv: ShotView? = null
    private var hud: HudView? = null
    private lateinit var slp: WindowManager.LayoutParams
    private val barriers = ArrayList<Barrier>()
    private val handler = Handler(Looper.getMainLooper())

    private var cx = 0f
    private var cy = 0f
    private var vx = 0f
    private var vy = 0f
    private var r = 0f
    private var floorY = 0f
    private var goalY = 0f
    private val bounce = 0.5f

    private var shotsLeft = 3
    private var flying = false
    private var over = false
    private var restFrames = 0
    private var lastNs = 0L

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

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startAsForeground()
        when (i?.action) {
            "SHOT_STOP" -> { stopSelf(); return START_NOT_STICKY }
            "SHOT_ADD" -> { if (sv == null) setup(); addBarrier() }
            "SHOT_RESET" -> { if (sv == null) setup() else reset() }
            else -> { if (sv == null) setup() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shot", "Shot Game", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String, label: String, icon: Int, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, code, Intent(this, ShotService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }
        val n = Notification.Builder(this, "shot")
            .setContentTitle("Shot game is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(action("SHOT_ADD", "Add barrier", android.R.drawable.ic_input_add, 11))
            .addAction(action("SHOT_RESET", "Reset", android.R.drawable.ic_menu_revert, 12))
            .addAction(action("SHOT_STOP", "Stop", android.R.drawable.ic_delete, 13))
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, n)
    }

    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        d = resources.displayMetrics.density
        sw = resources.displayMetrics.widthPixels
        sh = resources.displayMetrics.heightPixels
        r = 22 * d
        floorY = sh - 70 * d
        goalY = 64 * d

        val h = HudView(this)
        h.goalY = goalY
        wm.addView(h, params(sw, sh, touchable = false))
        hud = h

        val v = ShotView(this)
        slp = params((80 * d).toInt(), (80 * d).toInt())
        sv = v

        var dx0 = 0f
        var dy0 = 0f
        v.setOnTouchListener { _, e ->
            if (!flying && !over) {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY }
                    MotionEvent.ACTION_MOVE -> aim(dx0 - e.rawX, dy0 - e.rawY)
                    MotionEvent.ACTION_UP -> fire(dx0 - e.rawX, dy0 - e.rawY)
                    MotionEvent.ACTION_CANCEL -> clearAim()
                }
            }
            true
        }
        cx = sw / 2f
        cy = floorY - r
        slp.x = (cx - 40 * d).toInt()
        slp.y = (cy - 40 * d).toInt()
        wm.addView(v, slp)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun launchV(px: Float, py: Float): FloatArray? {
        val len = hypot(px, py)
        if (len < 20 * d) return null
        val k = min(len, 150 * d) / len * 0.2f
        return floatArrayOf(px * k, py * k)
    }

    private fun clearAim() {
        val h = hud ?: return
        h.dots = FloatArray(0)
        h.invalidate()
    }

    private fun aim(px: Float, py: Float) {
        val h = hud ?: return
        val v = launchV(px, py)
        if (v == null) {
            h.dots = FloatArray(0)
        } else {
            var x = cx
            var y = cy
            val ax = v[0]
            var ay = v[1]
            val pts = ArrayList<Float>()
            for (i in 1..30) {
                x += ax
                y += ay
                ay += 0.55f * d
                if (i % 2 == 0) { pts.add(x); pts.add(y) }
            }
            h.dots = pts.toFloatArray()
        }
        h.invalidate()
    }

    private fun fire(px: Float, py: Float) {
        val v = launchV(px, py)
        clearAim()
        if (v == null) return
        vx = v[0]
        vy = v[1]
        shotsLeft--
        hud?.let { it.shotsLeft = shotsLeft; it.invalidate() }
        flying = true
        sv?.tucked = true
        restFrames = 0
    }

    private fun applyPos() {
        val s = sv ?: return
        slp.x = (cx - 40 * d).toInt()
        slp.y = (cy - 40 * d).toInt()
        wm.updateViewLayout(s, slp)
    }

    private fun reset() {
        handler.removeCallbacksAndMessages(null)
        cx = sw / 2f
        cy = floorY - r
        vx = 0f
        vy = 0f
        shotsLeft = 3
        flying = false
        over = false
        restFrames = 0
        sv?.angle = 0f
        sv?.tucked = false
        hud?.let { it.shotsLeft = 3; it.message = null; it.dots = FloatArray(0); it.invalidate() }
        applyPos()
        sv?.invalidate()
    }

    private fun win() {
        flying = false
        sv?.tucked = false
        sv?.angle = 0f
        over = true
        vx = 0f
        vy = 0f
        hud?.let { it.message = "YOU MADE IT!"; it.invalidate() }
        handler.postDelayed({ reset() }, 3000)
    }

    private fun endShot() {
        flying = false
        sv?.tucked = false
        sv?.angle = 0f
        sv?.invalidate()
        if (shotsLeft <= 0) {
            over = true
            hud?.let { it.message = "OUT OF SHOTS"; it.invalidate() }
            handler.postDelayed({ reset() }, 2500)
        }
    }

    private fun addBarrier() {
        val v = BarrierView(this)
        val lp = params((150 * d).toInt(), (40 * d).toInt())
        lp.x = (sw / 2f - 75 * d).toInt()
        lp.y = (sh * 0.5f).toInt()
        val b = Barrier(v, lp)
        val widths = floatArrayOf(90f, 150f, 230f)
        var wi = 1
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (b.removed) return true
                wi = (wi + 1) % 3
                lp.width = (widths[wi] * d).toInt()
                wm.updateViewLayout(v, lp)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (b.removed) return
                b.removed = true
                wm.removeView(v)
                barriers.remove(b)
            }
        })
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        v.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (!b.removed) {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        barriers.add(b)
    }

    override fun doFrame(ns: Long) {
        val s = sv ?: return
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceAtMost(3f)
        lastNs = ns

        if (flying) {
            val hdt = dt / 3f
            var grounded = false
            for (step in 0 until 3) {
                vy += 0.55f * d * hdt
                cx += vx * hdt
                cy += vy * hdt

                if (cx < r) { cx = r; vx = abs(vx) * bounce }
                if (cx > sw - r) { cx = sw - r; vx = -abs(vx) * bounce }
                if (cy > floorY - r) {
                    cy = floorY - r
                    if (vy > 0f) vy = -vy * bounce
                    if (abs(vy) < 1.2f * d) vy = 0f
                    vx *= 0.97f
                    grounded = true
                }

                for (b in barriers) {
                    val l = b.lp.x.toFloat()
                    val t = b.lp.y.toFloat()
                    val rr = l + b.lp.width
                    val bb = t + b.lp.height
                    val qx = cx.coerceIn(l, rr)
                    val qy = cy.coerceIn(t, bb)
                    val dx = cx - qx
                    val dy = cy - qy
                    val d2 = dx * dx + dy * dy
                    if (d2 < r * r) {
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
                            vx -= (1 + bounce) * vn * nx
                            vy -= (1 + bounce) * vn * ny
                        }
                        if (ny < -0.5f) {
                            grounded = true
                            vx *= 0.97f
                            if (abs(vy) < 1.2f * d) vy = 0f
                        }
                    }
                }

                if (cy - r <= goalY) { win(); break }
            }

            if (!over) {
                s.angle += vx / d * 1.5f
                if (grounded && hypot(vx, vy) < 0.8f * d) {
                    restFrames++
                } else {
                    restFrames = 0
                }
                if (restFrames > 20) endShot()
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
            for (b in barriers) if (!b.removed) wm.removeView(b.view)
            barriers.clear()
            sv?.let { wm.removeView(it) }
            hud?.let { wm.removeView(it) }
        }
        sv = null
        hud = null
        super.onDestroy()
    }
}
