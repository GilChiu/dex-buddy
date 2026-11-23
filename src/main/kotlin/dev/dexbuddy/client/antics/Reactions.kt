package dev.dexbuddy.client.antics

import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.monster.Creeper
import java.util.UUID

/**
 * Watches what happens around the Trainer and lets the companion react: shinies and legendaries
 * nearby, catches, evolutions, level-ups, faints, getting hurt, big falls, creepers, weather, night.
 * Everything is read on the client, so this works on any server. Client thread.
 */
object Reactions {
    private class Member(val species: String, val level: Int, val fainted: Boolean)

    private var tick = 0
    private var warmup = 0
    private val party = HashMap<UUID, Member>()
    private val seenRare = HashSet<UUID>()
    private var lastHealth = -1f
    private var wasDead = false
    private var maxFall = 0f
    private var wasRaining = false
    private var wasThundering = false
    private var wasNight = false

    /** Joining a world: forget everything, and stay quiet while the party and world sync in. */
    fun reset() {
        warmup = 100; party.clear(); seenRare.clear(); lastHealth = -1f; wasDead = false; maxFall = 0f
        wasRaining = false; wasThundering = false; wasNight = false
    }

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        tick++
        val quiet = warmup > 0
        if (warmup > 0) warmup--

        // --- the Trainer ---
        val hp = player.health
        val dead = player.isDeadOrDying
        if (!quiet) {
            if (dead && !wasDead) Speaker.say("died", Speaker.pick(Lines.died), 30_000, urgent = true)
            else if (!dead && lastHealth > 0) {
                val lost = lastHealth - hp
                if (hp <= 4f && lastHealth > 4f) Speaker.say("lowhp", Speaker.pick(Lines.lowHealth), 60_000, urgent = true)
                else if (lost >= 4f) Speaker.say("ouch", Speaker.pick(Lines.ouch), 20_000)
            }
        }
        lastHealth = hp; wasDead = dead
        if (!player.onGround() && !player.isInWater && !player.abilities.flying) maxFall = maxOf(maxFall, player.fallDistance)
        else {
            if (maxFall >= 10f && !dead && !quiet) Speaker.say("fall", Speaker.pick(Lines.bigFall), 45_000)
            maxFall = 0f
        }

        if (tick % 20 != 0) return

        // --- the team ---
        val now = CobblemonClient.storage.party.slots.filterNotNull().associate { p ->
            p.uuid to Member(p.species.translatedName.string, p.level, p.isFainted())
        }
        if (!quiet && party.isNotEmpty()) {
            for ((id, m) in now) {
                val before = party[id]
                when {
                    before == null -> { Speaker.say("caught", Speaker.pick(Lines.caught, "mon" to m.species), 10_000, urgent = true); Memory.note("Caught a ${m.species} together") }
                    before.species != m.species -> { Speaker.say("evolved", Speaker.pick(Lines.evolved, "mon" to m.species, "old" to before.species), 5_000, urgent = true); Memory.note("Watched ${before.species} evolve into ${m.species}") }
                    m.level > before.level -> Speaker.say("level", Speaker.pick(Lines.levelUp, "mon" to m.species, "lv" to m.level.toString()), 30_000)
                    m.fainted && !before.fainted -> Speaker.say("fainted", Speaker.pick(Lines.fainted, "mon" to m.species), 30_000)
                }
            }
        }
        party.clear(); party.putAll(now)

        // --- around us ---
        if (!quiet) {
            val rare = level.getEntitiesOfClass(PokemonEntity::class.java, player.boundingBox.inflate(48.0)) {
                dev.dexbuddy.client.context.GameContext.isWild(it) && Radar.isRare(it) && it.uuid !in seenRare
            }.minByOrNull { it.distanceToSqr(player) }
            if (rare != null) {
                seenRare += rare.uuid
                val name = rare.pokemon.species.translatedName.string
                if (rare.pokemon.shiny) { Speaker.say("shiny", Speaker.pick(Lines.shiny, "mon" to name), 15_000, urgent = true); Memory.note("Spotted a shiny $name") }
                else if (rare.pokemon.species.labels.any { it == CobblemonPokemonLabels.LEGENDARY || it == CobblemonPokemonLabels.MYTHICAL })
                    { Speaker.say("legendary", Speaker.pick(Lines.legendary, "mon" to name), 30_000, urgent = true); Memory.note("Met the legendary $name") }
            }
            val creeper = level.getEntitiesOfClass(Creeper::class.java, player.boundingBox.inflate(5.0)).firstOrNull()
            if (creeper != null && (creeper.getSwelling(0f) > 0f || creeper.distanceTo(player) < 3.5f))
                Speaker.say("creeper", Speaker.pick(Lines.creeper), 30_000, urgent = true)
        }

        // --- the sky ---
        val raining = level.isRaining
        val thundering = level.isThundering
        val night = (level.dayTime % 24000) in 13000..23000
        if (!quiet) {
            if (thundering && !wasThundering) Speaker.say("weather", Speaker.pick(Lines.thunder), 300_000)
            else if (raining && !wasRaining) Speaker.say("weather", Speaker.pick(Lines.rain), 300_000)
            if (night && !wasNight) Speaker.say("night", Speaker.pick(Lines.night), 600_000)
        }
        wasRaining = raining; wasThundering = thundering; wasNight = night
    }
}
