package dev.dexbuddy.spawn

import com.cobblemon.mod.common.api.conditional.RegistryLikeCondition
import com.cobblemon.mod.common.api.conditional.RegistryLikeIdentifierCondition
import com.cobblemon.mod.common.api.conditional.RegistryLikeTagCondition
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.TimeRange
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.pokemon.Species
import com.mojang.datafixers.util.Either
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import java.lang.reflect.Modifier

/**
 * Server side: answers spawn questions from Cobblemon's world spawn pool, which includes every
 * datapack's spawn files. Runs on the server thread. Output is plain English for the model.
 */
object SpawnInfo {
    private const val MAX_HERE = 30
    private const val MAX_ENTRIES = 12

    private val details: List<PokemonSpawnDetail>
        get() = CobblemonSpawnPools.WORLD_SPAWN_POOL.details.filterIsInstance<PokemonSpawnDetail>().filter { it.isValid() }

    /** What can spawn where this player stands: right now, and at other times or weather. */
    fun here(player: ServerPlayer): String {
        val level = player.serverLevel()
        val pos = player.blockPosition()
        val biome = level.getBiome(pos)
        val biomeName = biome.unwrapKey().map { readable(it.location()) }.orElse("this biome")
        val dimension = level.dimension().location()
        val time = (level.dayTime % 24000).toInt()
        val sky = level.canSeeSky(pos)
        val raining = level.isRaining
        val thundering = level.isThundering
        val skyLight = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos)
        val light = level.getMaxLocalRawBrightness(pos)

        data class Row(val species: String, val bucket: String, val bucketWeight: Float, val weight: Float, val now: Boolean, val note: String)
        val rows = mutableListOf<Row>()
        for (d in details) {
            if (!d.conditions.any { fitsPlace(it, biome, dimension) }) continue
            if (d.anticonditions.any { fitsPlace(it, biome, dimension) && !it.biomes.isNullOrEmpty() }) continue
            val name = speciesName(d) ?: continue
            // An entry can list several conditions (any one is enough); judge it by the best-fitting one.
            val blockers = d.conditions.filter { fitsPlace(it, biome, dimension) }.map { c ->
                buildList {
                    c.timeRange?.let { if (!it.contains(time)) add("only ${timeName(it)}") }
                    c.canSeeSky?.let { if (it != sky) add(if (it) "only under open sky" else "only underground or under cover") }
                    boolField(c, "isRaining")?.let { if (it != raining) add(if (it) "only in rain" else "only without rain") }
                    boolField(c, "isThundering")?.let { if (it != thundering) add(if (it) "only in thunderstorms" else "only without storms") }
                    c.minY?.let { if (pos.y < it) add("only above y=${it.toInt()}") }
                    c.maxY?.let { if (pos.y > it) add("only below y=${it.toInt()}") }
                    c.minSkyLight?.let { if (skyLight < it) add("only in brighter open areas") }
                    c.maxSkyLight?.let { if (skyLight > it) add("only underground or in the dark") }
                    c.minLight?.let { if (light < it) add("only in light level $it+") }
                    c.maxLight?.let { if (light > it) add("only in darkness (light $it or less)") }
                    if (c.structures?.isNotEmpty() == true) add("only near certain structures")
                    if (c.moonPhase != null) add("only during certain moon phases")
                }
            }.minByOrNull { it.size } ?: emptyList()
            rows += Row(name, d.bucket.name, d.bucket.weight, d.weight, blockers.isEmpty(), (listOf(place(d)) + blockers).joinToString(", "))
        }
        if (rows.isEmpty()) return "No Cobblemon spawn data for $biomeName at this spot."
        val best = rows.groupBy { it.species }.map { (_, rs) -> rs.sortedWith(compareByDescending<Row> { it.now }.thenByDescending { it.bucketWeight }).first() }
            .sortedWith(compareByDescending<Row> { it.now }.thenByDescending { it.bucketWeight }.thenByDescending { it.weight })
        // A few from every rarity, so "anything rare here?" has an answer.
        val perBucket = mapOf("common" to 15, "uncommon" to 8, "rare" to 5, "ultra-rare" to 3)
        fun pick(rows: List<Row>, scale: Int) = rows.groupBy { it.bucket }.flatMap { (b, rs) -> rs.take((perBucket[b] ?: 3) / scale) }
            .sortedByDescending { it.bucketWeight }.take(MAX_HERE)
        val now = pick(best.filter { it.now }, 1)
        val later = pick(best.filter { !it.now }, 2)
        return buildString {
            append("Spawns in $biomeName (y=${pos.y}, ${if (sky) "open sky" else "under cover"}, ${weather(raining, thundering)}, ${clock(time)}):\n")
            append("Right now: ").append(if (now.isEmpty()) "none" else now.joinToString("; ") { "${it.species} (${it.bucket}, ${it.note})" }).append('\n')
            if (later.isNotEmpty()) append("Other times here: ").append(later.joinToString("; ") { "${it.species} (${it.bucket}, ${it.note})" })
            if (best.size > now.size + later.size) append("\n(${best.size - now.size - later.size} more not listed)")
        }
    }

    /** Every place and condition a species spawns under, from the spawn files. */
    fun whereSpawns(query: String): String {
        val species = findSpecies(query) ?: return "NO DATA: no species called \"$query\"."
        val entries = details.filter { speciesOf(it) == species }
        if (entries.isEmpty()) return "${species.name} has no natural spawns in this world (it may come from evolution, eggs, fossils, raids or events)."
        // Many entries differ only in weight or held items: say each distinct one once.
        val lines = entries.sortedByDescending { it.bucket.weight }.map { d ->
            val c = d.conditions.firstOrNull()
            val levels = d.levelRange?.let { "Lv${it.first}-${it.last}" } ?: ""
            buildString {
                append("${d.bucket.name}, $levels, ${place(d)}")
                if (c != null) describe(c).takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                d.anticonditions.firstOrNull()?.let { a -> describe(a).takeIf { it.isNotBlank() }?.let { append(", but not: ").append(it) } }
            }
        }.distinct()
        return buildString {
            append("${species.name} spawns:\n")
            lines.take(MAX_ENTRIES).forEach { append("- ").append(it).append('\n') }
            if (lines.size > MAX_ENTRIES) append("(${lines.size - MAX_ENTRIES} more variations)")
        }.trim()
    }

    // ---- conditions -----------------------------------------------------------------------------------

    private fun fitsPlace(c: SpawningCondition<*>, biome: Holder<Biome>, dimension: ResourceLocation): Boolean {
        val dims = c.dimensions
        if (!dims.isNullOrEmpty() && dimension !in dims) return false
        val biomes = c.biomes
        return biomes.isNullOrEmpty() || biomes.any { it.fits(biome) }
    }

    /** Where the Pokémon appears, from the condition's kind (land, water surface, underwater, fishing...). */
    private fun place(d: PokemonSpawnDetail): String = when (d.conditions.firstOrNull()?.javaClass?.simpleName) {
        "GroundedSpawningCondition", "GroundedTypeSpawningCondition" -> "on land"
        "SubmergedSpawningCondition", "SubmergedTypeSpawningCondition" -> "underwater"
        "SurfaceSpawningCondition", "SurfaceTypeSpawningCondition" -> "on the water surface"
        "SeafloorSpawningCondition", "SeafloorTypeSpawningCondition" -> "on the seafloor"
        "FishingSpawningCondition" -> "by fishing"
        else -> "in the area"
    }

    /** Readable summary of any condition class, including ones addons add, from its non-empty fields. */
    private fun describe(c: SpawningCondition<*>): String {
        val parts = mutableListOf<String>()
        c.biomes?.takeIf { it.isNotEmpty() }?.let { bs -> parts += "biomes: " + bs.joinToString(", ") { biomeCondition(it) } }
        c.dimensions?.takeIf { it.isNotEmpty() }?.let { ds -> parts += "in " + ds.joinToString("/") { readable(it) } }
        c.timeRange?.let { parts += "time: ${timeName(it)}" }
        c.canSeeSky?.let { parts += if (it) "needs open sky" else "under cover or in caves" }
        boolField(c, "isRaining")?.let { parts += if (it) "while raining" else "when not raining" }
        boolField(c, "isThundering")?.let { parts += if (it) "during thunderstorms" else "when not thundering" }
        if (c.minY != null || c.maxY != null) parts += "y ${c.minY?.toInt() ?: "any"} to ${c.maxY?.toInt() ?: "any"}"
        c.minLight?.let { parts += "light at least $it" }
        c.maxLight?.let { parts += "light at most $it" }
        c.structures?.takeIf { it.isNotEmpty() }?.let { ss -> parts += "near " + ss.joinToString(", ") { structure(it) } }
        c.moonPhase?.let { parts += "moon phase $it" }
        return parts.joinToString(", ")
    }

    private fun biomeCondition(b: RegistryLikeCondition<Biome>): String = when (b) {
        is RegistryLikeTagCondition<*> -> readable(b.tag.location()).removePrefix("is ") + " biomes"
        is RegistryLikeIdentifierCondition<*> -> readable(b.identifier)
        else -> "certain biomes"
    }

    private fun <T> structure(e: Either<ResourceLocation, TagKey<T>>): String =
        e.map({ readable(it) }, { readable(it.location()) })

    /** Fields like isRaining live on subclasses in some versions, so read them by name. */
    private fun boolField(o: Any, name: String): Boolean? {
        var cls: Class<*>? = o.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }?.let { f ->
                return try { f.isAccessible = true; f.get(o) as? Boolean } catch (_: Throwable) { null }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun timeName(t: TimeRange): String {
        val ranges = t.ranges.map { it.first to it.last }
        TimeRange.timeRanges.entries.firstOrNull { (_, v) -> v === t || v.ranges.map { it.first to it.last } == ranges }?.let { return it.key }
        return ranges.joinToString(" or ") { "ticks ${it.first}-${it.second}" }
    }

    // ---- names ----------------------------------------------------------------------------------------

    private fun speciesOf(d: PokemonSpawnDetail): Species? {
        val id = d.pokemon.species ?: return null
        return PokemonSpecies.getByName(id.substringAfter(':').lowercase())
            ?: ResourceLocation.tryParse(if (':' in id) id else "cobblemon:$id")?.let { PokemonSpecies.getByIdentifier(it) }
    }

    private fun speciesName(d: PokemonSpawnDetail): String? {
        val s = speciesOf(d) ?: return null
        val form = d.pokemon.form?.takeIf { it.isNotBlank() }?.let { " ($it form)" } ?: ""
        return s.name + form
    }

    private fun findSpecies(query: String): Species? {
        val key = norm(query)
        if (key.isEmpty()) return null
        return PokemonSpecies.species.firstOrNull { norm(it.name) == key || norm(it.resourceIdentifier.path) == key }
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun readable(id: ResourceLocation): String = id.path.substringAfterLast('/').replace('_', ' ')

    private fun weather(rain: Boolean, thunder: Boolean) = if (thunder) "thunderstorm" else if (rain) "raining" else "clear"

    private fun clock(t: Int) = when {
        t < 1000 -> "dawn"; t < 6000 -> "morning"; t < 9000 -> "midday"; t < 12000 -> "afternoon"
        t < 13000 -> "dusk"; t < 18000 -> "night"; t < 23000 -> "late night"; else -> "dawn"
    }
}
