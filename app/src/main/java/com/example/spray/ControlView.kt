package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View

class ControlView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var label = "DONE"

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FF2D6F")
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = Color.WHITE
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        p.textSize = 16 * d
        c.drawText(label, w / 2, h / 2 + 6 * d, p)
    }
}
