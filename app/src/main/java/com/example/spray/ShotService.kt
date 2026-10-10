package com.example.spray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager

/**
 * Hosts Lil Betito: one full-screen game view (locked to landscape) plus a small close button.
 * The game itself lives in Game (rules) and GameView (drawing + touch).
 */
class ShotService : Service(), Choreographer.FrameCallback {
    private lateinit var wm: WindowManager
    private var d = 1f
    private var game: Game? = null
    private var view: GameView? = null
    private var closeBtn: CloseView? = null
    private var sfx: Sfx? = null
    private var lastNs = 0L
    private var topInset = 0f

    private val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startAsForeground()
        val a = i?.action
        if (a == "SHOT_STOP") { stopSelf(); return START_NOT_STICKY }
        val fresh = view == null
        if (fresh) setup()
        sfx?.enabled = getSharedPreferences("betito", MODE_PRIVATE).getBoolean("sound", true)
        when (a) {
            "SHOT_SOLO", "SHOT_RESET" -> { if (!fresh) game?.requestNewGame(1) }
            "SHOT_TEST7" -> game?.requestNewGame(7)
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
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        topInset = if (sbId > 0) resources.getDimensionPixelSize(sbId).toFloat() else 24 * d

        val g = Game(d, sfx, getSharedPreferences("betito", MODE_PRIVATE))
        game = g

        // the game view fills the screen and asks for landscape
        val v = GameView(this, g)
        v.onResize = { w, h -> g.resize(w.toFloat(), h.toFloat(), topInset) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        wm.addView(v, lp)
        view = v

        // close button, top right, above the game so it always works
        val xv = CloseView(this)
        val xlp = WindowManager.LayoutParams(
            (36 * d).toInt(), (36 * d).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        xlp.gravity = Gravity.TOP or Gravity.END
        xlp.x = (12 * d).toInt()
        xlp.y = (topInset + 4 * d).toInt()
        xv.setOnClickListener { stopSelf() }
        wm.addView(xv, xlp)
        closeBtn = xv

        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(ns: Long) {
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceIn(0.1f, 2f)
        lastNs = ns
        game?.update(SystemClock.uptimeMillis(), dt)
        view?.invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this)
        if (::wm.isInitialized) {
            view?.let { wm.removeView(it) }
            closeBtn?.let { wm.removeView(it) }
        }
        sfx?.release()
        sfx = null
        view = null
        closeBtn = null
        game = null
        super.onDestroy()
    }
}
