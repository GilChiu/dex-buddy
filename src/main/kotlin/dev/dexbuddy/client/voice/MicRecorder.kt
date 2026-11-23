package dev.dexbuddy.client.voice

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import net.fabricmc.loader.api.FabricLoader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/** A finished recording: 16-bit mono PCM plus its sample rate and loudness. */
class Recording(val pcm: ByteArray, val sampleRate: Int, val rms: Double) {
    val seconds: Double get() = pcm.size / 2.0 / sampleRate

    /** The recording as a WAV file, which every Whisper endpoint accepts. */
    fun wav(): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }
}

/**
 * Push-to-talk microphone capture with javax.sound. The line is only open while the key is held.
 */
object MicRecorder {
    private val RATES = intArrayOf(16000, 48000, 44100)

    @Volatile private var line: TargetDataLine? = null
    @Volatile private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    private var rate = 16000

    /** Recent loudness 0..1 for the listening meter. */
    @Volatile var level: Float = 0f
        private set

    val isRecording: Boolean get() = line != null

    private var lastListeningMic: String? = null

    /** Opens an always-on line for the wake-word listener (same device choice as push-to-talk). */
    fun openListeningLine(): Pair<TargetDataLine, Int>? {
        val (mixer, format) = pickLine() ?: return null
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val l = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
            l.open(format, (format.sampleRate.toInt() / 10) * 2)
            l.start()
            val name = mixer?.mixerInfo?.name ?: "system default"
            if (name != lastListeningMic) { lastListeningMic = name; DexBuddyClient.LOG.info("[dexbuddy] \"Hey Dex\" is listening on: {}", name) }
            l to format.sampleRate.toInt()
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] could not open microphone for listening: {}", e.toString())
            null
        }
    }

    /** Applies the configured mic gain to finished audio (the wake-word path records by itself). */
    fun gain(pcm: ByteArray) = applyGain(pcm, BuddyConfig.settings.voice.micGain)

    /** Loudness heard by the always-on "Hey Dex" listener, for the phone's mic test meter. */
    @Volatile var idleLevel: Float = 0f

    /** Lets the wake-word path drive the listening meter. */
    fun setLevel(v: Float) { level = v }

    /** Opens the mic and starts capturing. Returns an error message, or null on success. */
    fun start(): String? {
        if (line != null) return null
        val (mixer, format) = pickLine() ?: return "No microphone found. Check your system's sound settings."
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val l = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
            l.open(format, (format.sampleRate.toInt() / 10) * 2) // ~100 ms buffer
            l.start()
            rate = format.sampleRate.toInt()
            synchronized(buffer) { buffer.reset() }
            line = l
            val maxBytes = BuddyConfig.settings.voice.maxSeconds.coerceIn(3, 120) * rate * 2
            thread = Thread({ pump(l, maxBytes) }, "dexbuddy-mic").apply { isDaemon = true; start() }
            null
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] could not open microphone: {}", e.toString())
            "Couldn't open the microphone (${e.javaClass.simpleName}). Is another app using it exclusively?"
        }
    }

    /** Stops capturing and returns what was said, or null if nothing was recorded. */
    fun stop(): Recording? {
        val l = line ?: return null
        line = null
        try { l.stop(); l.close() } catch (_: Exception) {}
        thread?.join(300)
        thread = null
        level = 0f
        val pcm = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
        if (pcm.isEmpty()) return null
        applyGain(pcm, BuddyConfig.settings.voice.micGain)
        return Recording(pcm, rate, rms(pcm, 0, pcm.size))
    }

    private fun pump(l: TargetDataLine, maxBytes: Int) {
        val chunk = ByteArray(rate / 25 * 2) // 40 ms
        var total = 0
        while (line === l) {
            val n = try { l.read(chunk, 0, chunk.size) } catch (_: Exception) { break }
            if (n <= 0) continue
            if (total + n <= maxBytes) {
                synchronized(buffer) { buffer.write(chunk, 0, n) }
                total += n
            }
            level = (rms(chunk, 0, n) * BuddyConfig.settings.voice.micGain * 4).toFloat().coerceIn(0f, 1f)
        }
    }

    // ---- device choice ------------------------------------------------------------------------------

    /** Every input device Java can record from. */
    fun inputNames(): List<String> = inputMixers().map { it.mixerInfo.name }

    private fun inputMixers(): List<Mixer> = AudioSystem.getMixerInfo().map { AudioSystem.getMixer(it) }.filter { m ->
        m.targetLineInfo.any { it is DataLine.Info && TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
    }

    private fun pickLine(): Pair<Mixer?, AudioFormat>? {
        val wanted = BuddyConfig.settings.voice.micDevice.trim().ifEmpty { voiceChatMic() ?: "" }
        val mixers = inputMixers()
        val preferred = if (wanted.isEmpty()) null else mixers.firstOrNull { namesMatch(it.mixerInfo.name, wanted) }
        if (wanted.isNotEmpty() && preferred == null) {
            DexBuddyClient.LOG.info("[dexbuddy] mic \"{}\" not found; using the system default", wanted)
        }
        for (rate in RATES) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (preferred != null && preferred.isLineSupported(info)) return preferred to format
            if (AudioSystem.isLineSupported(info)) return null to format
        }
        return null
    }

    /** Java truncates device names (31 chars on Windows), so match either way round. */
    private fun namesMatch(javaName: String, wanted: String): Boolean {
        val a = javaName.lowercase().trim()
        val b = wanted.lowercase().trim()
        return a.contains(b) || (a.length >= 8 && b.contains(a))
    }

    /** The microphone Simple Voice Chat is set to, so both use the same headset by default. */
    private fun voiceChatMic(): String? = try {
        val f = FabricLoader.getInstance().configDir.resolve("voicechat/voicechat-client.properties")
        if (!Files.exists(f)) null else Files.readAllLines(f)
            .firstOrNull { it.startsWith("microphone=") }
            ?.substringAfter('=')?.trim()?.removePrefix("OpenAL Soft on ")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    // ---- samples ------------------------------------------------------------------------------------

    private fun applyGain(pcm: ByteArray, gain: Double) {
        if (gain == 1.0) return
        for (i in 0 until pcm.size - 1 step 2) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)) * gain
            val c = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = (c and 0xFF).toByte(); pcm[i + 1] = (c shr 8).toByte()
        }
    }

    /** Root-mean-square loudness 0..1 of 16-bit little-endian samples. */
    fun rms(pcm: ByteArray, from: Int, to: Int): Double {
        var sum = 0.0
        var n = 0
        var i = from
        while (i + 1 < to) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)) / 32768.0
            sum += s * s; n++; i += 2
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }
}
