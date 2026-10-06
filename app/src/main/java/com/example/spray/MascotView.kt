package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The cheering buddy standing on the left side. Drawn from simple shapes.
 *
 * SWAP IN YOUR OWN ART: put a transparent PNG named mascot.png in
 * app/src/main/res/drawable-nodpi/ and it replaces the shapes. All the
 * dance moves (jump, flip, spin, shake) still work on your picture.
 */
class MascotView(ctx: Context) : View(ctx) {
    enum class Move(val ms: Long) {
        IDLE(0), CHEER(1500), TWERK(2200), BACKFLIP(1200), SPIN(1000),
        FLOSS(2400), FACEPALM(1900), MOONWALK(2200)
    }

    companion object {
        private val funny = arrayOf(Move.TWERK, Move.BACKFLIP, Move.SPIN, Move.FLOSS, Move.MOONWALK, Move.CHEER)
        fun randomFunny(): Move = funny.random()
        private const val PI_F = 3.1415927f
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()

    private var move = Move.IDLE
    private var moveStart = 0L
    private var nextRandom = 0L
    private var bubble: String? = null
    private var bubbleUntil = 0L

    private val skin = Color.parseColor("#9CCC65")
    private val skinDark = Color.parseColor("#7CB342")
    private val shellDark = Color.parseColor("#2E7D32")
    private val belly = Color.parseColor("#FFF59D")

    private val sprite: Bitmap? = try {
        val id = resources.getIdentifier("mascot", "drawable", ctx.packageName)
        if (id != 0) BitmapFactory.decodeResource(resources, id) else null
    } catch (e: Exception) {
        null
    }

    fun play(m: Move, say: String? = null) {
        move = m
        moveStart = SystemClock.uptimeMillis()
        if (say != null) {
            bubble = say
            bubbleUntil = moveStart + 1800L
        }
    }

    private fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color }
    private fun stroke(color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (nextRandom == 0L) nextRandom = now + 4000L
        if (move != Move.IDLE && now - moveStart > move.ms) {
            move = Move.IDLE
            nextRandom = now + 5000L + Random.nextLong(4000L)
        }
        if (move == Move.IDLE && now > nextRandom) play(randomFunny())

        val t = (now - moveStart) / 1000f
        val u = if (move.ms > 0L) ((now - moveStart).toFloat() / move.ms).coerceIn(0f, 1f) else 0f
        val ti = (now % 100000L) / 1000f

        var offX = 0f; var offY = 0f; var rot = 0f; var sx = 1f; var sy = 1f
        var armL = 20f; var armR = 20f; var hip = 0f; var headDy = 0f
        var spread = 0f; var liftL = 0f; var liftR = 0f
        var open = false; var sad = false; var palm = false

        when (move) {
            Move.IDLE -> {
                offY = sin(ti * 4f) * 2f
                rot = sin(ti * 2f) * 3f
                armL = 25f + sin(ti * 4f) * 10f
                armR = 25f + sin(ti * 4f + 3f) * 10f
            }
            Move.CHEER -> {
                offY = -abs(sin(t * 7f)) * 20f
                armL = 150f + sin(t * 14f) * 25f
                armR = 150f - sin(t * 14f) * 25f
                open = true
            }
            Move.TWERK -> {
                offY = 7f; sy = 0.92f; spread = 6f
                hip = sin(t * 32f) * 7f
                armL = 45f; armR = 45f
                headDy = sin(t * 8f) * 2f
                open = true
            }
            Move.BACKFLIP -> {
                val s = u * u * (3f - 2f * u)
                offY = -sin(PI_F * u) * 45f
                rot = -360f * s
                armL = 100f; armR = 100f
                open = true
            }
            Move.SPIN -> {
                sx = cos(u * 6f * PI_F)
                offY = -sin(PI_F * u) * 12f
                armL = 90f; armR = 90f
                open = true
            }
            Move.FLOSS -> {
                val s = sin(t * 11f)
                armR = s * 70f
                armL = -s * 70f
                hip = -s * 9f
                offY = 4f; spread = 3f
                open = true
            }
            Move.FACEPALM -> {
                sad = true
                if (u < 0.6f) {
                    palm = true
                    headDy = 5f
                    armL = 15f
                    offY = 2f
                    rot = sin(t * 20f) * 1.5f
                } else {
                    armL = 105f; armR = 105f
                    offY = -3f
                }
            }
            Move.MOONWALK -> {
                offX = cos(t * 3f) * 14f
                rot = -5f
                val s = sin(t * 12f)
                liftL = max(0f, s) * 5f
                liftR = max(0f, -s) * 5f
                armL = 40f + s * 20f
                armR = 40f - s * 20f
                open = true
            }
        }

        c.save()
        c.scale(d, d)

        // ground shadow
        fill(Color.argb(60, 0, 0, 0))
        rf.set(24f + offX * 0.5f, 127f, 80f + offX * 0.5f, 135f)
        c.drawOval(rf, p)

        c.save()
        c.translate(52f + offX, 98f + offY)
        c.rotate(rot)
        c.scale(sx, sy)
        c.translate(-52f, -98f)
        if (sprite != null) drawSprite(c)
        else drawTurtle(c, hip, headDy, spread, liftL, liftR, armL, armR, open, sad, palm)
        c.restore()

        // speech bubble
        val b = bubble
        if (b != null && now < bubbleUntil) {
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 14f
            p.textAlign = Paint.Align.CENTER
            val bw = p.measureText(b) + 18f
            var x0 = 58f
            if (x0 + bw > 148f) x0 = 148f - bw
            rf.set(x0, 6f, x0 + bw, 32f)
            fill(Color.WHITE)
            c.drawRoundRect(rf, 10f, 10f, p)
            stroke(Color.BLACK, 2f)
            c.drawRoundRect(rf, 10f, 10f, p)
            fill(Color.WHITE)
            path.reset()
            path.moveTo(x0 + 12f, 31f)
            path.lineTo(x0 + 6f, 43f)
            path.lineTo(x0 + 24f, 31f)
            path.close()
            c.drawPath(path, p)
            fill(Color.BLACK)
            c.drawText(b, x0 + bw / 2, 24f, p)
        }
        c.restore()
        postInvalidateOnAnimation()
    }

    private fun drawSprite(c: Canvas) {
        val bmp = sprite ?: return
        val sc = min(80f / bmp.width, 100f / bmp.height)
        val w = bmp.width * sc
        val h = bmp.height * sc
        rf.set(52f - w / 2, 130f - h, 52f + w / 2, 130f)
        c.drawBitmap(bmp, null, rf, null)
    }

    private fun arm(c: Canvas, side: Int, deg: Float) {
        val shx = 52f + side * 24f
        val shy = 90f
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        val ex = shx + side * sin(rad) * 24f
        val ey = shy + cos(rad) * 24f
        stroke(skinDark, 9f)
        c.drawLine(shx, shy, ex, ey, p)
        fill(skin)
        c.drawCircle(ex, ey, 6f, p)
    }

    private fun drawTurtle(
        c: Canvas, hip: Float, headDy: Float, spread: Float, liftL: Float, liftR: Float,
        armL: Float, armR: Float, open: Boolean, sad: Boolean, palm: Boolean
    ) {
        // legs
        for (side in intArrayOf(-1, 1)) {
            val lx = 52f + side * (12f + spread) + hip
            val ly = 116f - (if (side < 0) liftL else liftR)
            fill(skinDark)
            rf.set(lx - 7f, ly, lx + 7f, ly + 14f)
            c.drawRoundRect(rf, 6f, 6f, p)
            fill(skin)
            rf.set(lx - 10f, ly + 9f, lx + 10f, ly + 17f)
            c.drawOval(rf, p)
        }
        // shell + belly
        fill(shellDark)
        rf.set(22f + hip, 72f, 82f + hip, 124f)
        c.drawOval(rf, p)
        fill(belly)
        rf.set(30f + hip, 78f, 74f + hip, 118f)
        c.drawOval(rf, p)
        stroke(Color.parseColor("#F9A825"), 1.5f)
        c.drawLine(32f + hip, 92f, 72f + hip, 92f, p)
        c.drawLine(32f + hip, 104f, 72f + hip, 104f, p)
        c.drawLine(52f + hip, 80f, 52f + hip, 116f, p)

        // arms
        arm(c, -1, armL)
        if (palm) {
            stroke(skinDark, 9f)
            c.drawLine(76f, 90f, 60f, 70f + headDy, p)
            fill(skin)
            c.drawCircle(60f, 70f + headDy, 6f, p)
        } else {
            arm(c, 1, armR)
        }

        // head
        val hy = 62f + headDy
        fill(skin)
        c.drawCircle(52f, hy, 17f, p)
        fill(Color.parseColor("#FF2D6F"))
        rf.set(36f, hy - 11f, 68f, hy - 5f)
        c.drawRoundRect(rf, 3f, 3f, p)
        fill(Color.WHITE)
        c.drawCircle(45f, hy + 2f, 5.5f, p)
        c.drawCircle(59f, hy + 2f, 5.5f, p)
        fill(Color.BLACK)
        val py = if (sad) hy + 4f else hy + 2f
        c.drawCircle(46f, py, 2.4f, p)
        c.drawCircle(60f, py, 2.4f, p)
        if (open) {
            fill(Color.parseColor("#B71C1C"))
            rf.set(45f, hy + 8f, 59f, hy + 15f)
            c.drawOval(rf, p)
        } else if (sad) {
            stroke(Color.BLACK, 1.8f)
            rf.set(45f, hy + 10f, 59f, hy + 18f)
            c.drawArc(rf, 200f, 140f, false, p)
        } else {
            stroke(Color.BLACK, 1.8f)
            rf.set(44f, hy + 4f, 60f, hy + 14f)
            c.drawArc(rf, 20f, 140f, false, p)
        }
    }
}
