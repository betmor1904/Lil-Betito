package com.example.spray

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** One frame's pose of the crochet Betito. Angles are in degrees, offsets in game units (u). */
class TurtlePose {
    var face = 1f        // +1 faces right, -1 faces left (the picture is mirrored)
    var tilt = 0f        // positive = nose down
    var spin = 0f        // extra rotation for somersaults
    var sx = 1f          // stretch along his body
    var sy = 1f
    var lift = 0f        // up, in u
    var shakeX = 0f
    var front = 0f       // big front flipper
    var back = 0f        // back flipper
    var far = 0f         // far-side flipper (also the waving one)
    var headAng = 0f     // positive = look down
    var headDx = 0f
    var headDy = 0f
    var eyes = 0         // 0 open, 1 closed, 2 happy (^ ^)

    fun reset() {
        face = 1f; tilt = 0f; spin = 0f; sx = 1f; sy = 1f; lift = 0f; shakeX = 0f
        front = 0f; back = 0f; far = 0f; headAng = 0f; headDx = 0f; headDy = 0f; eyes = 0
    }
}

/**
 * The hand-made crochet turtle, drawn as separate pieces (body, head, three flippers) so he can move.
 * He is a side view: he faces right, and when he swims left the picture is mirrored.
 */
object TurtleRig {
    private class Part(val bmp: Bitmap, val l: Float, val t: Float, val px: Float, val py: Float)

    private var body: Part? = null
    private var flipFar: Part? = null
    private var flipBack: Part? = null
    private var flipFront: Part? = null
    private var head: Part? = null
    var loaded = false
        private set

    // how many picture pixels one game unit (u) covers; the whole turtle is about 1.9u long
    private const val PX_PER_U = 220f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(148, 172, 62) }
    private val lashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(58, 36, 20)
    }
    private val rect = RectF()
    private val tmpPose = TurtlePose()

    fun load(ctx: Context) {
        if (loaded) return
        try {
            val o = BitmapFactory.Options()
            o.inScaled = false
            fun bmp(id: Int) = BitmapFactory.decodeResource(ctx.resources, id, o)
            // l, t = top-left corner and px, py = turning point, in picture pixels from the middle of the turtle
            body = Part(bmp(R.drawable.crochet_body), -207.5f, -133f, 0f, 0f)
            flipFar = Part(bmp(R.drawable.crochet_flip_far), 108f, 14f, 120f, 30f)
            flipBack = Part(bmp(R.drawable.crochet_flip_back), -207f, 28f, -105f, 40f)
            flipFront = Part(bmp(R.drawable.crochet_flip_front), -92.5f, 8f, 30f, 25f)
            head = Part(bmp(R.drawable.crochet_head), 59.5f, -132.5f, 85f, 0f)
            loaded = body != null && head != null
        } catch (e: Exception) {
            loaded = false
        }
    }

    private fun drawPart(c: Canvas, part: Part?, k: Float, angle: Float, dx: Float, dy: Float) {
        val pt = part ?: return
        c.save()
        c.translate(pt.px * k + dx, pt.py * k + dy)
        c.rotate(angle)
        rect.set((pt.l - pt.px) * k, (pt.t - pt.py) * k, (pt.l - pt.px + pt.bmp.width) * k, (pt.t - pt.py + pt.bmp.height) * k)
        c.drawBitmap(pt.bmp, null, rect, bmpPaint)
        c.restore()
    }

    /** A lid painted over an eye: mode 1 = closed (sleepy curve), mode 2 = happy (^). Coordinates are picture pixels. */
    private fun drawLid(c: Canvas, ex: Float, ey: Float, rx: Float, ry: Float, k: Float, mode: Int) {
        rect.set((ex - rx * 1.12f) * k, (ey - ry * 1.12f) * k, (ex + rx * 1.12f) * k, (ey + ry * 1.12f) * k)
        c.drawOval(rect, lidPaint)
        lashPaint.strokeWidth = 3.6f * k
        rect.set((ex - rx * 0.8f) * k, (ey - ry * 0.55f) * k, (ex + rx * 0.8f) * k, (ey + ry * 0.55f) * k)
        if (mode == 2) c.drawArc(rect, 200f, 140f, false, lashPaint)
        else c.drawArc(rect, 20f, 140f, false, lashPaint)
    }

    /** Draws Betito centered on (0,0). u = game unit. */
    fun draw(c: Canvas, u: Float, p: TurtlePose) {
        val b = body ?: return
        val k = u / PX_PER_U
        c.save()
        c.translate(p.shakeX * u, -p.lift * u)
        c.rotate((p.tilt + p.spin) * p.face)
        c.scale(p.face * p.sx, p.sy)
        drawPart(c, b, k, 0f, 0f, 0f)
        drawPart(c, flipFar, k, p.far, 0f, 0f)
        drawPart(c, flipBack, k, p.back, 0f, 0f)
        drawPart(c, flipFront, k, p.front, 0f, 0f)
        // head (turns from the neck) with eyelids on top
        val h = head
        if (h != null) {
            c.save()
            c.translate(h.px * k + p.headDx * u, h.py * k + p.headDy * u)
            c.rotate(p.headAng)
            rect.set((h.l - h.px) * k, (h.t - h.py) * k, (h.l - h.px + h.bmp.width) * k, (h.t - h.py + h.bmp.height) * k)
            c.drawBitmap(h.bmp, null, rect, bmpPaint)
            if (p.eyes != 0) {
                drawLid(c, 127.5f - h.px, -46f - h.py, 22f, 21.5f, k, p.eyes)
                drawLid(c, 205.5f - h.px, -49.5f - h.py, 9f, 13.5f, k, p.eyes)
            }
            c.restore()
        }
        c.restore()
    }

    /** A simple swimming Betito for menus: he faces where he heads (mirrored when going left) and paddles. */
    fun drawSimple(c: Canvas, u: Float, headingDeg: Float, now: Long) {
        val rad = Math.toRadians(headingDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val q = tmpPose
        q.reset()
        q.face = if (dx >= 0f) 1f else -1f
        q.tilt = Math.toDegrees(atan2(dy.toDouble(), max(abs(dx), 0.0001f).toDouble())).toFloat().coerceIn(-35f, 35f)
        val ph = now / 260.0
        q.front = (sin(ph) * 18).toFloat()
        q.far = q.front * 0.8f
        q.back = -(sin(ph + 0.5) * 14).toFloat()
        q.lift = (sin(now / 500.0) * 0.03).toFloat()
        q.eyes = if (now % 3400L < 130L) 1 else 0
        draw(c, u, q)
    }
}

/** Decides what Betito is doing each frame: swimming, crouching to aim, stretching on launch, ouch on bumps, waving, cheering. */
class TurtleAnim {
    val pose = TurtlePose()
    private var lastNow = 0L
    private var face = 1f
    private var tilt = 0f
    private var phase = 0.0
    private var speedSm = 0f
    private var nextBlinkAt = 0L
    private var blinkUntil = 0L
    private var gesture = -1
    private var gestureAt = 0L
    private var gestureLen = 0L
    private var nextGestureAt = 0L

    fun update(g: Game, now: Long) {
        val d = g.d
        val dt = if (lastNow == 0L) 16f else (now - lastNow).coerceIn(0L, 60L).toFloat()
        lastNow = now
        val q = pose
        q.reset()

        // ---- which way is he facing / leaning
        val spd = hypot(g.vx, g.vy) / d
        speedSm += (spd - speedSm) * (1f - exp(-dt / 120f))
        val aimingNow = g.aiming && g.ready && g.aimPower > 0.12f
        var dirX = 0f
        var dirY = 0f
        var haveDir = false
        if (aimingNow) { dirX = g.aimDX; dirY = g.aimDY; haveDir = true }
        else if (spd > 0.7f) { dirX = g.vx; dirY = g.vy; haveDir = true }
        var tiltTarget = 0f
        if (haveDir) {
            val len = hypot(dirX, dirY)
            if (len > 0.0001f) { dirX /= len; dirY /= len }
            if (abs(dirX) > 0.2f) face = if (dirX > 0f) 1f else -1f
            tiltTarget = Math.toDegrees(atan2(dirY.toDouble(), max(abs(dirX), 0.0001f).toDouble())).toFloat().coerceIn(-50f, 50f)
        }
        tilt += (tiltTarget - tilt) * (1f - exp(-dt / 70f))
        q.face = face
        q.tilt = tilt

        // ---- paddling: slow when resting, quick when flying
        phase += dt * (0.0035 + 0.0010 * speedSm)
        val amp = (8f + 2f * speedSm).coerceIn(8f, 30f)
        q.front = amp * sin(phase).toFloat()
        q.far = amp * 0.85f * sin(phase - 0.5).toFloat()
        q.back = -amp * 0.8f * sin(phase + 0.4).toFloat()
        q.lift = (sin(now / 520.0) * 0.025).toFloat()

        // ---- idle gestures: only while resting and ready
        val bt = if (g.bumpAt == 0L) 100000L else now - g.bumpAt
        val ca = if (g.cheerAt == 0L) 100000L else now - g.cheerAt
        val oa = if (g.oopsAt == 0L) 100000L else now - g.oopsAt
        val idle = g.ready && !g.aiming && spd < 0.6f && !g.over && !g.paused && bt > 1000L && ca > 4000L && oa > 3000L
        if (idle) {
            if (nextGestureAt == 0L) nextGestureAt = now + 2000L + Random.nextLong(2000L)
            if (gesture < 0 && now >= nextGestureAt) {
                gesture = Random.nextInt(4)
                gestureAt = now
                gestureLen = when (gesture) { 0 -> 1700L; 1 -> 1500L; 2 -> 1100L; else -> 900L }
            }
            if (gesture >= 0) {
                val gt = (now - gestureAt).toFloat()
                if (gt > gestureLen) {
                    gesture = -1
                    nextGestureAt = now + 3500L + Random.nextLong(4500L)
                } else applyGesture(gesture, gt, gestureLen.toFloat())
            }
        } else {
            gesture = -1
            if (nextGestureAt == 0L || nextGestureAt < now + 2000L) nextGestureAt = now + 2500L + Random.nextLong(2500L)
        }

        // ---- crouching while he is pulled back to aim
        if (g.aiming && g.ready) {
            val a = g.aimPower.coerceIn(0f, 1f)
            q.sy *= 1f - 0.14f * a
            q.sx *= 1f + 0.07f * a
            q.headDy += 0.03f * a
            q.headAng += 7f * a
            q.front += 16f * a
            q.far += 14f * a
            q.back -= 10f * a
            q.shakeX += (sin(now / 28.0) * 0.018 * a * a).toFloat()
        }

        // ---- launch: stretch out, flippers snap back, happy squint
        val kt = if (g.kickAt == 0L) 100000L else now - g.kickAt
        if (kt < 700L) {
            val e = exp(-kt / 110f)
            q.sx *= 1f + 0.30f * e
            q.sy *= 1f - 0.20f * e
            q.front += 28f * e
            q.far += 24f * e
            q.back -= 18f * e
            q.headDx += 0.07f * e
            if (kt < 170L) q.eyes = 2
        }

        // ---- little happy moments (praise words)
        val ha = if (g.happyAt == 0L) 100000L else now - g.happyAt
        if (ha < 900L) {
            val e = sin(PI * ha / 900.0).toFloat().coerceAtLeast(0f)
            q.lift += 0.12f * e
            q.far += -35f * e + 14f * sin(ha / 70.0).toFloat() * e
            q.eyes = 2
        }

        // ---- hole cleared: a jump, a loop and a wave
        if (ca < 3500L) {
            val fade = if (ca > 2800L) (3500L - ca) / 700f else 1f
            val t = min(1f, ca / 800f)
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            q.spin += 360f * ease
            q.lift += 0.45f * sin(PI * t).toFloat()
            val w = min(1f, ca / 300f) * fade
            q.front += 20f * sin(ca / 65.0).toFloat() * w
            q.far += (-70f + 18f * sin(ca / 65.0).toFloat()) * w
            q.back -= 14f * sin(ca / 65.0).toFloat() * w
            q.headAng -= 8f * w
            if (fade > 0f) q.eyes = 2
        }

        // ---- over par / out of shots: head droops
        if (oa < 2600L) {
            var e = min(1f, oa / 400f)
            if (oa > 2000L) e *= (2600L - oa) / 600f
            q.headAng += 18f * e
            q.headDy += 0.07f * e
            q.headDx -= 0.03f * e
            q.sy *= 1f - 0.05f * e
            if (oa < 500L) q.eyes = 1
        }

        // ---- bump: tuck in, wobble, squint
        if (bt < 1000L) {
            val tuck = when {
                bt < 120L -> 1f
                bt < 520L -> { val k = (bt - 120L) / 400f; 1f - k * k * (3f - 2f * k) }
                else -> 0f
            }
            q.headDx -= 0.11f * tuck
            q.front += 22f * tuck
            q.far += 22f * tuck
            q.back -= 24f * tuck
            val wob = exp(-bt / 230f) * cos(bt / 50f)
            q.sx *= 1f - 0.20f * wob
            q.sy *= 1f + 0.16f * wob
            if (bt < 260L) q.eyes = 1
        }

        // ---- blinking
        if (now >= nextBlinkAt) {
            blinkUntil = now + 140L
            nextBlinkAt = now + 2400L + Random.nextLong(2600L)
        }
        if (q.eyes == 0 && now < blinkUntil) q.eyes = 1
    }

    private fun applyGesture(kind: Int, gt: Float, len: Float) {
        val q = pose
        val e = min(1f, min(gt / 250f, (len - gt) / 250f)).coerceIn(0f, 1f)
        when (kind) {
            0 -> {   // wave hello with the flipper by his head
                q.far = -75f * e + 16f * sin(gt / 85.0).toFloat() * e
                q.headAng -= 6f * e
                if (e > 0.3f) q.eyes = 2
            }
            1 -> {   // look around
                q.headAng += 14f * sin(gt * 2.0 * PI / len).toFloat()
                q.headDy -= 0.01f * e
            }
            2 -> {   // happy wiggle
                val s = sin(gt / 60.0).toFloat()
                q.spin += 7f * sin(gt / 70.0).toFloat() * e
                q.front += 20f * s * e
                q.far += 20f * s * e
                q.back -= 18f * s * e
                q.eyes = 2
            }
            else -> { // hop
                val h = sin(PI * gt / len).toFloat().coerceAtLeast(0f)
                q.lift += 0.28f * h
                q.sy *= 1f + 0.06f * h
                q.sx *= 1f - 0.04f * h
                q.front -= 25f * h
                q.far -= 25f * h
                q.eyes = 2
            }
        }
    }
}
