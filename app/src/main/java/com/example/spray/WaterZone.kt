package com.example.spray

/**
 * A patch of moving water. Rect zones (updraft, side stream, riptide) push along (dx, dy).
 * Whirlpools spin around their center; spin = 1 clockwise, -1 counter-clockwise.
 * speed is in pixels per 60fps frame. power fades 0..1 for pulsing currents.
 */
class WaterZone(
    val kind: Int,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val dx: Float,
    val dy: Float,
    val speed: Float,
    val spin: Float = 1f,
    val pulse: Boolean = false,
    val phaseMs: Long = 0L
) {
    var power = 1f
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
    val rad get() = (r - l) / 2f

    companion object {
        const val UP = 0
        const val SIDE = 1
        const val RIP = 2
        const val WHIRL = 3
    }
}
