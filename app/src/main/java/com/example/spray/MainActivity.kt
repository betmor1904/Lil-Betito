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
import android.widget.TextView

class MainActivity : Activity() {
    private fun send(action: String?) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java).setAction(action))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply { text = "Screen Skater"; textSize = 26f })
        box.addView(Button(this).apply { text = "Start skater"; setOnClickListener { send(null) } })
        box.addView(Button(this).apply { text = "Add rail"; setOnClickListener { send("ADD_RAIL") } })
        box.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        box.addView(TextView(this).apply {
            text = "\nTap: ollie\nDouble tap: kickflip\nSwipe sideways: shove-it\nSwipe up: 360 flip\n" +
                "Hold skater: pick up and place\nDrag rail: move it\nHold rail: remove it"
        })
        setContentView(box)
    }
}
