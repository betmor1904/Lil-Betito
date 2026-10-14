package com.example.spray

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Hand-made art that replaces the code-drawn shapes. Falls back to the shapes if an image can't load. */
object Sprites {
    var turtle: Bitmap? = null
        private set
    private val rect = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // where the shell's center is in the 512px turtle image, and how many image pixels one game unit (u) covers
    private const val TURTLE_CX = 223.5f
    private const val TURTLE_CY = 256.5f
    private const val TURTLE_PX_PER_U = 207.5f

    fun load(ctx: Context) {
        if (turtle != null) return
        try {
            val o = BitmapFactory.Options()
            o.inScaled = false
            turtle = BitmapFactory.decodeResource(ctx.resources, R.drawable.betito_turtle, o)
        } catch (e: Exception) {
            turtle = null
        }
    }

    /** Draws Betito centered on (0,0) facing right (+x), in game units where the shell is about 1.24u wide. */
    fun drawTurtle(c: Canvas, u: Float, scale: Float = 1f): Boolean {
        val b = turtle ?: return false
        val k = u / TURTLE_PX_PER_U * scale
        rect.set(-TURTLE_CX * k, -TURTLE_CY * k, (b.width - TURTLE_CX) * k, (b.height - TURTLE_CY) * k)
        c.drawBitmap(b, null, rect, bmpPaint)
        return true
    }
}
