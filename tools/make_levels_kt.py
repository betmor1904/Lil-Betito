#!/usr/bin/env python3
"""Wraps the output of levelgen (one `Lvl(...)` line per hole) into app/.../Levels.kt.

usage: make_levels_kt.py out.kt level_lines.txt [more_lines.txt ...]
"""
import sys

out = sys.argv[1]
lines = []
for fn in sys.argv[2:]:
    for ln in open(fn):
        ln = ln.rstrip()
        if ln.strip().startswith("Lvl("):
            lines.append(ln)

kt = '''package com.example.spray

/**
 * One-shot holes, made by tools/oneshot.cpp. Every hole was built around a solution: a shot that goes
 * from the start to the exit using flat PADDLES placed (and turned in 15 degree steps) by the player.
 * The solver checked the shot still wins with small aim/power errors, and that the hole can NOT be
 * solved in two shots without paddles. `shells` is how many paddles the tray holds; `ropes` are swinging shells you can cut (anchor x, y, length, phase, period ms, swing in radians); `hint` is the
 * solution (x, y, angle per paddle in design units, then the aim in degrees and the power 0..1).
 * Par is always 2 (1 shot = birdie).
 *
 * Coordinates are design units (1 unit = 1dp on a 840 x 300 play area); walls are
 * [left, top, right, bottom, kind] with kind 0 = column wall, 1 = rock.
 * After the last hole the list repeats, mirrored top to bottom.
 */
class Lvl(val startY: Float, val exitT: Float, val exitB: Float, val par: Int, val shells: Int, val walls: FloatArray, val hint: FloatArray = floatArrayOf(), val ropes: FloatArray = floatArrayOf())

object Levels {
    const val PLAY_H = 300f
    const val EXIT_X = 822f

    fun get(level: Int): Pair<Lvl, Boolean> {
        val i = (level - 1).coerceAtLeast(0)
        return Pair(all[i % all.size], (i / all.size) % 2 == 1)
    }

    val all = listOf(
@@LEVELS@@
    )
}
'''.replace('@@LEVELS@@', "\n".join(lines))
open(out, "w").write(kt)
print("wrote", out, len(lines), "levels")
