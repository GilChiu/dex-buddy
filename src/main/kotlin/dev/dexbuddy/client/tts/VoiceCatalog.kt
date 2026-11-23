package dev.dexbuddy.client.tts

/**
 * Voices known to work with the Piper engine we download (2023.11.14), each with a starting pitch/buzz
 * that suits the companion, and the license of its recordings.
 */
object VoiceCatalog {
    class Preset(val voice: String, val speaker: Int, val pitch: Double, val buzz: Double, val license: String, val about: String)

    val presets: Map<String, Preset> = linkedMapOf(
        "ljspeech" to Preset("en_US-ljspeech-medium", -1, 1.22, 0.28, "public domain", "clear female voice, strong effect"),
        "ljspeech-light" to Preset("en_US-ljspeech-medium", -1, 1.10, 0.12, "public domain", "same voice, gentler effect"),
        "kristin" to Preset("en_US-kristin-medium", -1, 1.15, 0.15, "public domain", "warm female voice"),
        "bryce" to Preset("en_US-bryce-medium", -1, 1.28, 0.18, "public domain", "male voice pitched up"),
        "libritts" to Preset("en_US-libritts_r-medium", 14, 1.12, 0.12, "CC BY 4.0 (credit LibriTTS-R)", "904 speakers: pick with /dexbuddy voice speaker <0-903>"),
    )

    const val SAMPLE_LINE = "Hey Trainer, I'm Volty! Garchomp is weak to Ice, four times over. Want to go find a shiny together?"

    /** Max speaker id for multi-speaker voices we know about. */
    fun speakerCount(voice: String): Int = if (voice.contains("libritts_r")) 904 else 1
}
