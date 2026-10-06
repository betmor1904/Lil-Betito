package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.min

/** Little round X button in the corner. Tap it to quit the game. */
class CloseView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2
        p.style = Paint.Style.FILL
        p.color = Color.argb(210, 20, 20, 20)
        c.drawCircle(w / 2, h / 2, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.WHITE
        c.drawCircle(w / 2, h / 2, r - d, p)
        p.strokeWidth = 3 * d
        p.strokeCap = Paint.Cap.ROUND
        val k = r * 0.38f
        c.drawLine(w / 2 - k, h / 2 - k, w / 2 + k, h / 2 + k, p)
        c.drawLine(w / 2 + k, h / 2 - k, w / 2 - k, h / 2 + k, p)
    }
}
