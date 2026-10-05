package com.example.spray

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private fun send(cls: Class<*>, action: String?) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, cls).setAction(action))
    }

    private fun LinearLayout.btn(label: String, onClick: () -> Unit) {
        addView(Button(this@MainActivity).apply { text = label; setOnClickListener { onClick() } })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply { text = "Screen Skater"; textSize = 26f })

        box.addView(TextView(this).apply { text = "\nSKATER MODE"; textSize = 16f })
        box.btn("Start skater") { send(OverlayService::class.java, null) }
        box.btn("Add rail") { send(OverlayService::class.java, "ADD_RAIL") }
        box.btn("Stop skater") { stopService(Intent(this, OverlayService::class.java)) }

        box.addView(TextView(this).apply { text = "\nSHOT GAME"; textSize = 16f })
        box.btn("Start shot game") { send(ShotService::class.java, null) }
        box.btn("Add barrier") { send(ShotService::class.java, "SHOT_ADD") }
        box.btn("Reset game") { send(ShotService::class.java, "SHOT_RESET") }
        box.btn("Stop game") { stopService(Intent(this, ShotService::class.java)) }

        box.addView(TextView(this).apply {
            text = "\nShot game: drag the skater back and let go to shoot. " +
                "Reach the yellow GOAL line at the top in 3 shots. " +
                "Drag red barriers over your apps. Tap a barrier to resize it, hold it to remove it."
        })
        setContentView(ScrollView(this).apply { addView(box) })
    }
}
