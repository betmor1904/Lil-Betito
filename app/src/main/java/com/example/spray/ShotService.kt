package com.example.spray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private class Barrier(val view: BarrierView, val lp: WindowManager.LayoutParams, var removed: Boolean = false)

class ShotService : Service(), Choreographer.FrameCallback {
    private lateinit var wm: WindowManager
    private var d = 1f
    private var sw = 0
    private var sh = 0

    private var sv: ShotView? = null
    private var hud: HudView? = null
    private var control: ControlView? = null
    private lateinit var slp: WindowManager.LayoutParams
    private lateinit var clp: WindowManager.LayoutParams
    private val barriers = ArrayList<Barrier>()
    private val handler = Handler(Looper.getMainLooper())

    // ball physics
    private var cx = 0f
    private var cy = 0f
    private var vx = 0f
    private var vy = 0f
    private var r = 0f
    private var floorY = 0f
    private var goalY = 0f
    private val bounce = 0.5f
    private var shotsLeft = 3
    private var flying = false
    private var over = false
    private var restFrames = 0
    private var boostFrames = 0
    private var lastNs = 0L

    // game mode
    private val phaseSetup = 0
    private val phasePlay = 1
    private val phaseQuiz = 2
    private val purposeBarriers = 0
    private val purposeRockets = 1
    private var quizPurpose = 0
    private var quizWho = ""
    private var rockets = 3
    private var power = 0          // ready power: 0 none, 1 buster, 2 warp, 3 sticky
    private var armedPower = 0     // the power that was ready when this shot was fired
    private var stickNow = false
    private var stickGrace = 0
    private var fx: FxView? = null
    private class Pickup(val view: PickupView, val type: Int, val x: Float, val y: Float)
    private val pickups = ArrayList<Pickup>()
    private val platforms = ArrayList<Barrier>()
    private var twoPlayer = false
    private var phase = phasePlay
    private var setterIdx = 0
    private val scores = IntArray(2)
    private var names = arrayOf("Player 1", "Player 2")
    private var mathMode = true
    private var quizIdx = 0
    private var earned = 0
    private var quiz: QuizView? = null
    private var sfx: Sfx? = null
    private val voices = arrayOf("wee", "wee2", "letsgo")
    private var closeBtn: CloseView? = null
    private var parts: PartsView? = null
    private var mascot: MascotView? = null
    private var combo = 0
    private val pent = intArrayOf(0, 2, 4, 7, 9, 12)   // happy notes the ding climbs through

    private val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun params(w: Int, h: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var f = flags
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startAsForeground()
        val a = i?.action
        if (a == "SHOT_STOP") { stopSelf(); return START_NOT_STICKY }
        val fresh = sv == null
        if (fresh) setup()
        sfx?.enabled = getSharedPreferences("betito", MODE_PRIVATE).getBoolean("sound", true)
        when (a) {
            "SHOT_2P" -> start2P()
            "SHOT_SOLO" -> startSolo()
            "SHOT_ADD" -> { if (fresh) startSolo(); addBarrier() }
            "SHOT_RESET" -> { if (fresh) startSolo() else resetMatch() }
            else -> { if (fresh) startSolo() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shot", "Lil Betito", NotificationManager.IMPORTANCE_LOW))
        fun action(name: String, label: String, icon: Int, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, code, Intent(this, ShotService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
        }
        val n = Notification.Builder(this, "shot")
            .setContentTitle("Lil Betito is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(action("SHOT_ADD", "Add barrier", android.R.drawable.ic_input_add, 11))
            .addAction(action("SHOT_RESET", "Reset", android.R.drawable.ic_menu_revert, 12))
            .addAction(action("SHOT_STOP", "Stop", android.R.drawable.ic_delete, 13))
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, n)
    }

    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sfx = Sfx(this)
        d = resources.displayMetrics.density
        sw = resources.displayMetrics.widthPixels
        sh = resources.displayMetrics.heightPixels
        r = 22 * d
        floorY = sh - 70 * d
        goalY = 64 * d

        val h = HudView(this)
        h.goalY = goalY
        wm.addView(h, params(sw, sh, touchable = false))
        hud = h

        val mv = MascotView(this)
        val mlp = params((150 * d).toInt(), (150 * d).toInt(), touchable = false)
        mlp.x = 0
        mlp.y = (floorY - 130 * d).toInt()
        wm.addView(mv, mlp)
        mascot = mv

        val pv = PartsView(this)
        wm.addView(pv, params(sw, sh, touchable = false))
        parts = pv

        val cv = ControlView(this)
        clp = params((100 * d).toInt(), (44 * d).toInt())
        clp.x = (12 * d).toInt()
        clp.y = (goalY + 10 * d).toInt()
        cv.setOnClickListener { if (twoPlayer && phase == phaseSetup) startPlay() }
        wm.addView(cv, clp)
        control = cv

        val xv = CloseView(this)
        val xlp = params((40 * d).toInt(), (40 * d).toInt())
        xlp.x = sw - (50 * d).toInt()
        xlp.y = (goalY + 40 * d).toInt()
        xv.setOnClickListener { stopSelf() }
        wm.addView(xv, xlp)
        closeBtn = xv

        val v = ShotView(this)
        slp = params((112 * d).toInt(), (112 * d).toInt())
        var dx0 = 0f
        var dy0 = 0f
        v.setOnTouchListener { _, e ->
    if (!flying && !over && phase == phasePlay) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY }
            MotionEvent.ACTION_MOVE -> {
                val dragX = dx0 - e.rawX
                val dragY = dy0 - e.rawY
                if (abs(dragX) > abs(dragY) && abs(dragY) < 40 * d) {
                    moveBeto(e.rawX)
                    dx0 = e.rawX
                    dy0 = e.rawY
                } else {
                    aim(dragX, dragY)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dragX = dx0 - e.rawX
                val dragY = dy0 - e.rawY
                if (abs(dragX) > abs(dragY) && abs(dragY) < 40 * d) {
                    moveBeto(e.rawX)
                } else {
                    fire(dragX, dragY)
                }
            }
            MotionEvent.ACTION_CANCEL -> clearAim()
        }
    }
    true
        }
        cx = sw / 2f
        cy = floorY - r
        slp.x = (cx - 56 * d).toInt()
        slp.y = (cy - 56 * d).toInt()
        wm.addView(v, slp)
        sv = v
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------- game modes ----------

    private fun startSolo() {
        handler.removeCallbacksAndMessages(null)
        dismissQuiz()
        stopFx()
        removePickups()
        mathMode = getSharedPreferences("betito", MODE_PRIVATE).getBoolean("math_on", false)
        twoPlayer = false
        phase = phasePlay
        lockBarriers(false)
        setControl(null)
        resetBall()
        hud?.let { it.score = null; it.banner = "LIL BETITO"; it.invalidate() }
        spawnPlatforms()
        if (mathMode) {
            phase = phaseQuiz
            hud?.let { it.banner = "ANSWER TO EARN ROCKETS"; it.invalidate() }
            startQuiz(purposeRockets, "LIL BETITO")
        } else {
            hud?.let { it.message = "LIL BETITO"; it.invalidate() }
            spawnPickups()
            handler.postDelayed({ hud?.let { it.message = null; it.invalidate() } }, 1500)
        }
    }

    private fun start2P() {
        handler.removeCallbacksAndMessages(null)
        val sp = getSharedPreferences("betito", MODE_PRIVATE)
        names = arrayOf(sp.getString("p1", "Player 1") ?: "Player 1", sp.getString("p2", "Player 2") ?: "Player 2")
        mathMode = sp.getBoolean("math_on", false)
        twoPlayer = true
        scores[0] = 0
        scores[1] = 0
        setterIdx = 0
        startSetup()
    }

    private fun resetMatch() {
        if (twoPlayer) start2P() else startSolo()
    }

    private fun scoreText() = "${names[0]} ${scores[0]} - ${scores[1]} ${names[1]}"

    private fun startSetup() {
        handler.removeCallbacksAndMessages(null)
        dismissQuiz()
        stopFx()
        removePickups()
        phase = phaseSetup
        resetBall()
        hud?.let {
            it.score = scoreText()
            it.message = null
        }
        if (mathMode) {
            removeAllBarriers()
            setControl(null)
            hud?.let { it.banner = "${names[setterIdx]}: ANSWER TO EARN BARRIERS"; it.invalidate() }
            startQuiz(purposeBarriers, names[setterIdx])
        } else {
            lockBarriers(false)
            setControl("DONE")
            hud?.let { it.banner = "${names[setterIdx]}: SET TRAPS"; it.invalidate() }
        }
        spawnPlatforms()
    }

    // ---------- math quiz ----------

    private fun startQuiz(purpose: Int, who: String) {
        quizPurpose = purpose
        quizWho = who
        earned = 0
        quizIdx = 0
        showQuiz()
        nextQuestion()
    }

    private fun showQuiz() {
        dismissQuiz()
        val q = QuizView(this)
        val lp = params(sw - (32 * d).toInt(), (300 * d).toInt())
        lp.x = (16 * d).toInt()
        lp.y = (sh * 0.22f).toInt()
        q.onPick = { i -> onAnswer(i) }
        wm.addView(q, lp)
        quiz = q
    }

    private fun dismissQuiz() {
        quiz?.let { wm.removeView(it) }
        quiz = null
    }

    private fun nextQuestion() {
        val q = quiz ?: return
        val a = Random.nextInt(2, 11)
        val b = Random.nextInt(2, 11)
        val ans = a * b
        val opts = LinkedHashSet<Int>()
        opts.add(ans)
        val cands = listOf(ans + a, ans - a, ans + b, ans - b, ans + 10, ans - 10, ans + 1, ans - 1)
        for (c in cands.shuffled()) {
            if (c > 0 && opts.size < 4) opts.add(c)
        }
        var extra = 2
        while (opts.size < 4) {
            opts.add(ans + extra)
            extra++
        }
        val list = opts.toMutableList()
        list.shuffle()
        q.reward = if (quizPurpose == purposeRockets) "rocket" else "barrier"
        q.title = "$quizWho: question ${quizIdx + 1} of 3   ($earned earned)"
        q.question = "$a \u00D7 $b = ?"
        q.options = list.map { it.toString() }
        q.correct = list.indexOf(ans)
        q.selected = -1
        q.invalidate()
    }

    private fun onAnswer(i: Int) {
        val q = quiz ?: return
        if (i == q.correct) {
            earned++
            snd("correct")
            if (quizPurpose == purposeRockets) {
                rockets = earned
                sv?.let { it.rockets = rockets; it.invalidate() }
                hud?.let { it.rockets = rockets; it.invalidate() }
            }
        } else {
            snd("wrong")
        }
        handler.postDelayed({
            if (quizIdx >= 2) {
                finishQuiz()
            } else {
                quizIdx++
                nextQuestion()
            }
        }, 1400)
    }

    private fun finishQuiz() {
        dismissQuiz()
        val n = earned
        if (n > 0) snd("pop")
        if (quizPurpose == purposeBarriers) {
            for (k in 0 until n) addBarrier(forced = true, slot = k)
            hud?.let {
                it.banner = if (n > 0) {
                    "${names[setterIdx]}: PLACE YOUR $n BARRIER" + (if (n == 1) "" else "S")
                } else {
                    "${names[setterIdx]}: NO BARRIERS EARNED"
                }
                it.invalidate()
            }
            setControl("DONE")
        } else {
            phase = phasePlay
            spawnPickups()
            val who = if (twoPlayer) "${names[1 - setterIdx]}: " else ""
            hud?.let {
                it.banner = who + "REACH THE TOP - $n ROCKET" + (if (n == 1) "" else "S")
                it.invalidate()
            }
        }
    }

    // ---------- bomb pickups, platforms + jackpot celebration ----------

    private fun spawnPickups() {
        removePickups()
        val margin = r + 16 * d
        val top = goalY + 100 * d
        val placed = ArrayList<FloatArray>()
        for (type in 1..3) {
            var px = sw / 2f
            var py = sh / 2f
            for (attempt in 0 until 40) {
                px = Random.nextFloat() * (sw - 2 * margin) + margin
                py = top + Random.nextFloat() * (floorY - 3 * r - top)
                var clear = true
                for (b in barriers) {
                    if (b.removed) continue
                    val l = b.lp.x - 30 * d
                    val t = b.lp.y - 30 * d
                    if (px > l && px < l + b.lp.width + 60 * d && py > t && py < t + b.lp.height + 60 * d) clear = false
                }
                for (o in placed) if (hypot(px - o[0], py - o[1]) < 90 * d) clear = false
                if (clear) break
            }
            placed.add(floatArrayOf(px, py))
            val v = PickupView(this, type)
            val lp = params((56 * d).toInt(), (56 * d).toInt(), touchable = false)
            lp.x = (px - 28 * d).toInt()
            lp.y = (py - 28 * d).toInt()
            wm.addView(v, lp)
            pickups.add(Pickup(v, type, px, py))
        }
    }

    private fun removePickups() {
        for (pk in pickups) wm.removeView(pk.view)
        pickups.clear()
    }

    private fun collectPickup(pk: Pickup) {
        wm.removeView(pk.view)
        pickups.remove(pk)
        snd("carrot")
        setPower(pk.type)
        mascot?.play(MascotView.Move.CHEER, when (pk.type) { 1 -> "BUSTER!"; 2 -> "WARP!"; else -> "STICKY!" })
        hud?.let {
            it.message = when (pk.type) { 1 -> "BUSTER BOMB!"; 2 -> "WARP BOMB!"; else -> "STICKY BOMB!" }
            it.invalidate()
        }
        handler.postDelayed({ if (!over) hud?.let { it.message = null; it.invalidate() } }, 1200)
    }

    /** The ready power shows as a glow on the turtle and a line under the goal. */
    private fun setPower(p: Int) {
        power = p
        sv?.let { it.power = p; it.invalidate() }
        hud?.let {
            it.powerLabel = when (p) {
                1 -> "BUSTER: BREAKS THE NEXT WALL"
                2 -> "WARP: SIDES WRAP AROUND"
                3 -> "STICKY: STICKS WHERE YOU LAND"
                else -> null
            }
            it.powerColor = when (p) {
                1 -> Color.parseColor("#FF9800")
                2 -> Color.parseColor("#B388FF")
                else -> Color.parseColor("#76FF03")
            }
            it.invalidate()
        }
    }

    /** Two platforms (one low, one high, on opposite sides). One jump can't reach the goal, so use them. */
    private fun clearPlatforms() {
        for (b in platforms) {
            if (!b.removed) {
                b.removed = true
                wm.removeView(b.view)
            }
            barriers.remove(b)
        }
        platforms.clear()
    }

    private fun spawnPlatforms() {
        clearPlatforms()
        val dist = floorY - 2 * r - goalY
        val lowOnLeft = Random.nextBoolean()
        for (i in 0 until 2) {
            val b = addBarrier(forced = true) ?: continue
            val w = ((if (i == 0) 170f else 110f) * d).toInt()
            val frac = if (i == 0) 0.38f + Random.nextFloat() * 0.08f else 0.72f + Random.nextFloat() * 0.06f
            val onLeft = (i == 0) == lowOnLeft
            val center = sw * (if (onLeft) 0.22f + Random.nextFloat() * 0.2f else 0.58f + Random.nextFloat() * 0.2f)
            b.lp.width = w
            b.lp.x = (center - w / 2f).toInt().coerceIn((4 * d).toInt(), sw - w - (4 * d).toInt())
            b.lp.y = (floorY - frac * dist).toInt()
            b.lp.flags = b.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(b.view, b.lp)
            platforms.add(b)
        }
    }

    private fun showFx() {
        stopFx()
        val f = FxView(this)
        f.durationMs = 3600L
        f.onFinished = { stopFx() }
        wm.addView(f, params(sw, sh, touchable = false))
        fx = f
    }

    private fun stopFx() {
        fx?.let { wm.removeView(it) }
        fx = null
    }

    private fun removeAllBarriers() {
        for (b in barriers) {
            if (!b.removed) {
                b.removed = true
                wm.removeView(b.view)
            }
        }
        barriers.clear()
    }

    private fun startPlay() {
        lockBarriers(true)
        snd("click")
        setControl(null)
        val shooter = names[1 - setterIdx]
        if (mathMode) {
            phase = phaseQuiz
            hud?.let { it.banner = "$shooter: ANSWER TO EARN ROCKETS"; it.invalidate() }
            startQuiz(purposeRockets, shooter)
        } else {
            phase = phasePlay
            hud?.let { it.banner = "$shooter: REACH THE TOP"; it.invalidate() }
            spawnPickups()
        }
    }

    private fun roundOver(shooterWon: Boolean) {
        val winner = if (shooterWon) 1 - setterIdx else setterIdx
        scores[winner]++
        val matchOver = scores[winner] >= 3
        if (!shooterWon) snd(if (matchOver) "win" else "lose")
        hud?.let {
            it.score = scoreText()
            it.message = when {
                matchOver -> "${names[winner]} WINS THE MATCH!"
                shooterWon -> "${names[winner]} MADE IT!"
                else -> "${names[winner]} BLOCKED IT!"
            }
            it.invalidate()
        }
        handler.postDelayed({
            if (matchOver) {
                start2P()
            } else {
                setterIdx = 1 - setterIdx
                startSetup()
            }
        }, if (matchOver) 4500L else if (shooterWon) 3800L else 3000L)
    }

    private fun lockBarriers(locked: Boolean) {
        for (b in barriers) {
            if (b.removed) continue
            b.lp.flags = if (locked) {
                b.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                b.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            wm.updateViewLayout(b.view, b.lp)
        }
    }

    private fun setControl(label: String?) {
        val c = control ?: return
        if (label == null) {
            c.visibility = View.INVISIBLE
            clp.flags = clp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            c.label = label
            c.visibility = View.VISIBLE
            clp.flags = clp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        wm.updateViewLayout(c, clp)
        c.invalidate()
    }

    // ---------- aiming and shooting ----------

    private fun launchV(px: Float, py: Float): FloatArray? {
        val len = hypot(px, py)
        if (len < 20 * d) return null
        val k = min(len, 150 * d) / len * vmax() / (150 * d)
        return floatArrayOf(px * k, py * k)
    }

    private fun tiltOf(x: Float, y: Float): Float =
        Math.toDegrees(atan2(y.toDouble(), max(abs(x), 0.1f * d).toDouble())).toFloat()

    /** Top launch speed. Even full power only rises about 64% of the way up, so you need a platform. */
    private fun vmax(): Float {
        val dist = floorY - 2 * r - goalY
        val vNeed = sqrt(2f * 0.55f * d * dist)
        val factor = floatArrayOf(0.60f, 0.76f, 0.78f, 0.80f)[rockets.coerceIn(0, 3)]
        return vNeed * factor
    }

    private fun clearAim() {
        hud?.let { it.dots = FloatArray(0); it.invalidate() }
        sv?.let { it.angle = 0f; it.invalidate() }
    }

    private fun aim(px: Float, py: Float) {
        val h = hud ?: return
        val s = sv
        val v = launchV(px, py)
        if (v == null) {
            h.dots = FloatArray(0)
            s?.angle = 0f
        } else {
            var x = cx
            var y = cy
            val ax = v[0]
            var ay = v[1]
            val pts = ArrayList<Float>()
            for (i in 1..30) {
                x += ax
                y += ay
                ay += 0.55f * d
                if (i % 2 == 0) { pts.add(x); pts.add(y) }
            }
            h.dots = pts.toFloatArray()
            s?.mirror = v[0] < 0f
            s?.angle = tiltOf(v[0], v[1])
        }
        h.invalidate()
        s?.invalidate()
    }

    private fun fire(px: Float, py: Float) {
        val v = launchV(px, py)
        hud?.let { it.dots = FloatArray(0); it.invalidate() }
        if (v == null) {
            sv?.let { it.angle = 0f; it.invalidate() }
            return
        }
        vx = v[0]
        vy = v[1]
        shotsLeft--
        hud?.let { it.shotsLeft = shotsLeft; it.combo = 0; it.invalidate() }
        combo = 0
        flying = true
        snd("launch", 0.2f + 0.12f * rockets)
        val voice = voices.random()
        snd(voice, 1f)
        mascot?.play(MascotView.randomFunny(), if (voice == "letsgo") "LET'S GOOO!" else "WEEEE!")
        armedPower = power
        stickGrace = 8
        stickNow = false
        boostFrames = 50
        restFrames = 0
    }

    private fun snd(name: String, vol: Float = 1f, gap: Long = 0L) {
        sfx?.play(name, vol, gap)
    }

    /**
     * Every wall / floor / barrier hit: bonk + a casino ding that climbs in pitch
     * with the combo + flying chunks. (x, y) is the hit point, (nx, ny) points away from the wall.
     */
    private fun hit(speed: Float, x: Float, y: Float, nx: Float, ny: Float, barrier: Boolean) {
        if (speed <= 2.5f * d) return
        combo++
        val vol = (speed / (14f * d)).coerceIn(0.4f, 1f)
        snd("bonk", vol, 150L)
        val semis = pent[min(combo - 1, pent.size - 1)]
        val rate = Math.pow(2.0, semis / 12.0).toFloat()
        sfx?.play("ding", vol, 60L, rate)
        if (combo >= 3) sfx?.play("coin", 0.7f, 120L)
        hud?.let { it.combo = combo; it.invalidate() }
        parts?.burst(x, y, nx, ny, speed, barrier, combo)
        if (combo == 3 || combo == 5 || combo == 8) {
            snd("woo", 0.9f)
            val dance = arrayOf(MascotView.Move.TWERK, MascotView.Move.FLOSS, MascotView.Move.SPIN).random()
            mascot?.play(dance, if (combo == 3) "WOO!" else if (combo == 5) "COMBO!" else "UNREAL!")
        }
    }

    /** BUSTER: the wall explodes and the power is used up. */
    private fun breakBarrier(b: Barrier) {
        val mx = b.lp.x + b.lp.width / 2f
        val my = b.lp.y + b.lp.height / 2f
        b.removed = true
        wm.removeView(b.view)
        barriers.remove(b)
        platforms.remove(b)
        snd("bust", 1f)
        parts?.smash(mx, my)
        parts?.smash(mx - b.lp.width / 4f, my)
        parts?.smash(mx + b.lp.width / 4f, my)
        mascot?.play(MascotView.Move.BACKFLIP, "SMASH!")
        armedPower = 0
        if (power == 1) setPower(0)
    }

    /** WARP: flash at the edge the turtle leaves and the edge he comes out of. */
    private fun warpFx(x: Float, dirX: Float) {
        snd("pop", 0.7f, 150L)
        parts?.burst(x, cy, dirX, 0f, 8f * d, false, 4)
    }

    /** STICKY: freeze exactly where he touched, then he can shoot again from there. */
    private fun stickBall() {
        stickNow = false
        vx = 0f
        vy = 0f
        snd("pop", 1f)
        parts?.goo(cx, cy)
        mascot?.play(MascotView.Move.CHEER, "STUCK IT!")
        armedPower = 0
        if (power == 3) setPower(0)
        endShot()
    }

    private fun applyPos() {
        val s = sv ?: return
        slp.x = (cx - 56 * d).toInt()
        slp.y = (cy - 56 * d).toInt()
        wm.updateViewLayout(s, slp)
    }
private fun moveBeto(x: Float) {
    val minX = r + 20 * d
    val maxX = sw - r - 20 * d
    cx = x.coerceIn(minX, maxX)
    cy = floorY - r
    applyPos()
}
    private fun resetBall() {
        cx = sw / 2f
        cy = floorY - r
        vx = 0f
        vy = 0f
        shotsLeft = 3
        flying = false
        over = false
        restFrames = 0
        boostFrames = 0
        rockets = if (mathMode) 0 else 3
        power = 0
        armedPower = 0
        stickNow = false
        combo = 0
        sv?.let { it.angle = 0f; it.boost = false; it.mirror = false; it.rockets = rockets; it.power = 0 }
        hud?.let { it.combo = 0; it.shotsLeft = 3; it.rockets = rockets; it.powerLabel = null; it.message = null; it.dots = FloatArray(0); it.invalidate() }
        applyPos()
        sv?.invalidate()
    }

    private fun win() {
        flying = false
        over = true
        vx = 0f
        vy = 0f
        sv?.let { it.boost = false; it.angle = 0f }
        removePickups()
        snd("jackpot")
        mascot?.play(MascotView.Move.BACKFLIP, "JACKPOT!")
        showFx()
        if (twoPlayer) {
            roundOver(true)
        } else {
            hud?.let { it.message = "YOU MADE IT!"; it.invalidate() }
            handler.postDelayed({ startSolo() }, 3800)
        }
    }

    private fun endShot() {
        flying = false
        if (combo >= 2) handler.postDelayed({ hud?.let { it.combo = 0; it.invalidate() } }, 1400)
        combo = 0
        sv?.let { it.boost = false; it.angle = 0f; it.invalidate() }
        if (armedPower != 0) {
            if (power == armedPower) setPower(0)
            armedPower = 0
        }
        if (shotsLeft <= 0) {
            over = true
            mascot?.play(MascotView.Move.FACEPALM, "OOF!")
            if (twoPlayer) {
                roundOver(false)
            } else {
                snd("lose")
                hud?.let { it.message = "OUT OF SHOTS"; it.invalidate() }
                handler.postDelayed({ startSolo() }, 2500)
            }
        }
    }

    // ---------- barriers ----------

    private fun addBarrier(forced: Boolean = false, slot: Int = 0): Barrier? {
        if (!forced && twoPlayer && (phase != phaseSetup || mathMode)) return null
        val v = BarrierView(this)
        val lp = params((150 * d).toInt(), (40 * d).toInt())
        lp.x = (sw / 2f - 75 * d).toInt()
        lp.y = (sh * 0.42f + slot * 55 * d).toInt()
        val b = Barrier(v, lp)
        val widths = floatArrayOf(90f, 150f, 230f)
        var wi = 1
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (b.removed) return true
                wi = (wi + 1) % 3
                lp.width = (widths[wi] * d).toInt()
                wm.updateViewLayout(v, lp)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (b.removed) return
                b.removed = true
                wm.removeView(v)
                barriers.remove(b)
            }
        })
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        v.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (!b.removed) {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        barriers.add(b)
        return b
    }

    // ---------- physics loop ----------

    override fun doFrame(ns: Long) {
        val s = sv ?: return
        val dt = if (lastNs == 0L) 1f else ((ns - lastNs) / 16_666_667f).coerceAtMost(3f)
        lastNs = ns

        if (flying) {
            val hdt = dt / 3f
            var grounded = false
            for (step in 0 until 3) {
                vy += 0.55f * d * hdt
                cx += vx * hdt
                cy += vy * hdt

                if (armedPower == 2) {
                    // WARP: sides are portals instead of walls
                    if (cx < -r) { warpFx(0f, 1f); cx += sw + 2 * r; warpFx(sw.toFloat(), -1f) }
                    else if (cx > sw + r) { warpFx(sw.toFloat(), -1f); cx -= sw + 2 * r; warpFx(0f, 1f) }
                } else {
                    if (cx < r) {
                        hit(abs(vx), 0f, cy, 1f, 0f, false)
                        if (armedPower == 3 && stickGrace <= 0) stickNow = true
                        cx = r
                        vx = abs(vx) * bounce
                    }
                    if (cx > sw - r) {
                        hit(abs(vx), sw.toFloat(), cy, -1f, 0f, false)
                        if (armedPower == 3 && stickGrace <= 0) stickNow = true
                        cx = sw - r
                        vx = -abs(vx) * bounce
                    }
                }
                if (cy > floorY - r) {
                    cy = floorY - r
                    if (vy > 0f) {
                        hit(vy, cx, floorY, 0f, -1f, false)
                        if (armedPower == 3 && stickGrace <= 0) stickNow = true
                        vy = -vy * bounce
                    }
                    if (abs(vy) < 1.2f * d) vy = 0f
                    vx *= 0.97f
                    grounded = true
                }

                var toBreak: Barrier? = null
                for (b in barriers) {
                    val l = b.lp.x.toFloat()
                    val t = b.lp.y.toFloat()
                    val rr = l + b.lp.width
                    val bb = t + b.lp.height
                    val qx = cx.coerceIn(l, rr)
                    val qy = cy.coerceIn(t, bb)
                    val dx = cx - qx
                    val dy = cy - qy
                    val d2 = dx * dx + dy * dy
                    if (armedPower == 1) {
                        // BUSTER: the first wall he touches breaks
                        // (a real hit only: resting on a platform or sliding along it doesn't break it)
                        if (d2 < r * r && toBreak == null && stickGrace <= 0) {
                            val dist = sqrt(d2)
                            val vn = if (dist > 0.01f) (vx * dx + vy * dy) / dist else -999f
                            if (vn < -2.5f * d) toBreak = b
                        }
                        continue
                    }
                    if (d2 < r * r) {
                        var nx = 0f
                        var ny = -1f
                        if (d2 > 0.0001f) {
                            val dist = sqrt(d2)
                            nx = dx / dist
                            ny = dy / dist
                            cx += nx * (r - dist)
                            cy += ny * (r - dist)
                        } else {
                            cy = t - r
                        }
                        val vn = vx * nx + vy * ny
                        if (vn < 0f) {
                            hit(-vn, qx, qy, nx, ny, true)
                            if (armedPower == 3 && stickGrace <= 0) stickNow = true
                            vx -= (1 + bounce) * vn * nx
                            vy -= (1 + bounce) * vn * ny
                        }
                        if (ny < -0.5f) {
                            grounded = true
                            vx *= 0.97f
                            if (abs(vy) < 1.2f * d) vy = 0f
                        }
                    }
                }

                toBreak?.let { breakBarrier(it) }

                if (cy - r <= goalY) { win(); break }
                if (stickNow) { stickBall(); break }
            }

            if (stickGrace > 0) stickGrace--
            if (!over && flying) {
                var got: Pickup? = null
                for (pk in pickups) {
                    if (hypot(cx - pk.x, cy - pk.y) < r + 18 * d) { got = pk; break }
                }
                got?.let { collectPickup(it) }
                if (abs(vx) > 0.5f * d) s.mirror = vx < 0f
                s.angle = tiltOf(vx, vy)
                if (boostFrames > 0) boostFrames--
                s.boost = boostFrames > 0 && rockets > 0
                if (grounded && hypot(vx, vy) < 0.8f * d) {
                    restFrames++
                } else {
                    restFrames = 0
                }
                if (restFrames > 20) endShot()
            }
            applyPos()
            s.invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this)
        handler.removeCallbacksAndMessages(null)
        if (::wm.isInitialized) {
            dismissQuiz()
            stopFx()
            removePickups()
            for (b in barriers) if (!b.removed) wm.removeView(b.view)
            barriers.clear()
            sv?.let { wm.removeView(it) }
            control?.let { wm.removeView(it) }
            closeBtn?.let { wm.removeView(it) }
            parts?.let { wm.removeView(it) }
            mascot?.let { wm.removeView(it) }
            hud?.let { wm.removeView(it) }
        }
        sfx?.release()
        sfx = null
        sv = null
        control = null
        closeBtn = null
        parts = null
        mascot = null
        hud = null
        super.onDestroy()
    }
}
