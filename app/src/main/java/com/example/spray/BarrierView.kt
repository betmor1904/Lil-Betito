package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View

class BarrierView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.argb(200, 211, 47, 47)
        c.drawRoundRect(0f, 0f, w, h, 8 * d, 8 * d, p)

        c.save()
        c.clipRect(0f, 0f, w, h)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8 * d
        p.color = Color.argb(160, 255, 235, 59)
        var x = -h
        while (x < w) {
            c.drawLine(x, h, x + h, 0f, p)
            x += 24 * d
        }
        c.restore()

        p.strokeWidth = 3 * d
        p.color = Color.WHITE
        c.drawRoundRect(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d, 8 * d, 8 * d, p)
    }
}
