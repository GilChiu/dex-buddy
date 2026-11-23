package dev.dexbuddy.client.tts

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The companion's voice: sentences in (as the reply streams), audio out in order. Synthesis runs on its
 * own worker so the next sentence is ready while the current one plays.
 */
object Tts {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dexbuddy-tts").apply { isDaemon = true } }
    private var engine: PiperEngine? = null
    private val installing = AtomicBoolean(false)
    @Volatile private var failed: String? = null

    /** Set per reply for the debug timing "first audio" line. */
    @Volatile private var replyStart = 0L
    @Volatile private var reportFirstAudio: ((Long) -> Unit)? = null

    private val zapWord = Regex("\\bb+z+t+\\b[!?.]*", RegexOption.IGNORE_CASE)

    val enabled: Boolean get() = BuddyConfig.settings.tts.enabled && failed == null

    fun isReady(): Boolean = PiperInstaller.isInstalled(BuddyConfig.settings.tts.voice)

    /** Loads the voice in the background (world join), so the first reply isn't slowed by it. */
    fun warmUp() {
        if (!BuddyConfig.settings.tts.enabled || !isReady()) return
        worker.execute { try { engineFor().warm() } catch (_: Exception) {} }
    }

    /** A new reply begins: stop anything still being said. */
    fun beginReply(startedAt: Long, onFirstAudio: ((Long) -> Unit)?) {
        VoicePlayer.stop()
        replyStart = startedAt
        reportFirstAudio = onFirstAudio
    }

    fun stop() = VoicePlayer.stop()

    /**
     * Queues one sentence. [onStart] runs on the client thread when its audio starts (show its subtitle
     * then). Returns false when there is no voice (disabled, missing or failed): the caller shows the
     * subtitle right away instead.
     */
    fun say(sentence: String, onStart: () -> Unit): Boolean {
        if (!enabled || Hush.active) return false
        if (!isReady()) { installInBackground(); return false }
        val hasZap = zapWord.containsMatchIn(sentence)
        val words = speakable(zapWord.replace(sentence, " "))
        worker.execute {
            try {
                var first = true
                fun started() {
                    if (first) {
                        first = false
                        reportFirstAudio?.let { report -> reportFirstAudio = null; report((System.nanoTime() - replyStart) / 1_000_000) }
                        onStart()
                    }
                }
                if (hasZap) VoicePlayer.enqueue(Sfx.zap()) { started() }
                if (words.any { it.isLetterOrDigit() }) {
                    val s = BuddyConfig.settings.tts
                    val clip = engineFor().synthesize(words)
                    if (clip != null) {
                        // Excitement lifts the voice a little; every sentence varies slightly, like a real speaker.
                        val e = s.expressiveness.coerceIn(0.0, 2.0)
                        val lift = when (words.trimEnd().lastOrNull()) { '!' -> 1 + 0.05 * e; '?' -> 1 + 0.03 * e; else -> 1.0 }
                        val jitter = 1 + (Math.random() - 0.5) * 0.04 * e
                        val mood = dev.dexbuddy.client.antics.Mood.state.pitch
                        val pcm = VoiceFx.apply(clip.pcm, clip.rate, s.pitch * lift * jitter * mood, s.buzz, wobble = 0.025 * e)
                        VoicePlayer.enqueue(Clip(withPause(pcm, clip.rate, words), clip.rate)) { started() }
                    } else if (!hasZap) {
                        Minecraft.getInstance().execute(onStart)
                    }
                } else if (!hasZap) {
                    Minecraft.getInstance().execute(onStart)
                }
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] speech failed: {}", e.toString())
                Minecraft.getInstance().execute(onStart)
            }
        }
        return true
    }

    /** Piper's length scale includes the pitch factor, since raising pitch by resampling also speeds it up. */
    private fun engineFor(): PiperEngine {
        val s = BuddyConfig.settings.tts
        val length = s.pitch.coerceIn(0.5, 2.0) / s.speed.coerceIn(0.5, 2.0)
        val e = s.expressiveness.coerceIn(0.0, 2.0)
        engine?.let { if (it.matches(s.voice, length, s.speaker, e)) return it; it.stop() }
        return PiperEngine(s.voice, length, s.speaker, e).also { engine = it }
    }

    /**
     * Text as the voice should read it: pauses stay as commas, symbols that come out as noise or are
     * read literally go. Piper uses the final . ! ? to shape the sentence's tone, so those stay.
     */
    private fun speakable(s: String): String = pronounce(s)
        .replace("…", ", ").replace(Regex("\\.{2,}"), ", ")
        .replace(Regex("\\s*[—–]\\s*"), ", ").replace(Regex("\\s-\\s"), ", ")
        .replace(Regex("[\"“”*_#~<>|\\[\\]{}]"), "")
        .replace(Regex("\\s+"), " ").replace(Regex(",\\s*,"), ",").trim()

    /**
     * Respells words the voice gets wrong (config tts.pronunciations, longest first, whole words only),
     * then turns any leftover accented e into "ay" (Flabébé -> Flabaybay) and drops other accents.
     */
    fun pronounce(s: String): String {
        var out = s
        val map = BuddyConfig.settings.tts.pronunciations
        for (word in map.keys.sortedByDescending { it.length }) {
            val how = map[word] ?: continue
            out = out.replace(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE), how)
        }
        out = out.replace("é", "ay").replace("É", "Ay")
        return java.text.Normalizer.normalize(out, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    /** A natural breath after the chunk: full stop after a sentence, a short one after a comma split. */
    private fun withPause(pcm: ShortArray, rate: Int, text: String): ShortArray {
        val base = BuddyConfig.settings.tts.sentencePauseMs.coerceIn(0, 1500)
        val ms = when (text.trimEnd().lastOrNull()) {
            '.', '!' -> base
            '?' -> (base * 1.15).toInt()
            ',', ';', ':' -> base / 2
            else -> (base * 0.6).toInt()
        }
        if (ms == 0) return pcm
        return pcm.copyOf(pcm.size + rate * ms / 1000)
    }

    /** Says the sample line in the current voice, downloading the voice first if needed. */
    fun sample() {
        if (!BuddyConfig.settings.tts.enabled) { chat("[Dex Buddy] The voice is off (tts.enabled in config).", ChatFormatting.GRAY); return }
        if (!isReady()) { afterInstall = { sample() }; installInBackground(); return }
        beginReply(System.nanoTime(), null)
        val line = VoiceCatalog.SAMPLE_LINE.replace("Volty", dev.dexbuddy.client.entity.CompanionClient.companionName)
        say(line) { dev.dexbuddy.client.ui.Subtitles.push(line) }
    }

    @Volatile private var afterInstall: (() -> Unit)? = null

    /** First use: fetch Piper and the voice once, telling the Trainer what's happening. */
    fun installInBackground() {
        if (!installing.compareAndSet(false, true)) return
        val voice = BuddyConfig.settings.tts.voice
        val name = dev.dexbuddy.client.entity.CompanionClient.companionName
        Thread({
            try {
                chat(if (Files.isRegularFile(PiperInstaller.executable())) "[Dex Buddy] Downloading the voice \"$voice\" (one time)..."
                    else "[Dex Buddy] Setting up $name's voice (one-time download, about 85 MB). Replies show as text until it's ready.", ChatFormatting.YELLOW)
                PiperInstaller.install(voice) { chat("[Dex Buddy] $it", ChatFormatting.GRAY) }
                chat("[Dex Buddy] $name's voice is ready!", ChatFormatting.GREEN)
                warmUp()
                afterInstall?.let { next -> afterInstall = null; Minecraft.getInstance().execute(next) }
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] voice setup failed: {}", e.toString())
                failed = e.message ?: e.javaClass.simpleName
                chat("[Dex Buddy] Couldn't set up the voice: $failed. Replies stay as text; /dexbuddy reload tries again.", ChatFormatting.RED)
            } finally {
                installing.set(false)
            }
        }, "dexbuddy-voice-setup").apply { isDaemon = true; start() }
    }

    /** /dexbuddy reload: forget a failure and restart the engine with the current settings. */
    fun reset() {
        failed = null
        worker.execute { engine?.stop(); engine = null }
        warmUp()
    }

    fun shutdown() { engine?.stop() }

    private fun chat(text: String, color: ChatFormatting) {
        Minecraft.getInstance().execute { Minecraft.getInstance().gui?.chat?.addMessage(Component.literal(text).withStyle(color)) }
    }
}
