package dev.dexbuddy.client.tts

import dev.dexbuddy.client.ui.Subtitles

/**
 * "Be quiet for 10 minutes": the companion's voice is off until then (or until "you can talk now").
 * It still answers what you ask, as text on screen; it just doesn't speak or chime in on its own.
 */
object Hush {
    @Volatile var until = 0L
        private set

    val active: Boolean get() = System.currentTimeMillis() < until

    fun quietFor(minutes: Int) {
        val m = minutes.coerceIn(1, 240)
        dev.dexbuddy.client.antics.Tricks.used("quiet")
        until = System.currentTimeMillis() + m * 60_000L
        VoicePlayer.stop()
        Subtitles.push("Okay, I'll stay quiet for $m minute${if (m == 1) "" else "s"}. Say \"you can talk now\" when you miss me!")
    }

    fun talkAgain() {
        val was = active
        until = 0L
        if (was) Subtitles.push("Yay, I can talk again!")
    }

    /** "Stop": cuts off whatever it's saying right now. */
    fun stopTalking() = VoicePlayer.stop()
}
