package dev.dexbuddy.games

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import dev.dexbuddy.entity.BuddyLink
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.entity.Look
import dev.dexbuddy.photo.PhotoStore
import dev.dexbuddy.photo.SocialStore
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.JukeboxBlockEntity
import java.time.LocalDate

/**
 * How the twenty collectible costumes are earned. Counters live on the Trainer (BuddyLink stats) and
 * grow while the companion is out; [check] runs every ten seconds from the companion and unlocks
 * whatever has been reached. Server thread.
 */
object CostumeUnlocks {
    private var server: net.minecraft.server.MinecraftServer? = null

    fun register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register { server = it }
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register { server = null }
        // Catches by type (Goth Bat, Witchy, Frost Fairy).
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL) { e ->
            val p = e.player
            if (!BuddyLink.owns(p)) return@subscribe
            for (t in e.pokemon.types) when (t.name.lowercase()) {
                "ghost" -> BuddyLink.bump(p, "caught_ghost")
                "psychic" -> BuddyLink.bump(p, "caught_psychic")
                "ice" -> BuddyLink.bump(p, "caught_ice")
            }
        }
        // Battles won with the companion out (Punk Rocker).
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { e ->
            val server = server ?: return@subscribe
            for (actor in e.winners) for (id in actor.getPlayerUUIDs()) {
                val p = server.playerList.getPlayer(id) ?: continue
                if (BuddyLink.isOut(p)) BuddyLink.bump(p, "wins")
            }
        }
        // A full night's sleep with the companion out (Cloud Dream).
        EntitySleepEvents.STOP_SLEEPING.register { entity, _ ->
            val p = entity as? ServerPlayer ?: return@register
            if (BuddyLink.isOut(p) && p.level().dayTime % 24000 < 1000) BuddyLink.bump(p, "nights")
        }
    }

    fun isNight(level: Level) = (level.dayTime % 24000) in 13000..23000

    /** Every ten seconds while the companion is out. */
    fun check(owner: ServerPlayer, b: DexBuddyEntity) {
        val u = { l: Look -> b.unlockCostume(l) }
        fun stat(k: String) = BuddyLink.stat(owner, k)
        if (b.friendship >= 20) u(Look.BUBBLEGUM)
        if (!b.hasCostume(Look.GLAM_STAR) && PhotoStore.count(owner.server, owner.uuid) >= 25) u(Look.GLAM_STAR)
        if (stat("berries") >= 30) u(Look.STRAWBERRY_MILK)
        if (stat("caught_ghost") >= 20) u(Look.GOTH_BAT)
        if (owner.level().dimension() == Level.END) u(Look.EMO_SCENE)
        if (stat("wins") >= 50) u(Look.PUNK_ROCKER)
        if (SocialStore.likesReceived(owner.uuid) >= 50) u(Look.ROCKSTAR)
        if (owner.level().dimension() == Level.NETHER) u(Look.METALHEAD)
        if (stat("pets") >= 100) u(Look.KAWAII_CAT)
        if (stat("found") >= 10) u(Look.BUNNY_HOP)
        if (stat("nights") >= 10) u(Look.CLOUD_DREAM)
        if (Charge.isCharged(owner.uuid) && BuddyLink.bump(owner, "charged_s", 10) >= 5 * 3600) u(Look.CYBER_NEON)
        if (SocialStore.friendCount(owner.uuid) >= 5) u(Look.Y2K_FLIP)
        if (stat("caught_psychic") >= 15) u(Look.WITCHY)
        val month = LocalDate.now().monthValue
        if (month == 10) u(Look.PUMPKIN_SPICE)
        if (stat("caught_ice") >= 20) u(Look.FROST_FAIRY)
        if (month in 3..5) u(Look.SAKURA)
        if (stat("found_night") >= 5) u(Look.PIXEL_GHOST)
        if (!b.hasCostume(Look.LOFI_CHILL) && musicNear(b.level() as ServerLevel, b.blockPosition())) u(Look.LOFI_CHILL)
    }

    /** A jukebox playing a disc within 12 blocks of the companion. */
    private fun musicNear(level: ServerLevel, at: BlockPos): Boolean {
        for (cx in ((at.x - 12) shr 4)..((at.x + 12) shr 4)) for (cz in ((at.z - 12) shr 4)..((at.z + 12) shr 4)) {
            val chunk = level.getChunkSource().getChunkNow(cx, cz) ?: continue
            for ((pos, be) in chunk.blockEntities) {
                if (be is JukeboxBlockEntity && pos.distSqr(at) <= 144.0 && be.songPlayer.isPlaying) return true
            }
        }
        return false
    }
}
