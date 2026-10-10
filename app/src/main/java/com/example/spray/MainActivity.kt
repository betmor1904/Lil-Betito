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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val pickCode = 7

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

        box.section("PLAY")
        box.btn("Start game") { send("SHOT_SOLO") }
        box.btn("Restart from level 1") { send("SHOT_RESET") }
        box.btn("Test: jump to level 7 (riptides + whirlpool)") { send("SHOT_TEST7") }
        box.btn("Stop game") { stopService(Intent(this, ShotService::class.java)) }

        box.section("BETO")
        box.btn("Choose Beto's photo") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), pickCode)
        }
        box.btn("Remove Beto's photo") {
            File(filesDir, "beto.png").delete()
            Toast.makeText(this, "Photo removed. It updates on the next level.", Toast.LENGTH_LONG).show()
        }

        box.addView(TextView(this).apply {
            text = "\nHow to play (turn your phone sideways): Lil Betito is in a flooded maze. Swim to " +
                "the glowing exit in the right wall before the stinging tide from the left catches him. " +
                "3 lives.\n\n" +
                "Controls: put your LEFT thumb down anywhere and drag to swim (a joystick appears where " +
                "you touch). RIGHT thumb: tap STROKE for a burst of speed, hold it to brake and dig in " +
                "against currents. Tap FIRE to throw a shell.\n\n" +
                "The shark patrols the maze. If he sees you he chases (! over his head). Break his line of " +
                "sight or hide in seaweed and he searches (?), then gives up. Seaweed hides you unless " +
                "he's right on top of you.\n\n" +
                "Shells (grab them in the maze, they aim at the shark for you): GREEN flies straight and " +
                "stuns him. RED bounces off walls. Orange BOMB shells explode, stun him longer, and blow " +
                "up the brown pillar blocks. Yellow SPEED KELP makes you fast for 5 seconds.\n\n" +
                "Currents (arrows show the flow): blue pushes you toward the exit, green up or down, red " +
                "pushes you back, purple whirlpools spin you. Brake to hold still in one.\n\n" +
                "Bonuses: CLEAN (never touch a wall), DIRECT (never touch a current), EXPLORER (ride " +
                "every current). Apples spin the slot reels. Clear a room for 1-3 stars."
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
            Toast.makeText(this, "Beto is in! He shows up on the next level.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't load that photo", Toast.LENGTH_LONG).show()
        }
    }
}
