package dev.dexbuddy.client.tts

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.sqrt

/**
 * Plays clips back to back on one audio line. Tracks loudness for the mouth animation and calls each
 * clip's onStart (on the client thread) the moment it begins, so subtitles match the voice.
 */
object VoicePlayer {
    private class Item(val clip: Clip, val onStart: (() -> Unit)?, val generation: Int)

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var generation = 0
    private var line: SourceDataLine? = null
    private var lineRate = 0

    /** True from the first sample of a reply until the last one has played. */
    @Volatile var speaking: Boolean = false
        private set

    /** Loudness of what is playing right now, 0..1 (drives the mouth). */
    @Volatile var level: Float = 0f
        private set

    init {
        Thread({ loop() }, "dexbuddy-voice").apply { isDaemon = true; start() }
    }

    fun enqueue(clip: Clip, onStart: (() -> Unit)? = null) {
        queue.put(Item(clip, onStart, generation))
    }

    /** Cuts the companion off: drops everything queued and silences the line. */
    fun stop() {
        generation++
        queue.clear()
        try { line?.flush() } catch (_: Exception) {}
        speaking = false
        level = 0f
    }

    private fun loop() {
        while (true) {
            val item = try { queue.take() } catch (_: InterruptedException) { return }
            if (item.generation != generation) continue
            try {
                play(item)
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] voice playback failed: {}", e.toString())
                try { line?.close() } catch (_: Exception) {}
                line = null
            }
            if (queue.isEmpty()) {
                try { line?.drain() } catch (_: Exception) {}
                if (queue.isEmpty()) { speaking = false; level = 0f }
            }
        }
    }

    private fun play(item: Item) {
        val clip = item.clip
        val l = lineFor(clip.rate)
        speaking = true
        item.onStart?.let { Minecraft.getInstance().execute(it) }
        val chunk = clip.rate / 50 // 20 ms
        val bytes = ByteArray(chunk * 2)
        var i = 0
        while (i < clip.pcm.size) {
            if (item.generation != generation) return
            val n = minOf(chunk, clip.pcm.size - i)
            val gain = volume()
            var sum = 0.0
            for (k in 0 until n) {
                val s = (clip.pcm[i + k] * gain).toInt().coerceIn(-32768, 32767)
                bytes[k * 2] = (s and 0xFF).toByte(); bytes[k * 2 + 1] = (s shr 8).toByte()
                val f = clip.pcm[i + k] / 32768.0
                sum += f * f
            }
            level = (sqrt(sum / n) * 4).toFloat().coerceIn(0f, 1f)
            // The line buffers ~200 ms ahead of the speakers; keep the mic held back past that plus an echo tail.
            dev.dexbuddy.VoiceState.muteWhileDexSpeaks = BuddyConfig.settings.voice.muteVoiceChatWhileDexSpeaks
            dev.dexbuddy.VoiceState.dexSpeakingUntil = System.currentTimeMillis() + 600
            l.write(bytes, 0, n * 2)
            i += n
        }
    }

    private fun lineFor(rate: Int): SourceDataLine {
        line?.let { if (lineRate == rate && it.isOpen) return it; it.drain(); it.close() }
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val l = AudioSystem.getSourceDataLine(format)
        l.open(format, rate / 5 * 2) // 200 ms buffer
        l.start()
        line = l
        lineRate = rate
        return l
    }

    /** Minecraft's master and Voice/Speech sliders, times the companion's own volume. */
    private fun volume(): Float {
        val o = Minecraft.getInstance().options
        return (o.getSoundSourceVolume(SoundSource.MASTER) * o.getSoundSourceVolume(SoundSource.VOICE) *
            BuddyConfig.settings.tts.volume.toFloat()).coerceIn(0f, 1f)
    }
}
