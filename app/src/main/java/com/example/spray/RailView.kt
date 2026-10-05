package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View

class RailView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = Color.parseColor("#B0BEC5")
        c.drawRect(w * 0.14f, 6 * d, w * 0.14f + 5 * d, h, p)
        c.drawRect(w * 0.86f - 5 * d, 6 * d, w * 0.86f, h, p)
        p.color = Color.parseColor("#ECEFF1")
        c.drawRoundRect(0f, 0f, w, 8 * d, 4 * d, 4 * d, p)
        p.color = Color.parseColor("#FF2D6F")
        c.drawRect(w * 0.45f, 2 * d, w * 0.55f, 6 * d, p)
    }
}
