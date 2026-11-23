package dev.dexbuddy.client.antics

import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.context.GameContext
import dev.dexbuddy.client.tts.Sfx
import dev.dexbuddy.client.voice.PushToTalk
import net.minecraft.client.Minecraft

/**
 * Rare-Cobblemon radar: when a wild shiny or legendary is within range, the Dex beeps, faster the
 * closer you get, and now and then says which way to go. Client thread.
 */
object Radar {
    private const val RANGE = 64.0
    private val RARE = setOf(CobblemonPokemonLabels.LEGENDARY, CobblemonPokemonLabels.MYTHICAL, CobblemonPokemonLabels.ULTRA_BEAST)

    /** The rare Cobblemon being tracked, or null. */
    @Volatile var target: PokemonEntity? = null
        private set

    private var nextBeep = 0L
    private var tick = 0

    fun isRare(e: PokemonEntity): Boolean =
        e.pokemon.shiny || e.pokemon.species.labels.any { it in RARE }

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { target = null; return }
        if (!BuddyConfig.settings.personality.radar || !PushToTalk.hasDevice(player)) { target = null; return }
        if (++tick % 10 == 0) {
            target = mc.level?.getEntitiesOfClass(PokemonEntity::class.java, player.boundingBox.inflate(RANGE)) {
                GameContext.isWild(it) && isRare(it) && !it.isInvisible
            }?.minByOrNull { it.distanceToSqr(player) }
        }
        val t = target?.takeIf { it.isAlive } ?: run { target = null; return }
        val dist = t.distanceTo(player)
        if (dist < 4) return // found it: no more beeping
        val now = System.currentTimeMillis()
        if (now >= nextBeep) {
            // 4 blocks away: 4 beeps a second. 64 blocks away: one every two seconds.
            val f = ((dist - 4) / (RANGE - 4)).coerceIn(0.0, 1.0)
            nextBeep = now + (250 + f * 1750).toLong()
            Sfx.blip(1.6 - f * 0.6)
            Speaker.say("radar", Speaker.pick(Lines.radarHint, "where" to GameContext.where(t)), 25_000)
        }
    }
}
