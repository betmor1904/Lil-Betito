package com.example.spray

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock

/** Tiny sound-effect player. Sounds live in res/raw. */
class Sfx(ctx: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = HashMap<String, Int>()
    private val last = HashMap<String, Long>()
    var enabled = true

    init {
        ids["launch"] = pool.load(ctx, R.raw.launch, 1)
        ids["bounce"] = pool.load(ctx, R.raw.bounce, 1)
        ids["thud"] = pool.load(ctx, R.raw.thud, 1)
        ids["pop"] = pool.load(ctx, R.raw.pop, 1)
        ids["click"] = pool.load(ctx, R.raw.click, 1)
        ids["correct"] = pool.load(ctx, R.raw.correct, 1)
        ids["wrong"] = pool.load(ctx, R.raw.wrong, 1)
        ids["win"] = pool.load(ctx, R.raw.win, 1)
        ids["lose"] = pool.load(ctx, R.raw.lose, 1)
        ids["carrot"] = pool.load(ctx, R.raw.carrot, 1)
        ids["bust"] = pool.load(ctx, R.raw.bust, 1)
        ids["jackpot"] = pool.load(ctx, R.raw.jackpot, 1)
        ids["bonk"] = pool.load(ctx, R.raw.bonk, 1)
        ids["wee"] = pool.load(ctx, R.raw.wee, 1)
        ids["wee2"] = pool.load(ctx, R.raw.wee2, 1)
        ids["letsgo"] = pool.load(ctx, R.raw.letsgo, 1)
        ids["ding"] = pool.load(ctx, R.raw.ding, 1)
        ids["coin"] = pool.load(ctx, R.raw.coin, 1)
        ids["woo"] = pool.load(ctx, R.raw.woo, 1)
    }

    fun play(name: String, volume: Float = 1f, minGapMs: Long = 0L, rate: Float = 1f) {
        if (!enabled) return
        val id = ids[name] ?: return
        val now = SystemClock.uptimeMillis()
        if (now - (last[name] ?: 0L) < minGapMs) return
        last[name] = now
        pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() {
        pool.release()
    }
}
