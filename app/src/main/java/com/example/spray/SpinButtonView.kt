package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** Big pulsing SPIN button. It only appears when the spin meter is full. */
class SpinButtonView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pulse = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 150.0).toFloat()
        val inset = 3 * d + 3 * d * (1f - pulse)
        rect.set(inset, inset, w - inset, h - inset)
        p.style = Paint.Style.FILL
        p.color = Color.argb(240, 0, 145, 234)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.argb((150 + 105 * pulse).toInt(), 255, 235, 59)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 24 * d
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        c.drawText("SPIN!", w / 2, h / 2 + 8 * d, p)
        p.clearShadowLayer()
        postInvalidateOnAnimation()
    }
}
