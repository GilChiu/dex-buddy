package dev.dexbuddy

/**
 * Tiny shared flag read by the Simple Voice Chat plugin, which Voice Chat may load on either side,
 * so it must not touch client classes.
 */
object VoiceState {
    /** True while the push-to-talk key is held and Dex Buddy is recording. */
    @Volatile
    var recording: Boolean = false

    /** Silence Simple Voice Chat while recording, so nearby players don't hear you talk to your Dex. */
    @Volatile
    var muteVoiceChat: Boolean = true

    /**
     * Until this time (System.currentTimeMillis) your own Dex is talking, plus a short echo tail. Your mic
     * is held back from Simple Voice Chat then, so nearby players don't hear your Dex through your speakers.
     */
    @Volatile
    var dexSpeakingUntil: Long = 0L

    /** Hold back the mic while your Dex talks (voice.muteVoiceChatWhileDexSpeaks). */
    @Volatile
    var muteWhileDexSpeaks: Boolean = true

    fun shouldSilenceMic(): Boolean =
        (recording && muteVoiceChat) || (muteWhileDexSpeaks && System.currentTimeMillis() < dexSpeakingUntil)
}
