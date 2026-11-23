package dev.dexbuddy.client.voice

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.dexbuddy.VoiceState
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.antics.Tricks
import dev.dexbuddy.client.tts.PiperInstaller
import dev.dexbuddy.client.tts.Sfx
import dev.dexbuddy.client.tts.Tts
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.sound.sampled.TargetDataLine

/**
 * "Hey Dex": an offline wake-word listener (Vosk, Apache-2.0). It listens only while you're in a world
 * with a Dex Buddy on you or out; nothing leaves the computer until it hears the wake phrase. Then it
 * records until you stop talking and hands the clip to the normal speech-to-text path.
 */
object WakeWord {
    private const val MODEL = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL.zip"
    private const val CHUNK_MS = 100

    private val dir: Path = PiperInstaller.root.resolve("vosk")
    private val installing = AtomicBoolean(false)
    @Volatile private var failed: String? = null
    @Volatile private var model: Model? = null
    @Volatile private var running = false
    /** Set when the mic choice changes: the listener closes the old mic and opens the new one. */
    @Volatile private var reopen = false

    fun reopenMic() { reopen = true }

    /** Push-to-talk needs the mic: the listener lets go of it until [paused] is cleared. */
    @Volatile var paused = false
    /** True while the listener has the mic open. */
    @Volatile var micOpen = false
        private set

    /** Updated every client tick: may we listen right now? (in a world, has a Dex, not mid-answer...) */
    @Volatile private var allowed = false

    /** True while waiting for the wake phrase (the HUD can show a small "ears open" dot). */
    @Volatile var armed = false
        private set

    fun enabled(): Boolean = BuddyConfig.settings.voice.activation.lowercase().let { it == "wakeword" || it == "both" }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val player = mc.player
            allowed = player != null && enabled() && failed == null &&
                (!BuddyConfig.settings.voice.requireItem || PushToTalk.hasDevice(player)) &&
                PushToTalk.state == PushToTalk.State.IDLE // (still listens while it answers, for "stop")
            if (player != null && enabled() && !running) start()
        }
    }

    private fun start() {
        running = true
        Thread({ loop() }, "dexbuddy-wakeword").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    /** /dexbuddy reload: try again after a failure. */
    fun reset() { failed = null }

    // ---- main loop ---------------------------------------------------------------------------------

    private fun loop() {
        try { LibVosk.setLogLevel(LogLevel.WARNINGS) } catch (_: Throwable) {}
        while (running) {
            try {
                if (!enabled() || Minecraft.getInstance().player == null) { running = false; armed = false; return }
                // Fetch and load the model first (once), so it's ready the moment the Trainer can talk.
                if (failed != null) { Thread.sleep(1000); continue }
                val m = model ?: run {
                    if (!Files.isDirectory(dir.resolve(MODEL))) { install(); Thread.sleep(1000); return@run null }
                    Model(dir.resolve(MODEL).toString()).also { model = it }
                } ?: continue
                if (!allowed || paused) { armed = false; Thread.sleep(100); continue }
                reopen = false
                val (line, rate) = MicRecorder.openListeningLine() ?: run { Thread.sleep(3000); null } ?: continue
                micOpen = true
                try {
                    Recognizer(m, rate.toFloat(), grammar()).use { rec -> rec.setWords(true); listen(line, rate, rec) }
                } finally {
                    armed = false
                    try { line.stop(); line.close() } catch (_: Exception) {}
                    micOpen = false
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                DexBuddyClient.LOG.warn("[dexbuddy] wake word listener stopped: {}", e.toString())
                failed = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                chat("[Dex Buddy] \"Hey Dex\" stopped working ($failed). The talk key still works; /dexbuddy reload tries again.", ChatFormatting.RED)
                armed = false
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** Waits for the wake phrase; returns when listening isn't allowed anymore (closes the mic). */
    private fun listen(line: TargetDataLine, rate: Int, rec: Recognizer) {
        val chunk = ByteArray(rate * 2 * CHUNK_MS / 1000)
        // The last ~2.5 s of audio: "Hey Dex, what's that?" said in one breath is already here by the time
        // the wake phrase is recognized, so it all goes into the recording.
        // Up to 10 s is kept: the small recognizer sometimes only confirms "Hey Dex" once you finish the
        // whole sentence, and by then a short buffer had already lost the start of the question.
        val recent = ArrayDeque<Pair<ByteArray, Double>>()
        var floor = 0.01
        armed = true
        while (running && allowed && !reopen && !paused) {
            val n = line.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            // While your Dex is talking, only "stop" counts (acted on mid-word, no "Hey Dex" needed);
            // anything else it hears is likely its own voice, so start clean once it's done.
            if (System.currentTimeMillis() < VoiceState.dexSpeakingUntil) {
                recent.clear()
                val fin = rec.acceptWaveForm(chunk, n)
                val h = if (fin) text(rec.result) else partial(rec.partialResult)
                if (fin && h.replace("[unk]", "").isNotBlank()) if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] listener heard (while talking): \"{}\"", h)
                // Answering "close-up, portrait or wide?" before it has finished asking: cut it off and go.
                // Exactly one kind named, so its own question leaking into the mic (all three) doesn't count.
                if (fin && dev.dexbuddy.client.antics.Commands.awaitingPhotoKind() && photoKindsIn(clean(h)) == 1) {
                    val said = clean(h)
                    if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] photo kind answer (while talking): \"{}\"", said)
                    mcExecute { dev.dexbuddy.client.tts.Hush.stopTalking(); dev.dexbuddy.client.antics.Commands.tryHandle(said) }
                    rec.reset()
                    continue
                }
                if (isStop(h, partial = !fin)) {
                    if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] heard \"{}\": stopping", h)
                    mcExecute {
                        dev.dexbuddy.client.tts.Hush.stopTalking()
                        if (dev.dexbuddy.client.photo.PhotoClient.filming) dev.dexbuddy.client.photo.PhotoClient.stopVideo()
                        Tricks.used("stop")
                        Tricks.nudge("stop", 2, "quiet", "Tip: say be quiet for 10 minutes for some peace. Say you can talk now to bring me back!")
                    }
                    rec.reset()
                }
                continue
            }
            val rms = MicRecorder.rms(chunk, 0, n)
            MicRecorder.idleLevel = (rms * BuddyConfig.settings.voice.micGain * 4).toFloat().coerceIn(0f, 1f)
            // The room's noise level: follows quiet moments, barely moves while you talk.
            floor = if (rms < floor * 3) floor * 0.95 + rms * 0.05 else floor * 0.998 + rms * 0.002
            recent.addLast(chunk.copyOf(n) to rms); while (recent.size > 100) recent.removeFirst()
            val threshold = maxOf(floor * 2.5, 0.004) // quiet headset mics talk at ~0.005

            // Dex just asked you something ("close-up or wide?"): your next words are the answer, no
            // "Hey Dex" needed. The window opens when it finishes talking and lasts a few seconds.
            val now = System.currentTimeMillis()
            if (followUpPending && !dev.dexbuddy.client.tts.VoicePlayer.speaking) { followUpPending = false; followUpUntil = now + FOLLOW_UP_MS; mcExecute { hint("${name()} is listening for your answer...") } }
            if (now < followUpUntil && recent.takeLast(4).count { it.second > threshold } >= 3) {
                followUpUntil = 0L
                rec.reset()
                armed = false
                capture(line, rate, speechBurst(recent.toList(), floor), floor, alreadySpeaking = true, followUp = true)
                recent.clear()
                return
            }

            // Only full phrases count (not mid-sentence guesses), and only when the recognizer is sure of
            // the wake words: chatting with friends often "sounds like" Hey Dex to a small model.
            if (!rec.acceptWaveForm(chunk, n)) continue
            val result = rec.result
            val heard = text(result)
            if (heard.isEmpty()) continue
            if (heard.replace("[unk]", "").isNotBlank()) if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] listener heard: \"{}\"", heard)
            // Things you can say without "Hey Dex": stop, be quiet for a while, talk again, and
            // questions that are clearly for the Dex ("what Pokémon is this?").
            if (isStop(heard, partial = false)) {
                // "Stop" ends a video that's rolling, or cuts it off mid-sentence.
                if (dev.dexbuddy.client.photo.PhotoClient.filming) mcExecute { dev.dexbuddy.client.photo.PhotoClient.stopVideo() }
                else if (dev.dexbuddy.client.tts.VoicePlayer.speaking || BuddyBrain.isBusy) mcExecute { dev.dexbuddy.client.tts.Hush.stopTalking() }
                continue
            }
            quietMinutes(heard)?.let { m -> if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] heard \"{}\": quiet for {} min", heard, m); mcExecute { dev.dexbuddy.client.tts.Hush.quietFor(m) }; continue }
            if (isTalkAgain(heard)) { mcExecute { dev.dexbuddy.client.tts.Hush.talkAgain() }; continue }
            // Just asked "close-up, portrait or wide?": the answer on its own, no "Hey Dex".
            val h0 = clean(heard)
            if (dev.dexbuddy.client.antics.Commands.awaitingPhotoKind() && PHOTO_KINDS.any { h0.contains(it) }) {
                DexBuddyClient.LOG.info("[dexbuddy] photo kind answer: \"{}\"", h0)
                mcExecute { dev.dexbuddy.client.antics.Commands.tryHandle(h0) }
                continue
            }
            // Tripod mode: "cheese" / "take the photo" shoots, "record" films, "done" packs up.
            if (dev.dexbuddy.client.antics.Commands.tripod) {
                when {
                    TRIPOD_SHOOT.any { h0 == it || h0.endsWith(" $it") } -> { mcExecute { dev.dexbuddy.client.photo.PhotoClient.takeTripodPhoto() }; continue }
                    TRIPOD_RECORD.any { h0 == it || h0.endsWith(" $it") } -> { mcExecute { dev.dexbuddy.client.photo.PhotoClient.takeTripodVideo() }; continue }
                    TRIPOD_DONE.any { h0 == it } -> { mcExecute { dev.dexbuddy.client.antics.Commands.endTripod() }; continue }
                }
            }
            // Still answering the last question: only stop / quiet / talk again count right now.
            if (BuddyBrain.isBusy) continue
            if (isDirect(heard)) {
                if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] heard a question for the Dex: \"{}\"", heard)
                mcExecute { Tricks.used("whats_this") }
                val burst = speechBurst(recent.toList(), floor)
                rec.reset(); armed = false
                capture(line, rate, burst, floor, alreadySpeaking = true, direct = true)
                recent.clear()
                return
            }
            if (!isWake(heard)) continue
            if (!confident(result)) { if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] wake word: heard \"{}\" but not sure enough ({})", heard, confSummary(result)); continue }
            DexBuddyClient.LOG.info("[dexbuddy] wake word: \"{}\" ({})", heard, confSummary(result))
            mcExecute {
                Tricks.used("hey_dex")
                Tricks.nudge("wake", 4, "hold_talk", "Tip: hold left-click with me in your hand to talk anytime. No Hey Dex needed!")
            }
            val burst = speechBurst(recent.toList(), floor)
            rec.reset()
            armed = false
            // Already heard more words after the wake phrase? Then the question has been said.
            val after = heard.lowercase().let { h -> phrases().mapNotNull { p -> h.indexOf(p).takeIf { it >= 0 }?.let { h.substring(it + p.length) } }.minByOrNull { it.length } ?: "" }
            capture(line, rate, burst, floor, after.contains("[unk]"))
            recent.clear()
            return // reopen fresh after each question
        }
    }

    private const val FOLLOW_UP_MS = 7_000L
    @Volatile private var followUpPending = false
    @Volatile private var followUpUntil = 0L

    /** Dex asked a question out loud: take the next thing you say as the answer, without "Hey Dex". */
    fun expectAnswer() { if (enabled()) followUpPending = true }

    private fun mcExecute(r: () -> Unit) = Minecraft.getInstance().execute(r)
    private fun hint(s: String) { Minecraft.getInstance().player?.displayClientMessage(Component.literal(s).withStyle(ChatFormatting.GOLD), true) }

    /**
     * The wake words were heard with fair confidence (average 60%+). Kept loose on purpose: the full
     * transcript check afterwards (it must really say "Hey Dex") is what stops false wake-ups.
     */
    private fun confident(json: String): Boolean = try {
        val words = JsonParser.parseString(json).asJsonObject.getAsJsonArray("result")
        if (words == null) true else {
            val wake = phrases().flatMap { it.split(' ') }.toSet()
            val conf = words.map { it.asJsonObject }.filter { it.get("word")?.asString in wake }.map { it.get("conf")?.asDouble ?: 1.0 }
            conf.isEmpty() || conf.average() >= 0.6
        }
    } catch (_: Exception) { true }

    private fun confSummary(json: String): String = try {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("result")?.joinToString(" ") { val o = it.asJsonObject; "${o.get("word")?.asString}=%.2f".format(o.get("conf")?.asDouble ?: 1.0) } ?: "no word data"
    } catch (_: Exception) { "?" }

    /**
     * The audio of what you just said, from where you started talking: skips any quiet at the end, then
     * walks back to the last pause (0.8 s) before it, keeping 0.3 s of lead-in. At least the last 2 s.
     */
    private fun speechBurst(recent: List<Pair<ByteArray, Double>>, floor: Double): List<ByteArray> {
        if (recent.isEmpty()) return emptyList()
        val threshold = maxOf(floor * 2.5, 0.004) // quiet headset mics talk at ~0.005
        var i = recent.size - 1
        while (i > 0 && recent[i].second <= threshold) i--
        var start = 0
        var quiet = 0
        while (i >= 0) {
            if (recent[i].second > threshold) quiet = 0
            else if (++quiet >= 8) { start = i + quiet - 3; break }
            i--
        }
        start = start.coerceIn(0, maxOf(0, recent.size - 20))
        return recent.subList(start, recent.size).map { it.first }
    }

    /** After the wake phrase: record until the Trainer stops talking, then send it on. */
    private fun capture(line: TargetDataLine, rate: Int, lead: List<ByteArray>, noiseFloor: Double, alreadySpeaking: Boolean, followUp: Boolean = false, direct: Boolean = false) {
        val mc = Minecraft.getInstance()
        Tts.stop()
        Sfx.listen()
        VoiceState.muteVoiceChat = BuddyConfig.settings.voice.muteVoiceChatWhileTalking
        VoiceState.recording = true
        mc.execute { PushToTalk.setWakeListening(true) }
        val out = ByteArrayOutputStream()
        lead.forEach { out.write(it) }
        val chunk = ByteArray(rate * 2 * CHUNK_MS / 1000)
        val threshold = maxOf(noiseFloor * 2.5, 0.004) // quiet headset mics talk at ~0.005
        val endMs = (BuddyConfig.settings.voice.endOfSpeechSeconds * 1000).toInt().coerceIn(400, 3000)
        val maxMs = BuddyConfig.settings.voice.maxSeconds.coerceIn(3, 60) * 1000
        // Said it all in one go: stop at the usual pause. Otherwise wait (up to 5 s) for the question.
        var spoke = alreadySpeaking
        var quietMs = 0
        var elapsed = 0
        while (running) {
            val n = line.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            out.write(chunk, 0, n)
            elapsed += CHUNK_MS
            val rms = MicRecorder.rms(chunk, 0, n)
            MicRecorder.setLevel((rms * 4).toFloat().coerceIn(0f, 1f))
            if (rms > threshold) { spoke = true; quietMs = 0 } else quietMs += CHUNK_MS
            if (spoke && quietMs >= endMs) break
            if (!spoke && elapsed >= 5000) break
            if (elapsed >= maxMs) break
        }
        VoiceState.recording = false
        MicRecorder.setLevel(0f)
        val released = System.nanoTime()
        val pcm = out.toByteArray()
        MicRecorder.gain(pcm)
        mc.execute {
            PushToTalk.setWakeListening(false)
            DexBuddyClient.LOG.info("[dexbuddy] captured {} s of speech (direct={}, follow-up={}, spoke={})", "%.1f".format(pcm.size / 2.0 / rate), direct, followUp, spoke)
            if (!spoke) {
                mc.player?.displayClientMessage(Component.literal("${name()} is listening... say your question after \"Hey Dex\"!").withStyle(ChatFormatting.GOLD), true)
            } else {
                PushToTalk.process(mc, Recording(pcm, rate, MicRecorder.rms(pcm, 0, pcm.size)), released, fromWake = !followUp && !direct, direct = direct)
            }
        }
    }

    // ---- phrases -----------------------------------------------------------------------------------

    /** Wake phrases plus sound-alikes the small model may hear instead ("dex" is rare; "decks" isn't). */
    private fun phrases(): List<String> {
        val base = BuddyConfig.settings.voice.wakePhrases.map { it.lowercase().trim() }.filter { it.isNotEmpty() } +
            listOf("hey ${name().lowercase()}")
        return base.flatMap { p -> listOf(p, p.replace("dex", "decks"), p.replace("dex", "dex's"), p.replace("volty", "volt")) }.distinct()
    }

    // ---- phrases that work without "Hey Dex" -----------------------------------------------------------

    private val STOP = listOf("stop", "stop it", "stop talking", "shut up", "be quiet", "quiet", "hush", "enough", "that's enough", "okay stop")
    private val TALK_AGAIN = listOf("you can talk now", "you can speak now", "you can talk again", "talk again", "you can talk")
    private val NUMBERS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "twenty five", "thirty", "forty five", "sixty")
    private val NUMBER_VALUE = NUMBERS.withIndex().associate { (i, w) -> w to (if (i < 20) i + 1 else when (w) { "twenty five" -> 25; "thirty" -> 30; "forty five" -> 45; else -> 60 }) } +
        mapOf("a" to 1, "an" to 1)
    private val QUIET_STARTS = listOf("be quiet for", "quiet for", "don't talk for", "stop talking for", "don't speak for", "mute for", "shut up for")
    /** Questions that are clearly for the Dex. */
    private val DIRECT = listOf("what pokemon is this", "what pokemon is that", "what is this pokemon", "what is that pokemon",
        "what's this pokemon", "what's that pokemon", "which pokemon is this", "which pokemon is that", "tell me about this pokemon",
        "what cobblemon is this", "what cobblemon is that")

    private val PHOTO_KINDS = listOf("close up", "closeup", "close", "portrait", "wide", "wide shot", "full body")
    private val TRIPOD_SHOOT = listOf("cheese", "take it", "take the photo", "take the picture", "take a photo", "take a picture", "snap", "shoot", "say cheese", "now")
    private val TRIPOD_RECORD = listOf("record", "start recording", "action", "roll", "film")
    private val TRIPOD_DONE = listOf("done", "we're done", "i'm done", "you can move", "come back", "follow me", "that's all")

    /** How many different shot kinds (close-up / portrait / wide) are named in [h]. */
    private fun photoKindsIn(h: String): Int =
        listOf(Regex("\\bclose\\s*up\\b|\\bcloseup\\b|\\bclose\\b"), Regex("\\bportrait\\b"), Regex("\\bwide\\b|\\bfull body\\b")).count { it.containsMatchIn(h) }

    private fun clean(h: String) = h.lowercase().replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()

    /** "stop" (and friends) as the whole thing said; mid-word while it talks, the last word is enough. */
    private fun isStop(heard: String, partial: Boolean): Boolean {
        val h = clean(heard)
        if (h.isEmpty()) return false
        return h in STOP || (partial && STOP.any { h.endsWith(it) && h.split(' ').size <= 3 })
    }

    private fun isTalkAgain(heard: String) = clean(heard).let { h -> TALK_AGAIN.any { h.contains(it) } }

    /** "be quiet for ten minutes" -> 10 (hours are turned into minutes). */
    private fun quietMinutes(heard: String): Int? {
        val h = clean(heard)
        val start = QUIET_STARTS.firstOrNull { h.contains(it) } ?: return null
        val rest = h.substringAfter(start).trim()
        val unit = Regex("\\b(minute|minutes|hour|hours)\\b").find(rest) ?: return null
        val n = NUMBER_VALUE[rest.substring(0, unit.range.first).trim()] ?: return null
        return if (unit.value.startsWith("hour")) n * 60 else n
    }

    /**
     * A question that sounds like it's for the Dex. The listener doesn't know the word "Cobblemon"
     * (it hears "what's this [unk]"), so "what's this / what is that" alone counts too; the transcript
     * check afterwards decides whether it really was.
     */
    private fun isDirect(heard: String) = clean(heard).let { h ->
        DIRECT.any { h.contains(it) } || h in setOf("what's this", "what is this", "what's that", "what is that", "which pokemon", "what pokemon")
    }

    /**
     * The full transcript really asks about a Cobblemon, so a friend's "what's this?" doesn't wake it:
     * it names one (Whisper writes "Cobblemon" many ways: "Cob, Pokemon", "Cobble mon"...), or it's a
     * short "what's this/that?" while you're looking right at one ([aiming]).
     */
    fun saidDirect(text: String, aiming: Boolean): Boolean {
        val t = text.lowercase()
        if (Regex("\\b(what|which|who)\\b").containsMatchIn(t) && Regex("pok[eé]\\s*mon|cob+\\w*|\\bmons?\\b|cobble").containsMatchIn(t)) return true
        val short = t.replace(Regex("[^a-z' ]"), " ").trim().split(Regex("\\s+")).size <= 5
        return aiming && short && Regex("\\bwhat('?s| is)\\s+(this|that|it)\\b").containsMatchIn(t)
    }

    private fun grammar(): String = JsonArray().apply {
        (phrases() + STOP + TALK_AGAIN + DIRECT + PHOTO_KINDS + TRIPOD_SHOOT + TRIPOD_RECORD + TRIPOD_DONE).distinct().forEach { add(it) }
        for (s in QUIET_STARTS) for (n in NUMBERS + listOf("a", "an")) for (u in listOf("minute", "minutes", "hour", "hours")) add("$s $n $u")
        add("[unk]")
    }.toString()

    private fun isWake(heard: String): Boolean {
        val h = " " + heard.lowercase().replace("[unk]", " ").replace(Regex("\\s+"), " ").trim() + " "
        return phrases().any { h.contains(" $it ") }
    }

    /**
     * Did the full transcript really say the wake phrase? The listener's small model can be fooled by
     * noise or game sounds; the real transcript can't, so a wake-up without "Dex" (or the name) in it is
     * dropped instead of answered.
     */
    fun saidWake(text: String): Boolean {
        // "Hey Dex" / "Okay Volty" somewhere, or the name as the very first word ("Dex, what's that?").
        // Just mentioning "my Dex" to a friend doesn't count.
        val names = (listOf("dex", "decks", "dicks", "dex's", "dax", "deck", name().lowercase(), "volt")).joinToString("|") { Regex.escape(it) }
        // Also the run-together spellings speech-to-text sometimes writes ("Hideks", "Heydex").
        val called = Regex("\\b(hey|hi|okay|ok|hay|yo|a)[,!.]?\\s+($names)\\b|\\b(hi|hey|hay)d[aeiy]?[kx]s?\\b", RegexOption.IGNORE_CASE)
        val first = Regex("^\\W*($names)\\b", RegexOption.IGNORE_CASE)
        return called.containsMatchIn(text) || first.containsMatchIn(text)
    }

    /** Removes "Hey Dex," from the start of a transcript (Whisper often hears it too). */
    fun stripWakePhrase(text: String): String {
        val names = (listOf("dex", "decks", "dicks", "dex's", "dax", name().lowercase(), "volt")).joinToString("|") { Regex.escape(it) }
        val atStart = Regex("^\\s*(hey|hi|okay|ok|hay|a)[,!.]?\\s+($names)\\b[,!.?]*\\s*", RegexOption.IGNORE_CASE)
        // The recording now starts a little before "Hey Dex", so drop anything said before it too.
        val inside = Regex("\\b(hey|hi|okay|ok|hay)[,!.]?\\s+($names)\\b[,!.?]*\\s*", RegexOption.IGNORE_CASE)
        val rest = atStart.find(text)?.let { text.substring(it.range.last + 1) }
            ?: inside.find(text)?.let { text.substring(it.range.last + 1) }
            ?: text
        return rest.trim().replaceFirstChar { it.uppercase() }
    }

    private fun text(json: String) = try { JsonParser.parseString(json).asJsonObject.get("text")?.asString ?: "" } catch (_: Exception) { "" }
    private fun partial(json: String) = try { JsonParser.parseString(json).asJsonObject.get("partial")?.asString ?: "" } catch (_: Exception) { "" }

    private fun name() = dev.dexbuddy.client.entity.CompanionClient.companionName

    // ---- one-time model download --------------------------------------------------------------------

    private fun install() {
        if (!installing.compareAndSet(false, true)) return
        try {
            chat("[Dex Buddy] Setting up \"Hey Dex\" listening (one-time download, about 40 MB). The talk key works meanwhile.", ChatFormatting.YELLOW)
            Files.createDirectories(dir)
            val zip = dir.resolve("$MODEL.zip.part")
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(15)).build()
            val res = http.send(HttpRequest.newBuilder(URI.create(MODEL_URL)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(zip))
            if (res.statusCode() != 200) error("download failed (HTTP ${res.statusCode()})")
            ZipInputStream(BufferedInputStream(Files.newInputStream(zip))).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = dir.resolve(e.name).normalize()
                    require(out.startsWith(dir)) { "bad archive entry" }
                    if (e.isDirectory) Files.createDirectories(out) else { Files.createDirectories(out.parent); Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            Files.deleteIfExists(zip)
            chat("[Dex Buddy] Say \"Hey Dex\" to talk to ${name()}!", ChatFormatting.GREEN)
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] wake word setup failed: {}", e.toString())
            failed = e.message ?: e.javaClass.simpleName
            chat("[Dex Buddy] Couldn't set up \"Hey Dex\": $failed. The talk key still works.", ChatFormatting.RED)
        } finally {
            installing.set(false)
        }
    }

    /** Test aid: runs a WAV file through the wake-word recognizer and reports what it heard. */
    fun testFile(path: Path): String {
        val m = model ?: if (Files.isDirectory(dir.resolve(MODEL))) Model(dir.resolve(MODEL).toString()).also { model = it } else return "model not installed yet"
        val b = Files.readAllBytes(path)
        val rate = java.nio.ByteBuffer.wrap(b, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        Recognizer(m, rate.toFloat(), grammar()).use { rec ->
            rec.acceptWaveForm(b.copyOfRange(44, b.size), b.size - 44)
            val heard = text(rec.finalResult)
            return "heard \"$heard\" -> wake=${isWake(heard)}"
        }
    }

    private fun chat(text: String, color: ChatFormatting) {
        Minecraft.getInstance().execute { Minecraft.getInstance().gui?.chat?.addMessage(Component.literal(text).withStyle(color)) }
    }
}
