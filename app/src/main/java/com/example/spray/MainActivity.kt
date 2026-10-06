package com.example.spray

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val pickCode = 7
    private lateinit var n1: EditText
    private lateinit var n2: EditText

    private fun send(action: String?) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, ShotService::class.java).setAction(action))
    }

    private fun LinearLayout.btn(title: String, onClick: () -> Unit) {
        addView(Button(this@MainActivity).apply { text = title; setOnClickListener { onClick() } })
    }

    private fun LinearLayout.section(title: String) {
        addView(TextView(this@MainActivity).apply { text = title; textSize = 16f; setPadding(0, 40, 0, 8) })
    }

    private fun saveNames() {
        val a = n1.text.toString().trim().take(12).ifEmpty { "Player 1" }
        val b = n2.text.toString().trim().take(12).ifEmpty { "Player 2" }
        getSharedPreferences("betito", MODE_PRIVATE).edit().putString("p1", a).putString("p2", b).apply()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val sp = getSharedPreferences("betito", MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply { text = "Lil Betito"; textSize = 30f })

        box.addView(CheckBox(this).apply {
            text = "Sound effects"
            isChecked = sp.getBoolean("sound", true)
            setOnCheckedChangeListener { _, on -> sp.edit().putBoolean("sound", on).apply() }
        })
        box.addView(CheckBox(this).apply {
            text = "Math quiz (paused for testing)"
            isChecked = sp.getBoolean("math_on", false)
            setOnCheckedChangeListener { _, on -> sp.edit().putBoolean("math_on", on).apply() }
        })

        box.section("SOLO")
        box.btn("Start solo game") { send("SHOT_SOLO") }

        box.section("2 PLAYERS (one phone)")
        n1 = EditText(this).apply { hint = "Player 1 name"; setSingleLine(); setText(sp.getString("p1", "Player 1")) }
        n2 = EditText(this).apply { hint = "Player 2 name"; setSingleLine(); setText(sp.getString("p2", "Player 2")) }
        box.addView(n1)
        box.addView(n2)
        box.btn("Start 2 player game") { saveNames(); send("SHOT_2P") }

        box.section("GAME CONTROLS")
        box.btn("Add barrier") { send("SHOT_ADD") }
        box.btn("Reset game") { send("SHOT_RESET") }
        box.btn("Stop game") { stopService(Intent(this, ShotService::class.java)) }

        box.section("BETO")
        box.btn("Choose Beto's photo") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), pickCode)
        }
        box.btn("Remove Beto's photo") {
            File(filesDir, "beto.png").delete()
            Toast.makeText(this, "Photo removed. Add new barriers to update them.", Toast.LENGTH_LONG).show()
        }

        box.addView(TextView(this).apply {
            text = "\nHow to play: drag Lil Betito back and let go to shoot. Reach the yellow GOAL line " +
                "in 3 shots. Answer the math questions to earn special rockets: with no rockets Lil Betito " +
                "only flies about half way up, and more rockets means more power. " +
                "In 2 player mode one person answers to earn barriers, places them, and taps DONE. " +
                "The other answers to earn rockets and shoots. Then you swap. First to 3 wins. " +
                "One jump can't reach the goal now: land on the platforms and jump from there. Grab bombs in the air: " +
                "BUSTER breaks through the next wall, WARP wraps you around the screen sides, " +
                "STICKY sticks you exactly where you land. Tap a barrier to resize it, hold it to remove it."
        })
        setContentView(ScrollView(this).apply { addView(box) })
    }

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
            Toast.makeText(this, "Beto is in! Add new barriers to see him.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't load that photo", Toast.LENGTH_LONG).show()
        }
    }
}
