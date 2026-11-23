package dev.dexbuddy.games

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import dev.dexbuddy.net.QuizLinePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.text.Normalizer
import java.util.UUID

/**
 * Quizzes run on the server so answers can't be faked: "Who's that Cobblemon?" from real species data,
 * and type-matchup questions. Correct answers pay Numismatic coins when that mod is installed (with a
 * streak bonus and an hourly cap). Server thread.
 */
object QuizManager {
    private class Session(val kind: String, val answer: String, val accepts: Set<String>, val clues: List<String>, var hintsUsed: Int = 0, val speciesId: String = "", var guesses: Int = 0)

    /** Wrong guesses allowed per question (so answers can't be brute-forced for coins). */
    private const val MAX_GUESSES = 6

    private val sessions = HashMap<UUID, Session>()
    private val streaks = HashMap<UUID, Int>()
    private val coinsThisHour = HashMap<UUID, Pair<Long, Int>>()
    private const val COIN_CAP_PER_HOUR = 30
    private val COIN = ResourceLocation.fromNamespaceAndPath("numismatic-overhaul", "bronze_coin")

    private val best = HashMap<UUID, Int>()

    /** Longest run of correct answers this session (for the Dex Ball costume). */
    fun bestStreak(id: UUID): Int = best[id] ?: 0

    fun forget(id: UUID) { sessions.remove(id) }

    fun handle(player: ServerPlayer, action: String) {
        val id = player.uuid
        when {
            action.startsWith("start:") -> start(player, action.removePrefix("start:"))
            action == "hint" -> {
                val s = sessions[id] ?: return send(player, "No question right now! Want a quiz?", false)
                if (s.hintsUsed >= s.clues.size - 1) return send(player, "That's all my clues! Make your best guess, or say 'I give up'.", true, s.speciesId)
                s.hintsUsed++
                send(player, "Hint: ${s.clues[s.hintsUsed]}", true, s.speciesId)
            }
            action == "giveup" -> {
                val s = sessions.remove(id) ?: return
                streaks[id] = 0
                send(player, "It was ${s.answer}! Bzzt, so close. Want another one?", false, s.speciesId, reveal = true)
            }
            action == "stop" -> { sessions.remove(id); send(player, "Quiz over! That was fun.", false) }
            action.startsWith("answer:") -> answer(player, action.removePrefix("answer:"))
        }
    }

    private fun start(player: ServerPlayer, kind: String) {
        val s = if (kind == "type") typeQuestion() else whoQuestion() ?: return send(player, "Hmm, my Dex is empty right now. Try later!", false)
        sessions[player.uuid] = s
        val intro = if (s.kind == "who") "Who's that Cobblemon? " else "Type quiz! "
        send(player, intro + s.clues[0], true, s.speciesId)
    }

    private fun answer(player: ServerPlayer, said: String) {
        val id = player.uuid
        val s = sessions[id] ?: return
        val guess = norm(said.take(60))
        // "I think it's Pikachu" counts, but not a list of every possible answer at once.
        val short = said.trim().split(Regex("\\s+")).size <= 6 && guess.length <= 40
        val hits = s.accepts.count { a -> guess.contains(a) }
        // (A species' accepted spellings are all the same answer; a type quiz's are different answers.)
        val right = s.accepts.any { a -> guess == a || (a.length >= 6 && lev(guess, a) <= 2) } || (short && (if (s.kind == "type") hits == 1 else hits >= 1))
        if (!right) {
            if (++s.guesses >= MAX_GUESSES) {
                sessions.remove(id); streaks[id] = 0
                return send(player, "Out of guesses! It was ${s.answer}. Want another one?", false, s.speciesId, reveal = true)
            }
            val next = if (s.hintsUsed < s.clues.size - 1) " Want a hint? Just say hint!" else " Say 'I give up' to hear the answer."
            return send(player, "Nope, not quite!$next", true, s.speciesId)
        }
        sessions.remove(id)
        val streak = (streaks[id] ?: 0) + 1
        streaks[id] = streak
        best[id] = maxOf(best[id] ?: 0, streak)
        // Ten in a row earns the Dex Ball costume (right away if the companion is out, else next time it is).
        if (streak >= 10) dev.dexbuddy.entity.DexBuddyEntity.active[id]?.takeIf { it.isAlive }?.unlockCostume(dev.dexbuddy.entity.Look.BALL)
        val coins = reward(player, (1 + streak / 3 - s.hintsUsed).coerceIn(1, 5))
        val prize = if (coins > 0) " Here's $coins coin${if (coins > 1) "s" else ""}!" else ""
        val hype = when { streak >= 5 -> "$streak in a row! You're a walking Dex!"; streak >= 3 -> "That's $streak in a row!"; else -> "" }
        send(player, "Correct! It's ${s.answer}! $hype$prize Another one?".replace("  ", " "), false, s.speciesId, reveal = true)
    }

    /** Numismatic bronze coins, if that mod is installed; capped per hour so quizzes can't be farmed. */
    private fun reward(player: ServerPlayer, wanted: Int): Int {
        val item = BuiltInRegistries.ITEM.getOptional(COIN).orElse(null) ?: return 0
        val now = System.currentTimeMillis()
        val (since, given) = coinsThisHour[player.uuid]?.takeIf { now - it.first < 3_600_000 } ?: (now to 0)
        val n = minOf(wanted, COIN_CAP_PER_HOUR - given)
        if (n <= 0) return 0
        coinsThisHour[player.uuid] = since to given + n
        val stack = ItemStack(item, n)
        if (!player.inventory.add(stack)) player.drop(stack, false)
        return n
    }

    // ---- questions ------------------------------------------------------------------------------

    private fun whoQuestion(): Session? {
        val pool = PokemonSpecies.implemented.filter { it.nationalPokedexNumber > 0 }
        if (pool.isEmpty()) return null
        val sp = pool.random()
        val types = sp.types.joinToString(" and ") { pretty(it.name) }
        val abilities = sp.abilities.map { it.template.name } .distinct()
        val normal = sp.abilities.firstOrNull { it !is HiddenAbility }?.template?.name
        val article = if (types.first().lowercaseChar() in "aeiou") "an" else "a"
        val clues = mutableListOf("It's $article $types type from generation ${generation(sp.nationalPokedexNumber)}.")
        clues += buildString {
            if (normal != null) append("One of its abilities is {ability:$normal}. ")
            append("It's about ${"%.1f".format(sp.height / 10f)} meters tall and weighs ${"%.1f".format(sp.weight / 10f)} kilograms.")
        }
        sp.preEvolution?.species?.let { clues += "It evolves from ${it.name}." } ?: run {
            if (sp.evolutions.isNotEmpty()) clues += "It can still evolve!" else clues += "It doesn't evolve from anything."
        }
        clues += "Its name starts with ${sp.name.first().uppercase()} and has ${sp.name.length} letters."
        val accepts = setOf(norm(sp.name), norm(sp.resourceIdentifier.path))
        if (abilities.isEmpty()) clues.removeAt(1)
        return Session("who", sp.name, accepts, clues, speciesId = sp.resourceIdentifier.toString())
    }

    private fun typeQuestion(): Session {
        val defender = TypeChart.types.random()
        val strong = TypeChart.superEffectiveAgainst(defender)
        val clue = "Name one type that's super effective against ${pretty(defender)}!"
        val hint = "There are ${strong.size} right answers. One of them starts with ${pretty(strong.random()).first()}."
        return Session("type", strong.joinToString(", ") { pretty(it) }, strong.map { norm(it) }.toSet(), listOf(clue, hint))
    }

    private fun generation(n: Int) = when {
        n <= 151 -> 1; n <= 251 -> 2; n <= 386 -> 3; n <= 493 -> 4; n <= 649 -> 5
        n <= 721 -> 6; n <= 809 -> 7; n <= 905 -> 8; else -> 9
    }

    private fun send(player: ServerPlayer, line: String, open: Boolean, species: String = "", reveal: Boolean = false) =
        ServerPlayNetworking.send(player, QuizLinePayload(line, open, species, reveal))

    private fun pretty(s: String) = s.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").split(' ')
        .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}

/** The standard type chart (attacking -> defending), for type quizzes on the server. */
object TypeChart {
    val types = listOf("normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground", "flying",
        "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy")

    private val strong: Map<String, List<String>> = mapOf(
        "fire" to listOf("grass", "ice", "bug", "steel"),
        "water" to listOf("fire", "ground", "rock"),
        "electric" to listOf("water", "flying"),
        "grass" to listOf("water", "ground", "rock"),
        "ice" to listOf("grass", "ground", "flying", "dragon"),
        "fighting" to listOf("normal", "ice", "rock", "dark", "steel"),
        "poison" to listOf("grass", "fairy"),
        "ground" to listOf("fire", "electric", "poison", "rock", "steel"),
        "flying" to listOf("grass", "fighting", "bug"),
        "psychic" to listOf("fighting", "poison"),
        "bug" to listOf("grass", "psychic", "dark"),
        "rock" to listOf("fire", "ice", "flying", "bug"),
        "ghost" to listOf("psychic", "ghost"),
        "dragon" to listOf("dragon"),
        "dark" to listOf("psychic", "ghost"),
        "steel" to listOf("ice", "rock", "fairy"),
        "fairy" to listOf("fighting", "dragon", "dark"),
    )

    fun superEffectiveAgainst(defender: String): List<String> = strong.filter { defender in it.value }.keys.toList()
}
