package dev.dexbuddy.client.tts

import dev.dexbuddy.client.BuddyConfig
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The companion's little electric chirps, synthesized in code (original sounds, no files). */
object Sfx {
    private const val RATE = 22050

    /** Rising two-note chirp: "I'm listening". */
    fun listen() = play(chirp(listOf(880.0 to 0.06, 1320.0 to 0.08)))

    /** Short falling blip: "got it, thinking". */
    fun send() = play(chirp(listOf(1180.0 to 0.05, 790.0 to 0.07)))

    /** Low double buzz: something went wrong. */
    fun error() = play(chirp(listOf(220.0 to 0.09, 0.0 to 0.04, 196.0 to 0.12), square = true))

    /** Radar ping; [pitch] 1 = normal, higher = more urgent. */
    fun blip(pitch: Double) = play(chirp(listOf(1250.0 * pitch to 0.045)), gain = 0.35f)

    /** The electric "bzzt" the companion says instead of reading the word out. */
    fun zap(): Clip = Clip(zapPcm(), RATE)

    private fun play(pcm: ShortArray, gain: Float = 0.55f) {
        if (!BuddyConfig.settings.tts.soundEffects) return
        val level = gain
        Thread({
            try {
                val o = Minecraft.getInstance().options
                val gain = o.getSoundSourceVolume(SoundSource.MASTER) * o.getSoundSourceVolume(SoundSource.VOICE) * level
                val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format); line.start()
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    val s = (pcm[i] * gain).toInt()
                    bytes[i * 2] = (s and 0xFF).toByte(); bytes[i * 2 + 1] = (s shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size); line.drain(); line.close()
            } catch (_: Exception) {
            }
        }, "dexbuddy-sfx").apply { isDaemon = true; start() }
    }

    private fun chirp(notes: List<Pair<Double, Double>>, square: Boolean = false): ShortArray {
        val out = ArrayList<Short>()
        var phase = 0.0
        for ((freq, seconds) in notes) {
            val n = (seconds * RATE).toInt()
            for (i in 0 until n) {
                val env = minOf(1.0, i / (RATE * 0.004)) * minOf(1.0, (n - i) / (RATE * 0.012))
                phase += 2 * PI * freq / RATE
                val wave = if (freq == 0.0) 0.0 else if (square) (if (sin(phase) >= 0) 0.6 else -0.6) else sin(phase) + 0.25 * sin(phase * 3)
                out += (wave * env * 9000).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private fun zapPcm(): ShortArray {
        val n = (0.22 * RATE).toInt()
        val rnd = java.util.Random(7)
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val env = exp(-t * 9) * minOf(1.0, i / (RATE * 0.003))
            val buzz = if (sin(2 * PI * 150 * t * (1 + t * 2)) >= 0) 0.5 else -0.5
            val crackle = (rnd.nextDouble() - 0.5) * 0.5 * (if (sin(2 * PI * 31 * t) > 0.3) 1 else 0)
            ((buzz + crackle) * env * 8000).toInt().toShort()
        }
    }
}
