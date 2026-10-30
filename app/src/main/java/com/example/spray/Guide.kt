package com.example.spray

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/** Everything Octo can explain. The key matches an icon in Art.icon and a tip in Game. */
object Guide {
    class Entry(val key: String, val title: String, val text: String, val color: Int)

    val entries = listOf(
        Entry("shoot", "SHOOTING", "When Betito stops (green ring), touch anywhere, pull back and let go. Pull further for more power.", Color.parseColor("#76FF03")),
        Entry("bank", "BANK SHOTS", "Rocks and shells are bouncy! The red dots trace your whole path, with a ring at each bounce (up to 3). Line up the ricochets to get around corners. Pull back into the X, or tap a 2nd finger, to cancel without spending a shot.", Color.parseColor("#80DEEA")),
        Entry("goal", "THE HOLE", "Get Betito through the glowing gap in the right wall.", Color.parseColor("#76FF03")),
        Entry("shell", "SHELLS", "Tap a shell in the tray (bottom right), pull and let go to place it. BOUNCY (orange) kicks you out faster, RUBBER (green) is a clean mirror, MEGA (pink) is a huge kick, SPONGE (gray) soaks the hit, TWIST (blue) bends your bounce. Shells cost no shot. Tap a placed shell to take it back.", Color.parseColor("#FFB74D")),
        Entry("score", "PAR & SCORING", "Par is the shots a hole should take. Finish OVER par and you must REPLAY it! 1 under = BIRDIE, 2 under = EAGLE.", Color.parseColor("#FF5252")),
    )

    fun find(key: String): Entry? = entries.firstOrNull { it.key == key }
}

/**
 * Octo's Field Guide: a paged, illustrated list of every item, 4 per page.
 * Used by the main menu (How to play) and the pause menu (Item guide).
 */
class GuideBook(private val d: Float) {
    var page = 0
    val perPage = 4
    val pages get() = (Guide.entries.size + perPage - 1) / perPage
    private val prevR = RectF()
    private val nextR = RectF()
    private val closeR = RectF()
    private val panel = RectF()
    private val cell = RectF()

    fun draw(c: Canvas, p: Paint, path: Path, w: Float, h: Float, now: Long) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(190, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        val m = 14 * d
        panel.set(m, m, w - m, h - m)
        p.color = Color.parseColor("#0D2B4F")
        c.drawRoundRect(panel, 22 * d, 22 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.color = Color.parseColor("#FFD54F")
        c.drawRoundRect(panel, 22 * d, 22 * d, p)
        p.style = Paint.Style.FILL

        // header: Octo + title
        Art.octo(c, p, path, panel.left + 34 * d, panel.top + 30 * d, 15 * d, now)
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        p.textSize = 20 * d
        p.color = Color.WHITE
        p.setShadowLayer(3f, 1f, 2f, Color.BLACK)
        c.drawText("OCTO'S FIELD GUIDE", panel.left + 62 * d, panel.top + 34 * d, p)
        p.clearShadowLayer()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 11 * d
        p.color = Color.argb(170, 255, 255, 255)
        c.drawText("Page ${page + 1} of $pages", panel.left + 62 * d, panel.top + 50 * d, p)

        // close X
        closeR.set(panel.right - 50 * d, panel.top + 10 * d, panel.right - 12 * d, panel.top + 48 * d)
        p.color = Color.argb(220, 20, 20, 30)
        c.drawCircle(closeR.centerX(), closeR.centerY(), 17 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.WHITE
        val k = 6 * d
        c.drawLine(closeR.centerX() - k, closeR.centerY() - k, closeR.centerX() + k, closeR.centerY() + k, p)
        c.drawLine(closeR.centerX() + k, closeR.centerY() - k, closeR.centerX() - k, closeR.centerY() + k, p)
        p.style = Paint.Style.FILL

        // 2 x 2 grid of items
        val gridT = panel.top + 62 * d
        val gridB = panel.bottom - 54 * d
        val gx = panel.left + 14 * d
        val gw = panel.width() - 28 * d
        val cw = (gw - 10 * d) / 2f
        val ch = (gridB - gridT - 10 * d) / 2f
        for (i in 0 until perPage) {
            val idx = page * perPage + i
            if (idx >= Guide.entries.size) break
            val e = Guide.entries[idx]
            val col = i % 2
            val row = i / 2
            cell.set(gx + col * (cw + 10 * d), gridT + row * (ch + 10 * d), gx + col * (cw + 10 * d) + cw, gridT + row * (ch + 10 * d) + ch)
            p.color = Color.argb(140, 3, 20, 40)
            c.drawRoundRect(cell, 14 * d, 14 * d, p)
            val iconS = min(26 * d, ch / 2f - 8 * d)
            val ix = cell.left + 12 * d + iconS
            val iy = cell.centerY()
            p.color = Color.argb(50, Color.red(e.color), Color.green(e.color), Color.blue(e.color))
            c.drawCircle(ix, iy, iconS + 6 * d, p)
            Art.icon(c, p, path, e.key, ix, iy, iconS, now, d)
            val tx = ix + iconS + 14 * d
            val tw = cell.right - tx - 10 * d
            p.textAlign = Paint.Align.LEFT
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 14 * d
            p.color = e.color
            var ty = cell.top + 22 * d
            c.drawText(e.title, tx, ty, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 12 * d
            p.color = Color.WHITE
            val lines = Art.wrap(p, e.text, tw)
            val lh = 15 * d
            val room = ((cell.bottom - ty - 6 * d) / lh).toInt()
            if (lines.size > room) p.textSize = 11 * d
            for (ln in Art.wrap(p, e.text, tw)) {
                ty += lh
                if (ty > cell.bottom - 4 * d) break
                c.drawText(ln, tx, ty, p)
            }
        }

        // page buttons
        val by = panel.bottom - 46 * d
        prevR.set(panel.left + 16 * d, by, panel.left + 136 * d, by + 36 * d)
        nextR.set(panel.right - 136 * d, by, panel.right - 16 * d, by + 36 * d)
        if (page > 0) Art.button(c, p, prevR, "< BACK", Color.parseColor("#1565C0"), d, 14f)
        Art.button(c, p, nextR, if (page < pages - 1) "NEXT >" else "GOT IT!", Color.parseColor("#2E7D32"), d, 14f)
        // page dots
        for (i in 0 until pages) {
            p.color = if (i == page) Color.parseColor("#FFD54F") else Color.argb(90, 255, 255, 255)
            c.drawCircle(panel.centerX() + (i - (pages - 1) / 2f) * 14 * d, by + 18 * d, 4 * d, p)
        }
    }

    /** Returns true when the guide should close. */
    fun tap(x: Float, y: Float): Boolean {
        if (closeR.contains(x, y)) return true
        if (page > 0 && prevR.contains(x, y)) { page--; return false }
        if (nextR.contains(x, y)) {
            if (page < pages - 1) page++ else return true
        }
        return false
    }
}
