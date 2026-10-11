package com.example.spray

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The game screen: a normal full-screen activity (landscape) that hosts the game view.
 * Pauses itself when you leave the app; the back gesture opens the pause menu.
 */
class GameActivity : Activity(), Choreographer.FrameCallback {
    private var game: Game? = null
    private var view: GameView? = null
    private var sfx: Sfx? = null
    private var lastNs = 0L
    private var running = false
    private var backCb: Any? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("betito", MODE_PRIVATE)
        val s = Sfx(this)
        s.enabled = prefs.getBoolean("sound", true)
        sfx = s
        val d = resources.displayMetrics.density
        val g = Game(d, s, prefs)
        game = g

        val v = GameView(this, g)
        v.onResize = { w, h -> g.resize(w.toFloat(), h.toFloat(), 6 * d) }
        v.onQuit = { finish() }
        view = v

        // keep the game clear of camera notches: pad the view by the cutout's safe area
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#031428"))
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (Build.VERSION.SDK_INT >= 28) {
                val cut = insets.displayCutout
                lp.setMargins(cut?.safeInsetLeft ?: 0, cut?.safeInsetTop ?: 0, cut?.safeInsetRight ?: 0, cut?.safeInsetBottom ?: 0)
            }
            v.layoutParams = lp
            insets
        }
        setContentView(root)
        hideSystemBars(window)

        if (intent.getBooleanExtra("continue", false)) {
            g.requestNewGame(prefs.getInt("save_level", 1).coerceAtLeast(1), prefs.getInt("save_score", 0))
        } else {
            prefs.edit().putInt("save_level", 1).putInt("save_score", 0).apply()
            g.requestNewGame(1, 0)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { view?.onBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        }
    }

    // Android 13+ uses the OnBackInvokedCallback registered in onCreate; this is only for older phones.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Used on Android 12 and older")
    override fun onBackPressed() {
        view?.onBack()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(window)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars(window)
        sfx?.resume()
        if (!running) {
            running = true
            lastNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onPause() {
        // leaving the app pauses the game, so you come back to the pause menu
        view?.pause()
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        sfx?.pause()
        super.onPause()
    }

    override fun doFrame(ns: Long) {
        if (!running) return
        val ms = if (lastNs == 0L) 16L else ((ns - lastNs) / 1_000_000L)
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceIn(0.1f, 2f)
        lastNs = ns
        game?.tick(ms, dt)
        view?.invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCb as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        sfx?.release()
        sfx = null
        super.onDestroy()
    }

    companion object {
        /** Full screen: hide the status and navigation bars (swipe from the edge to see them). */
        fun hideSystemBars(window: android.view.Window) {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }
}
