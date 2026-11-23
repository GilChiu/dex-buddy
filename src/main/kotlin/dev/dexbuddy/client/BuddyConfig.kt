package dev.dexbuddy.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/** LLM provider settings. Any OpenAI-compatible chat completions endpoint works (Groq, OpenRouter, Ollama, LM Studio). */
class LlmSettings {
    var baseUrl: String = "https://api.groq.com/openai/v1"
    /** Never logged, never sent anywhere but [baseUrl]. */
    var apiKey: String = ""
    /** Leave blank to pick automatically from the provider's model list (first match in [preferredModels]). */
    var model: String = ""
    var preferredModels: MutableList<String> = mutableListOf(
        "openai/gpt-oss-120b", "openai/gpt-oss-20b", "moonshotai/kimi-k2-instruct",
        "meta-llama/llama-4-maverick-17b-128e-instruct", "meta-llama/llama-4-scout-17b-16e-instruct",
    )
    var temperature: Double = 0.7
    /** Room for a short spoken reply plus any reasoning the model does first. */
    var maxTokens: Int = 400
    var useTools: Boolean = true
    /** Extra fields merged into every request body, e.g. {"reasoning_effort": "low"}. Applied after the defaults. */
    var extraBody: JsonObject = JsonObject()
    var timeoutSeconds: Int = 30
}

class BuddySettings {
    /** Your Dex companion's name: an original little plasma spirit. Rename it to anything you like. */
    var companionName: String = "Volty"
    /** What the companion calls you. Blank = your player name. */
    var preferredName: String = ""
    /** short | normal | chatty */
    var replyLength: String = "short"
    /** Language the companion replies in (passed to STT and TTS in later phases). */
    var language: String = "English"
    var debugTimings: Boolean = false
    /** Optional path to a persona.txt that replaces the built-in one. */
    var personaOverridePath: String = ""
    var showSubtitles: Boolean = true
    /** What goes in chat: "none" (subtitles only), "companion" (its replies too) or "both" (your words as well). */
    var chatLog: String = "none"
    var llm: LlmSettings = LlmSettings()
    var stt: SttSettings = SttSettings()
    var voice: VoiceSettings = VoiceSettings()
    var tts: TtsSettings = TtsSettings()
    var personality: PersonalitySettings = PersonalitySettings()
    var music: MusicSettings = MusicSettings()
}

/** Dex's music player. */
class MusicSettings {
    /** 0..1, on top of Minecraft's Master and Jukebox/Note Blocks sliders. */
    var volume: Double = 0.6
    /** Let nearby players hear what your Dex plays (they hear it fade with distance, like proximity chat). */
    var share: Boolean = true
    /** Hear music other players' Dexes are playing nearby. */
    var hearOthers: Boolean = true
}

/** The companion speaking up on its own: reactions, battle commentary, radar, moods, banter. */
class PersonalitySettings {
    /** Master switch for everything it says without being asked. */
    var reactions: Boolean = true
    /** 0 = never speaks up, 1 = normal, 2 = very chatty. */
    var chattiness: Double = 1.0
    var battleCommentary: Boolean = true
    /** Beeps and hints when a shiny or legendary Cobblemon is nearby. */
    var radar: Boolean = true
    /** Bored when ignored, sleepy at night, excited near rare Cobblemon. */
    var moods: Boolean = true
    /** Chats with other Trainers' companions nearby. */
    var banter: Boolean = true
    /** Groups of remarks switched off on the phone's Settings page (ids from SpeakCategory). */
    var muted: MutableList<String> = mutableListOf()
}

/** The companion's spoken voice: Piper, free and offline. Downloaded once on first use. */
class TtsSettings {
    var enabled: Boolean = true
    /** Any Piper voice id from huggingface.co/rhasspy/piper-voices. Default is public domain (LJ Speech). */
    var voice: String = "en_US-ljspeech-medium"
    /** For voices with many speakers (LibriTTS-R has 904): which one. -1 = the voice's default. */
    var speaker: Int = -1
    /** Volty's own volume on top of Minecraft's Voice/Speech slider (0..1). */
    var volume: Double = 1.0
    /** Talking speed (1 = normal). */
    var speed: Double = 1.05
    /** Higher = smaller, squeakier companion. 1 = the voice's natural pitch. */
    var pitch: Double = 1.10
    /** Electric buzz on the voice, 0 (none) to 1 (very fuzzy). */
    var buzz: Double = 0.12
    /** How lively the delivery is: 0 = flat and robotic, 1 = normal, 2 = very animated. */
    var expressiveness: Double = 1.0
    /** Pause after each sentence, in milliseconds (commas get about half). */
    var sentencePauseMs: Int = 260
    /** Short original chirps when you start and stop talking. */
    var soundEffects: Boolean = true
    /**
     * How to say words the voice gets wrong: word -> spelled the way it sounds. Add your own with
     * /dexbuddy voice pronounce <word> <sounds like>.
     */
    var pronunciations: MutableMap<String, String> = linkedMapOf(
        "Cobblemon" to "Cobble mon",
        "Pokémon" to "Poh kay mon",
        "Pokemon" to "Poh kay mon",
        "Pokédex" to "Poh kay dex",
        "Poké Ball" to "Poh kay ball",
        "Poké Balls" to "Poh kay balls",
        "Poké" to "Poh kay",
        "Dex" to "Decks",
    )
}

/** Speech-to-text: any OpenAI-compatible /audio/transcriptions endpoint (Groq Whisper by default, free tier). */
class SttSettings {
    /** Blank = same as llm.baseUrl. */
    var baseUrl: String = ""
    /** Blank = same as llm.apiKey. */
    var apiKey: String = ""
    /** Blank = pick a Whisper model from the provider's model list. */
    var model: String = ""
    /** ISO language code of what you speak, e.g. "en". Blank = auto-detect (slower, less accurate). */
    var language: String = "en"
    var timeoutSeconds: Int = 20
}

class VoiceSettings {
    /** Part of a microphone name, e.g. "Razer". Blank = the mic Simple Voice Chat uses, else the system default. List them with /dexbuddy mics. */
    var micDevice: String = ""
    var micGain: Double = 1.0
    /** Must you have a Dex Buddy (hotbar or offhand) to talk? On servers without Dex Buddy, any Cobblemon Pokédex counts. */
    var requireItem: Boolean = true
    /** Silence Simple Voice Chat while you hold the talk key. */
    var muteVoiceChatWhileTalking: Boolean = true
    /** Hold your mic back from Simple Voice Chat while your Dex talks, so its voice doesn't leak to nearby players through your speakers. */
    var muteVoiceChatWhileDexSpeaks: Boolean = true
    var maxSeconds: Int = 30
    /** How you start talking: "wakeword" (say "Hey Dex"), "key" (hold the talk key) or "both". */
    var activation: String = "wakeword"
    /** Wake phrases. "hey <companion name>" always works too. */
    var wakePhrases: MutableList<String> = mutableListOf("hey dex", "okay dex")
    /** Seconds of quiet that end your question after the wake word. */
    var endOfSpeechSeconds: Double = 0.9
}

object BuddyConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val path = FabricLoader.getInstance().configDir.resolve("dexbuddy.json")

    @Volatile
    var settings: BuddySettings = BuddySettings()
        private set

    fun load() {
        settings = try {
            if (Files.exists(path)) {
                gson.fromJson(Files.readString(path), BuddySettings::class.java) ?: BuddySettings()
            } else BuddySettings()
        } catch (e: Exception) {
            // Message only: the file may hold an API key.
            DexBuddyClient.LOG.warn("[dexbuddy] config/dexbuddy.json could not be read ({}); using defaults", e.javaClass.simpleName)
            BuddySettings()
        }
        save() // writes any new fields with their defaults
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(settings))
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] could not save config: {}", e.javaClass.simpleName)
        }
    }

    val configFileName: String get() = "config/dexbuddy.json"
}
