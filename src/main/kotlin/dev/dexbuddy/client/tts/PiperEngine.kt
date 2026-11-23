package dev.dexbuddy.client.tts

import dev.dexbuddy.client.DexBuddyClient
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A clip of 16-bit mono audio. */
class Clip(val pcm: ShortArray, val rate: Int)

/**
 * Keeps one Piper process running with the voice loaded, so each sentence costs ~0.15 s instead of a
 * model load. Piper reads one line of text per sentence and answers with the path of the WAV it wrote.
 * Single-threaded use only (the TTS worker).
 */
class PiperEngine(
    private val voice: String,
    private val lengthScale: Double,
    private val speaker: Int = -1,
    private val expressiveness: Double = 1.0,
) {
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private val outDir: Path = PiperInstaller.root.resolve("tmp")

    fun matches(voice: String, lengthScale: Double, speaker: Int, expressiveness: Double) =
        this.voice == voice && this.lengthScale == lengthScale && this.speaker == speaker && this.expressiveness == expressiveness

    private fun start() {
        Files.createDirectories(outDir)
        val exe = PiperInstaller.executable()
        val args = mutableListOf(
            exe.toString(),
            "--model", PiperInstaller.model(voice).toString(),
            "--output_dir", outDir.toString(),
            "--length_scale", "%.3f".format(java.util.Locale.ROOT, lengthScale),
            "--sentence_silence", "0.05",
            // Piper's variation knobs: more noise = livelier intonation and rhythm (defaults 0.667 / 0.8 sound flat).
            "--noise_scale", "%.3f".format(java.util.Locale.ROOT, (0.5 + 0.35 * expressiveness).coerceIn(0.3, 1.2)),
            "--noise_w", "%.3f".format(java.util.Locale.ROOT, (0.6 + 0.35 * expressiveness).coerceIn(0.3, 1.4)),
        )
        if (speaker >= 0) args += listOf("--speaker", speaker.toString())
        val pb = ProcessBuilder(args).directory(exe.parent.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        process = p
        stdin = p.outputStream.bufferedWriter(Charsets.UTF_8)
        stdout = p.inputStream.bufferedReader(Charsets.UTF_8)
        DexBuddyClient.LOG.info("[dexbuddy] voice engine started ({})", voice)
    }

    /** Loads the voice now (about a second) so the first sentence later is fast. */
    fun warm() { if (process?.isAlive != true) start() }

    fun synthesize(text: String): Clip? {
        val line = text.replace(Regex("\\s+"), " ").trim()
        if (line.isEmpty()) return null
        if (process?.isAlive != true) start()
        return try {
            stdin!!.apply { write(line); newLine(); flush() }
            val path = stdout!!.readLine() ?: run { stop(); return null }
            val wav = Path.of(path.trim())
            val bytes = Files.readAllBytes(wav)
            Files.deleteIfExists(wav)
            parseWav(bytes)
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] voice engine failed: {}", e.toString())
            stop()
            null
        }
    }

    fun stop() {
        try { stdin?.close() } catch (_: Exception) {}
        process?.let { p -> if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        process = null; stdin = null; stdout = null
    }

    private fun parseWav(b: ByteArray): Clip? {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 22050
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = buf.getInt(pos + 4)
            if (id == "fmt ") rate = buf.getInt(pos + 12)
            if (id == "data") {
                val n = minOf(len, b.size - pos - 8) / 2
                val pcm = ShortArray(n) { buf.getShort(pos + 8 + it * 2) }
                return Clip(pcm, rate)
            }
            pos += 8 + len + (len and 1)
        }
        return null
    }
}
