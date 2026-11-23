package dev.dexbuddy.client.tts

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Turns a plain voice into the companion's: higher pitch and a light electric buzz. Our own DSP, so the
 * result is an original voice, not a copy of anyone's.
 */
object VoiceFx {
    /**
     * [pitch] > 1 raises the voice. Piper already stretched the speech by the same factor, so resampling
     * here brings the length back to normal while the pitch goes up.
     */
    fun apply(pcm: ShortArray, rate: Int, pitch: Double, buzz: Double, wobble: Double = 0.0): ShortArray {
        val shifted = if (pitch == 1.0 && wobble == 0.0) pcm else resample(pcm, pitch, wobble, rate)
        if (buzz <= 0.0) return shifted
        val out = ShortArray(shifted.size)
        val depth = buzz.coerceIn(0.0, 1.0) * 0.45
        val hum = 2 * PI * 92.0 / rate      // ring-modulation rate: the "electric" flutter
        val crackle = 2 * PI * 1730.0 / rate // faint high shimmer
        for (i in shifted.indices) {
            val s = shifted[i] / 32768.0
            val ring = 1.0 - depth + depth * (0.5 + 0.5 * sin(hum * i))
            var v = s * ring + s * depth * 0.18 * sin(crackle * i)
            v = tanh(v * (1.0 + buzz * 1.4)) / tanh(1.0 + buzz * 1.4) // gentle saturation
            out[i] = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Linear-interpolation resample, reading [factor] samples per output sample. [wobble] slowly sways the
     * pitch up and down (a few percent, under once a second) so long sentences don't sound flat.
     */
    private fun resample(pcm: ShortArray, factor: Double, wobble: Double, rate: Int): ShortArray {
        val out = ArrayList<Short>((pcm.size / factor).toInt() + 16)
        val sway = 2 * PI * 0.85 / rate
        val phase = Math.random() * 2 * PI
        var pos = 0.0
        var i = 0
        while (pos < pcm.size - 1) {
            val a = pos.toInt()
            val f = pos - a
            out += (pcm[a] * (1 - f) + pcm[a + 1] * f).toInt().toShort()
            pos += factor * (1 + wobble * sin(sway * i + phase))
            i++
        }
        return out.toShortArray()
    }
}
