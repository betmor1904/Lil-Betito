package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View

/** A small gold coin that pops out of a coin brick. Fly into it for points. */
class CoinView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val m = width / 2f
        val rr = m - 2 * d
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FFC107")
        c.drawCircle(m, m, rr, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.parseColor("#FF8F00")
        c.drawCircle(m, m, rr, p)
        p.style = Paint.Style.FILL
        p.color = Color.argb(170, 255, 255, 255)
        c.drawCircle(m - rr * 0.3f, m - rr * 0.3f, rr * 0.25f, p)
    }
}
