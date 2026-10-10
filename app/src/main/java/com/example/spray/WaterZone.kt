package com.example.spray

/**
 * A patch of moving water. Rect zones (updraft, side stream, riptide) push along (dx, dy).
 * Whirlpools spin around their center; spin = 1 clockwise, -1 counter-clockwise.
 * speed is in pixels per 60fps frame. power fades 0..1 for pulsing currents.
 */
class WaterZone(
    var kind: Int,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    var dx: Float,
    var dy: Float,
    val speed: Float,
    var spin: Float = 1f,
    val pulse: Boolean = false,
    val phaseMs: Long = 0L
) {
    val id: Int = nextId++
    var power = 1f
    var forcedUntil = 0L      // SPIN forces pulsing currents fully on until this time
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
    val rad get() = (r - l) / 2f

    /** A buoy was hit: reverse the flow. A backward riptide becomes a forward stream and vice versa. */
    fun flip() {
        if (kind == WHIRL) { spin = -spin; return }
        dx = -dx
        dy = -dy
        if (dx > 0f) kind = UP else if (dx < 0f) kind = RIP
    }

    companion object {
        const val UP = 0
        const val SIDE = 1
        const val RIP = 2
        const val WHIRL = 3
        private var nextId = 0
    }
}
