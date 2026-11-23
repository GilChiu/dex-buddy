package dev.dexbuddy.client.voice

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.mojang.blaze3d.platform.InputConstants
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.VoiceState
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.llm.LlmException
import dev.dexbuddy.client.tts.Sfx
import dev.dexbuddy.client.tts.Tts
import dev.dexbuddy.net.HelloPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.concurrent.Executors

/**
 * Hold the talk key (I by default) to record, release to send: speech-to-text, then the same pipeline
 * as /buddy. Recording only starts with no screen open and (by default) a Dex Buddy in your hotbar or offhand.
 */
object PushToTalk {
    enum class State { IDLE, LISTENING, TRANSCRIBING }

    @Volatile var state: State = State.IDLE
        private set

    /** The wake-word listener shows its own recording through the same listening HUD. */
    fun setWakeListening(on: Boolean) {
        if (on) { if (state == State.IDLE) state = State.LISTENING } else if (state == State.LISTENING) state = State.IDLE
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dexbuddy-stt").apply { isDaemon = true } }
    private val POKEDEX: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "pokedex"))

    /** Too short to be a question: a tap of the key. */
    private const val MIN_SECONDS = 0.35
    /** Quieter than this the whole time means nothing was said (Whisper invents words from silence). */
    private const val MIN_RMS = 0.0015 // quiet headset mics average ~0.004 over a whole question

    /** A refused press (no device, still answering, mic error) is ignored until the key goes up. */
    private var waitForRelease = false

    lateinit var key: KeyMapping
        private set

    fun register() {
        key = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.dexbuddy.talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, "key.categories.dexbuddy"),
        )
        // Before Minecraft handles the keys this tick, so the click never becomes a punch.
        ClientTickEvents.START_CLIENT_TICK.register { tickDexClick(it) }
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
    }

    /**
     * Holding the Dex Buddy in your main hand, holding left-click talks to it (always, whatever the
     * activation setting). The click is taken before Minecraft sees it: no punch, no mining, no swing.
     */
    @Volatile var dexClick = false
        private set

    private fun tickDexClick(mc: Minecraft) {
        val p = mc.player
        val holding = p != null && p.mainHandItem.`is`(DexBuddyMod.DEX_BUDDY)
        if (!holding || mc.screen != null || dev.dexbuddy.client.photo.PhotoClient.viewfinder || dev.dexbuddy.client.scan.ScanClient.scanning) { dexClick = false; return }
        val k = KeyBindingHelper.getBoundKeyOf(mc.options.keyAttack)
        val window = mc.window.window
        val down = if (k.type == InputConstants.Type.MOUSE) GLFW.glfwGetMouseButton(window, k.value) == GLFW.GLFW_PRESS
            else InputConstants.isKeyDown(window, k.value)
        while (mc.options.keyAttack.consumeClick()) { /* swallowed: it's the talk button now */ }
        if (down) mc.options.keyAttack.setDown(false)
        dexClick = down
    }

    private fun tick(mc: Minecraft) {
        // With activation "wakeword" the talk key does nothing (say "Hey Dex" instead); left-click with the Dex always works.
        val keyOn = !BuddyConfig.settings.voice.activation.equals("wakeword", true)
        val pressed = (keyOn && key.isDown) || dexClick
        val held = pressed && mc.screen == null && mc.player != null
        if (waitForRelease) { if (!pressed) { waitForRelease = false; WakeWord.paused = false }; return }
        when {
            held && state == State.IDLE -> {
                // The "Hey Dex" listener has the mic: ask it to let go, and start once it has.
                WakeWord.paused = true
                if (!WakeWord.micOpen) begin(mc)
            }
            !held && state == State.LISTENING && keyRecording -> finish(mc)
            !held && state == State.IDLE && WakeWord.paused -> WakeWord.paused = false
        }
    }

    /** True only while the talk key (not the wake word) is recording. */
    private var keyRecording = false

    private fun begin(mc: Minecraft) {
        val player = mc.player ?: return
        if (BuddyBrain.isBusy) {
            actionBar("${name()} is still answering...")
            waitForRelease = true
            return
        }
        if (BuddyConfig.settings.voice.requireItem && !hasDevice(player)) {
            actionBar(if (serverHasMod()) "Hold a Dex Buddy (hotbar or offhand) to talk to ${name()}" else "Hold a Pokédex (hotbar or offhand) to talk to ${name()}")
            waitForRelease = true
            return
        }
        VoiceState.muteVoiceChat = BuddyConfig.settings.voice.muteVoiceChatWhileTalking
        val err = MicRecorder.start()
        if (err != null) {
            chat(Component.literal("[Dex Buddy] $err").withStyle(ChatFormatting.RED))
            waitForRelease = true
            return
        }
        VoiceState.recording = true
        state = State.LISTENING
        keyRecording = true
        if (dexClick) dev.dexbuddy.client.antics.Tricks.used("hold_talk")
        Tts.stop() // talking over the companion cuts it off
        Sfx.listen()
        worker.execute { SpeechToText.warmUp() }
    }

    private fun finish(mc: Minecraft) {
        val released = System.nanoTime()
        keyRecording = false
        val rec = MicRecorder.stop()
        VoiceState.recording = false
        WakeWord.paused = false // the listener can have the mic back
        process(mc, rec, released)
    }

    /**
     * Sends a 16-bit mono WAV file through the same path as the mic (test aid: /dexbuddy hear <file>).
     * Returns an error message, or null when it was sent.
     */
    fun hearFile(path: java.nio.file.Path): String? {
        val bytes = try { java.nio.file.Files.readAllBytes(path) } catch (e: Exception) { return "can't read $path" }
        val rec = parseWav(bytes) ?: return "not a 16-bit mono PCM WAV file"
        if (state != State.IDLE || BuddyBrain.isBusy) return "busy, try again in a moment"
        process(Minecraft.getInstance(), rec, System.nanoTime())
        return null
    }

    private fun parseWav(b: ByteArray): Recording? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = buf.getInt(pos + 4)
            if (id == "fmt ") { channels = buf.getShort(pos + 10).toInt(); rate = buf.getInt(pos + 12); bits = buf.getShort(pos + 22).toInt() }
            if (id == "data") {
                if (channels != 1 || bits != 16 || rate <= 0) return null
                val pcm = b.copyOfRange(pos + 8, minOf(b.size, pos + 8 + len))
                return Recording(pcm, rate, MicRecorder.rms(pcm, 0, pcm.size))
            }
            pos += 8 + len + (len and 1)
        }
        return null
    }

    /** Shared by the talk key, the wake word and /dexbuddy hear. */
    private val hallucinations = setOf("thank you", "thanks for watching", "thank you for watching", "you", "bye", "okay", "so", "hmm")

    fun process(mc: Minecraft, rec: Recording?, released: Long, fromWake: Boolean = false, direct: Boolean = false) {
        if (rec == null || rec.seconds < MIN_SECONDS) { DexBuddyClient.LOG.info("[dexbuddy] recording too short, dropped"); state = State.IDLE; return }
        if (rec.rms < MIN_RMS) {
            DexBuddyClient.LOG.info("[dexbuddy] recording too quiet (rms {}), dropped", "%.4f".format(rec.rms))
            state = State.IDLE
            Sfx.error()
            actionBar("${name()} didn't hear anything. Check your mic?")
            return
        }
        state = State.TRANSCRIBING
        Sfx.send()
        val hint = spellingHint()
        // Looking right at a Cobblemon? Then a short "what's this?" is clearly for the Dex.
        val aiming = direct && dev.dexbuddy.client.context.GameContext.lookedAtPokemon() != null
        worker.execute {
            try {
                val raw = SpeechToText.transcribe(rec, hint)
                val text = WakeWord.stripWakePhrase(raw)
                val sttMs = (System.nanoTime() - released) / 1_000_000
                val bare = text.lowercase().replace(Regex("[^a-z ]"), "").trim()
                // A false "Hey Dex" (noise, the game, someone else talking): stay quiet instead of answering.
                val falseWake = (fromWake && (!WakeWord.saidWake(raw) || bare in hallucinations || bare.isEmpty())) ||
                    (direct && !WakeWord.saidDirect(raw, aiming))
                if (DexBuddyClient.logTalk) DexBuddyClient.LOG.info("[dexbuddy] transcript: \"{}\" -> {}", raw, if (falseWake) "ignored (not for the Dex)" else "asking")
                mc.execute {
                    state = State.IDLE
                    if (falseWake) return@execute
                    if (text.isBlank() || text.length < 2) actionBar("${name()} didn't catch that. Try again?")
                    else BuddyBrain.ask(text, startedAt = released, sttMs = sttMs, spoken = true)
                }
            } catch (e: LlmException) {
                DexBuddyClient.LOG.warn("[dexbuddy] speech-to-text failed: {} ({})", e.kind, e.message)
                mc.execute {
                    state = State.IDLE
                    chat(Component.literal("[Dex Buddy] ${e.message}. Check stt/llm settings in config/dexbuddy.json.").withStyle(ChatFormatting.RED))
                }
            } catch (e: Exception) {
                DexBuddyClient.LOG.error("[dexbuddy] speech-to-text failed", e)
                mc.execute { state = State.IDLE; actionBar("Bzzt... my ears are fuzzy. Try again?") }
            }
        }
    }

    // ---- item requirement ----------------------------------------------------------------------------

    /** True when the server also runs Dex Buddy (singleplayer always does). */
    fun serverHasMod(): Boolean = try { ClientPlayNetworking.canSend(HelloPayload.TYPE) } catch (_: Exception) { false }

    /** A Dex Buddy anywhere in the hotbar or offhand; on servers without the mod, any Cobblemon Pokédex too. */
    fun hasDevice(player: Player): Boolean {
        val pokedexOk = !serverHasMod()
        fun ok(s: ItemStack) = s.`is`(DexBuddyMod.DEX_BUDDY) || (pokedexOk && s.`is`(POKEDEX))
        return (0 until 9).any { ok(player.inventory.getItem(it)) } || ok(player.offhandItem) ||
            dev.dexbuddy.client.entity.CompanionClient.mine(player, 32.0) != null // your companion is out with you
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Words Whisper should spell right: the companion's name and your team. */
    private fun spellingHint(): String = try {
        val party = CobblemonClient.storage.party.slots.filterNotNull().map { it.species.translatedName.string }
        // Pokémon in sight are the ones the Trainer is most likely to ask about.
        val mc = Minecraft.getInstance()
        val nearby = mc.player?.let { p ->
            mc.level?.getEntitiesOfClass(PokemonEntity::class.java, p.boundingBox.inflate(48.0))
                ?.sortedBy { it.distanceToSqr(p) }?.map { it.pokemon.species.translatedName.string }?.distinct()?.take(12)
        } ?: emptyList()
        // Names to spell right, plus phrases Whisper otherwise mishears ("weak to" -> "week 2").
        (listOf(name(), "Cobblemon", "Dex") + party + nearby + listOf("What is it weak to?", "resistant to", "evolve", "spawn", "ability", "moveset"))
            .distinct().joinToString(", ")
    } catch (_: Exception) {
        name()
    }

    private fun name() = dev.dexbuddy.client.entity.CompanionClient.companionName

    private fun actionBar(text: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true)
    }

    private fun chat(c: Component) { Minecraft.getInstance().gui?.chat?.addMessage(c) }
}
