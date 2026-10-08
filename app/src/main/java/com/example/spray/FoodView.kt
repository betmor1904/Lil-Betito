package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View

/** Food for Lil Betito: an apple. Eating it gives energy, so steer him into the tricky spots. */
class FoodView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val m = width / 2f
        val rr = m - 3 * d
        p.style = Paint.Style.FILL
        p.color = Color.argb(70, 255, 235, 59)
        c.drawCircle(m, m, m, p)
        p.color = Color.parseColor("#E53935")
        c.drawCircle(m, m + 1 * d, rr, p)
        p.color = Color.argb(150, 255, 255, 255)
        c.drawCircle(m - rr * 0.35f, m - rr * 0.25f, rr * 0.22f, p)
        p.color = Color.parseColor("#43A047")
        c.drawOval(m + 1 * d, 1 * d, m + rr * 0.8f, 1 * d + rr * 0.5f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = Color.parseColor("#6D4C41")
        c.drawLine(m, m - rr * 0.8f, m, 2 * d, p)
    }
}
