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
    // poses: 0 swim forward, 1 swim up, 2 look up, 3 wave, 4 happy wave, 5 spin, 6 swim away, 7 wave goodbye
    const val POSE_COUNT = 8
    const val POSE_HAPPY = 4
    const val POSE_WAVE = 3
    const val POSE_GOODBYE = 7
    const val DECAL_COUNT = 8
    // comic sound-effect patches
    const val POP_CLOUD = 0
    const val POP_BURST = 1
    const val POP_BING = 2
    const val POP_POW = 3
    const val POP_ZAP = 4
    const val POP_BOOM = 5
    const val POP_WHAM = 6
    const val POP_BAM = 7
    var loaded = false
        private set
    private val shells = arrayOfNulls<Bitmap>(ShellKinds.COUNT)
    private var bomb: Bitmap? = null
    private var shark: Bitmap? = null
    private val poses = arrayOfNulls<Bitmap>(POSE_COUNT)
    private val decals = arrayOfNulls<Bitmap>(DECAL_COUNT)
    private val pops = arrayOfNulls<Bitmap>(8)
    // crochet critters: 0 octopus, 1 seahorse, 2 clownfish, 3 crab, 4 starfish, 5 dolphin, 6 jellyfish
    const val PAL_COUNT = 7
    private val pals = arrayOfNulls<Bitmap>(PAL_COUNT)
    var reef: Bitmap? = null
        private set
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
            shark = bmp(R.drawable.crochet_shark)
            val poseIds = intArrayOf(R.drawable.pose_swim_forward, R.drawable.pose_swim_up, R.drawable.pose_look_up,
                R.drawable.pose_wave, R.drawable.pose_happy_wave, R.drawable.pose_spin, R.drawable.pose_swim_away,
                R.drawable.pose_wave_goodbye)
            for (i in poseIds.indices) poses[i] = bmp(poseIds[i])
            val decalIds = intArrayOf(R.drawable.decal_swirl_a, R.drawable.decal_swirl_b, R.drawable.decal_bubble,
                R.drawable.decal_waves_a, R.drawable.decal_waves_b, R.drawable.decal_arrows_a, R.drawable.decal_arrows_b,
                R.drawable.decal_splash)
            for (i in decalIds.indices) decals[i] = bmp(decalIds[i])
            val popIds = intArrayOf(R.drawable.pop_cloud, R.drawable.pop_burst, R.drawable.pop_bing, R.drawable.pop_pow,
                R.drawable.pop_zap, R.drawable.pop_boom, R.drawable.pop_wham, R.drawable.pop_bam)
            for (i in popIds.indices) pops[i] = bmp(popIds[i])
            try {
                val palIds = intArrayOf(R.drawable.pal_octopus, R.drawable.pal_seahorse, R.drawable.pal_clownfish,
                    R.drawable.pal_crab, R.drawable.pal_starfish, R.drawable.pal_dolphin, R.drawable.pal_jellyfish)
                for (i in palIds.indices) pals[i] = bmp(palIds[i])
                reef = bmp(R.drawable.bg_reef)
            } catch (e: Exception) {
            }
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

    /** Draws the crochet shark centered on (0,0), nose to the right, `w` wide. */
    fun drawShark(c: Canvas, w: Float): Boolean {
        if (!loaded) return false
        val b = shark ?: return false
        val h = w * b.height / b.width
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        paint.alpha = 255
        c.drawBitmap(b, null, rect, paint)
        return true
    }

    /** Draws one of Betito's poses centered on (x, y), `h` tall, tilted by `rot` degrees. */
    fun drawPose(c: Canvas, pose: Int, x: Float, y: Float, h: Float, rot: Float = 0f, flip: Boolean = false): Boolean {
        if (!loaded) return false
        val b = poses.getOrNull(pose) ?: return false
        val w = h * b.width / b.height
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        if (flip) c.scale(-1f, 1f)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        paint.alpha = 255
        c.drawBitmap(b, null, rect, paint)
        c.restore()
        return true
    }

    /** Draws a yarn water squiggle (swirl, waves, arrows, bubble ring, splash) centered on (x, y), `w` wide. */
    fun drawDecal(c: Canvas, i: Int, x: Float, y: Float, w: Float, a: Int, rot: Float = 0f) {
        if (!loaded) return
        val b = decals.getOrNull(i) ?: return
        val h = w * b.height / b.width
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        paint.alpha = a
        c.drawBitmap(b, null, rect, paint)
        c.restore()
    }

    /** Draws a comic sound-effect patch (POW!, ZAP!...) centered on (x, y), `w` wide. */
    fun drawPop(c: Canvas, kind: Int, x: Float, y: Float, w: Float, a: Int, rot: Float) {
        if (!loaded) return
        val b = pops.getOrNull(kind) ?: return
        val h = w * b.height / b.width
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        paint.alpha = a
        c.drawBitmap(b, null, rect, paint)
        c.restore()
    }

    /** Draws a crochet critter centered on (x, y), `h` tall. Returns false if it has no picture. */
    fun drawPal(c: Canvas, i: Int, x: Float, y: Float, h: Float, rot: Float = 0f, flip: Boolean = false,
                sx: Float = 1f, sy: Float = 1f, a: Int = 255): Boolean {
        val b = pals.getOrNull(i) ?: return false
        val w = h * b.width / b.height
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        c.scale(if (flip) -sx else sx, sy)
        rect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        paint.alpha = a
        c.drawBitmap(b, null, rect, paint)
        c.restore()
        return true
    }

    /** Draws the reef picture to cover the whole w x h area (center-crop). */
    fun drawReef(c: Canvas, w: Float, h: Float): Boolean {
        val b = reef ?: return false
        val k = maxOf(w / b.width, h / b.height)
        val bw = b.width * k
        val bh = b.height * k
        rect.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
        paint.alpha = 255
        c.drawBitmap(b, null, rect, paint)
        return true
    }
}
