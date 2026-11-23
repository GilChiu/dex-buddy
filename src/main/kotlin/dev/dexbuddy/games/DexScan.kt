package dev.dexbuddy.games

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokedex.scanning.PokemonScannedEvent
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.pokedex.ServerConfirmedRegisterPacket
import com.cobblemon.mod.common.util.safePokedex
import dev.dexbuddy.DexBuddyMod
import net.minecraft.server.level.ServerPlayer

/**
 * A finished Dex Buddy scan counts as a Cobblemon Pokédex scan: the species is registered in that
 * Trainer's Pokédex exactly as Cobblemon's own scanner does it (seen, or caught if it's their own), so
 * the two Dexes always agree on what you've found. Server thread.
 */
object DexScan {
    private const val MAX_DISTANCE = 40.0

    fun scan(player: ServerPlayer, entityId: Int) {
        val e = player.level().getEntity(entityId) as? PokemonEntity ?: return
        // Like Cobblemon's own scanner: close enough and actually in view (no scanning through walls).
        if (e.distanceTo(player) > MAX_DISTANCE || !e.isAlive || !player.hasLineOfSight(e)) return
        try {
            val dex = player.safePokedex() ?: return
            val data: com.cobblemon.mod.common.pokedex.scanner.PokedexEntityData = e.resolvePokemonScan() ?: return
            val learned = dex.getNewInformation(data)
            if (e.owner?.uuid == player.uuid) dex.catch(e.pokemon) else dex.encounter(data)
            CobblemonEvents.POKEMON_SCANNED.emit(PokemonScannedEvent(player, data, e))
            ServerConfirmedRegisterPacket(e.pokemon.species.resourceIdentifier, learned).sendToPlayer(player)
        } catch (t: Throwable) {
            DexBuddyMod.LOG.warn("[dexbuddy] couldn't register a scan in the Pokédex: {}", t.toString())
        }
    }
}
