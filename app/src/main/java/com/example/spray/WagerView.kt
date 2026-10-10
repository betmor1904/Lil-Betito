package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** The wager buttons: [ - ]  10%  [ + ]. Greyed out once bets close for the level. */
class WagerView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var pct = 10
    var locked = false
    var onMinus: (() -> Unit)? = null
    var onPlus: (() -> Unit)? = null
    private var pressed = 0   // -1 minus, 1 plus

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val side = when {
            e.x < width / 3f -> -1
            e.x > width * 2f / 3f -> 1
            else -> 0
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = side; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (side == pressed) {
                    if (side == -1) onMinus?.invoke()
                    if (side == 1) onPlus?.invoke()
                }
                pressed = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }

    private fun button(c: Canvas, l: Float, label: String, down: Boolean) {
        val s = height.toFloat()
        rect.set(l + 2 * d, 2 * d, l + s - 2 * d, s - 2 * d)
        p.style = Paint.Style.FILL
        p.color = when {
            locked -> Color.argb(200, 60, 60, 60)
            down -> Color.parseColor("#FFB300")
            else -> Color.argb(230, 30, 20, 50)
        }
        c.drawRoundRect(rect, 8 * d, 8 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = if (locked) Color.GRAY else Color.parseColor("#FFD54F")
        c.drawRoundRect(rect, 8 * d, 8 * d, p)
        p.style = Paint.Style.FILL
        p.color = if (locked) Color.GRAY else Color.WHITE
        p.textSize = 22 * d
        c.drawText(label, rect.centerX(), rect.centerY() + 8 * d, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        button(c, 0f, "−", pressed == -1)
        button(c, w - h, "+", pressed == 1)
        p.style = Paint.Style.FILL
        p.color = if (locked) Color.GRAY else Color.parseColor("#FFD54F")
        p.textSize = 14 * d
        p.setShadowLayer(4f, 1f, 1f, Color.BLACK)
        c.drawText("$pct%", w / 2, h / 2 + 5 * d, p)
        p.clearShadowLayer()
    }
}
