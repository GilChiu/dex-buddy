package dev.dexbuddy.client.llm

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.client.context.GameContext
import dev.dexbuddy.client.knowledge.DataCards
import dev.dexbuddy.client.knowledge.KnowledgeIndex
import dev.dexbuddy.client.knowledge.Names
import net.minecraft.client.Minecraft
import java.util.concurrent.TimeUnit

/** OpenAI function-calling tools. Results are plain text and are the source of truth for the model. */
object Tools {
    val definitions: JsonArray by lazy {
        JsonArray().apply {
            add(fn("get_pokemon", "Full Dex data for one Cobblemon species, including its learnset.",
                props("name_or_number" to str("Species name or national Dex number"), "form" to str("Optional form name")), "name_or_number"))
            add(fn("get_move", "Data for one move.", props("name" to str("Move name")), "name"))
            add(fn("get_ability", "Data for one ability.", props("name" to str("Ability name")), "name"))
            add(fn("get_item", "Data for one item.", props("name" to str("Item name")), "name"))
            add(fn("type_matchup", "Damage multiplier of attacking types against defending types.",
                props("attacking_types" to arr("Attacking type names"), "defending_types" to arr("Defending type names (1 or 2)")),
                "attacking_types", "defending_types"))
            add(fn("search_pokemon", "Find species matching filters (all optional).",
                props(
                    "type" to str("Must have this type"),
                    "ability" to str("Can have this ability"),
                    "learns_move" to str("Can learn this move"),
                    "egg_group" to str("In this egg group"),
                    "limit" to JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Max results, default 10") },
                )))
            add(fn("get_party", "The Trainer's current team with levels, HP, status and moves.", props()))
            add(fn("get_pokedex_progress", "How many species the Trainer has seen and caught.", props()))
            add(fn("get_spawns_here", "Which Cobblemon spawn where the Trainer stands, right now and at other times.", props()))
            add(fn("get_spawn_locations", "Where and when one species spawns: biomes, time, weather, rarity, levels.",
                props("name" to str("Species name")), "name"))
            add(fn("get_surroundings", "Biome, dimension, time, weather, light, and what the Trainer is looking at.", props()))
            add(fn("get_nearby_pokemon", "Every wild Cobblemon already out in the world near the Trainer right now, with level, shiny, distance and direction.", props()))
            add(fn("remember_fact", "Save something personal your Trainer told you, to remember in future chats (their favorite Cobblemon, a nickname, a goal...). Short, plain words, NO apostrophes or quotes.",
                props("fact" to str("e.g. Favorite Cobblemon is Eevee")), "fact"))
        }
    }

    /** Runs a tool call. Game data is read on the client thread; this blocks the worker until it's done. */
    fun execute(call: ToolCall): String {
        val args = try { JsonParser.parseString(call.arguments).asJsonObject } catch (_: Exception) { JsonObject() }
        // Spawn answers arrive on the client thread, so these wait here on the worker instead.
        when (call.name) {
            "get_spawns_here" -> return dev.dexbuddy.client.SpawnClient.request("")
            "get_spawn_locations" -> return dev.dexbuddy.client.SpawnClient.request(args.s("name"))
            "remember_fact" -> return dev.dexbuddy.client.antics.Memory.rememberFact(args.s("fact"))
        }
        return try {
            Minecraft.getInstance().submit<String> { run(call.name, args) }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "ERROR: the Dex could not read that right now."
        }
    }

    private fun run(name: String, a: JsonObject): String = when (name) {
        "get_pokemon" -> {
            val q = a.s("name_or_number")
            val sp = KnowledgeIndex.findSpecies(q)
            if (sp == null) "NO DATA: no species called \"$q\" exists in the Dex." else DataCards.pokemon(sp, withLearnset = true)
        }
        "get_move" -> KnowledgeIndex.findMove(a.s("name"))?.let { DataCards.move(it) } ?: "NO DATA: no move called \"${a.s("name")}\"."
        "get_ability" -> KnowledgeIndex.findAbility(a.s("name"))?.let { DataCards.ability(it) } ?: "NO DATA: no ability called \"${a.s("name")}\"."
        "get_item" -> KnowledgeIndex.findItem(a.s("name"))?.let { DataCards.item(it) } ?: "NO DATA: no item called \"${a.s("name")}\"."
        "type_matchup" -> typeMatchup(a)
        "search_pokemon" -> search(a)
        "get_party" -> GameContext.party().let { if (it.isEmpty()) "The Trainer has no Cobblemon with them." else it.joinToString("\n") }
        "get_pokedex_progress" -> GameContext.pokedexProgress()
        "get_surroundings" -> GameContext.surroundings()
        "get_nearby_pokemon" -> GameContext.nearbyPokemon(limit = 30)
        else -> "ERROR: unknown tool $name"
    }

    private fun typeMatchup(a: JsonObject): String {
        val atk = a.list("attacking_types").map { it to DataCards.findType(it) }
        val def = a.list("defending_types").map { it to DataCards.findType(it) }
        val unknown = (atk + def).filter { it.second == null }.map { it.first }
        if (unknown.isNotEmpty()) return "NO DATA: unknown type(s): ${unknown.joinToString()}"
        val defTypes = def.mapNotNull { it.second }
        return atk.joinToString("\n") { (n, t) ->
            "$n vs ${defTypes.joinToString("/") { DataCards.typeName(it) }}: x${DataCards.fmt(DataCards.multiplier(t!!, defTypes))}"
        }
    }

    private fun search(a: JsonObject): String {
        val type = a.s("type").takeIf { it.isNotBlank() }?.let { DataCards.findType(it) ?: return "NO DATA: unknown type \"${a.s("type")}\"" }
        val ability = a.s("ability").takeIf { it.isNotBlank() }?.let { KnowledgeIndex.findAbility(it) ?: return "NO DATA: unknown ability \"${a.s("ability")}\"" }
        val move = a.s("learns_move").takeIf { it.isNotBlank() }?.let { KnowledgeIndex.findMove(it) ?: return "NO DATA: unknown move \"${a.s("learns_move")}\"" }
        val egg = a.s("egg_group").takeIf { it.isNotBlank() }?.let { Names.normalize(it) }
        if (egg != null && !DataCards.hasFullSpeciesData()) return "UNAVAILABLE: egg group data is not in the Dex here yet."
        val limit = (a.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt ?: 10).coerceIn(1, 30)
        val matches = PokemonSpecies.implemented.filter { sp ->
            (type == null || sp.types.any { it == type }) &&
                (ability == null || sp.abilities.any { it.template == ability }) &&
                (move == null || move in sp.moves.getAllLegalMoves()) &&
                (egg == null || sp.eggGroups.any { Names.normalize(it.name) == egg })
        }.sortedBy { it.nationalPokedexNumber }
        if (matches.isEmpty()) return "No species in this world match."
        val shown = matches.take(limit).joinToString(", ") { DataCards.speciesName(it) }
        return "${matches.size} match: $shown" + if (matches.size > limit) " (and ${matches.size - limit} more)" else ""
    }

    // ---- schema helpers ----------------------------------------------------------------------------

    private fun JsonObject.s(k: String): String = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
    private fun JsonObject.list(k: String): List<String> {
        val e = get(k) ?: return emptyList()
        return if (e.isJsonArray) e.asJsonArray.map { it.asString } else listOf(e.asString)
    }

    private fun str(desc: String) = JsonObject().apply { addProperty("type", "string"); addProperty("description", desc) }
    private fun arr(desc: String) = JsonObject().apply {
        addProperty("type", "array"); addProperty("description", desc)
        add("items", JsonObject().apply { addProperty("type", "string") })
    }
    private fun props(vararg p: Pair<String, JsonObject>) = JsonObject().apply { p.forEach { (k, v) -> add(k, v) } }
    private fun fn(name: String, desc: String, properties: JsonObject, vararg required: String) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", desc)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", properties)
                add("required", JsonArray().apply { required.forEach { add(it) } })
            })
        })
    }
}
