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
            text = "\nHow to play (turn your phone sideways): UNDERWATER GOLF. Lil Betito is the ball. " +
                "Get him through the glowing exit in the right wall in as few shots as you can.\n\n" +
                "Shooting: when he's stopped (green ring), put a finger down anywhere, pull back and let go. " +
                "He pivots to face your aim. The ring around him is your POWER GAUGE, and the dashed circle " +
                "shows how far that power glides in still water. The dotted line shows the start of the path.\n\n" +
                "Currents are boost lanes: ride with one and you speed up. Ride 2 in one shot = NICE!!, " +
                "3 = DEADLY COMBO!!, 4+ = KING OF THE SEA!!. Rocks bounce you, so bank shots work. Seaweed " +
                "stops you dead.\n\n" +
                "BUOYS: hit one with a shot and its linked current FLIPS direction. Turn a red riptide into a " +
                "blue boost lane! A combo plus a buoy hit in one shot = PERFECT!!\n\n" +
                "GOLD LOCKS: hit one to blow open a shortcut gate in a wall. PINK STARS: bonus points. Hit 2 " +
                "or 3 targets in one shot for DOUBLE TAP!! or TRIPLE THREAT!!\n\n" +
                "KELP SLIDES: shoot into either glowing end of a seaweed path and it whips you around the corner. " +
                "Slides count toward combos. Near the exit the water sucks you into the hole.\n\n" +
                "The boxes at the top are your scorecard: every shot ticks one off. The flag marks par.\n\n" +
                "Sharks patrol back and forth. They won't eat you, but they bump your shot off course.\n\n" +
                "Scoring: PAR, BIRDIE (1 under), EAGLE (2 under), ALBATROSS (3 under), HOLE IN ONE. Fast holes " +
                "get a speed bonus. Big combos and apples spin the slot reels. Use double par and you replay the hole."
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
