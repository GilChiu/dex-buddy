package dev.dexbuddy.client.context

import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import dev.dexbuddy.client.BuddyConfig
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Immutable snapshot of what the companion can "see". Taken on the client thread, then handed to the worker.
 */
data class Snapshot(
    val playerName: String,
    val preferredName: String,
    val party: List<String>,
    val surroundings: String,
    val inBattle: Boolean,
    val pokedex: String,
) {
    fun autoContext(): String = buildString {
        append("Trainer: ").append(playerName).append('\n')
        append(surroundings).append('\n')
        append("Battle in progress: ").append(if (inBattle) "yes" else "no").append('\n')
        append("Team: ").append(if (party.isEmpty()) "no Cobblemon with them" else party.joinToString("; "))
    }
}

object GameContext {
    /** Client thread only. */
    fun snapshot(): Snapshot {
        val mc = Minecraft.getInstance()
        val name = mc.player?.gameProfile?.name ?: "Trainer"
        val preferred = BuddyConfig.settings.preferredName.ifBlank { name }
        return Snapshot(name, preferred, party(), surroundings(), CobblemonClient.battle != null, pokedexProgress())
    }

    fun party(): List<String> = CobblemonClient.storage.party.slots.filterNotNull().map { describe(it) }

    fun describe(p: Pokemon): String {
        val species = p.species.translatedName.string
        val nick = p.nickname?.string?.takeIf { it.isNotBlank() && it != species }
        val label = if (nick != null) "$nick (a $species)" else species
        val hpPct = if (p.maxHealth > 0) p.currentHealth * 100 / p.maxHealth else 0
        val status = when {
            p.isFainted() -> "fainted"
            else -> p.status?.status?.showdownName?.let { statusName(it) } ?: "healthy"
        }
        val shiny = if (p.shiny) ", shiny" else ""
        val moves = p.moveSet.getMoves().joinToString("/") { it.template.displayName.string }
        return "$label Lv${p.level}, HP ${p.currentHealth}/${p.maxHealth} ($hpPct%), $status$shiny, ability ${abilityName(p)}, moves $moves"
    }

    private fun abilityName(p: Pokemon): String =
        net.minecraft.client.resources.language.I18n.get(p.ability.displayName)

    private fun statusName(showdown: String): String = when (showdown) {
        "psn" -> "poisoned"; "tox" -> "badly poisoned"; "brn" -> "burned"; "par" -> "paralyzed"
        "slp" -> "asleep"; "frz" -> "frozen"; else -> showdown
    }

    fun surroundings(): String {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return "Location: unknown"
        val player: Player = mc.player ?: return "Location: unknown"
        val pos = player.blockPosition()
        val biome = level.getBiome(pos).unwrapKey().map { readable(it.location().path) }.orElse("unknown biome")
        val dim = when (val d = level.dimension().location().path) {
            "overworld" -> "the Overworld"; "the_nether" -> "the Nether"; "the_end" -> "the End"; else -> readable(d)
        }
        val t = (level.dayTime % 24000).toInt()
        val time = when {
            t < 1000 -> "dawn"; t < 6000 -> "morning"; t < 9000 -> "midday"; t < 12000 -> "afternoon"
            t < 13000 -> "dusk"; t < 18000 -> "night"; t < 23000 -> "late night"; else -> "dawn"
        }
        val weather = when {
            level.isThundering -> "thunderstorm"; level.isRaining -> "raining"; else -> "clear"
        }
        val light = level.getMaxLocalRawBrightness(pos)
        return "Place: $biome in $dim at x=${pos.x}, y=${pos.y}, z=${pos.z} | Time: $time | Weather: $weather | Light: $light/15 | Trainer HP ${player.health.toInt()}/${player.maxHealth.toInt()}\n" +
            "Looking at: ${lookTarget()}\nWild Cobblemon around (already spawned): ${nearbyPokemon()}"
    }

    private const val LOOK_RANGE = 48.0
    private const val NEARBY_RANGE = 64.0
    /** Aim assist: a Pokémon within this many degrees of the crosshair counts as "the one I'm looking at". */
    private const val AIM_ASSIST_DEGREES = 7.0

    /**
     * The Pokémon the Trainer is looking at, up to [LOOK_RANGE] blocks away (the vanilla crosshair only
     * reaches ~4 blocks). Exact crosshair hits win; otherwise the visible Pokémon closest to the aim line.
     */
    /** The last Cobblemon scanned with the Dex: "what's it weak to?" right after a scan means that one. */
    private var scanned: PokemonEntity? = null
    private var scannedAt = 0L

    fun noteScanned(e: PokemonEntity) { scanned = e; scannedAt = System.currentTimeMillis() }

    /** What you're aiming at; if nothing, the one you scanned in the last minute and a half. */
    fun lookedAtPokemon(): PokemonEntity? =
        aimedPokemon() ?: scanned?.takeIf { it.isAlive && System.currentTimeMillis() - scannedAt < 90_000 }

    private fun aimedPokemon(): PokemonEntity? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        (mc.hitResult as? EntityHitResult)?.entity?.let { if (it is PokemonEntity) return it }
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        // Stop the ray at the first solid block, so Pokémon behind walls don't count.
        val wall = level.clip(ClipContext(eye, eye.add(look.scale(LOOK_RANGE)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
        val reach = if (wall.type == HitResult.Type.MISS) LOOK_RANGE else eye.distanceTo(wall.location)
        val end = eye.add(look.scale(reach))
        ProjectileUtil.getEntityHitResult(player, eye, end, player.boundingBox.expandTowards(look.scale(reach)).inflate(1.0),
            { it is PokemonEntity && !it.isInvisible }, reach * reach)?.entity?.let { return it as PokemonEntity }
        val cos = cos(Math.toRadians(AIM_ASSIST_DEGREES))
        return level.getEntitiesOfClass(PokemonEntity::class.java, player.boundingBox.inflate(LOOK_RANGE))
            .asSequence()
            .filter { !it.isInvisible && player.hasLineOfSight(it) }
            .map { e -> e to look.dot(e.boundingBox.center.subtract(eye).normalize()) }
            .filter { (_, d) -> d >= cos }
            .maxByOrNull { (e, d) -> d - eye.distanceTo(e.boundingBox.center) * 0.0005 }?.first
    }

    /** One Pokémon in plain words: whose it is, species, form, level, shiny, distance and direction. */
    /**
     * Who owns this Cobblemon, as this client can tell. The Pokemon's own owner field is only filled in for
     * your own team; another Trainer's sent-out Cobblemon carries its owner on the synced entity instead.
     */
    fun ownerOf(e: PokemonEntity): java.util.UUID? = e.ownerUUID ?: e.pokemon.getOwnerUUID()

    /** Wild = nobody owns it and it isn't a battle stand-in. */
    fun isWild(e: PokemonEntity): Boolean = ownerOf(e) == null && !e.isBattleClone() && e.battleId == null

    fun describeSeen(e: PokemonEntity): String {
        val mc = Minecraft.getInstance()
        val p = e.pokemon
        val owner = when (ownerOf(e)) { null -> "wild"; mc.player?.uuid -> "your own"; else -> "another Trainer's" }
        val form = p.form.name.takeIf { it.isNotBlank() && !it.equals("normal", true) }?.let { " ($it form)" } ?: ""
        val nick = p.nickname?.string?.takeIf { it.isNotBlank() }?.let { " named $it" } ?: ""
        return "$owner ${p.species.translatedName.string}$form$nick Lv${p.level}${if (p.shiny) ", SHINY" else ""}, ${where(e)}"
    }

    /** "8 blocks ahead-left", relative to where the Trainer faces. */
    fun where(e: net.minecraft.world.entity.Entity): String {
        val player = Minecraft.getInstance().player ?: return ""
        val dx = e.x - player.x
        val dz = e.z - player.z
        val dist = sqrt(dx * dx + dz * dz + (e.y - player.y).let { it * it }).toInt()
        // Minecraft yaw grows clockwise (0 = south, 90 = west), so target-minus-facing > 0 means "to the right".
        val angle = Math.toDegrees(atan2(-dx, dz)) - player.yRot
        val a = -(((angle % 360) + 540) % 360 - 180) // flip so positive = left, matching the labels below
        val dir = when {
            a >= -22.5 && a < 22.5 -> "ahead"
            a >= 22.5 && a < 67.5 -> "ahead-left"
            a >= 67.5 && a < 112.5 -> "to the left"
            a >= 112.5 && a < 157.5 -> "behind-left"
            a <= -22.5 && a > -67.5 -> "ahead-right"
            a <= -67.5 && a > -112.5 -> "to the right"
            a <= -112.5 && a > -157.5 -> "behind-right"
            else -> "behind"
        }
        val height = (e.y - player.y).let { if (it > 4) ", above" else if (it < -4) ", below" else "" }
        return "$dist blocks $dir$height"
    }

    /** Wild Pokémon already spawned within [NEARBY_RANGE] blocks, nearest first, with where they are. */
    fun nearbyPokemon(limit: Int = 10): String {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return "none"
        val list = mc.level?.getEntitiesOfClass(PokemonEntity::class.java, player.boundingBox.inflate(NEARBY_RANGE))
            ?.filter { ownerOf(it) == null && !it.isInvisible }
            ?.sortedBy { it.distanceToSqr(player) } ?: return "none"
        if (list.isEmpty()) return "none"
        val shown = list.take(limit).joinToString("; ") { e ->
            val p = e.pokemon
            "${p.species.translatedName.string} Lv${p.level}${if (p.shiny) " SHINY" else ""} (${where(e)})"
        }
        return if (list.size > limit) "$shown; and ${list.size - limit} more" else shown
    }

    fun lookTarget(): String {
        val mc = Minecraft.getInstance()
        lookedAtPokemon()?.let { return describeSeen(it) }
        val hit = mc.hitResult ?: return "nothing"
        return when (hit.type) {
            HitResult.Type.ENTITY -> {
                val e = (hit as EntityHitResult).entity
                "${e.displayName?.string ?: "a creature"} (an ordinary animal or creature, not a Cobblemon)"
            }
            HitResult.Type.BLOCK -> {
                val pos = (hit as BlockHitResult).blockPos
                mc.level?.getBlockState(pos)?.block?.name?.string ?: "a block"
            }
            else -> "nothing in particular"
        }
    }

    /** Friendship hearts with the companion: from the one floating nearby, else the Dex Buddy you carry. */
    fun friendship(): Int? {
        val player = Minecraft.getInstance().player ?: return null
        dev.dexbuddy.client.entity.CompanionClient.mine(player)?.let { return it.friendship }
        return heldDexBuddy()?.let { dev.dexbuddy.entity.DexBuddyEntity.friendshipOf(it) }
    }

    /** The Dex Buddy item in your inventory, if your companion is tucked away. */
    fun heldDexBuddy(): net.minecraft.world.item.ItemStack? {
        val player = Minecraft.getInstance().player ?: return null
        return (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }
            .firstOrNull { it.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) }
    }

    /** What the Trainer has built around them, for "roast my build": the main blocks, roof, light. */
    fun buildSummary(): String {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return "nothing"
        val level = mc.level ?: return "nothing"
        val base = player.blockPosition()
        val natural = setOf("Grass Block", "Dirt", "Stone", "Water", "Sand", "Deepslate", "Short Grass", "Tall Grass", "Gravel")
        val counts = HashMap<String, Int>()
        val r = 7
        for (x in -r..r) for (y in -3..9) for (z in -r..r) {
            val state = level.getBlockState(base.offset(x, y, z))
            if (state.isAir) continue
            val name = state.block.name.string
            if (name in natural) continue
            counts[name] = (counts[name] ?: 0) + 1
        }
        val roof = (1..12).any { !level.getBlockState(base.above(it)).isAir }
        val top = counts.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "${it.key} x${it.value}" }
        val light = level.getMaxLocalRawBrightness(base)
        return if (counts.isEmpty()) "just natural terrain, no building at all" else
            "${counts.size} kinds of building blocks within 7 blocks: $top. ${if (roof) "There's a roof overhead." else "No roof."} Light level $light."
    }

    fun pokedexProgress(): String {
        val dex = CobblemonClient.clientPokedexData
        var caught = 0
        var seen = 0
        for (id in dex.speciesRecords.keys) {
            when (dex.getKnowledgeForSpecies(id)) {
                PokedexEntryProgress.CAUGHT -> { caught++; seen++ }
                PokedexEntryProgress.ENCOUNTERED -> seen++
                else -> {}
            }
        }
        val total = PokemonSpecies.implemented.size
        return "Dex progress: $seen seen, $caught caught, out of $total species found in this world"
    }

    private fun readable(id: String): String = id.replace('_', ' ').replace('/', ' ')
}
