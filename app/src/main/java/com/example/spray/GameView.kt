package com.example.spray

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the whole game in one view (sea, maze, currents, shark, turtle, effects, HUD, controls)
 * and turns touches into input. Slingshot: touch anywhere, pull back, let go.
 */
class GameView(ctx: Context, private val g: Game) : View(ctx) {
    init { Sprites.load(ctx); TurtleRig.load(ctx); Yarn.load(ctx) }
    private val anim = TurtleAnim()
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val water = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint()
    private val bgTint = Paint()
    private val seaP = Paint()
    private val wallP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rockP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeP = Paint()
    private val path = Path()
    private val starPath = Path()
    private val rect = RectF()
    private val src = Rect()
    private val clip = Path()
    private val photo: Bitmap? = try {
        BitmapFactory.decodeFile(File(ctx.filesDir, "beto.png").path)
    } catch (e: Exception) {
        null
    }
    private val neon = intArrayOf(
        Color.parseColor("#FFEB3B"), Color.parseColor("#FF4081"), Color.parseColor("#00E5FF"),
        Color.parseColor("#76FF03"), Color.parseColor("#E040FB")
    )

    // slingshot touch state: put a finger down anywhere, pull back, let go
    private var aimPtr = -1
    private var aimSX = 0f
    private var aimSY = 0f
    private var aimTX = 0f
    private var aimTY = 0f
    private val maxPull get() = 125 * d
    private val deadPull get() = 24 * d

    var onResize: ((Int, Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0A3D62"), Color.parseColor("#06274A"), Color.parseColor("#031428")),
            null, Shader.TileMode.CLAMP)
        bgTint.shader = bg.shader
        bgTint.alpha = 95
        seaP.shader = Yarn.sea
        wallP.shader = Yarn.wall
        rockP.shader = Yarn.rock
        holeP.shader = Yarn.hole
        onResize?.invoke(w, h)
    }

    // ============================================================
    // touch
    // ============================================================

    /** Set by the activity: go back to the main menu. */
    var onQuit: (() -> Unit)? = null

    private val pauseR = RectF()
    private val btnR = Array(5) { RectF() }
    private val skipR = RectF()
    private var turnPtr = -1
    private val trayR = Array(ShellKinds.COUNT) { RectF() }
    private var pulledThisTouch = false
    private val bombBtnR = RectF()
    private var guideOpen = false
    private val guide = GuideBook(d)
    private var pressed = -1
    private var tapDownAt = 0L

    private fun cancelAim() {
        aimPtr = -1
        g.aiming = false
        g.launchQueued = false
    }

    fun pause() {
        if (!g.started) return
        cancelAim()
        g.paused = true
    }

    /** Back button / gesture: closes whatever is open, otherwise pauses. */
    fun onBack() {
        when {
            guideOpen -> guideOpen = false
            g.paused -> g.paused = false
            g.tip != null -> g.dismissTip()
            else -> pause()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x0 = e.getX(e.actionIndex)
        val y0 = e.getY(e.actionIndex)
        // the field guide, opened from the pause menu
        if (guideOpen) {
            if (e.actionMasked == MotionEvent.ACTION_UP && guide.tap(x0, y0)) guideOpen = false
            return true
        }
        // pause menu buttons
        if (g.paused) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressed = btnR.indexOfFirst { it.contains(x0, y0) }
                MotionEvent.ACTION_UP -> {
                    val hit = btnR.indexOfFirst { it.contains(x0, y0) }
                    if (hit >= 0 && hit == pressed) {
                        g.sfxClick()
                        when (hit) {
                            0 -> g.paused = false
                            1 -> { guide.page = 0; guideOpen = true }
                            2 -> g.restartHole()
                            3 -> g.setSound(!g.soundOn)
                            4 -> onQuit?.invoke()
                        }
                    }
                    pressed = -1
                }
                MotionEvent.ACTION_CANCEL -> pressed = -1
            }
            return true
        }
        // Octo is explaining something: tap to carry on
        if (g.tip != null) {
            cancelAim()
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (skipR.contains(x0, y0)) g.skipAllTips() else g.dismissTip()
            }
            return true
        }
        // the BOMB button
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !g.over && bombBtnR.contains(x0, y0)) {
            g.toggleBombMode(g.clock)
            return true
        }
        // cut a swinging rope
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !g.over && g.tryCutRope(x0, y0, g.clock)) return true
        // a shell is being dragged (from the tray, or one that was already placed)
        if (dragPtr != -1) {
            val i = e.findPointerIndex(dragPtr).let { if (it >= 0) it else 0 }
            val fx = e.getX(i)
            val fy = e.getY(i)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragMove(fx, fy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragEnd(fx, fy, e.actionMasked == MotionEvent.ACTION_CANCEL)
                MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == dragPtr) dragEnd(fx, fy, false)
            }
            return true
        }
        // setup buttons and shell drag start
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !g.over) {
            if (resetR.contains(x0, y0) && g.canSetUp(g.clock)) {
                g.sfxClick()
                g.resetSetup(g.clock)
                return true
            }
            val ti = trayR.indexOfFirst { it.contains(x0, y0) }
            if (ti >= 0) {
                if (g.pouch[ti] > 0 && g.canSetUp(g.clock)) {
                    dragPtr = e.getPointerId(0)
                    dragKind = ti
                    dragShell = null
                    dragX = x0
                    dragY = y0 - dragLift
                    dragFromX = x0
                    dragFromY = y0
                    dragMoved = false
                    g.sfxClick()
                } else {
                    g.popup = "NO ${ShellKinds.names[ti]} SHELLS LEFT"
                    g.popupAt = g.clock
                }
                return true
            }
            val grab = if (g.canSetUp(g.clock)) g.shellAt(x0, y0) else null
            if (grab != null) {
                dragPtr = e.getPointerId(0)
                dragKind = grab.kind
                dragShell = grab
                dragOffX = grab.x - x0
                dragOffY = grab.y - y0
                dragX = grab.x
                dragY = grab.y
                dragFromX = x0
                dragFromY = y0
                dragMoved = false
                return true
            }
        }
        // the pause button
        if (e.actionMasked == MotionEvent.ACTION_DOWN && pauseR.contains(x0, y0)) {
            g.sfxClick()
            pause()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                g.skipIntro(g.clock)
                if (aimPtr != -1 && id != aimPtr && g.aiming) {
                    // a second finger tapped while you were aiming: cancel, no shot spent
                    cancelAim()
                    announceCancel()
                    return true
                }
                if (turnPtr == -1 && g.beginTurnGrab(x, y, g.clock)) {
                    turnPtr = id
                } else if (aimPtr == -1) {
                    tapDownAt = g.clock
                    pulledThisTouch = false
                    aimPtr = id
                    aimSX = x
                    aimSY = y
                    aimTX = x
                    aimTY = y
                    updateAim()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (turnPtr != -1) {
                    val ti = e.findPointerIndex(turnPtr)
                    if (ti >= 0) g.setTurnDir(e.getX(ti), e.getY(ti))
                    return true
                }
                val i = e.findPointerIndex(aimPtr)
                if (i >= 0) {
                    aimTX = e.getX(i)
                    aimTY = e.getY(i)
                    updateAim()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                val id = if (cancel) -2 else e.getPointerId(e.actionIndex)
                if (turnPtr != -1 && (cancel || id == turnPtr)) {
                    if (cancel) g.turnGrab = false else g.endTurnGrab(g.clock)
                    turnPtr = -1
                    return true
                }
                if (id == aimPtr || cancel) {
                    // let go = launch (dragging back inside the small circle cancels)
                    if (!cancel && g.aiming) g.launchQueued = true
                    else if (!cancel && pulledThisTouch) announceCancel()   // dragged back into the circle and let go
                    else if (!cancel && g.clock - tapDownAt < 450L && hypot(aimTX - aimSX, aimTY - aimSY) < deadPull) g.tapShell(aimSX, aimSY, g.clock)
                    aimPtr = -1
                    g.aiming = false
                }
            }
        }
        return true
    }

    // ---------- shell drag and drop ----------
    private var dragPtr = -1
    private var dragKind = -1
    private var dragShell: ShellObj? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var dragFromX = 0f
    private var dragFromY = 0f
    private var dragMoved = false
    private var dragValid = true
    private val dragLift get() = 44 * d          // the shell rides above your thumb so you can see it
    private val resetR = RectF()

    private fun overTray(x: Float, y: Float) = trayR.any { !it.isEmpty && it.contains(x, y) }

    private fun dragMove(fx: Float, fy: Float) {
        if (hypot(fx - dragFromX, fy - dragFromY) > 10 * d) dragMoved = true
        val sh = dragShell
        if (sh == null) {
            dragX = fx
            dragY = fy - dragLift
            dragValid = g.shellSpotFree(dragX, dragY, null, dragKind, g.lastPadAng) && !overTray(fx, fy)
        } else {
            dragX = fx + dragOffX
            dragY = fy + dragOffY
            dragValid = g.moveShell(sh, dragX, dragY)   // the shell stays at the last free spot
        }
    }

    private fun dragEnd(fx: Float, fy: Float, cancel: Boolean) {
        val sh = dragShell
        if (!cancel) {
            if (sh == null) {
                // new shell from the tray: drop it if the spot is free (dragging back onto the tray cancels)
                if (dragMoved && !overTray(fx, fy)) g.placeShell(dragKind, dragX, dragY, g.clock)
            } else if (overTray(fx, fy)) {
                g.takeBackShell(sh)   // dropped on the tray: back in the pouch
            } else if (!dragMoved) {
                if (sh.paddle) g.rotateShell(sh, g.clock) else g.takeBackShell(sh)   // tap: turn a paddle
            }
        }
        dragPtr = -1
        dragKind = -1
        dragShell = null
        dragMoved = false
    }

    private fun announceCancel() {
        g.sfxClick()
        g.popup = "SHOT CANCELLED - NOTHING USED"
        g.popupAt = g.clock
    }

    /** Pull vector = where you touched minus where your finger is now. He launches the opposite way you drag. */
    private fun updateAim() {
        val dx = aimSX - aimTX
        val dy = aimSY - aimTY
        val len = hypot(dx, dy)
        if (len < deadPull) {
            g.aiming = false
            return
        }
        g.aiming = true
        pulledThisTouch = true
        g.aimDX = dx / len
        g.aimDY = dy / len
        g.aimPower = ((len - deadPull) / (maxPull - deadPull)).coerceIn(0f, 1f)
    }

    // ============================================================
    // drawing
    // ============================================================

    /** Comic sound-effect patches that pop out, wobble and fade. */
    private fun drawPops(c: Canvas, now: Long) {
        if (!Yarn.loaded || g.pops.isEmpty()) return
        for (pp in g.pops) {
            val age = now - pp.at
            if (age < 0 || age > 800L) continue
            val sc = when {
                age < 140L -> 0.3f + 1.0f * (age / 140f)
                age < 260L -> 1.3f - 0.3f * ((age - 140L) / 120f)
                age < 600L -> 1f
                else -> 1f - 0.25f * ((age - 600L) / 200f)
            }
            val a = if (age < 600L) 255 else (255 * (1f - (age - 600L) / 200f)).toInt().coerceIn(0, 255)
            val wob = sin(age / 55.0).toFloat() * 3f * (1f - age / 800f)
            val x = pp.x.coerceIn(pp.size * 0.5f, width - pp.size * 0.5f)
            val y = (pp.y - age / 800f * 10 * d).coerceIn(pp.size * 0.4f, height - pp.size * 0.4f)
            Yarn.drawPop(c, pp.kind, x, y, pp.size * sc, a, pp.rot + wob)
        }
    }

    /** After a hole is cleared Betito zooms across the whole screen in front of everything, big, with a spin in the middle. */
    private fun drawFlyBy(c: Canvas, w: Float, h: Float, now: Long) {
        if (!Yarn.loaded || g.cheerAt == 0L) return
        Yarn.drawParty(c, w, h, now, g.cheerAt)
        val age = now - g.cheerAt
        val dur = 3000L
        if (age < 0 || age > dur) return
        val t = age / dur.toFloat()
        val e = t * t * (3f - 2f * t) * 0.35f + t * 0.65f          // eased, but keeps moving
        val size = h * 0.62f
        val x = -size * 0.6f + (w + size * 1.2f) * e
        val y = h * 0.52f - sin(t * 3.1416f) * h * 0.16f
        val pose = when {
            t < 0.35f -> 0
            t < 0.62f -> 5
            t < 0.85f -> 4
            else -> 0
        }
        val rot = if (pose == 5) (t - 0.35f) / 0.27f * 720f else -cos(t * 3.1416f) * 14f
        // bubble wake
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        for (i in 1..9) {
            p.color = Color.argb((190 * (1f - i / 10f)).toInt(), 225, 245, 255)
            c.drawCircle(x - size * 0.4f - i * 22 * d, y + sin(now / 200.0 + i).toFloat() * 10 * d, (11 - i) * d, p)
        }
        p.style = Paint.Style.FILL
        Yarn.drawPose(c, pose, x, y, size, rot)
    }

    /** Faint yarn squiggles drifting in the sea so it feels like water, kept pale so walls and shells still pop. */
    private fun drawWaterDecals(c: Canvas, w: Float, h: Float, now: Long) {
        val xs = floatArrayOf(0.12f, 0.33f, 0.55f, 0.78f, 0.90f, 0.22f, 0.66f, 0.45f, 0.06f)
        val ys = floatArrayOf(0.30f, 0.75f, 0.22f, 0.68f, 0.28f, 0.52f, 0.46f, 0.86f, 0.80f)
        val ids = intArrayOf(0, 3, 5, 1, 7, 2, 4, 6, 2)
        for (i in xs.indices) {
            val bob = sin(now / 1700.0 + i * 1.9).toFloat() * 4 * d
            Yarn.drawDecal(c, ids[i], w * xs[i], h * ys[i] + bob, (if (ids[i] == 2) 34 else 96) * d, 46, sin(now / 2600.0 + i).toFloat() * 3f)
        }
    }

    override fun onDraw(c: Canvas) {
        val now = g.clock
        val w = width.toFloat()
        val h = height.toFloat()
        if (Yarn.loaded) {
            if (Yarn.drawReef(c, w, h)) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(135, 6, 40, 70)   // darken so shells and walls stay readable
                c.drawRect(0f, 0f, w, h, p)
            } else {
                c.drawRect(0f, 0f, w, h, seaP)      // knitted sea
                c.drawRect(0f, 0f, w, h, bgTint)    // deeper toward the bottom
            }
            drawWaterDecals(c, w, h, now)
        } else c.drawRect(0f, 0f, w, h, bg)
        if (!g.started) return

        // screen shake on big moments
        val sAge = now - g.shakeAt
        val shaking = g.shakeAt != 0L && sAge < 320L
        if (shaking) {
            val a = g.shakeAmp * (1f - sAge / 320f)
            c.save()
            c.translate((Math.random().toFloat() - 0.5f) * 2 * a, (Math.random().toFloat() - 0.5f) * 2 * a)
        }
        drawLightRays(c, w, h, now)
        drawFish(c)
        drawCritters(c, w, h, now)
        drawBubbles(c)
        drawWater(c, now)
        drawWeeds(c, now)
        drawRails(c, now)
        drawWalls(c, now)
        drawPickups(c, now)
        drawTargets(c, now)
        drawFlipIcons(c, now)
        for (s in g.sharks) drawShark(c, s, now)
        drawShells(c, now)
        drawAimPreview(c, now)
        drawTracer(c)
        drawTurtle(c, now)
        drawParticles(c)
        drawPops(c, now)
        if (shaking) c.restore()
        drawHud(c, w, now)
        drawTracker(c, w, now)
        drawOcto(c, w, now)
        drawControls(c, now)
        drawShellButton(c, now)
        drawPraise(c, w, h, now)
        drawMessages(c, w, h, now)
        drawFlyBy(c, w, h, now)
        drawIntro(c, w, h, now)
        if (g.flash > 0f) {
            val fc = if (g.flashColor != 0) g.flashColor else Color.WHITE
            p.style = Paint.Style.FILL
            p.color = Color.argb((180 * g.flash).toInt().coerceIn(0, 255), Color.red(fc), Color.green(fc), Color.blue(fc))
            c.drawRect(0f, 0f, w, h, p)
        }
        if (w < h) {
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = 16 * d
            c.drawText("Turn your phone sideways for more room!", w / 2, h * 0.55f, p)
        }
        val real = SystemClock.uptimeMillis()
        drawPauseButton(c, w)
        g.tip?.let { drawTip(c, w, h, it, real) }
        if (g.paused) drawPauseMenu(c, w, h, real)
        if (guideOpen) guide.draw(c, p, path, w, h, real)
    }

    // ============================================================
    // pause button + pause menu
    // ============================================================

    private fun drawPauseButton(c: Canvas, w: Float) {
        val r = 18 * d
        val cx = w - 12 * d - r
        val cy = g.topInset + 4 * d + r
        pauseR.set(cx - r - 8 * d, cy - r - 8 * d, cx + r + 8 * d, cy + r + 8 * d)
        p.style = Paint.Style.FILL
        p.color = Color.argb(210, 20, 20, 30)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r - d, p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(cx - 6 * d, cy - 7 * d, cx - 2 * d, cy + 7 * d, d, d, p)
        c.drawRoundRect(cx + 2 * d, cy - 7 * d, cx + 6 * d, cy + 7 * d, d, d, p)
    }

    private fun drawPauseMenu(c: Canvas, w: Float, h: Float, real: Long) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(185, 0, 8, 24)
        c.drawRect(0f, 0f, w, h, p)
        val top = h * 0.12f
        Art.octo(c, p, path, w / 2 - 92 * d, top + 18 * d, 17 * d, real)
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textSize = 30 * d
        p.color = Color.WHITE
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        c.drawText("PAUSED", w / 2 - 62 * d, top + 26 * d, p)
        p.clearShadowLayer()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 12 * d
        p.color = Color.argb(200, 255, 255, 255)
        c.drawText("HOLE ${g.level}  ·  SHOT ${g.shots} / PAR ${g.par}  ·  SCORE ${g.score}", w / 2 - 62 * d, top + 44 * d, p)

        val bw = min(210 * d, (w - 60 * d) / 2f)
        val bh = 46 * d
        val gap = 12 * d
        val y0 = top + 70 * d
        val labels = arrayOf("RESUME", "ITEM GUIDE", "RESTART HOLE", if (g.soundOn) "SOUND: ON" else "SOUND: OFF", "QUIT TO MENU")
        val colors = intArrayOf(
            Color.parseColor("#2E7D32"), Color.parseColor("#1565C0"), Color.parseColor("#EF6C00"),
            Color.parseColor("#6A1B9A"), Color.parseColor("#C62828")
        )
        for (i in 0 until 5) {
            val col = i % 2
            val row = i / 2
            val bx = if (i == 4) w / 2 - bw / 2 else w / 2 - bw - gap / 2 + col * (bw + gap)
            val by = y0 + row * (bh + gap)
            btnR[i].set(bx, by, bx + bw, by + bh)
            Art.button(c, p, btnR[i], labels[i], colors[i], d, 15f, pressed == i)
        }
    }

    // ============================================================
    // Octo's tips
    // ============================================================

    private val spot = Path()

    /** Dims everything except the thing Octo is talking about, and shows his speech bubble. */
    private fun drawTip(c: Canvas, w: Float, h: Float, t: Game.Tip, real: Long) {
        val e = Guide.find(t.key) ?: return
        val age = real - g.tipShownReal
        val grow = (age / 300f).coerceIn(0f, 1f)
        val rad = t.rad * (1.6f - 0.6f * grow)
        spot.reset()
        spot.fillType = Path.FillType.EVEN_ODD
        spot.addRect(0f, 0f, w, h, Path.Direction.CW)
        spot.addCircle(t.x, t.y, rad, Path.Direction.CW)
        p.style = Paint.Style.FILL
        p.color = Color.argb((175 * grow).toInt(), 0, 6, 20)
        c.drawPath(spot, p)
        val pulse = 0.5f + 0.5f * sin(real / 200.0).toFloat()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.argb((160 + 90 * pulse).toInt(), Color.red(e.color), Color.green(e.color), Color.blue(e.color))
        c.drawCircle(t.x, t.y, rad + pulse * 4 * d, p)
        p.style = Paint.Style.FILL

        // the bubble goes on the side away from the spotlight
        val bw = min(320 * d, w * 0.44f)
        val onRight = t.x < w / 2
        val bx = if (onRight) w - bw - 24 * d else 24 * d
        p.typeface = Typeface.DEFAULT
        p.textSize = 14 * d
        val textX = bx + 16 * d
        val lines = Art.wrap(p, e.text, bw - 32 * d)
        val bh = 64 * d + lines.size * 19 * d + 34 * d
        val by = ((h - bh) / 2f - 20 * d).coerceIn(g.topInset + 8 * d, h - bh - 70 * d)
        val sc = 0.7f + 0.3f * (age / 220f).coerceIn(0f, 1f)
        c.save()
        c.scale(sc, sc, bx + bw / 2, by + bh / 2)

        // pointer from the bubble to the spotlight
        val px = if (onRight) bx else bx + bw
        val py = (by + bh / 2).coerceIn(by + 20 * d, by + bh - 20 * d)
        val ang = kotlin.math.atan2(t.y - py, t.x - px)
        val ex = t.x - cos(ang) * (rad + 6 * d)
        val ey = t.y - sin(ang) * (rad + 6 * d)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.pathEffect = dash
        p.color = Color.argb(220, 255, 255, 255)
        c.drawLine(px, py, ex, ey, p)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        c.drawCircle(ex, ey, 5 * d, p)

        rect.set(bx, by, bx + bw, by + bh)
        p.color = Color.argb(245, 255, 255, 255)
        c.drawRoundRect(rect, 18 * d, 18 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4 * d
        p.color = e.color
        c.drawRoundRect(rect, 18 * d, 18 * d, p)
        p.style = Paint.Style.FILL

        // icon + title
        val ix = bx + 34 * d
        val iy = by + 34 * d
        p.color = Color.parseColor("#0D2B4F")
        c.drawCircle(ix, iy, 22 * d, p)
        Art.icon(c, p, path, e.key, ix, iy, 16 * d, real, d)
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.color = Color.parseColor("#4A148C")
        p.textSize = 18 * d
        val title = e.title
        if (p.measureText(title) > bw - 80 * d) p.textSize = 18 * d * (bw - 80 * d) / p.measureText(title)
        c.drawText(title, ix + 32 * d, iy - 2 * d, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 10 * d
        p.color = Color.argb(160, 74, 20, 140)
        c.drawText("OCTO SAYS:", ix + 32 * d, iy + 14 * d, p)

        // the explanation
        p.typeface = Typeface.DEFAULT
        p.textSize = 14 * d
        p.color = Color.parseColor("#1A1035")
        var ty = by + 64 * d + 12 * d
        for (ln in lines) {
            c.drawText(ln, textX, ty, p)
            ty += 19 * d
        }

        // tap to continue / skip
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 12 * d
        val blink = if (age > 450L) (0.55f + 0.45f * sin(real / 250.0).toFloat()) else 0.25f
        p.color = Color.argb((255 * blink).toInt(), 46, 125, 50)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("TAP TO CONTINUE ▸", bx + bw - 16 * d, by + bh - 14 * d, p)
        p.textAlign = Paint.Align.LEFT
        p.color = Color.argb(150, 90, 90, 110)
        p.textSize = 11 * d
        c.drawText("SKIP TIPS", textX, by + bh - 14 * d, p)
        c.restore()
        skipR.set(bx, by + bh - 34 * d, bx + 110 * d, by + bh + 4 * d)

        // Octo himself, by the bubble
        val ox = if (onRight) bx + bw - 30 * d else bx + 30 * d
        val tipFace = when (t.key) {
            "shark", "red", "score" -> Sprites.FACE_SURPRISE
            "turn", "shell", "buoy", "slide" -> Sprites.FACE_WINK
            "combo", "star", "apple", "speed", "lock", "goal" -> Sprites.FACE_HAPPY
            else -> Sprites.FACE_SMILE
        }
        Art.octo(c, p, path, ox, by + bh + 26 * d, 22 * d, real, age < 1600L, tipFace)
    }

    private fun drawLightRays(c: Canvas, w: Float, h: Float, now: Long) {
        p.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val sway = sin(now / 2400.0 + i * 1.7).toFloat() * 40 * d
            val x = w * (0.15f + 0.23f * i) + sway
            path.reset()
            path.moveTo(x - 20 * d, 0f)
            path.lineTo(x + 30 * d, 0f)
            path.lineTo(x + 140 * d, h)
            path.lineTo(x + 40 * d, h)
            path.close()
            p.color = Color.argb(18, 180, 230, 255)
            c.drawPath(path, p)
        }
    }

    /** Crochet friends drifting behind the play field. */
    private fun drawCritters(c: Canvas, w: Float, h: Float, now: Long) {
        if (!Yarn.loaded) return
        val t = now / 1000.0
        // jellyfish drifts up and down, pulsing
        val jx = w * 0.22f + sin(t * 0.3).toFloat() * 18 * d
        val jy = h * 0.5f + sin(t * 0.5).toFloat() * h * 0.12f
        val pulse = sin(t * 3.0).toFloat()
        Yarn.drawPal(c, 6, jx, jy, 64 * d, sin(t * 0.7).toFloat() * 5f, false, 1f - 0.05f * pulse, 1f + 0.08f * pulse, 150)
        // seahorse bobs near the right
        Yarn.drawPal(c, 1, w * 0.78f, h * 0.62f + sin(t * 1.1).toFloat() * 10 * d, 60 * d,
            sin(t * 1.1).toFloat() * 6f, false, 1f, 1f, 150)
        // crab scuttles along the bottom
        val k = ((now % 30000L) / 30000f)
        val tri = if (k < 0.5f) k * 2 else (1 - k) * 2
        val cx = w * (0.1f + 0.8f * tri)
        val wob = sin(now / 90.0).toFloat() * 3f
        Yarn.drawPal(c, 3, cx, h - 22 * d, 38 * d, wob, k >= 0.5f, 1f, 1f, 190)
        // dolphin leaps in an arc every so often
        val per = 14000L
        val dk = (now % per) / per.toFloat()
        if (dk < 0.22f) {
            val u = dk / 0.22f
            val dx = w * (-0.1f + 1.2f * u)
            val dy = h * 0.55f - sin(u * Math.PI).toFloat() * h * 0.38f
            Yarn.drawPal(c, 5, dx, dy, 70 * d, (u - 0.5f) * 90f, false, 1f, 1f, 170)
        }
    }

    private fun drawFish(c: Canvas) {
        p.style = Paint.Style.FILL
        for (f in g.fish) {
            val dir = if (f.vx >= 0) 1f else -1f
            if (Yarn.drawPal(c, 2, f.x, f.y, f.size * 2.4f, sin(f.x / (20 * d)) * 4f, dir < 0, 1f, 1f, 170)) continue
            p.color = Color.argb(150, Color.red(f.color), Color.green(f.color), Color.blue(f.color))
            c.drawOval(f.x - f.size, f.y - f.size * 0.5f, f.x + f.size, f.y + f.size * 0.5f, p)
            path.reset()
            path.moveTo(f.x - dir * f.size * 0.8f, f.y)
            path.lineTo(f.x - dir * f.size * 1.6f, f.y - f.size * 0.5f)
            path.lineTo(f.x - dir * f.size * 1.6f, f.y + f.size * 0.5f)
            path.close()
            c.drawPath(path, p)
            p.color = Color.argb(180, 0, 0, 0)
            c.drawCircle(f.x + dir * f.size * 0.5f, f.y - f.size * 0.1f, f.size * 0.12f, p)
        }
    }

    private fun drawBubbles(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = Color.argb(110, 200, 240, 255)
        for (b in g.bubbles) c.drawCircle(b.x, b.y, b.rad, p)
        p.style = Paint.Style.FILL
    }

    private fun zoneColor(kind: Int): IntArray = when (kind) {
        WaterZone.UP -> intArrayOf(0, 229, 255)
        WaterZone.SIDE -> intArrayOf(29, 233, 182)
        WaterZone.RIP -> intArrayOf(255, 82, 82)
        else -> intArrayOf(179, 136, 255)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val k = 9 * d
        val qx = -dy * k
        val qy = dx * k
        c.drawLine(x, y, x - dx * k + qx, y - dy * k + qy, water)
        c.drawLine(x, y, x - dx * k - qx, y - dy * k - qy, water)
    }

    /** Currents: tinted patches with streaks sliding along the flow and a big arrow. */
    private fun drawWater(c: Canvas, now: Long) {
        for (z in g.zones) {
            val pw = z.power
            if (pw < 0.03f) continue
            val col = zoneColor(z.kind)
            water.style = Paint.Style.FILL
            water.color = Color.argb((40 * pw).toInt(), col[0], col[1], col[2])
            if (z.kind == WaterZone.WHIRL) {
                c.drawCircle(z.cx, z.cy, z.rad, water)
                water.style = Paint.Style.STROKE
                water.strokeWidth = 3 * d
                water.strokeCap = Paint.Cap.ROUND
                val turn = (now % 100000L) / 1000f * 160f * z.spin
                for (i in 0 until 4) {
                    val rr = z.rad * (0.3f + 0.18f * i)
                    rect.set(z.cx - rr, z.cy - rr, z.cx + rr, z.cy + rr)
                    water.color = Color.argb((150 * pw).toInt(), col[0], col[1], col[2])
                    c.drawArc(rect, turn * (1.4f - 0.15f * i) + i * 72f, 70f, false, water)
                    c.drawArc(rect, turn * (1.4f - 0.15f * i) + i * 72f + 180f, 70f, false, water)
                }
                continue
            }
            c.drawRect(z.l, z.t, z.r, z.b, water)
            c.save()
            c.clipRect(z.l, z.t, z.r, z.b)
            water.style = Paint.Style.STROKE
            water.strokeWidth = 2.5f * d
            water.strokeCap = Paint.Cap.ROUND
            water.color = Color.argb((150 * pw).toInt(), col[0], col[1], col[2])
            val along = 44 * d
            val across = 20 * d
            val dash = 16 * d
            val shift = (now % 100000L) * (z.speed / 16.67f) % along
            if (z.dy != 0f) {
                var x = z.l + across / 2
                var lane = 0
                while (x < z.r) {
                    val st = if (lane % 2 == 0) 0f else along / 2
                    var y = if (z.dy > 0f) z.t - along + (shift + st) % along else z.t - along + (along - (shift + st) % along)
                    while (y < z.b + along) {
                        c.drawLine(x, y, x, y + dash, water)
                        y += along
                    }
                    x += across
                    lane++
                }
            } else {
                var y = z.t + across / 2
                var lane = 0
                while (y < z.b) {
                    val st = if (lane % 2 == 0) 0f else along / 2
                    var x = if (z.dx > 0f) z.l - along + (shift + st) % along else z.l - along + (along - (shift + st) % along)
                    while (x < z.r + along) {
                        c.drawLine(x, y, x + dash, y, water)
                        x += along
                    }
                    y += across
                    lane++
                }
            }
            c.restore()
            val ax = (z.l + z.r) / 2f
            val ay = (z.t + z.b) / 2f
            val len = 16 * d
            water.strokeWidth = 4 * d
            water.color = Color.argb((220 * pw).toInt(), 255, 255, 255)
            c.drawLine(ax - z.dx * len, ay - z.dy * len, ax + z.dx * len, ay + z.dy * len, water)
            arrowHead(c, ax + z.dx * len, ay + z.dy * len, z.dx, z.dy)
        }
    }

    /** Seaweed hiding spots: fronds sway, and part when Betito is inside. */
    private fun drawWeeds(c: Canvas, now: Long) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (wd in g.weeds) {
            val inside = g.cx > wd.l && g.cx < wd.r && g.cy > wd.t && g.cy < wd.b
            p.style = Paint.Style.FILL
            p.color = Color.argb(40, 76, 175, 80)
            rect.set(wd.l, wd.t, wd.r, wd.b)
            c.drawRoundRect(rect, 10 * d, 10 * d, p)
            p.style = Paint.Style.STROKE
            for (i in 0 until 6) {
                val bx = wd.l + (wd.r - wd.l) * (i + 0.5f) / 6f
                val part = if (inside) (if (bx < g.cx) -10 * d else 10 * d) else 0f
                val sway = sin(now / 500.0 + wd.seed + i).toFloat() * 8 * d + part
                p.strokeWidth = (5 - (i % 2)) * d
                p.color = if (i % 2 == 0) Color.argb(230, 46, 125, 50) else Color.argb(230, 102, 187, 106)
                path.reset()
                path.moveTo(bx, wd.b)
                path.quadTo(bx - sway, (wd.t + wd.b) / 2f, bx + sway, wd.t - 4 * d)
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    /** Walls are underwater rock. Pillars show Beto's photo if one was chosen. */
    private fun drawWalls(c: Canvas, now: Long) {
        for (w in g.blocks) {
            if (!w.alive) continue
            rect.set(w.l, w.t, w.r, w.b)
            p.style = Paint.Style.FILL
            if (w.gate) {
                // a locked gate: gold bars that pulse, opened by the gold switch
                val pulse = 0.6f + 0.4f * sin(now / 250.0).toFloat()
                p.color = Color.argb((60 * pulse).toInt(), 255, 193, 7)
                c.drawRect(w.l - 6 * d, w.t, w.r + 6 * d, w.b, p)
                p.color = Color.parseColor("#5D4037")
                c.drawRoundRect(rect, 3 * d, 3 * d, p)
                p.color = Color.parseColor("#FFC107")
                var yy = w.t + 5 * d
                while (yy < w.b - 3 * d) {
                    c.drawRect(w.l + 1 * d, yy, w.r - 1 * d, yy + 3 * d, p)
                    yy += 9 * d
                }
                lock(c, (w.l + w.r) / 2f, (w.t + w.b) / 2f, 7 * d)
                continue
            }
            if (w.plug) {
                // coral plug: pink-orange coral with bumpy polyps and cracks; bombs clear it
                val pu = 0.5f + 0.5f * sin(now / 420.0).toFloat()
                p.color = Color.argb((50 + 40 * pu).toInt(), 255, 112, 67)
                c.drawRoundRect(w.l - 7 * d, w.t - 4 * d, w.r + 7 * d, w.b + 4 * d, 10 * d, 10 * d, p)
                p.color = Color.parseColor("#E8567A")
                c.drawRoundRect(rect, 7 * d, 7 * d, p)
                var yy = w.t + 7 * d
                var k = 0
                while (yy < w.b - 4 * d) {
                    p.color = if (k % 2 == 0) Color.parseColor("#FF8A65") else Color.parseColor("#FFB74D")
                    c.drawCircle((w.l + w.r) / 2f + (if (k % 2 == 0) -3 else 3) * d, yy, 5.5f * d, p)
                    yy += 11 * d
                    k++
                }
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * d
                p.color = Color.parseColor("#FFF3E0")
                c.drawRoundRect(rect, 7 * d, 7 * d, p)
                p.style = Paint.Style.FILL
                continue
            }
            if (Yarn.loaded) {
                // yarn: purple braid for walls, brown cable knit for rocks
                c.drawRoundRect(rect, 6 * d, 6 * d, if (w.pillar) rockP else wallP)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.6f * d
                p.color = if (w.pillar) Color.argb(170, 60, 30, 14) else Color.argb(170, 36, 14, 60)
                c.drawRoundRect(rect, 6 * d, 6 * d, p)
                p.style = Paint.Style.FILL
            } else {
                p.color = if (w.pillar) Color.parseColor("#6D4C41") else Color.parseColor("#4A3B5C")
                c.drawRoundRect(rect, 5 * d, 5 * d, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * d
                p.color = if (w.pillar) Color.parseColor("#A1887F") else Color.parseColor("#8E7CC3")
                c.drawRoundRect(rect, 5 * d, 5 * d, p)
                p.style = Paint.Style.FILL
            }
            if (w.pillar && photo != null) {
                val size = min(w.r - w.l, w.b - w.t) - 6 * d
                val mx = (w.l + w.r) / 2f
                val my = (w.t + w.b) / 2f
                clip.reset()
                clip.addCircle(mx, my, size / 2, Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                src.set(0, 0, photo.width, photo.height)
                rect.set(mx - size / 2, my - size / 2, mx + size / 2, my + size / 2)
                c.drawBitmap(photo, src, rect, null)
                c.restore()
            }
        }
        // the exit: a glowing door in the right wall
        val pulse = 0.6f + 0.4f * sin(now / 220.0).toFloat()
        for (i in 0 until 4) {
            p.color = Color.argb(((90 * pulse).toInt() - i * 18).coerceIn(0, 255), 118, 255, 3)
            c.drawRect(g.exitX - (i + 1) * 14 * d, g.exitGapT, g.exitX + 30 * d, g.exitGapB, p)
        }
        if (Yarn.loaded) c.drawRect(g.exitX, g.exitGapT, g.exitX + 12 * d, g.exitGapB, holeP)   // lime yarn in the hole
        p.color = Color.parseColor("#76FF03")
        val my = (g.exitGapT + g.exitGapB) / 2f
        path.reset()
        path.moveTo(g.exitX + 14 * d, my)
        path.lineTo(g.exitX - 2 * d, my - 10 * d)
        path.lineTo(g.exitX - 2 * d, my + 10 * d)
        path.close()
        c.drawPath(path, p)
    }

    private fun drawPickups(c: Canvas, now: Long) {
        for (pk in g.pickups) {
            val bob = sin(now / 300.0 + pk.seed).toFloat() * 3 * d
            val x = pk.x
            val y = pk.y + bob
            // soft glow so they pop against the water
            p.style = Paint.Style.FILL
            p.color = Color.argb(50, 255, 255, 255)
            c.drawCircle(x, y, 16 * d, p)
            when (pk.type) {
                Game.P_APPLE -> {
                    p.color = Color.parseColor("#E53935")
                    c.drawCircle(x, y + 1 * d, 9 * d, p)
                    p.color = Color.parseColor("#43A047")
                    c.drawOval(x, y - 12 * d, x + 7 * d, y - 6 * d, p)
                }
                Game.P_BOMB -> drawBombBody(c, x, y, 9 * d, now, 0L, 255)
                else -> {
                    p.color = Color.parseColor("#FFEB3B")
                    c.drawCircle(x, y, 10 * d, p)
                    p.color = Color.parseColor("#F57F17")
                    path.reset()
                    path.moveTo(x + 2 * d, y - 8 * d)
                    path.lineTo(x - 5 * d, y + 1 * d)
                    path.lineTo(x, y + 1 * d)
                    path.lineTo(x - 2 * d, y + 8 * d)
                    path.lineTo(x + 5 * d, y - 1 * d)
                    path.lineTo(x, y - 1 * d)
                    path.close()
                    c.drawPath(path, p)
                }
            }
        }
    }

    private fun targetColor(t: Target): IntArray = when (t.kind) {
        Game.T_GATE -> intArrayOf(255, 193, 7)
        Game.T_BONUS -> intArrayOf(255, 64, 129)
        else -> zoneColor(t.zone?.kind ?: WaterZone.UP)
    }

    /**
     * Things to shoot. Current buoys (striped rings) flip their current, gold lock switches open a
     * gate, pink stars are bonus points. A faint dashed line shows what each one controls.
     */
    private fun drawTargets(c: Canvas, now: Long) {
        for (t in g.targets) {
            if (t.done) continue
            val col = targetColor(t)
            val hitAge = now - t.hitAt
            // link line to whatever it controls
            val lx: Float
            val ly: Float
            val zz = t.zone
            val gb = t.gate
            if (zz != null) { lx = zz.cx; ly = zz.cy } else if (gb != null) { lx = (gb.l + gb.r) / 2f; ly = (gb.t + gb.b) / 2f } else { lx = t.x; ly = t.y }
            if (lx != t.x || ly != t.y) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.pathEffect = dash
                p.color = Color.argb(if (g.aiming) 120 else 60, col[0], col[1], col[2])
                c.drawLine(t.x, t.y, lx, ly, p)
                p.pathEffect = null
            }
            val bob = sin(now / 350.0 + t.x).toFloat() * 2 * d
            val y = t.y + bob
            val pop = if (t.hitAt != 0L && hitAge < 400L) 1f + 0.5f * (1f - hitAge / 400f) else 1f
            val rr = g.targetR * pop
            p.style = Paint.Style.FILL
            val pulse = 0.5f + 0.5f * sin(now / 300.0 + t.y).toFloat()
            p.color = Color.argb((40 + 60 * pulse).toInt(), col[0], col[1], col[2])
            c.drawCircle(t.x, y, rr * 1.8f, p)
            when (t.kind) {
                Game.T_BONUS -> {
                    c.save()
                    c.rotate((now % 6000L) / 6000f * 360f, t.x, y)
                    p.color = Color.WHITE
                    star(c, t.x, y, rr * 1.15f)
                    p.color = Color.rgb(col[0], col[1], col[2])
                    star(c, t.x, y, rr * 0.85f)
                    c.restore()
                }
                Game.T_GATE -> {
                    p.color = Color.parseColor("#3E2723")
                    c.drawCircle(t.x, y, rr, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3 * d
                    p.color = Color.rgb(col[0], col[1], col[2])
                    c.drawCircle(t.x, y, rr, p)
                    p.style = Paint.Style.FILL
                    lock(c, t.x, y, rr * 0.55f)
                }
                else -> {
                    p.color = Color.WHITE
                    c.drawCircle(t.x, y, rr, p)
                    p.color = Color.rgb(col[0], col[1], col[2])
                    c.drawCircle(t.x, y, rr * 0.72f, p)
                    p.color = Color.WHITE
                    c.drawCircle(t.x, y, rr * 0.42f, p)
                    p.color = Color.rgb(col[0], col[1], col[2])
                    c.drawCircle(t.x, y, rr * 0.18f, p)
                    // flip arrows
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2 * d
                    p.color = Color.argb(220, 255, 255, 255)
                    rect.set(t.x - rr * 1.35f, y - rr * 1.35f, t.x + rr * 1.35f, y + rr * 1.35f)
                    val spin = (now % 4000L) / 4000f * 360f
                    c.drawArc(rect, spin, 100f, false, p)
                    c.drawArc(rect, spin + 180f, 100f, false, p)
                    p.style = Paint.Style.FILL
                }
            }
        }
    }

    /** A little padlock icon. */
    private fun lock(c: Canvas, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.28f
        p.color = Color.parseColor("#FFE082")
        rect.set(x - s * 0.5f, y - s * 1.1f, x + s * 0.5f, y - s * 0.1f)
        c.drawArc(rect, 180f, 180f, false, p)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FFC107")
        rect.set(x - s * 0.75f, y - s * 0.4f, x + s * 0.75f, y + s * 0.7f)
        c.drawRoundRect(rect, s * 0.2f, s * 0.2f, p)
        p.color = Color.parseColor("#5D4037")
        c.drawCircle(x, y + s * 0.1f, s * 0.15f, p)
    }

    /** Kelp slides: a green ribbon with swaying fronds and glowing mouths at both ends. */
    private fun drawRails(c: Canvas, now: Long) {
        for (rl in g.rails) {
            path.reset()
            path.moveTo(rl.xs[0], rl.ys[0])
            for (i in 1 until rl.xs.size) path.lineTo(rl.xs[i], rl.ys[i])
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = 26 * d
            p.color = Color.argb(45, 102, 187, 106)
            c.drawPath(path, p)
            p.strokeWidth = 3 * d
            p.color = Color.argb(170, 46, 125, 50)
            c.drawPath(path, p)
            // fronds along both edges
            p.strokeWidth = 2.5f * d
            for (i in 1 until rl.xs.size step 2) {
                val dx = rl.xs[i] - rl.xs[i - 1]
                val dy = rl.ys[i] - rl.ys[i - 1]
                val l = hypot(dx, dy).coerceAtLeast(0.01f)
                val nx = -dy / l
                val ny = dx / l
                for (side in intArrayOf(-1, 1)) {
                    val sway = sin(now / 400.0 + i + side).toFloat() * 4 * d
                    val bx = rl.xs[i] + nx * side * 12 * d
                    val by = rl.ys[i] + ny * side * 12 * d
                    p.color = if (side > 0) Color.argb(200, 102, 187, 106) else Color.argb(200, 46, 125, 50)
                    c.drawLine(bx, by, bx + nx * side * 7 * d + dx / l * sway, by + ny * side * 7 * d + dy / l * sway, p)
                }
            }
            // moving sparkles show it's a slide
            p.style = Paint.Style.FILL
            val k = (now % 1200L) / 1200f
            for (m in 0 until 3) {
                val f = ((k + m / 3f) % 1f)
                val idx = (f * (rl.xs.size - 1)).toInt().coerceIn(0, rl.xs.size - 1)
                p.color = Color.argb(200, 204, 255, 144)
                c.drawCircle(rl.xs[idx], rl.ys[idx], 3 * d, p)
            }
            // mouths
            val ready = now >= rl.coolUntil
            for (e in intArrayOf(0, rl.xs.size - 1)) {
                val pulse = 0.5f + 0.5f * sin(now / 220.0 + e).toFloat()
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3 * d
                p.color = Color.argb(if (ready) (140 + 100 * pulse).toInt() else 70, 118, 255, 3)
                c.drawCircle(rl.xs[e], rl.ys[e], g.r + 6 * d + pulse * 3 * d, p)
            }
            p.style = Paint.Style.FILL
        }
    }

    /** The hole's pull: a swirl of arrows drawing you into the exit. */
    private fun drawSuction(c: Canvas, now: Long) {
        if (g.colX.isEmpty()) return
        c.save()
        c.clipRect(g.suckFromX, g.playT, g.exitX, g.playB)
        val hx = g.holeX
        val hy = g.holeY
        p.style = Paint.Style.FILL
        p.color = Color.argb(28, 118, 255, 3)
        c.drawCircle(hx, hy, g.suckR, p)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 2.5f * d
        // rings shrinking into the hole
        for (i in 0 until 4) {
            val f = 1f - (((now % 1600L) / 1600f) + i / 4f) % 1f
            val rr = g.suckR * f
            p.color = Color.argb((150 * (1f - f)).toInt().coerceIn(0, 255), 178, 255, 89)
            rect.set(hx - rr, hy - rr, hx + rr, hy + rr)
            c.drawArc(rect, 100f + f * 90f, 160f, false, p)
        }
        c.restore()
        p.style = Paint.Style.FILL
    }

    /** Scorecard: one box per shot. Green up to par, the par box has a flag, orange after. Each shot ticks a box. */
    private fun drawTracker(c: Canvas, w: Float, now: Long) {
        val n = g.maxShots
        val bw = 13 * d
        val gap = 3 * d
        val total = n * bw + (n - 1) * gap
        val x0 = w / 2f - total / 2f + 22 * d
        val y0 = g.playT + 5 * d
        rect.set(x0 - 52 * d, y0 - 3 * d, x0 + total + 6 * d, y0 + bw + 3 * d)
        p.style = Paint.Style.FILL
        p.color = Color.argb(150, 2, 10, 30)
        c.drawRoundRect(rect, 8 * d, 8 * d, p)
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 10 * d
        p.color = Color.WHITE
        c.drawText("SHOTS", x0 - 46 * d, y0 + bw - 2 * d, p)
        for (i in 0 until n) {
            val bx = x0 + i * (bw + gap)
            val shotNo = i + 1
            val base = when {
                shotNo < g.par -> intArrayOf(118, 255, 3)
                shotNo == g.par -> intArrayOf(255, 235, 59)
                else -> intArrayOf(255, 145, 0)
            }
            val used = shotNo <= g.shots
            val justNow = used && shotNo == g.shots && now - g.lastShotAt < 450L
            val sc = if (justNow) 1f + 0.6f * (1f - (now - g.lastShotAt) / 450f) else 1f
            val cxB = bx + bw / 2f
            val cyB = y0 + bw / 2f
            rect.set(cxB - bw / 2f * sc, cyB - bw / 2f * sc, cxB + bw / 2f * sc, cyB + bw / 2f * sc)
            if (used) {
                p.style = Paint.Style.FILL
                p.color = Color.rgb(base[0], base[1], base[2])
                c.drawRoundRect(rect, 3 * d, 3 * d, p)
                // the tick mark
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * d
                p.strokeCap = Paint.Cap.ROUND
                p.color = Color.argb(230, 10, 20, 40)
                c.drawLine(rect.left + 3 * d, cyB, cxB - 1 * d, rect.bottom - 3 * d, p)
                c.drawLine(cxB - 1 * d, rect.bottom - 3 * d, rect.right - 3 * d, rect.top + 3 * d, p)
            } else {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Color.argb(170, base[0], base[1], base[2])
                c.drawRoundRect(rect, 3 * d, 3 * d, p)
            }
            if (shotNo == g.par) {
                // little flag over the par box
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Color.WHITE
                c.drawLine(cxB, y0 - 1 * d, cxB, y0 - 12 * d, p)
                p.style = Paint.Style.FILL
                p.color = Color.parseColor("#FF1744")
                path.reset()
                path.moveTo(cxB, y0 - 12 * d)
                path.lineTo(cxB + 8 * d, y0 - 9 * d)
                path.lineTo(cxB, y0 - 6 * d)
                path.close()
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    /** Before each hole: a big card with the hole number and its par. */
    private fun drawIntro(c: Canvas, w: Float, h: Float, now: Long) {
        val left = g.introUntil - now
        if (left <= 0L || g.over) return
        val age = 2600L - left
        val a = when {
            left < 300L -> left / 300f
            else -> 1f
        }
        p.style = Paint.Style.FILL
        p.color = Color.argb((150 * a).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        val sc = bounce(age, 350L)
        val cy = h * 0.48f
        c.save()
        c.scale(sc, sc, w / 2, cy)
        rect.set(w / 2 - 170 * d, cy - 85 * d, w / 2 + 170 * d, cy + 75 * d)
        p.color = Color.argb((235 * a).toInt(), 13, 71, 161)
        c.drawRoundRect(rect, 22 * d, 22 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4 * d
        p.color = Color.argb((255 * a).toInt(), 255, 214, 0)
        c.drawRoundRect(rect, 22 * d, 22 * d, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        p.color = Color.argb((255 * a).toInt(), 255, 255, 255)
        p.textSize = 22 * d
        c.drawText("HOLE ${g.level}", w / 2, cy - 45 * d, p)
        p.textSize = 54 * d
        val col = neon[((now / 120) % neon.size).toInt()]
        p.color = Color.argb((255 * a).toInt(), Color.red(col), Color.green(col), Color.blue(col))
        c.drawText("PAR ${g.par}", w / 2, cy + 15 * d, p)
        p.clearShadowLayer()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 11 * d
        p.color = Color.argb((220 * a).toInt(), 255, 255, 255)
        c.drawText(if (g.par >= 3) "SHELLS ${g.shellsLeft}   1 = EAGLE   2 = BIRDIE   3 = PAR   ${g.par + 1} = LOSE   ★ ${g.totalStars()}"
            else "SHELLS ${g.shellsLeft}   1 SHOT = BIRDIE   2 SHOTS = PAR   3 = LOSE   ★ ${g.totalStars()}", w / 2, cy + 40 * d, p)
        p.textSize = 10 * d
        p.color = Color.argb((160 * a).toInt(), 255, 255, 255)
        c.drawText("TAP TO START", w / 2, cy + 62 * d, p)
        c.restore()
    }

    private val dash = DashPathEffect(floatArrayOf(6f, 6f), 0f)

    /** A patrolling shark. He bumps shots off course; he doesn't eat Betito. */
    private fun drawShark(c: Canvas, s: Shark, now: Long) {
        val u = 16 * d
        val bumped = now - s.bumpAt < 500L
        if (now < s.stunUntil) {
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 15 * d
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            p.color = Color.parseColor("#FFF176")
            c.drawText("\u26A1 zzz", s.x, s.y - 30 * d + sin(now / 150.0).toFloat() * 3 * d, p)
            p.clearShadowLayer()
        }
        c.save()
        c.translate(s.x, s.y)
        c.rotate(s.heading)
        // facing left he'd be upside down, so flip him over instead
        if (cos(Math.toRadians(s.heading.toDouble())) < -0.01) c.scale(1f, -1f)
        if (Yarn.drawShark(c, u * 3.3f) || Sprites.drawShark(c, u * 1.1f)) {
            c.restore()
            if (now - s.bumpAt < 500L) {
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 14 * d
                p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                p.color = Color.parseColor("#FFD54F")
                c.drawText("BONK", s.x, s.y - 26 * d, p)
                p.clearShadowLayer()
            }
            return
        }
        val wag = sin(now / 110.0).toFloat() * 0.25f * u
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#607D8B")
        path.reset()
        path.moveTo(-1.1f * u, 0f)
        path.lineTo(-1.8f * u, -0.6f * u + wag)
        path.lineTo(-1.6f * u, wag * 0.3f)
        path.lineTo(-1.8f * u, 0.6f * u + wag)
        path.close()
        c.drawPath(path, p)
        c.drawOval(-1.3f * u, -0.55f * u, 1.3f * u, 0.55f * u, p)
        p.color = Color.parseColor("#CFD8DC")
        c.drawOval(-0.9f * u, 0f, 1.1f * u, 0.45f * u, p)
        p.color = Color.parseColor("#455A64")
        path.reset()
        path.moveTo(-0.2f * u, -0.5f * u)
        path.lineTo(0.3f * u, -0.95f * u)
        path.lineTo(0.5f * u, -0.45f * u)
        path.close()
        c.drawPath(path, p)
        p.color = Color.BLACK
        c.drawCircle(0.8f * u, -0.18f * u, 0.11f * u, p)
        // a goofy grin instead of teeth: he's a nuisance, not a killer
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.08f * u
        rect.set(0.45f * u, 0.0f, 0.95f * u, 0.35f * u)
        c.drawArc(rect, 20f, 120f, false, p)
        p.style = Paint.Style.FILL
        c.restore()
        if (bumped) {
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 14 * d
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            p.color = Color.parseColor("#FFD54F")
            c.drawText("BONK", s.x, s.y - 22 * d, p)
            p.clearShadowLayer()
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Lil Betito seen from above (head and legs tuck in when he bumps something). */
    private fun drawTurtle(c: Canvas, now: Long) {
        val u = g.r * 1.1f
        val bt = now - g.bumpAt
        val tuck = when {
            g.bumpAt == 0L -> 0f
            bt < 120L -> 1f
            bt < 520L -> { val k = (bt - 120L) / 400f; 1f - k * k * (3f - 2f * k) }
            else -> 0f
        }
        val out = 1f - tuck
        val alpha = 255
        c.save()
        c.translate(g.cx, g.cy)
        // glow: yellow when boosted, cyan otherwise
        val boosted = now < g.speedUntil
        p.style = Paint.Style.FILL
        p.color = if (boosted) Color.argb(110, 255, 235, 59) else Color.argb(70, 0, 229, 255)
        c.drawCircle(0f, 0f, 1.9f * u, p)
        if (TurtleRig.loaded) {
            // the crochet Betito: a side view that paddles, crouches, stretches, tucks and cheers
            anim.update(g, now)
            TurtleRig.draw(c, u * 1.5f, anim.pose)
            c.restore()
            drawComboTag(c, now)
            return
        }
        c.rotate(g.heading)
        val kt = now - g.kickAt
        val sq = if (g.kickAt == 0L || kt > 180L) 0f else 1f - kt / 180f
        c.scale(1f + 0.35f * sq, 1f - 0.25f * sq)
        // the hand-made turtle picture; it shrinks a little when he tucks in
        val usedSprite = Sprites.drawTurtle(c, u, 0.88f + 0.12f * out)
        val flap = sin(now / 90.0).toFloat() * 18f * out
        p.color = Color.argb(alpha, 124, 179, 66)
        if (!usedSprite) for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            leg(c, lerp(0.15f * u, 0.34f * u, out), s * lerp(0.2f * u, 0.66f * u, out), s * (40f + flap), u)
            leg(c, lerp(-0.15f * u, -0.34f * u, out), s * lerp(0.2f * u, 0.60f * u, out), s * (-40f - flap), u)
        }
        if (!usedSprite) {
        path.reset()
        path.moveTo(-0.5f * u, -0.08f * u)
        path.lineTo(lerp(-0.5f * u, -0.82f * u, out), 0f)
        path.lineTo(-0.5f * u, 0.08f * u)
        path.close()
        c.drawPath(path, p)
        }
        val hx = lerp(0.35f * u, 0.80f * u, out)
        p.color = Color.argb(alpha, 156, 204, 101)
        if (!usedSprite) c.drawCircle(hx, 0f, 0.2f * u, p)
        if (!usedSprite && out > 0.5f) {
            p.color = Color.argb(alpha, 0, 0, 0)
            c.drawCircle(hx + 0.07f * u, -0.08f * u, 0.035f * u, p)
            c.drawCircle(hx + 0.07f * u, 0.08f * u, 0.035f * u, p)
        }
        if (!usedSprite) {
        p.color = Color.argb(alpha, 46, 125, 50)
        c.drawOval(-0.62f * u, -0.52f * u, 0.62f * u, 0.52f * u, p)
        p.color = Color.argb(alpha, 102, 187, 106)
        c.drawOval(-0.52f * u, -0.43f * u, 0.52f * u, 0.43f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.06f * u
        p.color = Color.argb(alpha, 46, 125, 50)
        c.drawOval(-0.22f * u, -0.17f * u, 0.22f * u, 0.17f * u, p)
        c.drawLine(0.22f * u, 0f, 0.5f * u, 0f, p)
        c.drawLine(-0.22f * u, 0f, -0.5f * u, 0f, p)
        }
        p.style = Paint.Style.FILL
        c.restore()
        drawComboTag(c, now)
    }

    /** Live combo counter over his head. */
    private fun drawComboTag(c: Canvas, now: Long) {
        if (g.shotCombo >= 2 && !g.ready && !g.over) {
            val age = now - g.comboAt
            val sc = if (age < 250) 1f + 0.6f * (1f - age / 250f) else 1f
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
            p.textSize = 15 * d * sc
            p.setShadowLayer(4f, 1f, 1f, Color.BLACK)
            p.color = neon[((now / 90) % neon.size).toInt()]
            c.drawText("COMBO x${g.shotCombo}", g.cx, g.cy - 26 * d, p)
            p.clearShadowLayer()
            p.typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun leg(c: Canvas, x: Float, y: Float, angle: Float, u: Float) {
        c.save()
        c.rotate(angle, x, y)
        c.drawOval(x - 0.22f * u, y - 0.12f * u, x + 0.22f * u, y + 0.12f * u, p)
        c.restore()
    }

    /** An orange tracer line that follows the turtle while it flies. */
    private fun drawTracer(c: Canvas) {
        val n = g.trN
        if (n < 2) return
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        var prev = g.trailPoint(0)
        for (i in 1 until n) {
            val cur = g.trailPoint(i)
            val k = i / n.toFloat()
            val dx = g.trX[cur] - g.trX[prev]
            val dy = g.trY[cur] - g.trY[prev]
            if (dx * dx + dy * dy < (80 * d) * (80 * d)) {
                p.strokeWidth = (2f + 4f * k) * d
                p.color = Color.argb((60 + 195 * k).toInt(), 255, 138, 20)
                c.drawLine(g.trX[prev], g.trY[prev], g.trX[cur], g.trY[cur], p)
            }
            prev = cur
        }
        p.style = Paint.Style.FILL
    }

    private fun drawParticles(c: Canvas) {
        p.style = Paint.Style.FILL
        for (i in 0 until g.pMax) {
            val life = g.pLife[i]
            if (life <= 0f) continue
            val k = (life / g.pMaxLife[i]).coerceIn(0f, 1f)
            val col = g.pColor[i]
            p.color = Color.argb((Color.alpha(col) * k).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(g.px[i], g.py[i], g.pSize[i] * (0.5f + 0.5f * k), p)
        }
    }

    // ============================================================
    // HUD
    // ============================================================

    private fun drawHud(c: Canvas, w: Float, now: Long) {
        val top = g.topInset
        val barB = g.playT - 14 * d
        p.style = Paint.Style.FILL
        p.color = Color.argb(170, 2, 10, 30)
        c.drawRect(0f, 0f, w, barB, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        p.color = Color.WHITE
        p.textSize = 15 * d
        val lv = "HOLE ${g.level}"
        c.drawText(lv, 14 * d, top + 17 * d, p)
        var x = 14 * d + p.measureText(lv) + 12 * d
        // shots vs par, colored like a golf scorecard
        p.textSize = 13 * d
        p.color = when {
            g.shots < g.par -> Color.parseColor("#76FF03")
            g.shots == g.par -> Color.parseColor("#FFEB3B")
            else -> Color.parseColor("#FF9100")
        }
        val st = "SHOT ${g.shots}  PAR ${g.par}"
        c.drawText(st, x, top + 17 * d, p)
        x += p.measureText(st) + 10 * d
        p.textSize = 11 * d
        val end = if (g.levelEndMs != 0L) g.levelEndMs else now
        val secs = ((end - g.levelStartMs) / 1000f).coerceAtLeast(0f)
        p.color = if (secs < g.par * 3f) Color.parseColor("#80D8FF") else Color.WHITE
        val tm = "%.1fs".format(secs)
        c.drawText(tm, x, top + 17 * d, p)
        x += p.measureText(tm) + 10 * d
        if (g.bestCombo >= 2) {
            p.color = neon[((now / 150) % neon.size).toInt()]
            c.drawText("BEST COMBO x${g.bestCombo}", x, top + 17 * d, p)
        }

        p.textSize = 11 * d
        p.color = Color.parseColor("#FFEB3B")
        val sc = "SCORE ${g.score}   BEST ${g.best}"
        c.drawText(sc, 14 * d, top + 32 * d, p)
        p.color = if (g.shots >= g.maxShots - 2) Color.parseColor("#FF5252") else Color.argb(150, 255, 255, 255)
        p.textSize = 10 * d
        c.drawText("MAX ${g.maxShots}", 14 * d + p.measureText(sc) + 30 * d, top + 32 * d, p)
        p.clearShadowLayer()

    }

    /** One slot symbol. 0 cherry, 1 bell, 2 bar, 3 seven, 4 coin. */
    private fun symbol(c: Canvas, s: Int, x: Float, y: Float, size: Float) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        when (s) {
            0 -> {
                p.color = Color.parseColor("#E53935")
                c.drawCircle(x - size * 0.18f, y + size * 0.15f, size * 0.17f, p)
                c.drawCircle(x + size * 0.2f, y + size * 0.2f, size * 0.17f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * d
                p.color = Color.parseColor("#2E7D32")
                c.drawLine(x - size * 0.18f, y + size * 0.05f, x + size * 0.12f, y - size * 0.32f, p)
                c.drawLine(x + size * 0.2f, y + size * 0.1f, x + size * 0.12f, y - size * 0.32f, p)
                p.style = Paint.Style.FILL
            }
            1 -> {
                p.color = Color.parseColor("#FFD54F")
                path.reset()
                path.moveTo(x - size * 0.32f, y + size * 0.2f)
                path.quadTo(x - size * 0.28f, y - size * 0.35f, x, y - size * 0.35f)
                path.quadTo(x + size * 0.28f, y - size * 0.35f, x + size * 0.32f, y + size * 0.2f)
                path.close()
                c.drawPath(path, p)
            }
            2 -> {
                p.color = Color.WHITE
                c.drawRect(x - size * 0.38f, y - size * 0.16f, x + size * 0.38f, y + size * 0.16f, p)
                p.color = Color.BLACK
                p.textSize = size * 0.28f
                c.drawText("BAR", x, y + size * 0.1f, p)
            }
            3 -> {
                p.color = Color.parseColor("#FF1744")
                p.textSize = size * 0.8f
                c.drawText("7", x, y + size * 0.28f, p)
            }
            else -> {
                p.color = Color.parseColor("#FFC107")
                c.drawCircle(x, y, size * 0.3f, p)
            }
        }
    }

    /** Octo the octopus narrates from the top bar with a speech bubble. */
    private fun drawOcto(c: Canvas, w: Float, now: Long) {
        val ox = w * 0.47f
        val oy = g.topInset + 16 * d
        val talking = g.octoText != null && now - g.octoAt < 1400L
        val speaking = g.octoText != null && now - g.octoAt < 2600L
        val pulse = if (talking) 1f + 0.08f * sin(now / 70.0).toFloat() else 1f
        if (!Yarn.drawPal(c, 0, ox, oy + sin(now / 400.0).toFloat() * 1.5f * d, 34 * d, sin(now / 700.0).toFloat() * 4f, false, pulse, pulse))
            Art.octo(c, p, path, ox, oy, 9.5f * d, now, talking, if (speaking) g.octoFace else -1)

        val txt = g.octoText ?: return
        val age = now - g.octoAt
        if (age > 2600) return
        val a = if (age > 2200) ((2600 - age) / 400f) else 1f
        p.textSize = 11 * d
        p.typeface = Typeface.DEFAULT_BOLD
        val tw = p.measureText(txt)
        val bx = ox + 18 * d
        val maxW = w - 60 * d - 3 * 28 * d - bx - 10 * d
        val boxW = min(tw + 16 * d, maxW)
        rect.set(bx, oy - 12 * d, bx + boxW, oy + 12 * d)
        p.color = Color.argb((240 * a).toInt(), 255, 255, 255)
        c.drawRoundRect(rect, 10 * d, 10 * d, p)
        path.reset()
        path.moveTo(bx + 2 * d, oy - 4 * d)
        path.lineTo(bx - 7 * d, oy)
        path.lineTo(bx + 2 * d, oy + 4 * d)
        path.close()
        c.drawPath(path, p)
        p.color = Color.argb((255 * a).toInt(), 30, 20, 60)
        p.textAlign = Paint.Align.LEFT
        if (tw > boxW - 16 * d) p.textSize = 11 * d * (boxW - 16 * d) / tw
        c.drawText(txt, bx + 8 * d, oy + 4 * d, p)
    }

    /** Gauge color: green for soft shots, through yellow and orange, to red at full power. Grey while he's moving. */
    private fun powerColor(k: Float): Int {
        if (!g.ready) return Color.rgb(150, 160, 175)
        return when {
            k < 0.33f -> Color.rgb(118, 255, 3)
            k < 0.6f -> Color.rgb(255, 235, 59)
            k < 0.85f -> Color.rgb(255, 145, 0)
            else -> Color.rgb(255, 61, 0)
        }
    }

    private fun aimColor(): Int = powerColor(g.aimPower)

    /** The ready ring around Betito, and the dotted path preview while aiming. */
    private fun drawAimPreview(c: Canvas, now: Long) {
        if (g.over) return
        if (g.ready) {
            val pulse = 0.5f + 0.5f * sin(now / 180.0).toFloat()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb((90 + 90 * pulse).toInt(), 118, 255, 3)
            c.drawCircle(g.cx, g.cy, g.r * 1.7f + pulse * 3 * d, p)
            p.style = Paint.Style.FILL
        }
        if (g.turnGrab) {
            // direction arrow while time is frozen
            val ax = g.cx + g.turnDX * g.r * 6f
            val ay = g.cy + g.turnDY * g.r * 6f
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 4 * d
            p.color = Color.argb(230, 128, 222, 234)
            c.drawLine(g.cx + g.turnDX * g.r * 1.6f, g.cy + g.turnDY * g.r * 1.6f, ax, ay, p)
            val px2 = -g.turnDY
            val py2 = g.turnDX
            c.drawLine(ax, ay, ax - g.turnDX * 10 * d + px2 * 7 * d, ay - g.turnDY * 10 * d + py2 * 7 * d, p)
            c.drawLine(ax, ay, ax - g.turnDX * 10 * d - px2 * 7 * d, ay - g.turnDY * 10 * d - py2 * 7 * d, p)
            p.style = Paint.Style.FILL
        } else if (g.canTurn) {
            // a quick tap now turns him: one per shot
            val pulse = 0.5f + 0.5f * sin(now / 120.0).toFloat()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = Color.argb((120 + 120 * pulse).toInt(), 128, 222, 234)
            c.drawCircle(g.cx, g.cy, g.r * 2.1f + pulse * 3 * d, p)
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 11 * d
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            p.color = Color.argb(230, 128, 222, 234)
            c.drawText("TAP TURTLE, FLIP IT, LET GO!", g.cx, g.cy - g.r * 2.6f, p)
            p.clearShadowLayer()
        }
        if (!g.aiming) return
        // shot gauge: a ring around Betito that fills with power
        val gr = g.r * 2.4f
        rect.set(g.cx - gr, g.cy - gr, g.cx + gr, g.cy + gr)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = 6 * d
        p.color = Color.argb(90, 0, 0, 0)
        c.drawArc(rect, 135f, 270f, false, p)
        val segs = 12
        val filled = (g.aimPower * segs).toInt().coerceIn(0, segs)
        for (i in 0 until segs) {
            val k = (i + 1) / segs.toFloat()
            p.color = if (i < filled || (i == 0 && g.aimPower > 0f)) powerColor(k) else Color.argb(60, 255, 255, 255)
            c.drawArc(rect, 135f + i * 22.5f + 1.5f, 19.5f, false, p)
        }
        p.style = Paint.Style.FILL
        drawCue(c)
        if (g.previewN == 0 && g.previewBounceN == 0) return
        drawPoolLine(c, now)
    }

    /** The pool-table aim line: small red dots along the whole path, a ghost turtle ring at every bounce. */
    private fun drawPoolLine(c: Canvas, now: Long) {
        val alphaBySeg = intArrayOf(240, 200, 160, 120)
        p.style = Paint.Style.FILL
        for (i in 0 until g.previewN) {
            val seg = g.previewSeg[i].coerceIn(0, 3)
            val a = alphaBySeg[seg]
            val rad = (2.4f - 0.3f * seg) * d
            p.color = Color.argb(a * 2 / 3, 60, 0, 0)
            c.drawCircle(g.previewX[i], g.previewY[i], rad + 1.2f * d, p)
            p.color = Color.argb(a, 255, 45, 45)
            c.drawCircle(g.previewX[i], g.previewY[i], rad, p)
        }
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 10 * d
        for (b in 0 until g.previewBounceN) {
            val bx = g.previewBX[b]
            val by = g.previewBY[b]
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb(220 - 30 * b, 255, 255, 255)
            c.drawCircle(bx, by, g.r * 0.95f + 1.5f * d * sin(now / 100.0 + b).toFloat(), p)
            p.style = Paint.Style.FILL
            p.color = Color.argb(240, 255, 45, 45)
            c.drawCircle(bx, by, 3 * d, p)
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            p.color = Color.WHITE
            c.drawText("${b + 1}", bx, by - g.r * 1.2f, p)
            p.clearShadowLayer()
        }
        if (g.previewStops) {
            // where he would come to rest
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.pathEffect = dash
            p.color = Color.argb(160, 255, 255, 255)
            c.drawCircle(g.previewEndX, g.previewEndY, g.r, p)
            p.pathEffect = null
        }
        p.style = Paint.Style.FILL
    }

    /** A pool cue behind Betito: it slides back as you pull, so the shot feels like lining up on a table. */
    private fun drawCue(c: Canvas) {
        if (!g.aiming || !g.ready || g.shellMode || g.bombMode) return
        val gap = g.r * 1.7f + 30 * d * g.aimPower
        val len = 110 * d
        val ux = -g.aimDX
        val uy = -g.aimDY
        val x0 = g.cx + ux * gap
        val y0 = g.cy + uy * gap
        val x1 = x0 + ux * len
        val y1 = y0 + uy * len
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 5.5f * d
        p.color = Color.argb(230, 215, 178, 122)
        c.drawLine(x0 + ux * 8 * d, y0 + uy * 8 * d, x1, y1, p)
        p.strokeWidth = 4.2f * d
        p.color = Color.argb(240, 78, 52, 46)
        c.drawLine(x0 + ux * 8 * d, y0 + uy * 8 * d, x0 + ux * 30 * d, y0 + uy * 30 * d, p)
        p.strokeWidth = 5.5f * d
        p.color = Color.argb(240, 100, 181, 246)
        c.drawLine(x0, y0, x0 + ux * 6 * d, y0 + uy * 6 * d, p)
        p.style = Paint.Style.FILL
    }

    private fun drawShellBody(c: Canvas, x: Float, y: Float, rad: Float, a: Int, kind: Int = 0) {
        if (Yarn.drawShell(c, kind, x, y, rad, a)) return   // the crochet shell picture
        val col = ShellKinds.colors[kind]
        p.style = Paint.Style.FILL
        p.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(x, y, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * d
        p.color = Color.argb(a, Color.red(col) / 2, Color.green(col) / 2, Color.blue(col) / 2)
        c.drawCircle(x, y, rad, p)
        for (i in -1..1) {
            val ox = i * rad * 0.45f
            c.drawLine(x + ox * 0.4f, y + rad * 0.85f, x + ox * 1.3f, y - rad * 0.8f, p)
        }
        if (kind == 4) {
            // twist: a little clockwise arrow on top
            p.color = Color.argb(a, 255, 255, 255)
            rect.set(x - rad * 0.5f, y - rad * 0.5f, x + rad * 0.5f, y + rad * 0.5f)
            c.drawArc(rect, 200f, 230f, false, p)
        }
        p.style = Paint.Style.FILL
    }

    /** A flat paddle: a rounded bar with a teal knit look and a little turn arrow in the middle. */
    private fun drawPaddle(c: Canvas, x: Float, y: Float, ang: Float, a: Int, hit: Boolean) {
        val half = g.padHalf * (if (hit) 1.08f else 1f)
        val th = g.padR * (if (hit) 1.3f else 1f)
        val deg = Math.toDegrees(ang.toDouble()).toFloat()
        c.save()
        c.translate(x, y)
        c.rotate(deg)
        p.style = Paint.Style.FILL
        p.color = Color.argb(a / 4, 0, 0, 0)
        rect.set(-half + 2 * d, -th + 3 * d, half + 2 * d, th + 3 * d)
        c.drawRoundRect(rect, th, th, p)
        p.color = Color.argb(a, 38, 198, 218)
        rect.set(-half, -th, half, th)
        c.drawRoundRect(rect, th, th, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * d
        p.color = Color.argb(a, 0, 96, 110)
        c.drawRoundRect(rect, th, th, p)
        // stitches along the bar
        p.strokeWidth = 1.2f * d
        p.color = Color.argb(a, 224, 247, 250)
        var sx0 = -half + th * 1.3f
        while (sx0 < half - th) {
            c.drawLine(sx0, -th * 0.45f, sx0 + th * 0.5f, th * 0.45f, p)
            sx0 += th * 1.2f
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    private fun drawShells(c: Canvas, now: Long) {
        for (rp in g.ropes) {
            val bob = rp.bob
            if (!rp.cut) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.5f * d
                p.color = Color.parseColor("#F4B183")
                c.drawLine(rp.ax, rp.ay, bob.x, bob.y, p)
                p.strokeWidth = 1.2f * d
                p.color = Color.argb(150, 150, 85, 45)
                c.drawLine(rp.ax, rp.ay, bob.x, bob.y, p)
                run {
                    p.strokeWidth = 2 * d
                    p.color = Color.argb((90 + 70 * sin(now / 250.0)).toInt(), 255, 255, 255)
                    c.drawCircle(bob.x, bob.y, g.shellR * (1.45f + 0.1f * sin(now / 250.0).toFloat()), p)
                }
            } else if (now - rp.cutAt < 1600L) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.5f * d
                p.color = Color.argb((255 * (1f - (now - rp.cutAt) / 1600f)).toInt(), 244, 177, 131)
                val sag = min(1f, (now - rp.cutAt) / 400f) * 10 * d
                c.drawLine(rp.ax, rp.ay, rp.ax + rp.cutDx * rp.len * 0.35f, rp.ay + rp.cutDy * rp.len * 0.35f + sag, p)
            }
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#8D5524")
            c.drawCircle(rp.ax, rp.ay, 5 * d, p)
            if (rp.cut) continue
            val pop = if (now - bob.hitAt < 160) 1.25f - (now - bob.hitAt) / 160f * 0.25f else 1f
            p.color = Color.argb(60, 0, 0, 0)
            c.drawCircle(bob.x + 2 * d, bob.y + 3 * d, g.shellR * pop, p)
            drawShellBody(c, bob.x, bob.y, g.shellR * pop, 255, 0)
        }
        for (sh in g.shells) {
            if (sh.paddle) { drawPaddle(c, sh.x, sh.y, sh.ang, 255, now - sh.hitAt < 160); continue }
            val pop = if (now - sh.hitAt < 160) 1.25f - (now - sh.hitAt) / 160f * 0.25f else 1f
            p.color = Color.argb(60, 0, 0, 0)
            c.drawCircle(sh.x + 2 * d, sh.y + 3 * d, g.shellR * pop, p)
            if (sh.bomb) drawBombBody(c, sh.x, sh.y, g.shellR * 1.1f, now, sh.explodeAt, 255)
            else drawShellBody(c, sh.x, sh.y, g.shellR * pop, 255, sh.kind)
        }
        // aiming a shell: show the landing spot
        if (g.bombMode && g.aiming && g.ready) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.pathEffect = dash
            p.color = Color.argb(170, 255, 112, 67)
            c.drawLine(g.cx, g.cy, g.shellLandX, g.shellLandY, p)
            c.drawCircle(g.shellLandX, g.shellLandY, g.blastR, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
            drawBombBody(c, g.shellLandX, g.shellLandY, g.shellR * 1.1f, now, 0L, 130)
        }
        if (g.shellMode && g.aiming && g.ready) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.pathEffect = dash
            val kc = ShellKinds.colors[g.shellKind]
            p.color = Color.argb(190, Color.red(kc), Color.green(kc), Color.blue(kc))
            c.drawLine(g.cx, g.cy, g.shellLandX, g.shellLandY, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
            drawShellBody(c, g.shellLandX, g.shellLandY, g.shellR, 130, g.shellKind)
        }
    }

    /** A flip icon on every tappable current; it pulses while you still have flips. */
    private fun drawFlipIcons(c: Canvas, now: Long) {
        if (g.over) return
        val live = g.flipsLeft > 0
        val pulse = if (live) 0.5f + 0.5f * sin(now / 260.0).toFloat() else 0f
        for (z in g.flippable) {
            val col = zoneColor(z.kind)
            val x = z.cx
            val y = g.flipIconY(z)
            val rr = (13 + 2 * pulse) * d
            val age = now - g.flipAt
            p.style = Paint.Style.FILL
            p.color = Color.argb(if (live) 200 else 90, 10, 20, 40)
            c.drawCircle(x, y, rr, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = if (live) Color.rgb(col[0], col[1], col[2]) else Color.argb(120, 200, 200, 200)
            c.drawCircle(x, y, rr, p)
            val spin = if (age in 0..500) age / 500f * 360f else 0f
            p.color = Color.argb(if (live) 240 else 110, 255, 255, 255)
            rect.set(x - rr * 0.6f, y - rr * 0.6f, x + rr * 0.6f, y + rr * 0.6f)
            c.drawArc(rect, 20f + spin, 130f, false, p)
            c.drawArc(rect, 200f + spin, 130f, false, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawBombBody(c: Canvas, x: Float, y: Float, rad: Float, now: Long, explodeAt: Long, a: Int) {
        if (Yarn.drawBomb(c, x, y, rad, a)) {
            val left0 = if (explodeAt > 0) explodeAt - now else 9999L
            if (left0 in 0..700 && (now / 90) % 2 == 0L) {   // about to go off: flash red
                p.style = Paint.Style.FILL
                p.color = Color.argb(a * 3 / 5, 255, 40, 30)
                c.drawCircle(x, y, rad, p)
            }
            return
        }
        val left = if (explodeAt > 0) explodeAt - now else 9999L
        val hot = left in 0..700 && (now / 90) % 2 == 0L
        p.style = Paint.Style.FILL
        p.color = if (hot) Color.argb(a, 220, 50, 40) else Color.argb(a, 30, 30, 38)
        c.drawCircle(x, y, rad, p)
        p.color = Color.argb(a * 2 / 5, 255, 255, 255)
        c.drawCircle(x - rad * 0.35f, y - rad * 0.35f, rad * 0.28f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(a, 200, 170, 110)
        c.drawLine(x + rad * 0.3f, y - rad * 0.8f, x + rad * 0.8f, y - rad * 1.4f, p)
        p.style = Paint.Style.FILL
        p.color = if ((now / 70) % 2 == 0L) Color.argb(a, 255, 235, 59) else Color.argb(a, 255, 112, 67)
        c.drawCircle(x + rad * 0.85f, y - rad * 1.45f, 3 * d, p)
    }

    private fun drawBombButton(c: Canvas, now: Long) {
        if (g.over) { bombBtnR.setEmpty(); return }
        val rad = 26 * d
        val bx = width - 16 * d - rad
        val by = height - 16 * d - rad - 64 * d
        bombBtnR.set(bx - rad - 6 * d, by - rad - 6 * d, bx + rad + 6 * d, by + rad + 6 * d)
        val armed = g.bombMode
        p.style = Paint.Style.FILL
        p.color = if (armed) Color.argb(235, 255, 112, 67) else Color.argb(190, 20, 30, 50)
        c.drawCircle(bx, by, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        p.color = if (armed) Color.WHITE else Color.argb(200, 255, 112, 67)
        c.drawCircle(bx, by, rad - d, p)
        drawBombBody(c, bx, by - 1 * d, 10 * d, now, 0L, if (g.bombsLeft > 0) 255 else 90)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = Color.WHITE
        p.textSize = 11 * d
        p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        c.drawText("x${g.bombsLeft}", bx, by + rad - 6 * d, p)
        if (armed) c.drawText("PULL TO THROW BOMB", bx - 70 * d, by - rad - 6 * d, p)
        p.clearShadowLayer()
    }

    /** The shell tray (bottom right): one round button per shell type you have this hole. Tap one, then pull to fire it. */
    private fun drawShellButton(c: Canvas, now: Long) {
        bombBtnR.setEmpty()
        for (r in trayR) r.setEmpty()
        if (g.over) return
        val rad = 22 * d
        val bx = width - 14 * d - rad
        var by = height - 14 * d - rad
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        for (k in 0 until ShellKinds.COUNT) {
            if (g.pouchStart[k] <= 0) continue
            trayR[k].set(bx - rad - 6 * d, by - rad - 4 * d, bx + rad + 6 * d, by + rad + 4 * d)
            val armed = dragKind == k
            val have = g.pouch[k]
            val col = ShellKinds.colors[k]
            p.style = Paint.Style.FILL
            p.color = if (armed) Color.argb(240, Color.red(col), Color.green(col), Color.blue(col)) else Color.argb(190, 20, 30, 50)
            c.drawCircle(bx, by, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = if (armed) Color.WHITE else Color.argb(if (have > 0) 220 else 90, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(bx, by, rad - d, p)
            if (k == ShellKinds.PADDLE) { c.save(); c.scale(0.55f, 0.55f, bx, by - 3 * d); drawPaddle(c, bx, by - 3 * d, -0.6f, if (have > 0) 255 else 80, false); c.restore() }
            else drawShellBody(c, bx, by - 3 * d, 9 * d, if (have > 0) 255 else 80, k)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.textSize = 10 * d
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            c.drawText("x$have", bx, by + rad - 5 * d, p)
            if (armed) {
                p.textAlign = Paint.Align.RIGHT
                p.textSize = 11 * d
                c.drawText("${ShellKinds.names[k]}: DRAG TO THE WATER", bx - rad - 10 * d, by + 4 * d, p)
                p.textAlign = Paint.Align.CENTER
            }
            p.clearShadowLayer()
            by -= rad * 2 + 8 * d
        }
        drawSetupExtras(c, now, bx, rad)
    }

    /** RESET button, the "drag me" hint, and the ghost shell that follows your thumb. */
    private fun drawSetupExtras(c: Canvas, now: Long, bx: Float, rad: Float) {
        resetR.setEmpty()
        val canSet = g.canSetUp(now)
        if (canSet && (g.shells.any { !it.bomb } || g.shots > 0)) {
            val bw = 66 * d
            val bh = 30 * d
            val rx = bx - rad - 6 * d - 14 * d - bw
            val ry = height - 14 * d - bh
            resetR.set(rx, ry, rx + bw, ry + bh)
            p.style = Paint.Style.FILL
            p.color = Color.argb(200, 20, 30, 50)
            c.drawRoundRect(resetR, 8 * d, 8 * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb(220, 255, 193, 7)
            c.drawRoundRect(resetR, 8 * d, 8 * d, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 11 * d
            c.drawText("RESET", resetR.centerX(), resetR.centerY() + 4 * d, p)
        }
        // a nudge the first time: shells are placed BEFORE the shot
        if (canSet && g.shots == 0 && g.shellsLeft > 0 && g.shells.none { !it.bomb } && dragPtr == -1) {
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 12 * d
            p.color = Color.argb(200 - (90 * Math.abs(Math.sin(now / 350.0))).toInt(), 255, 241, 118)
            p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            c.drawText("1. DRAG A PADDLE OUT, TAP IT TO TURN   2. PULL BACK + LET GO", width / 2f, height - 18 * d, p)
            p.clearShadowLayer()
        }
        // ghost shell under the thumb (green = it fits, red = blocked)
        if (dragPtr != -1 && dragShell == null && dragKind >= 0) {
            if (dragKind == ShellKinds.PADDLE) drawPaddle(c, dragX, dragY, g.lastPadAng, if (dragValid) 230 else 120, false)
            else drawShellBody(c, dragX, dragY, g.shellR, if (dragValid) 230 else 120, dragKind)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = if (dragValid) Color.argb(220, 118, 255, 3) else Color.argb(220, 255, 82, 82)
            c.drawCircle(dragX, dragY, (if (dragKind == ShellKinds.PADDLE) g.padHalf + 6 * d else g.shellR + 4 * d), p)
            p.style = Paint.Style.FILL
        } else if (dragPtr != -1 && dragShell != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = if (dragValid) Color.argb(220, 118, 255, 3) else Color.argb(220, 255, 82, 82)
            c.drawCircle(dragShell!!.x, dragShell!!.y, (if (dragShell!!.paddle) g.padHalf + 6 * d else g.shellR + 5 * d), p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawControls(c: Canvas, now: Long) {
        // slingshot band: from where you touched to where your finger is
        if (aimPtr != -1) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(aimSX, aimSY, deadPull, p)
            if (g.aiming) {
                // the cancel zone: drag back in here (or tap a 2nd finger) and let go to cancel
                p.color = Color.argb(150, 255, 193, 7)
                p.strokeWidth = 3 * d
                c.drawCircle(aimSX, aimSY, deadPull, p)
                val xs = deadPull * 0.38f
                c.drawLine(aimSX - xs, aimSY - xs, aimSX + xs, aimSY + xs, p)
                c.drawLine(aimSX - xs, aimSY + xs, aimSX + xs, aimSY - xs, p)
                val col = aimColor()
                val pull = hypot(aimSX - aimTX, aimSY - aimTY).coerceAtMost(maxPull)
                val ex = aimSX - g.aimDX * pull
                val ey = aimSY - g.aimDY * pull
                p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = (5 - 2.5f * g.aimPower) * d   // the band thins as it stretches
                p.color = Color.argb(200, Color.red(col), Color.green(col), Color.blue(col))
                c.drawLine(aimSX, aimSY, ex, ey, p)
                p.style = Paint.Style.FILL
                c.drawCircle(ex, ey, 10 * d, p)
                // power meter
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 12 * d
                p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                val label = if (!g.ready) "WAIT..." else "POWER ${(g.aimPower * 100).toInt()}%"
                c.drawText(label, aimSX, aimSY - deadPull - 8 * d, p)
                p.textSize = 10 * d
                p.color = Color.argb(220, 255, 213, 79)
                c.drawText("BACK IN THE X = CANCEL", aimSX, aimSY + deadPull + 16 * d, p)
                p.clearShadowLayer()
            }
            p.style = Paint.Style.FILL
        } else if (now - g.levelStartMs < 6000 && g.shots == 0) {
            // how-to hint: a little animated pull-back
            val k = ((now - g.levelStartMs) % 1400L) / 1400f
            val hx = width * 0.3f
            val hy = height - 70 * d
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.argb(140, 255, 255, 255)
            c.drawLine(hx, hy, hx - 60 * d * k, hy + 10 * d * k, p)
            p.style = Paint.Style.FILL
            p.color = Color.argb(170, 255, 255, 255)
            c.drawCircle(hx - 60 * d * k, hy + 10 * d * k, 9 * d, p)
            p.textAlign = Paint.Align.CENTER
            p.textSize = 11 * d
            c.drawText("TOUCH, PULL BACK, LET GO", hx, hy - 18 * d, p)
        }
    }

    // ============================================================
    // praise, stars, messages
    // ============================================================

    private fun bounce(age: Long, dur: Long): Float {
        val t = (age.toFloat() / dur).coerceIn(0f, 1f)
        return if (t < 0.6f) 0.3f + 1.0f * (t / 0.6f) else 1.3f - 0.3f * ((t - 0.6f) / 0.4f)
    }

    private fun fitText(c: Canvas, t: String, x: Float, y: Float, size: Float, maxW: Float) {
        p.textSize = size
        val mw = p.measureText(t)
        if (mw > maxW) p.textSize = size * maxW / mw
        c.drawText(t, x, y, p)
    }

    private fun drawPraise(c: Canvas, w: Float, h: Float, now: Long) {
        val txt = g.praise ?: return
        val age = now - g.praiseAt
        val life = if (g.praiseBig) 1600L else 1100L
        if (age > life) return
        val sc = bounce(age, 280L)
        val alpha = if (age > life - 300) ((life - age) / 300f).coerceIn(0f, 1f) else 1f
        val y = h * 0.42f
        val size = (if (g.praiseBig) 40 else 28) * d
        c.save()
        c.scale(sc, sc, w / 2, y)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 7 * d
        p.color = Color.argb((255 * alpha).toInt(), 74, 20, 140)
        fitText(c, txt, w / 2, y, size, w * 0.8f)
        p.style = Paint.Style.FILL
        val col = neon[((now / 90) % neon.size).toInt()]
        p.color = Color.argb((255 * alpha).toInt(), Color.red(col), Color.green(col), Color.blue(col))
        fitText(c, txt, w / 2, y, size, w * 0.8f)
        c.restore()
        p.typeface = Typeface.DEFAULT_BOLD
        if (g.praiseBig) {
            val k = (age / 700f).coerceIn(0f, 1f)
            for (i in 0 until 12) {
                val a = i * 0.5236f
                val dist = (40 + 160 * k) * d
                val nc = neon[i % neon.size]
                p.color = Color.argb((255 * (1f - k) * alpha).toInt(), Color.red(nc), Color.green(nc), Color.blue(nc))
                star(c, w / 2 + cos(a) * dist, y - 12 * d + sin(a) * dist * 0.5f, (6 - 3 * k) * d)
            }
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, rad: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val a = -1.5708f + i * 0.6283f
            val rr = if (i % 2 == 0) rad else rad * 0.45f
            val sx = x + cos(a) * rr
            val sy = y + sin(a) * rr
            if (i == 0) starPath.moveTo(sx, sy) else starPath.lineTo(sx, sy)
        }
        starPath.close()
        c.drawPath(starPath, p)
    }

    private fun drawMessages(c: Canvas, w: Float, h: Float, now: Long) {
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        val b = g.banner
        if (b != null) {
            p.color = Color.WHITE
            p.setShadowLayer(4f, 2f, 2f, Color.BLACK)
            fitText(c, b, w / 2, g.playT + 40 * d, 15 * d, w * 0.6f)
            p.clearShadowLayer()
        }
        val pu = g.popup
        if (pu != null) {
            val age = now - g.popupAt
            if (age < 900) {
                p.color = Color.argb((255 * (1f - age / 900f)).toInt().coerceIn(0, 255), 128, 216, 255)
                p.setShadowLayer(3f, 1f, 1f, Color.BLACK)
                fitText(c, pu, g.cx, g.cy - 30 * d - age / 900f * 20 * d, 13 * d, w * 0.5f)
                p.clearShadowLayer()
            }
        }
        val m = g.message
        if (m != null) {
            p.color = Color.WHITE
            p.setShadowLayer(8f, 3f, 3f, Color.BLACK)
            fitText(c, m, w / 2, h * 0.6f, 32 * d, w * 0.8f)
            p.clearShadowLayer()
            if (g.stars > 0) {
                drawStars(c, w, h, now)
            }
        }
    }

    private fun drawStars(c: Canvas, w: Float, h: Float, now: Long) {
        val y = h * 0.6f - 50 * d
        for (i in 0 until 3) {
            val age = now - g.starsAt - 300L * i
            if (age < 0) continue
            val sc = bounce(age, 320L)
            val x = w / 2 + (i - 1) * 58 * d
            val rad = (if (i == 1) 26 else 21) * d * sc
            val yy = y - (if (i == 1) 6 * d else 0f)
            p.style = Paint.Style.FILL
            if (Yarn.drawPal(c, 4, x, yy, rad * 2.3f, (i - 1) * 8f + sin(now / 500.0 + i).toFloat() * 4f, false, 1f, 1f,
                    if (i < g.stars) 255 else 70)) continue
            p.color = if (i < g.stars) Color.parseColor("#FFD600") else Color.argb(120, 60, 60, 80)
            star(c, x, yy, rad)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = if (i < g.stars) Color.parseColor("#FF6F00") else Color.argb(160, 200, 200, 220)
            star(c, x, yy, rad)
            p.style = Paint.Style.FILL
        }
    }
}
