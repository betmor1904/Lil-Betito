package com.example.spray

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The start screen: an underwater title card with Octo and Betito, big buttons,
 * Octo's Field Guide (how to play) and a settings panel. Everything is drawn here.
 */
const val CREATOR = "A. Moreno"

class MenuView(ctx: Context, private val prefs: SharedPreferences) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint()
    private val path = Path()
    private val panel = RectF()
    private val guide = GuideBook(d)

    // what the activity does for us
    var onPlay: ((Boolean) -> Unit)? = null
    var onPickPhoto: (() -> Unit)? = null
    var onRemovePhoto: (() -> Unit)? = null
    var onPrivacy: (() -> Unit)? = null
    var onSoundChanged: (() -> Unit)? = null

    private enum class Screen { MAIN, GUIDE, SETTINGS, CONFIRM }
    private var screen = Screen.MAIN

    private class Btn(val id: String, val r: RectF = RectF())
    private val buttons = ArrayList<Btn>()
    private var pressedId: String? = null

    private var savedLevel = 1
    private var best = 0
    private val bubX = FloatArray(22)
    private val bubY = FloatArray(22)
    private val bubR = FloatArray(22)
    private val bubS = FloatArray(22)
    private val start = SystemClock.uptimeMillis()
    private var lastFrame = 0L

    init {
        refresh()
    }

    /** Re-reads saved progress (after coming back from a game). */
    fun refresh() {
        savedLevel = prefs.getInt("save_level", 1)
        best = prefs.getInt("best_score", 0)
    }

    /** Back: close a sub-screen. Returns false on the main screen (the app should close). */
    fun onBack(): Boolean {
        if (screen == Screen.MAIN) return false
        screen = if (screen == Screen.CONFIRM) Screen.SETTINGS else Screen.MAIN
        return true
    }

    fun openGuide() {
        guide.page = 0
        screen = Screen.GUIDE
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0E5A8A"), Color.parseColor("#0A3D62"), Color.parseColor("#031428")),
            null, Shader.TileMode.CLAMP)
        for (i in bubX.indices) {
            bubX[i] = Random.nextFloat() * w
            bubY[i] = Random.nextFloat() * h
            bubR[i] = (2 + Random.nextFloat() * 6) * d
            bubS[i] = (0.3f + Random.nextFloat() * 0.9f) * d
        }
    }

    // ============================================================
    // drawing
    // ============================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f else ((now - lastFrame) / 16.67f).coerceIn(0f, 3f)
        lastFrame = now
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        drawScenery(c, w, h, now, dt)
        buttons.clear()
        drawMain(c, w, h, now)
        when (screen) {
            Screen.GUIDE -> guide.draw(c, p, path, w, h, now)
            Screen.SETTINGS -> drawSettings(c, w, h, now)
            Screen.CONFIRM -> drawConfirm(c, w, h)
            else -> {}
        }
        postInvalidateOnAnimation()
    }

    private fun drawScenery(c: Canvas, w: Float, h: Float, now: Long, dt: Float) {
        p.style = Paint.Style.FILL
        for (i in 0 until 5) {
            val sway = sin(now / 2600.0 + i * 1.7).toFloat() * 40 * d
            val x = w * (0.08f + 0.22f * i) + sway
            path.reset()
            path.moveTo(x - 20 * d, 0f)
            path.lineTo(x + 34 * d, 0f)
            path.lineTo(x + 150 * d, h)
            path.lineTo(x + 40 * d, h)
            path.close()
            p.color = Color.argb(20, 180, 230, 255)
            c.drawPath(path, p)
        }
        // sand and seaweed along the bottom
        p.color = Color.parseColor("#1B3A4B")
        path.reset()
        path.moveTo(0f, h)
        path.lineTo(0f, h - 26 * d)
        var x = 0f
        while (x <= w + 40 * d) {
            path.quadTo(x + 30 * d, h - 40 * d, x + 60 * d, h - 26 * d)
            x += 60 * d
        }
        path.lineTo(w, h)
        path.close()
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 9) {
            val bx = w * (0.04f + 0.115f * i)
            val sway = sin(now / 600.0 + i).toFloat() * 10 * d
            p.strokeWidth = (5 - i % 2) * d
            p.color = if (i % 2 == 0) Color.argb(220, 46, 125, 50) else Color.argb(220, 102, 187, 106)
            path.reset()
            path.moveTo(bx, h - 20 * d)
            path.quadTo(bx - sway, h - 50 * d, bx + sway, h - (70 + (i % 3) * 18) * d)
            c.drawPath(path, p)
        }
        // bubbles
        p.strokeWidth = 1.3f * d
        p.color = Color.argb(120, 200, 240, 255)
        for (i in bubX.indices) {
            bubY[i] -= bubS[i] * dt
            bubX[i] += sin(now / 500.0 + i).toFloat() * 0.2f * d * dt
            if (bubY[i] < -10 * d) { bubY[i] = h + 10 * d; bubX[i] = Random.nextFloat() * w }
            c.drawCircle(bubX[i], bubY[i], bubR[i], p)
        }
        p.style = Paint.Style.FILL
        // Betito swimming across behind everything
        val k = ((now - start) % 14000L) / 14000f
        val tx = -60 * d + (w + 120 * d) * k
        val ty = h * 0.8f + sin(now / 700.0).toFloat() * 14 * d
        val heading = Math.toDegrees(kotlin.math.atan2(Math.cos(now / 700.0) * 14 * d / 700.0, ((w + 120 * d) / 14000.0))).toFloat()
        Art.turtle(c, p, path, tx, ty, 20 * d, heading, now)
    }

    private fun addBtn(id: String, l: Float, t: Float, r: Float, b: Float): RectF {
        val bt = Btn(id)
        bt.r.set(l, t, r, b)
        buttons.add(bt)
        return bt.r
    }

    private fun drawMain(c: Canvas, w: Float, h: Float, now: Long) {
        // title, left half
        val tx = w * 0.27f
        val ty = h * 0.36f
        val bounce = sin(now / 500.0).toFloat() * 3 * d
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        val size = min(56 * d, w * 0.075f)
        p.textSize = size
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 9 * d
        p.color = Color.parseColor("#4A148C")
        c.drawText("TURTLE", tx, ty - size * 0.95f + bounce, p)
        c.drawText("SHOT!", tx, ty + bounce, p)
        p.style = Paint.Style.FILL
        p.textSize = size
        p.color = Color.parseColor("#FFEB3B")
        p.setShadowLayer(6f, 2f, 3f, Color.argb(160, 0, 0, 0))
        c.drawText("TURTLE", tx, ty - size * 0.95f + bounce, p)
        p.color = Color.parseColor("#76FF03")
        c.drawText("SHOT!", tx, ty + bounce, p)
        p.clearShadowLayer()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 15 * d
        p.color = Art.NEON[((now / 400) % Art.NEON.size).toInt()]
        c.drawText("~ UNDERWATER GOLF ~", tx, ty + 28 * d, p)
        p.textSize = 12 * d
        p.color = Color.argb(200, 255, 255, 255)
        if (best > 0) c.drawText("BEST SCORE  $best", tx, ty + 50 * d, p)
        // Octo floats just left of the title
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textSize = size
        val titleW = p.measureText("TURTLE")
        p.typeface = Typeface.DEFAULT_BOLD
        Art.octo(c, p, path, (tx - titleW / 2f - 28 * d).coerceAtLeast(26 * d), ty - size * 0.75f, 20 * d, now)

        // buttons, right half
        val bw = min(250 * d, w * 0.36f)
        val bx = w * 0.72f - bw / 2f
        val bh = 50 * d
        val gap = 12 * d
        val total = 4 * bh + 3 * gap
        var by = (h - total) / 2f - 8 * d
        val active = screen == Screen.MAIN
        fun big(id: String, label: String, color: Int, ts: Float = 18f) {
            val r = if (active) addBtn(id, bx, by, bx + bw, by + bh) else RectF(bx, by, bx + bw, by + bh)
            Art.button(c, p, r, label, color, d, ts, pressedId == id)
            by += bh + gap
        }
        drawCredit(c, w, h)
        if (savedLevel > 1) {
            big("continue", "CONTINUE · HOLE $savedLevel", Color.parseColor("#2E7D32"), 17f)
            big("new", "NEW GAME", Color.parseColor("#1565C0"))
        } else {
            big("new", "PLAY", Color.parseColor("#2E7D32"), 22f)
        }
        big("guide", "HOW TO PLAY", Color.parseColor("#6A1B9A"))
        big("settings", "SETTINGS", Color.parseColor("#37474F"))
    }

    /** The maker's signature, bottom center of the start screen. */
    private fun drawCredit(c: Canvas, w: Float, h: Float) {
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.ITALIC)
        p.textSize = 13 * d
        val a = "Created with "
        val heart = "\u2665"
        val b = " by $CREATOR"
        val total = p.measureText(a) + p.measureText(heart) + p.measureText(b)
        var x = w / 2 - total / 2
        val y = h - 9 * d
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        p.color = Color.argb(230, 255, 255, 255)
        c.drawText(a, x, y, p)
        x += p.measureText(a)
        p.color = Color.parseColor("#FF4081")
        c.drawText(heart, x, y, p)
        x += p.measureText(heart)
        p.color = Color.parseColor("#FFD54F")
        c.drawText(b, x, y, p)
        p.clearShadowLayer()
        p.typeface = Typeface.DEFAULT_BOLD
    }

    private fun drawPanel(c: Canvas, w: Float, h: Float, pw: Float, ph: Float, title: String, now: Long) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(190, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        panel.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        p.color = Color.parseColor("#0D2B4F")
        c.drawRoundRect(panel, 22 * d, 22 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.parseColor("#FFD54F")
        c.drawRoundRect(panel, 22 * d, 22 * d, p)
        p.style = Paint.Style.FILL
        Art.octo(c, p, path, panel.left + 34 * d, panel.top + 30 * d, 15 * d, now)
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textSize = 22 * d
        p.color = Color.WHITE
        c.drawText(title, panel.left + 62 * d, panel.top + 38 * d, p)
        p.typeface = Typeface.DEFAULT_BOLD
    }

    private fun drawSettings(c: Canvas, w: Float, h: Float, now: Long) {
        val pw = min(560 * d, w - 32 * d)
        val ph = min(330 * d, h - 24 * d)
        drawPanel(c, w, h, pw, ph, "SETTINGS", now)
        // close
        val cr = addBtn("close", panel.right - 52 * d, panel.top + 10 * d, panel.right - 12 * d, panel.top + 50 * d)
        p.color = Color.argb(220, 20, 20, 30)
        c.drawCircle(cr.centerX(), cr.centerY(), 17 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.WHITE
        val k = 6 * d
        c.drawLine(cr.centerX() - k, cr.centerY() - k, cr.centerX() + k, cr.centerY() + k, p)
        c.drawLine(cr.centerX() + k, cr.centerY() - k, cr.centerX() - k, cr.centerY() + k, p)
        p.style = Paint.Style.FILL

        val sound = prefs.getBoolean("sound", true)
        val tips = prefs.getBoolean("tips", true)
        val items = listOf(
            Triple("sound", if (sound) "SOUND: ON" else "SOUND: OFF", if (sound) "#2E7D32" else "#616161"),
            Triple("tips", if (tips) "OCTO'S TIPS: ON" else "OCTO'S TIPS: OFF", if (tips) "#6A1B9A" else "#616161"),
            Triple("tipsagain", "SHOW TIPS AGAIN", "#1565C0"),
            Triple("photo", "BETO'S PHOTO", "#00838F"),
            Triple("nophoto", "REMOVE PHOTO", "#455A64"),
            Triple("reset", "RESET PROGRESS", "#C62828"),
        )
        val gx = panel.left + 20 * d
        val gw = panel.width() - 40 * d
        val bw = (gw - 14 * d) / 2f
        val bh = 44 * d
        val top = panel.top + 64 * d
        for ((i, it) in items.withIndex()) {
            val col = i % 2
            val row = i / 2
            val l = gx + col * (bw + 14 * d)
            val t = top + row * (bh + 12 * d)
            val r = addBtn(it.first, l, t, l + bw, t + bh)
            Art.button(c, p, r, it.second, Color.parseColor(it.third), d, 14f, pressedId == it.first)
        }
        // footer: privacy policy link + version
        val fy = panel.bottom - 18 * d
        p.textAlign = Paint.Align.LEFT
        p.textSize = 12 * d
        p.color = Color.parseColor("#80D8FF")
        val label = "Privacy policy"
        c.drawText(label, panel.left + 22 * d, fy, p)
        val tw = p.measureText(label)
        addBtn("privacy", panel.left + 14 * d, fy - 22 * d, panel.left + 30 * d + tw, fy + 10 * d)
        p.textAlign = Paint.Align.RIGHT
        p.color = Color.argb(140, 255, 255, 255)
        c.drawText("Created with \u2665 by $CREATOR  ·  v${BuildConfig.VERSION_NAME}", panel.right - 22 * d, fy, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawConfirm(c: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        drawPanel(c, w, h, min(420 * d, w - 32 * d), 190 * d, "START OVER?", now)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 14 * d
        p.color = Color.WHITE
        c.drawText("This sends you back to hole 1.", panel.centerX(), panel.top + 80 * d, p)
        c.drawText("Your best score is kept.", panel.centerX(), panel.top + 100 * d, p)
        val bw = 150 * d
        val by = panel.bottom - 64 * d
        val a = addBtn("cancel", panel.centerX() - bw - 8 * d, by, panel.centerX() - 8 * d, by + 44 * d)
        Art.button(c, p, a, "CANCEL", Color.parseColor("#37474F"), d, 15f, pressedId == "cancel")
        val b = addBtn("doreset", panel.centerX() + 8 * d, by, panel.centerX() + bw + 8 * d, by + 44 * d)
        Art.button(c, p, b, "RESET", Color.parseColor("#C62828"), d, 15f, pressedId == "doreset")
    }

    // ============================================================
    // touch
    // ============================================================

    private fun hit(x: Float, y: Float): String? = buttons.lastOrNull { it.r.contains(x, y) }?.id

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        if (screen == Screen.GUIDE) {
            if (e.actionMasked == MotionEvent.ACTION_UP && guide.tap(x, y)) screen = Screen.MAIN
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressedId = hit(x, y)
            MotionEvent.ACTION_UP -> {
                val id = hit(x, y)
                if (id != null && id == pressedId) act(id)
                pressedId = null
            }
            MotionEvent.ACTION_CANCEL -> pressedId = null
        }
        return true
    }

    private fun act(id: String) {
        playSoundClick()
        when (id) {
            "continue" -> onPlay?.invoke(true)
            "new" -> onPlay?.invoke(false)
            "guide" -> openGuide()
            "settings" -> screen = Screen.SETTINGS
            "close" -> screen = Screen.MAIN
            "sound" -> {
                prefs.edit().putBoolean("sound", !prefs.getBoolean("sound", true)).apply()
                onSoundChanged?.invoke()
            }
            "tips" -> prefs.edit().putBoolean("tips", !prefs.getBoolean("tips", true)).apply()
            "tipsagain" -> {
                prefs.edit().remove("tips_seen").putBoolean("tips", true).apply()
                android.widget.Toast.makeText(context, "Octo will explain everything again!", android.widget.Toast.LENGTH_SHORT).show()
            }
            "photo" -> onPickPhoto?.invoke()
            "nophoto" -> onRemovePhoto?.invoke()
            "reset" -> screen = Screen.CONFIRM
            "cancel" -> screen = Screen.SETTINGS
            "doreset" -> {
                prefs.edit().putInt("save_level", 1).putInt("save_score", 0).putInt("max_level", 1).apply()
                refresh()
                screen = Screen.MAIN
            }
            "privacy" -> onPrivacy?.invoke()
        }
    }

    var clickSound: (() -> Unit)? = null
    private fun playSoundClick() {
        clickSound?.invoke()
    }
}
