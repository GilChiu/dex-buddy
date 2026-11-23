package dev.dexbuddy.games

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.entity.BuddyLink
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.net.MusicActionPayload
import dev.dexbuddy.net.MusicStatePayload
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Who's playing music on their Dex, and what. The server never touches the audio: it keeps each
 * Trainer's "now playing" (song id, where in the song, paused or not) and tells players nearby, whose own
 * game streams the song and fades it with distance. Server thread.
 */
object MusicServer {
    /** Players within this many blocks are told about the music (they hear it out to ~24 blocks). */
    private const val TELL_RANGE = 48.0

    private class Session(
        val owner: UUID, var id: String, var title: String, var author: String, var durationMs: Long,
        var posMs: Long, var at: Long, var playing: Boolean, var share: Boolean,
    ) {
        /** Where in the song it is right now. */
        fun position(now: Long) = if (playing) posMs + (now - at) else posMs
    }

    private val sessions = HashMap<UUID, Session>()
    /** Who has been told about which session (so newcomers in range get it once, and leavers get a stop). */
    private val told = HashMap<UUID, MutableSet<UUID>>()

    private fun enabled() = dev.dexbuddy.proxy.ProxyServer.cfg.musicEnabled

    fun register() {
        PayloadTypeRegistry.playC2S().register(MusicActionPayload.TYPE, MusicActionPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(MusicStatePayload.TYPE, MusicStatePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(MusicActionPayload.TYPE) { p, ctx ->
            val player = ctx.player()
            player.server.execute { handle(player, p.json) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { h, server -> server.execute { end(server, h.player.uuid) } }
        ServerTickEvents.END_SERVER_TICK.register { server -> if (server.tickCount % 40 == 0) sync(server) }
    }

    private fun handle(player: ServerPlayer, json: String) {
        if (!BuddyLink.owns(player)) return
        if (!enabled()) {
            // Tell the player's game, so the phone can say so instead of silently doing nothing.
            if (dev.dexbuddy.RateLimit.allow(player.uuid, "music_off", 1, 3_000))
                ServerPlayNetworking.send(player, MusicStatePayload(JsonObject().apply { addProperty("owner", player.uuid.toString()); addProperty("disabled", true) }.toString()))
            return
        }
        // Every change is told to everyone nearby: a few per second at most.
        if (!dev.dexbuddy.RateLimit.allow(player.uuid, "music", 6, 2_000)) return
        val o = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return }
        val now = System.currentTimeMillis()
        when (o.get("op")?.asString) {
            "now" -> {
                val id = o.get("id")?.asString ?: return
                if (!dev.dexbuddy.net.MusicIds.allowed(id)) return
                sessions[player.uuid] = Session(player.uuid, id, dev.dexbuddy.net.MusicIds.clean(o.get("title")?.asString ?: "", 200), dev.dexbuddy.net.MusicIds.clean(o.get("author")?.asString ?: "", 100),
                    o.get("dur")?.asLong ?: 0L, o.get("pos")?.asLong ?: 0L, now, o.get("playing")?.asBoolean ?: true, o.get("share")?.asBoolean ?: true)
                told[player.uuid] = HashSet()
                DexBuddyMod.LOG.info("[dexbuddy] {} is playing music on their Dex", player.gameProfile.name)
            }
            "pause", "resume" -> sessions[player.uuid]?.let { s -> s.posMs = o.get("pos")?.asLong ?: s.position(now); s.at = now; s.playing = o.get("op").asString == "resume"; told[player.uuid] = HashSet() }
            "share" -> sessions[player.uuid]?.let { s -> s.share = o.get("share")?.asBoolean ?: true; told[player.uuid] = HashSet() }
            "stop" -> { end(player.server, player.uuid); return }
        }
        sync(player.server)
    }

    /** The music stopped (or its Trainer left): everyone who was told hears it end. */
    private fun end(server: MinecraftServer, owner: UUID) {
        sessions.remove(owner) ?: return
        val stop = MusicStatePayload(JsonObject().apply { addProperty("owner", owner.toString()); addProperty("stop", true) }.toString())
        told.remove(owner)?.forEach { id -> server.playerList.getPlayer(id)?.let { ServerPlayNetworking.send(it, stop) } }
        server.playerList.getPlayer(owner)?.let { ServerPlayNetworking.send(it, stop) }
    }

    /** Tells players in range about music they haven't heard about yet, and tells leavers it ended for them. */
    private fun sync(server: MinecraftServer) {
        if (sessions.isEmpty()) return
        val now = System.currentTimeMillis()
        for (s in sessions.values.toList()) {
            val owner = server.playerList.getPlayer(s.owner) ?: continue
            // Where the sound comes from: the floating Dex if it's out, otherwise the Trainer carrying it.
            val buddy = DexBuddyEntity.active[s.owner]?.takeIf { it.isAlive && it.level() == owner.level() }
            val source = buddy ?: owner
            val set = told.getOrPut(s.owner) { HashSet() }
            val state = JsonObject().apply {
                addProperty("owner", s.owner.toString()); addProperty("name", owner.gameProfile.name)
                addProperty("entity", source.id); addProperty("fromDex", buddy != null)
                addProperty("id", s.id); addProperty("title", s.title); addProperty("author", s.author)
                addProperty("dur", s.durationMs); addProperty("pos", s.position(now)); addProperty("playing", s.playing)
            }.toString()
            for (p in owner.serverLevel().players()) {
                val inRange = p.uuid == s.owner || (s.share && p.distanceToSqr(source) <= TELL_RANGE * TELL_RANGE)
                if (inRange && p.uuid !in set) { ServerPlayNetworking.send(p, MusicStatePayload(state)); set += p.uuid }
                else if (!inRange && p.uuid in set) {
                    ServerPlayNetworking.send(p, MusicStatePayload(JsonObject().apply { addProperty("owner", s.owner.toString()); addProperty("stop", true) }.toString()))
                    set -= p.uuid
                }
            }
            // The sound source changed (the Dex came out, or went back in): tell everyone again.
            if (lastSource[s.owner] != source.id) { lastSource[s.owner] = source.id; set.clear() }
        }
    }

    private val lastSource = HashMap<UUID, Int>()
}
