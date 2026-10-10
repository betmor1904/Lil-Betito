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
            text = "\nHow to play: Lil Betito is stuck in a flooded maze. Swim up through the rows of " +
                "walls and out the gap in the ceiling before the rising floor catches him. Walls never " +
                "break: plan your route around them. 3 lives.\n\n" +
                "Steering: one finger and he turns toward it. Two fingers: the second finger above the " +
                "first swims up harder (green line), below brakes (amber line). Two-finger moves burn " +
                "your Spin meter.\n\n" +
                "Currents (the arrows show where the water goes): blue updrafts carry you up through a " +
                "gap, green streams push you sideways, red riptides push down through a gap (that row " +
                "always has a second, narrow gap too), and from level 7 whirlpools spin you to a new " +
                "heading. Inside a current the water steers, your finger only nudges. Brake to dig in " +
                "and hold still. Swimming with the flow refills Spin.\n\n" +
                "Bonuses, three ways to solve a room: CLEAN (never touch a wall or the ceiling), " +
                "DIRECT (never touch a current) and EXPLORER (ride every current). Watch the tracker " +
                "under the Spin bar.\n\n" +
                "Apples spin the 3 slot reels at the top right: 3 of a kind pays 5x the escape value " +
                "(777 pays 10x), a pair 2x, no match half. Worth the detour?\n\n" +
                "Spin meter full? Tap SPIN: pulsing currents turn on for 5 seconds, the floor drops, " +
                "and you get a free slot spin.\n\n" +
                "Clear a room to earn 1-3 stars: escape for 1, a style bonus for 2, and two bonuses, " +
                "a fast escape or a last-second CLUTCH escape for 3.\n\n" +
                "Escaping pays 100, 300 or 600 points, 1000 at the last moment, plus a speed bonus. " +
                "Rooms 6-10 are 2x, 11-20 are 3x and 21+ are 5x."
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
