package com.example.spray

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** REEF CHESS: the turtles-versus-sharks strategy game. */
class ChessActivity : Activity() {
    private var sfx: Sfx? = null
    private var backCb: Any? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("betito", MODE_PRIVATE)
        val s = Sfx(this)
        s.enabled = prefs.getBoolean("sound", true)
        sfx = s
        Yarn.load(this)
        val v = ChessView(this, prefs, s)
        v.onQuit = { finish() }
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#031428"))
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        GameActivity.hideSystemBars(window)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { finish() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        }
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Used on Android 12 and older")
    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()
        GameActivity.hideSystemBars(window)
        sfx?.resume()
    }

    override fun onPause() {
        sfx?.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) GameActivity.hideSystemBars(window)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCb as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        sfx?.release()
        sfx = null
        super.onDestroy()
    }
}
