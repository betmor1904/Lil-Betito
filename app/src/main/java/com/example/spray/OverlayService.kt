package com.example.spray

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import kotlin.math.abs

private class Rail(val view: RailView, val lp: WindowManager.LayoutParams, var removed: Boolean = false)

class OverlayService : Service(), Choreographer.FrameCallback {
    private lateinit var wm: WindowManager
    private var d = 1f
    private var sw = 0
    private var sh = 0

    private var skater: SkaterView? = null
    private lateinit var slp: WindowManager.LayoutParams
    private val rails = ArrayList<Rail>()

    private var x = 0f
    private var y = 0f
    private var vy = 0f
    private var facing = 1
    private var onGround = false
    private var rail: Rail? = null
    private var dragging = false
    private var lastNs = 0L
    private var feetOff = 0f

    private val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun params(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startAsForeground()
        when (i?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "ADD_RAIL" -> { if (skater == null) setup(); addRail() }
            else -> if (skater == null) setup()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("skate", "Screen Skater", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String, label: String, icon: Int, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, code, Intent(this, OverlayService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }
        val n = Notification.Builder(this, "skate")
            .setContentTitle("Skater is rolling")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .addAction(action("ADD_RAIL", "Add rail", android.R.drawable.ic_input_add, 1))
            .addAction(action("STOP", "Stop", android.R.drawable.ic_delete, 2))
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        d = resources.displayMetrics.density
        sw = resources.displayMetrics.widthPixels
        sh = resources.displayMetrics.heightPixels
        feetOff = 105 * d

        val v = SkaterView(this)
        slp = params((150 * d).toInt(), (110 * d).toInt())
        x = sw / 2f - 75 * d
        y = sh * 0.3f
        slp.x = x.toInt(); slp.y = y.toInt()

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { trick("ollie"); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { trick("kickflip"); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (abs(vX) > abs(vY)) trick("shove") else if (vY < 0) trick("tre")
                return true
            }
            override fun onLongPress(e: MotionEvent) { dragging = true; vy = 0f; rail = null }
        })
        var ox = 0f; var oy = 0f; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox = x; oy = y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    x = ox + e.rawX - tx; y = oy + e.rawY - ty; applyPos()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
        wm.addView(v, slp)
        skater = v
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun trick(name: String) {
        if (dragging || !(onGround || rail != null)) return
        vy = -14f * d
        onGround = false
        rail = null
        skater?.startTrick(name)
    }

    private fun applyPos() {
        val sk = skater ?: return
        slp.x = x.toInt(); slp.y = y.toInt()
        wm.updateViewLayout(sk, slp)
    }

    private fun addRail() {
        val v = RailView(this)
        val lp = params((150 * d).toInt(), (26 * d).toInt())
        lp.x = (sw / 2f - 75 * d).toInt(); lp.y = (sh * 0.55f).toInt()
        val r = Rail(v, lp)
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) {
                r.removed = true
                wm.removeView(v)
                rails.remove(r)
            }
        })
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (!r.removed) when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, lp)
                }
            }
            true
        }
        wm.addView(v, lp)
        rails.add(r)
    }

    override fun doFrame(ns: Long) {
        val sk = skater ?: return
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceAtMost(3f)
        lastNs = ns

        if (!dragging) {
            val prevFeet = y + feetOff
            x += facing * (if (rail != null) 4.5f else 3f) * d * dt
            val cxs = x + sk.width / 2f

            val r = rail
            if (r != null) {
                if (r.removed || cxs < r.lp.x || cxs > r.lp.x + r.lp.width) rail = null
                else y = r.lp.y - feetOff
            }
            if (rail == null) {
                vy += 0.7f * d * dt
                y += vy * dt
                val feet = y + feetOff
                var landed = false
                if (vy >= 0) for (rl in rails) {
                    val top = rl.lp.y.toFloat()
                    if (prevFeet <= top + 6 * d && feet >= top &&
                        cxs >= rl.lp.x && cxs <= rl.lp.x + rl.lp.width) {
                        rail = rl; y = top - feetOff; vy = 0f; onGround = false; landed = true
                        break
                    }
                }
                if (!landed) {
                    val floor = sh - 70 * d
                    if (y + feetOff >= floor) { y = floor - feetOff; vy = 0f; onGround = true }
                    else onGround = false
                }
            }

            val maxX = (sw - sk.width).toFloat()
            if (x < 0f) { x = 0f; facing = 1 }
            if (x > maxX) { x = maxX; facing = -1 }

            val g = rail != null
            if (sk.facing != facing || sk.grinding != g) {
                sk.facing = facing; sk.grinding = g; sk.invalidate()
            }
            applyPos()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this)
        for (r in rails) if (!r.removed) wm.removeView(r.view)
        rails.clear()
        skater?.let { wm.removeView(it) }
        skater = null
        super.onDestroy()
    }
}
