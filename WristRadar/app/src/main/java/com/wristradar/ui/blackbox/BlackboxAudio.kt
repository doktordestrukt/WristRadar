package com.wristradar.ui.blackbox

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.wristradar.R

/**
 * Sound for the BLACKBOX screen, all bundled in the APK (res/raw):
 *   glitch2 -> then hacker_music looping until dismissed -> glitch3 on dismiss.
 * If a file named res/raw/laugh.* is added later, it plays together with glitch2.
 */
class BlackboxAudio(private val ctx: Context) {
    private var sfx: MediaPlayer? = null
    private var music: MediaPlayer? = null
    private var musicWanted = false
    private var stopped = false

    fun start(sound: Boolean, withMusic: Boolean) {
        musicWanted = withMusic
        if (!sound) { startMusic(); return }
        laughResId()?.let { oneShot(it) }
        sfx = create(R.raw.glitch2)?.apply {
            setOnCompletionListener { mp -> mp.release(); if (sfx === mp) sfx = null; startMusic() }
            start()
        }
        if (sfx == null) startMusic()
    }

    private fun startMusic() {
        if (!musicWanted || stopped || music != null) return
        music = create(R.raw.hacker_music)?.apply {
            isLooping = true
            setVolume(0.8f, 0.8f)
            start()
        }
    }

    fun pause() {
        runCatching { music?.takeIf { it.isPlaying }?.pause() }
        runCatching { sfx?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume() {
        if (stopped) return
        runCatching { sfx?.start() }
        runCatching { music?.start() }
    }

    fun stop(outro: Boolean) {
        stopped = true
        runCatching { sfx?.release() }; sfx = null
        runCatching { music?.release() }; music = null
        if (outro) oneShot(R.raw.glitch3)
    }

    private fun oneShot(resId: Int) {
        create(resId)?.apply { setOnCompletionListener { it.release() }; start() }
    }

    private fun create(resId: Int): MediaPlayer? = runCatching {
        MediaPlayer.create(
            ctx, resId,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            0,
        )
    }.getOrNull()

    @SuppressLint("DiscouragedApi")
    private fun laughResId(): Int? = ctx.resources.getIdentifier("laugh", "raw", ctx.packageName).takeIf { it != 0 }

    companion object {
        /** Short glitch used when opening the security log. */
        fun glitch1(ctx: Context) {
            runCatching { MediaPlayer.create(ctx, R.raw.glitch1)?.apply { setOnCompletionListener { it.release() }; start() } }
        }
    }
}
