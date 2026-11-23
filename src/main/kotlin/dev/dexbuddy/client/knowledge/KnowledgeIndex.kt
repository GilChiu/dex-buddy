package dev.dexbuddy.client.knowledge

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item

/**
 * Name index over everything Cobblemon (and its addons) registered at runtime. Nothing is hardcoded:
 * species, moves and abilities come from Cobblemon's registries (which datapacks extend), items from the
 * item registry filtered to Cobblemon and every mod that depends on Cobblemon.
 *
 * Build and read it on the client thread only: Cobblemon's registries are plain maps that its sync
 * packets replace on that thread.
 */
object KnowledgeIndex {
    class ItemEntry(val id: ResourceLocation, val item: Item, val name: String)

    class Built(
        val species: Map<String, Species>,
        val moves: Map<String, MoveTemplate>,
        val abilities: Map<String, AbilityTemplate>,
        val items: Map<String, ItemEntry>,
        val itemNamespaces: Set<String>,
        val speciesTotal: Int,
        val moveTotal: Int,
        /** Consonant skeleton -> species, for names speech-to-text mangles (see [Names.skeleton]). */
        val speciesBySound: Map<String, List<Species>>,
    )

    private var built: Built? = null

    fun invalidate() { built = null }

    fun get(): Built {
        val b = built
        if (b != null && b.speciesTotal == PokemonSpecies.count() && b.moveTotal == Moves.count()) return b
        return build().also { built = it }
    }

    /** Cobblemon plus every installed mod that declares a dependency on it. */
    fun cobblemonNamespaces(): Set<String> {
        val ids = linkedSetOf("cobblemon")
        for (mod in FabricLoader.getInstance().allMods) {
            if (mod.metadata.id == "dexbuddy") continue
            if (mod.metadata.dependencies.any { it.modId == "cobblemon" }) ids += mod.metadata.id
        }
        return ids
    }

    private fun build(): Built {
        val species = HashMap<String, Species>()
        for (s in PokemonSpecies.species) {
            species.putIfAbsent(Names.normalize(s.name), s)
            species.putIfAbsent(Names.normalize(s.translatedName.string), s)
            species.putIfAbsent(Names.normalize(s.resourceIdentifier.path), s)
        }
        val bySound = HashMap<String, MutableList<Species>>()
        for (s in PokemonSpecies.species) {
            val key = Names.skeleton(s.translatedName.string)
            if (key.length >= 3) bySound.getOrPut(key) { mutableListOf() }.let { if (s !in it) it += s }
        }
        val moves = HashMap<String, MoveTemplate>()
        for (m in Moves.all()) {
            moves.putIfAbsent(Names.normalize(m.name), m)
            moves.putIfAbsent(Names.normalize(m.displayName.string), m)
        }
        val abilities = HashMap<String, AbilityTemplate>()
        for (a in Abilities.all()) {
            abilities.putIfAbsent(Names.normalize(a.name), a)
            abilities.putIfAbsent(Names.normalize(I18n.get(a.displayName)), a)
        }
        val namespaces = cobblemonNamespaces()
        val items = HashMap<String, ItemEntry>()
        for (entry in BuiltInRegistries.ITEM.entrySet()) {
            val id = entry.key.location()
            if (id.namespace !in namespaces) continue
            val e = ItemEntry(id, entry.value, I18n.get(entry.value.descriptionId))
            items.putIfAbsent(Names.normalize(e.name), e)
            items.putIfAbsent(Names.normalize(id.path), e)
        }
        return Built(species, moves, abilities, items, namespaces, PokemonSpecies.count(), Moves.count(), bySound)
    }

    // ---- lookups (exact, then fuzzy) -------------------------------------------------------------

    fun findSpecies(query: String): Species? {
        val q = query.trim()
        q.removePrefix("#").toIntOrNull()?.let { n ->
            return PokemonSpecies.species.firstOrNull { it.nationalPokedexNumber == n && it.implemented }
                ?: PokemonSpecies.species.firstOrNull { it.nationalPokedexNumber == n }
        }
        return fuzzy(Names.normalize(q), get().species)
    }

    fun findMove(query: String): MoveTemplate? = fuzzy(Names.normalize(query), get().moves)
    fun findAbility(query: String): AbilityTemplate? = fuzzy(Names.normalize(query), get().abilities)
    fun findItem(query: String): ItemEntry? = fuzzy(Names.normalize(query), get().items)

    fun <T> fuzzy(key: String, map: Map<String, T>): T? {
        if (key.isEmpty()) return null
        map[key]?.let { return it }
        val budget = Names.fuzzBudget(key.length)
        if (budget == 0) return null
        var best: T? = null
        var bestD = budget + 1
        for ((k, v) in map) {
            val d = Names.levenshtein(key, k, budget)
            if (d < bestD) { bestD = d; best = v; if (d == 0) break }
        }
        return best
    }
}
