package dev.dexbuddy.client.brain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.context.GameContext
import dev.dexbuddy.client.knowledge.DataCards
import dev.dexbuddy.client.knowledge.NameMatcher
import dev.dexbuddy.client.llm.LlmClient
import dev.dexbuddy.client.llm.LlmException
import dev.dexbuddy.client.llm.Tools
import dev.dexbuddy.client.tts.Tts
import dev.dexbuddy.client.ui.Subtitles
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One question in, the companion's reply out, sentence by sentence. Game state and data cards are captured on
 * the client thread; the model call and tool loop run on a worker; output hops back to the client thread.
 */
object BuddyBrain {
    private const val MAX_EXCHANGES = 6
    private const val MAX_TOOL_ROUNDS = 3

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dexbuddy-brain").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val history = ArrayDeque<Pair<String, String>>() // trainer line, companion line (worker thread only)
    private var announcedModel: String? = null

    /** Clears conversation memory (new world session). */
    fun resetSession() {
        worker.execute { history.clear() }
    }

    /** A question is being answered. */
    val isBusy: Boolean get() = busy.get()

    /** Where the question being answered was typed (VoltyChat.PHONE / CHAT), or NONE for voice. */
    @Volatile private var typedSource = dev.dexbuddy.client.phone.VoltyChat.NONE

    /** Waiting for the first sentence of the reply (the HUD shows "thinking"). */
    @Volatile var thinking: Boolean = false
        private set

    @Volatile private var sttMs: Long = -1

    /** Speech-to-text mishears sound-alikes; tell the model so it reads the intent, not the typo. */
    private const val SPOKEN_NOTE = "\n\nYour Trainer said this out loud and it was written down by ear, so words may be swapped for sound-alikes (\"week 2\" for \"weak to\", \"pick a chew\" for \"Pikachu\"). Answer what they most likely meant; never point out the mishearing."

    /**
     * Client thread. [startedAt] is when the Trainer finished speaking (voice) or now (text), so the
     * debug timings measure what the Trainer actually waits for.
     */
    fun ask(message: String, startedAt: Long = System.nanoTime(), sttMs: Long = -1, spoken: Boolean = false) {
        val text = message.trim()
        if (text.isEmpty()) return
        // Typed questions (phone chat or "@dex") and their answers go into the phone's chat with it.
        val typed = dev.dexbuddy.client.phone.VoltyChat.take()
        if (typed != dev.dexbuddy.client.phone.VoltyChat.NONE) dev.dexbuddy.client.phone.VoltyChat.user(text)
        // "Stay", "light the way", quizzes, hide and seek...: done instantly, no AI needed.
        dev.dexbuddy.client.phone.VoltyChat.syncSource = typed
        val handled = try { dev.dexbuddy.client.antics.Commands.tryHandle(text) } finally { dev.dexbuddy.client.phone.VoltyChat.syncSource = dev.dexbuddy.client.phone.VoltyChat.NONE }
        if (handled) return
        typedSource = typed
        if (!busy.compareAndSet(false, true)) {
            say(Component.literal("${dev.dexbuddy.client.entity.CompanionClient.companionName} is still answering. One sec!").withStyle(ChatFormatting.GRAY))
            return
        }
        thinking = true
        this.sttMs = sttMs
        val t0 = startedAt
        Tts.beginReply(t0) { ms -> if (BuddyConfig.settings.debugTimings) debug("first audio ${ms}ms after you finished") }
        if (BuddyConfig.settings.chatLog.equals("both", true)) {
            say(Component.literal("You: ").withStyle(ChatFormatting.GRAY).append(Component.literal(text).withStyle(ChatFormatting.WHITE)))
        }

        val system: String
        val cardNames: String
        val spawnQueries: List<String>
        try {
            val snap = GameContext.snapshot()
            val named = NameMatcher.find(text)
            // The Pokémon in the crosshair gets its full card, so "what is this?" needs no tool call.
            val looked = GameContext.lookedAtPokemon()
            val hits = if (looked != null && named.none { it is NameMatcher.Hit.Pokemon && it.species == looked.pokemon.species })
                listOf<NameMatcher.Hit>(NameMatcher.Hit.Pokemon(looked.pokemon.species)) + named else named
            val lookCard = looked?.let { "[TRAINER IS LOOKING AT] ${GameContext.describeSeen(it)}\n" } ?: ""
            spawnQueries = spawnQueriesFor(text, hits)
            cardNames = hits.joinToString {
                when (it) {
                    is NameMatcher.Hit.Pokemon -> DataCards.speciesName(it.species)
                    is NameMatcher.Hit.Move -> it.move.displayName.string
                    is NameMatcher.Hit.Ability -> it.ability.name
                    is NameMatcher.Hit.Item -> it.item.name
                }
            }
            dev.dexbuddy.client.antics.Mood.noteActivity()
            val mood = "\nYour mood right now: ${dev.dexbuddy.client.antics.Mood.state.describe} (let it color your tone a little)."
            val bond = GameContext.friendship()?.let { "\nFriendship with your Trainer: $it hearts (more hearts, more affectionate and playful)." } ?: ""
            system = Persona.render(snap, lookCard + DataCards.forHits(hits)) + mood + bond +
                specialMode(text) + dev.dexbuddy.client.antics.Memory.forPrompt() + if (spoken) SPOKEN_NOTE else ""
        } catch (e: Exception) {
            busy.set(false)
            thinking = false
            DexBuddyClient.LOG.error("[dexbuddy] could not read the world for context", e)
            say(Component.literal("Bzzt... my sensors glitched. Try again?").withStyle(ChatFormatting.GOLD))
            return
        }
        val tContext = System.nanoTime()
        worker.execute { answer(text, system, cardNames, t0, tContext, spawnQueries, spoken) }
    }

    private val rateTeam = Regex("\\b(rate|judge|grade|review|roast) (my|our) (team|party|squad|lineup)\\b|\\bhow (good|strong|bad) is my (team|party)\\b", RegexOption.IGNORE_CASE)
    private val roastBuild = Regex("\\b(roast|rate|judge|review) (my|this|our) (build|base|house|home|castle|farm|place|room)\\b|\\bwhat do you think of my (build|base|house)\\b", RegexOption.IGNORE_CASE)

    /** Fun modes the Trainer can ask for: an honest-but-funny team review, or a roast of their build. */
    private fun specialMode(text: String): String = when {
        rateTeam.containsMatchIn(text) ->
            "\n\nSPECIAL: Your Trainer wants you to RATE THEIR TEAM. Be a funny, honest coach: a score out of ten, one compliment, " +
                "one cheeky roast about a weakness (shared type weaknesses, low levels, odd move choices), and one real tip. 3 to 5 sentences. Use the team data."
        roastBuild.containsMatchIn(text) ->
            "\n\nSPECIAL: Your Trainer wants you to ROAST THEIR BUILD. Be playful and teasing, never mean: comment on the blocks and " +
                "layout below like a dramatic home-makeover judge, end with a score out of ten and one genuine compliment. 3 to 5 sentences.\n" +
                "BUILD AROUND THE TRAINER: ${GameContext.buildSummary()}"
        else -> ""
    }

    private val spawnWords = Regex("\\b(spawn|spawns|spawning|where|find|found|catch|caught|live|lives|habitat|appear|appears|nearby|around here)\\b", RegexOption.IGNORE_CASE)
    private val hereWords = Regex("\\b(here|nearby|around|this biome|this area)\\b", RegexOption.IGNORE_CASE)

    /** Spawn lookups worth doing before the model runs: named species, or "here" ("" = here). */
    private fun spawnQueriesFor(text: String, hits: List<NameMatcher.Hit>): List<String> {
        if (!spawnWords.containsMatchIn(text)) return emptyList()
        val species = hits.filterIsInstance<NameMatcher.Hit.Pokemon>().map { it.species.name }.take(2)
        return if (species.isNotEmpty()) species else if (hereWords.containsMatchIn(text) || text.contains("spawn", true)) listOf("") else emptyList()
    }

    private fun answer(text: String, baseSystem: String, cardNames: String, t0: Long, tContext: Long, spawnQueries: List<String>, spoken: Boolean = false) {
        val settings = BuddyConfig.settings
        val llm = settings.llm
        var firstToken = 0L
        val reply = StringBuilder()
        val splitter = SentenceSplitter { sentence -> speak(clean(sentence)) }
        try {
            // Spawn data lives on the server: fetch it now rather than make the model ask for it.
            var system = baseSystem
            if (spawnQueries.isNotEmpty()) {
                val tSpawn = System.nanoTime()
                val answers = spawnQueries.map { dev.dexbuddy.client.SpawnClient.request(it, 1500) }
                system += "\n\nSPAWN DATA (from this world's spawn records; the only truth about spawns)\n" + answers.joinToString("\n")
                if (settings.debugTimings) debug("spawn data ${(System.nanoTime() - tSpawn) / 1_000_000}ms for [${spawnQueries.joinToString { it.ifEmpty { "here" } }}]")
            }
            val model = LlmClient.model(llm)
            if (model != announcedModel) {
                announcedModel = model
                onClient { say(Component.literal("[Dex Buddy] using model $model").withStyle(ChatFormatting.DARK_GRAY)) }
            }
            val messages = JsonArray().apply {
                add(msg("system", system))
                for ((u, a) in history) { add(msg("user", u)); add(msg("assistant", a)) }
                add(msg("user", text))
            }
            val tools = if (llm.useTools) Tools.definitions else null
            var round = 0
            var fumbles = 0
            while (true) {
                val onText: (String) -> Unit = { delta ->
                    if (firstToken == 0L) firstToken = System.nanoTime()
                    reply.append(delta)
                    splitter.feed(delta)
                }
                val completion = try {
                    // A fumbled tool call gets one retry, then an answer without tools.
                    LlmClient.stream(llm, messages, if (round < MAX_TOOL_ROUNDS && fumbles < 2) tools else null, onText)
                } catch (e: LlmException) {
                    if (e.kind != LlmException.Kind.TOOL_FAILED || fumbles >= 2) throw e
                    fumbles++
                    continue
                }
                if (completion.toolCalls.isEmpty()) break
                round++
                // Echo the assistant's tool request, then answer each call.
                messages.add(JsonObject().apply {
                    addProperty("role", "assistant")
                    if (completion.text.isNotEmpty()) addProperty("content", completion.text) else add("content", com.google.gson.JsonNull.INSTANCE)
                    add("tool_calls", JsonArray().apply {
                        completion.toolCalls.forEach { c ->
                            add(JsonObject().apply {
                                addProperty("id", c.id); addProperty("type", "function")
                                add("function", JsonObject().apply { addProperty("name", c.name); addProperty("arguments", c.arguments) })
                            })
                        }
                    })
                })
                for (c in completion.toolCalls) {
                    val result = Tools.execute(c)
                    if (settings.debugTimings) debug("tool ${c.name}(${c.arguments}) -> ${result.length} chars")
                    messages.add(JsonObject().apply {
                        addProperty("role", "tool"); addProperty("tool_call_id", c.id); addProperty("content", result)
                    })
                }
            }
            splitter.flush()
            val full = clean(reply.toString())
            if (full.isBlank()) speak("Bzzt? I lost my train of thought. Ask me again?")
            dev.dexbuddy.client.phone.VoltyChat.answer(full.ifBlank { "Bzzt? I lost my train of thought. Ask me again?" }, typedSource)
            remember(text, full)
            // It asked you something back: answer without saying "Hey Dex" again.
            if (spoken && full.trimEnd().endsWith("?")) dev.dexbuddy.client.voice.WakeWord.expectAnswer()
            if (settings.debugTimings) {
                val ms = { t: Long -> (t - t0) / 1_000_000 }
                val stt = if (sttMs >= 0) "speech-to-text ${sttMs}ms, " else ""
                debug("${stt}context ${ms(tContext)}ms (~${system.length / 4} prompt tokens, ${LlmClient.lastModelUsed}), first token ${if (firstToken > 0) ms(firstToken) else -1}ms, total ${ms(System.nanoTime())}ms, tool rounds $round, cards [$cardNames]")
            }
        } catch (e: LlmException) {
            splitter.flush()
            DexBuddyClient.LOG.warn("[dexbuddy] reply failed: {} ({})", e.kind, e.message)
            fail(e)
        } catch (e: Exception) {
            splitter.flush()
            DexBuddyClient.LOG.error("[dexbuddy] reply failed", e)
            speak("Bzzt... my signal's fuzzy. Try again in a moment!")
        } finally {
            thinking = false
            busy.set(false)
        }
    }

    private fun fail(e: LlmException) {
        val (line, hint) = when (e.kind) {
            LlmException.Kind.NO_KEY -> "Bzzt! My link to the Dex network isn't set up yet." to
                "Add your API key under llm.apiKey in ${BuddyConfig.configFileName}, then run /dexbuddy reload."
            LlmException.Kind.BAD_KEY -> "Bzzt! The Dex network won't let me in." to
                "Your API key was rejected. Check llm.apiKey in ${BuddyConfig.configFileName}, then /dexbuddy reload."
            LlmException.Kind.RATE_LIMIT -> "Whoa, too many questions at once! Give me a few seconds." to "The provider is rate limiting. Wait a moment."
            LlmException.Kind.BAD_MODEL -> "Bzzt? My data link is pointed somewhere strange." to
                "Model problem: ${e.message}. Set llm.model in ${BuddyConfig.configFileName} (or leave it blank to auto-pick)."
            LlmException.Kind.NETWORK -> "Bzzt... my signal's fuzzy. Try again in a moment!" to "Network: ${e.message}. Check llm.baseUrl and your connection."
            LlmException.Kind.SERVER, LlmException.Kind.TOOL_FAILED -> "Bzzt... the Dex network hiccuped. Try again?" to "Provider: ${e.message}"
        }
        speak(line)
        dev.dexbuddy.client.phone.VoltyChat.answer(line, typedSource)
        onClient { say(Component.literal("[Dex Buddy] $hint").withStyle(ChatFormatting.RED)) }
    }

    private fun remember(user: String, reply: String) {
        if (reply.isBlank()) return
        history.addLast(user to reply)
        while (history.size > MAX_EXCHANGES) history.removeFirst()
    }

    private fun speak(sentence: String) {
        if (sentence.isBlank()) return
        thinking = false
        if (!BuddyConfig.settings.chatLog.equals("none", true)) onClient {
            say(Component.literal("${dev.dexbuddy.client.entity.CompanionClient.companionName}: ").withStyle(ChatFormatting.GOLD).append(Component.literal(sentence).withStyle(ChatFormatting.WHITE)))
        }
        // With a voice, each subtitle appears as its sentence starts playing; without one, right away.
        val spoken = Tts.say(sentence) { Subtitles.push(sentence) }
        if (!spoken) onClient { Subtitles.push(sentence) }
    }

    private fun debug(s: String) {
        DexBuddyClient.LOG.info("[dexbuddy] {}", s)
        onClient { say(Component.literal("[Dex Buddy debug] $s").withStyle(ChatFormatting.DARK_GRAY)) }
    }

    /** Spoken output: drop markdown and list markers the persona asks the model not to use. */
    private fun clean(s: String): String =
        s.replace(Regex("[*_#`]+"), "").replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            // In this world they're Cobblemon (item names like Poké Ball and Pokédex are left alone).
            .replace(Regex("\\bPok[eé]mon\\b", RegexOption.IGNORE_CASE), "Cobblemon")
            .trim()

    private fun msg(role: String, content: String) = JsonObject().apply { addProperty("role", role); addProperty("content", content) }

    private fun onClient(block: () -> Unit) = Minecraft.getInstance().execute(block)

    private fun say(c: Component) {
        Minecraft.getInstance().gui?.chat?.addMessage(c)
    }
}
