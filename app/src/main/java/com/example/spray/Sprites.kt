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
    var shark: Bitmap? = null
        private set
    /** Octo's faces: 0 smile, 1 happy (open mouth), 2 wink, 3 surprised. */
    val octo = arrayOfNulls<Bitmap>(4)
    const val FACE_SMILE = 0
    const val FACE_HAPPY = 1
    const val FACE_WINK = 2
    const val FACE_SURPRISE = 3
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
            shark = BitmapFactory.decodeResource(ctx.resources, R.drawable.shark_blue, o)
            octo[0] = BitmapFactory.decodeResource(ctx.resources, R.drawable.octo_smile, o)
            octo[1] = BitmapFactory.decodeResource(ctx.resources, R.drawable.octo_happy, o)
            octo[2] = BitmapFactory.decodeResource(ctx.resources, R.drawable.octo_wink, o)
            octo[3] = BitmapFactory.decodeResource(ctx.resources, R.drawable.octo_surprise, o)
        } catch (e: Exception) {
            turtle = null
        }
    }

    // shark image: nose-right, body center at (298.5, 257.5), 143 image pixels per game unit
    /** Draws the blue shark centered on (0,0) facing right. */
    fun drawShark(c: Canvas, u: Float): Boolean {
        val b = shark ?: return false
        val k = u / 143f
        rect.set(-298.5f * k, -257.5f * k, (b.width - 298.5f) * k, (b.height - 257.5f) * k)
        c.drawBitmap(b, null, rect, bmpPaint)
        return true
    }

    // Octo images: head center at x=257, head center y=210, 172 image pixels per head radius (s)
    /** Draws Octo with a face. (x, y) is the middle of his head, s his head radius. */
    fun drawOcto(c: Canvas, x: Float, y: Float, s: Float, face: Int): Boolean {
        val b = octo[face.coerceIn(0, 3)] ?: return false
        val k = s / 172f
        rect.set(x - 257f * k, y - 210f * k, x + (b.width - 257f) * k, y + (b.height - 210f) * k)
        c.drawBitmap(b, null, rect, bmpPaint)
        return true
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
