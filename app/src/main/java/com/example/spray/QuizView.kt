package com.example.spray

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Multiple-choice math panel (no keyboard needed, so it works as an overlay). */
class QuizView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var title = ""
    var question = ""
    var options: List<String> = listOf("", "", "", "")
    var correct = 0
    var selected = -1
    var reward = "barrier"
    var onPick: ((Int) -> Unit)? = null

    private fun rectOf(i: Int): RectF {
        val gap = 16 * d
        val bw = (width - 3 * gap) / 2
        val left = gap + (i % 2) * (bw + gap)
        val top = 130 * d + (i / 2) * 72 * d
        return RectF(left, top, left + bw, top + 60 * d)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && selected < 0) {
            for (i in 0 until 4) {
                if (rectOf(i).contains(e.x, e.y)) {
                    selected = i
                    invalidate()
                    onPick?.invoke(i)
                    break
                }
            }
        }
        return true
    }

    private fun fit(c: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.argb(245, 28, 32, 48)
        c.drawRoundRect(0f, 0f, w, h, 20 * d, 20 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.parseColor("#FFEB3B")
        c.drawRoundRect(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d, 20 * d, 20 * d, p)

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = Color.parseColor("#FFEB3B")
        fit(c, title, w / 2, 34 * d, 15 * d, w - 32 * d)
        p.color = Color.WHITE
        fit(c, question, w / 2, 100 * d, 44 * d, w - 32 * d)

        for (i in 0 until 4) {
            val r = rectOf(i)
            p.style = Paint.Style.FILL
            p.color = when {
                selected < 0 -> Color.parseColor("#1E88E5")
                i == correct -> Color.parseColor("#43A047")
                i == selected -> Color.parseColor("#E53935")
                else -> Color.parseColor("#37474F")
            }
            c.drawRoundRect(r, 14 * d, 14 * d, p)
            p.color = Color.WHITE
            fit(c, options[i], r.centerX(), r.centerY() + 10 * d, 28 * d, r.width() - 16 * d)
        }

        if (selected >= 0) {
            if (selected == correct) {
                p.color = Color.parseColor("#A5D6A7")
                fit(c, "Correct! +1 $reward", w / 2, h - 12 * d, 15 * d, w - 32 * d)
            } else {
                p.color = Color.parseColor("#EF9A9A")
                fit(c, "Not quite! The answer is ${options[correct]}", w / 2, h - 12 * d, 15 * d, w - 32 * d)
            }
        }
    }
}
