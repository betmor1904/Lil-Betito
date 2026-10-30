package com.example.spray

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * The yarn look: crochet shell pictures and knit textures for the sea, walls, rocks and the hole.
 * If anything fails to load, everything falls back to the old code-drawn shapes.
 */
object Yarn {
    var loaded = false
        private set
    private val shells = arrayOfNulls<Bitmap>(ShellKinds.COUNT)
    private var bomb: Bitmap? = null
    var sea: BitmapShader? = null
        private set
    var wall: BitmapShader? = null
        private set
    var rock: BitmapShader? = null
        private set
    var hole: BitmapShader? = null
        private set
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun load(ctx: Context) {
        if (loaded) return
        try {
            val res = ctx.resources
            val o = BitmapFactory.Options()
            o.inScaled = false
            fun bmp(id: Int): Bitmap = BitmapFactory.decodeResource(res, id, o)
            val d = res.displayMetrics.density
            // one stitch is the same physical size on every phone
            fun shader(id: Int): BitmapShader {
                val s = BitmapShader(bmp(id), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val m = Matrix()
                m.setScale(d / 3f, d / 3f)
                s.setLocalMatrix(m)
                return s
            }
            // shell kinds: 0 bouncy (lightning), 1 rubber, 2 mega (flames), 3 sponge, 4 twist (waves)
            shells[0] = bmp(R.drawable.crochet_shell_bouncy)
            shells[1] = bmp(R.drawable.crochet_shell_rubber)
            shells[2] = bmp(R.drawable.crochet_shell_mega)
            shells[3] = bmp(R.drawable.crochet_shell_sponge)
            shells[4] = bmp(R.drawable.crochet_shell_twist)
            bomb = bmp(R.drawable.crochet_bomb)
            sea = shader(R.drawable.yarn_sea)
            wall = shader(R.drawable.yarn_wall)
            rock = shader(R.drawable.yarn_rock)
            hole = shader(R.drawable.yarn_hole)
            loaded = true
        } catch (e: Exception) {
            loaded = false
        }
    }

    private fun draw(c: Canvas, b: Bitmap, x: Float, y: Float, rad: Float, a: Int) {
        val w = rad * 2.4f
        val h = w * b.height / b.width
        rect.set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
        paint.alpha = a
        c.drawBitmap(b, null, rect, paint)
    }

    /** Draws a crochet shell centered on (x, y). Returns false if there is no picture for it. */
    fun drawShell(c: Canvas, kind: Int, x: Float, y: Float, rad: Float, a: Int): Boolean {
        if (!loaded) return false
        val b = shells.getOrNull(kind) ?: return false
        draw(c, b, x, y, rad, a)
        return true
    }

    fun drawBomb(c: Canvas, x: Float, y: Float, rad: Float, a: Int): Boolean {
        if (!loaded) return false
        val b = bomb ?: return false
        draw(c, b, x, y, rad, a)
        return true
    }
}
