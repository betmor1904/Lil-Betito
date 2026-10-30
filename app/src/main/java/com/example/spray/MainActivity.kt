package com.example.spray

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** The start menu. Play opens GameActivity. */
class MainActivity : Activity() {
    private val pickCode = 7
    private var menu: MenuView? = null
    private var sfx: Sfx? = null
    private var backCb: Any? = null

    companion object {
        const val PRIVACY_URL = "https://github.com/betmor1904/Lil-Betito/blob/main/PRIVACY.md"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("betito", MODE_PRIVATE)
        val s = Sfx(this)
        s.enabled = prefs.getBoolean("sound", true)
        sfx = s
        val m = MenuView(this, prefs)
        m.clickSound = { sfx?.play("click", 0.7f, 0L) }
        m.onSoundChanged = { sfx?.enabled = prefs.getBoolean("sound", true) }
        m.onPlay = { cont ->
            sfx?.play("letsgo", 0.8f, 0L)
            startActivity(Intent(this, GameActivity::class.java).putExtra("continue", cont))
        }
        m.onReef = {
            sfx?.play("letsgo", 0.8f, 0L)
            startActivity(Intent(this, ReefActivity::class.java))
        }
        m.onPickPhoto = {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), pickCode)
        }
        m.onRemovePhoto = {
            File(filesDir, "beto.png").delete()
            Toast.makeText(this, "Photo removed.", Toast.LENGTH_SHORT).show()
        }
        m.onPrivacy = {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, PRIVACY_URL, Toast.LENGTH_LONG).show()
            }
        }
        menu = m
        setContentView(m)
        GameActivity.hideSystemBars(window)

        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { if (menu?.onBack() != true) finish() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        }
    }

    // Android 13+ uses the OnBackInvokedCallback registered in onCreate; this is only for older phones.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Used on Android 12 and older")
    override fun onBackPressed() {
        if (menu?.onBack() != true) finish()
    }

    override fun onResume() {
        super.onResume()
        GameActivity.hideSystemBars(window)
        menu?.refresh()
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

    @Deprecated("Fine for a single photo pick")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != pickCode || res != RESULT_OK || uri == null) return
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = max(1, min(bounds.outWidth, bounds.outHeight) / 320)
            }
            val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (src == null) {
                Toast.makeText(this, "Couldn't load that photo", Toast.LENGTH_LONG).show()
                return
            }
            val s = min(src.width, src.height)
            val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
            val out = Bitmap.createScaledBitmap(sq, 160, 160, true)
            File(filesDir, "beto.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, "Beto is in! He shows up on the rock pillars.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't load that photo", Toast.LENGTH_LONG).show()
        }
    }
}
