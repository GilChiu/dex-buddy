package dev.dexbuddy.photo

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.net.PhotoChunkPayload
import dev.dexbuddy.net.PhotoSavedPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Every Trainer's photo gallery, kept on the server (in the world folder) so friends can see posts.
 * world/dexbuddy/photos/<player uuid>/<photo id>.jpg plus an index.json of when/where/how each was taken.
 * Server thread.
 */
object PhotoStore {
    const val MAX_PHOTOS = 200
    private const val MAX_BYTES = 600_000
    private const val MAX_CHUNKS = 20
    /** Video clips: a bundle of small JPEG frames (see ClipDataPayload). */
    private const val MAX_CLIP_BYTES = 9_000_000
    private const val MAX_CLIP_CHUNKS = 300

    /** Video clips kept per Trainer (each can be a few MB). */
    private const val MAX_CLIPS = 30
    /** Largest picture side accepted (the camera makes 640x360 photos and 480x270 clip frames). */
    private const val MAX_SIDE = 2048

    private fun clipMaxSeconds() = dev.dexbuddy.proxy.ProxyServer.cfg.clipMaxSeconds.coerceIn(0, 60)

    /**
     * Reads only a JPEG's header: true if it's a real JPEG of a sane size. Stops "image bombs" (a small file
     * that unpacks into a giant picture) from reaching other players' games.
     */
    fun saneJpeg(b: ByteArray): Boolean {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return false
        return try {
            javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(b)).use { iis ->
                val r = javax.imageio.ImageIO.getImageReadersByFormatName("jpeg").next()
                try { r.input = iis; val w = r.getWidth(0); val h = r.getHeight(0); w in 1..MAX_SIDE && h in 1..MAX_SIDE } finally { r.dispose() }
            }
        } catch (_: Exception) { false }
    }

    /** The frames of a clip file, or null if it isn't one: [fps, frames...]. */
    private fun readClip(b: ByteArray): Pair<Int, List<ByteArray>>? {
        if (b.size < 12 || String(b, 0, 4) != "DXC1") return null
        val buf = java.nio.ByteBuffer.wrap(b)
        buf.position(4)
        val fps = buf.int; val count = buf.int
        if (fps !in 1..30 || count !in 1..2000) return null
        val frames = ArrayList<ByteArray>(count)
        repeat(count) {
            if (buf.remaining() < 4) return null
            val len = buf.int
            if (len !in 4..400_000 || buf.remaining() < len) return null
            val f = ByteArray(len); buf.get(f)
            if (!saneJpeg(f)) return null
            frames += f
        }
        return fps to frames
    }

    /** Sends a clip in pieces (or "not a clip"). */
    private fun sendClip(player: ServerPlayer, id: String, file: Path?) {
        val bytes = try { file?.takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) } } catch (_: Exception) { null }
        if (bytes == null) { ServerPlayNetworking.send(player, dev.dexbuddy.net.ClipDataPayload(id, 0, 0, ByteArray(0))); return }
        val c = dev.dexbuddy.net.ClipDataPayload.CHUNK
        val total = (bytes.size + c - 1) / c
        for (i in 0 until total) ServerPlayNetworking.send(player, dev.dexbuddy.net.ClipDataPayload(id, i, total, bytes.copyOfRange(i * c, minOf(bytes.size, (i + 1) * c))))
    }
    private val ID = Regex("^[a-f0-9-]{36}$")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private class Partial(val owner: UUID, val total: Int, val mode: String) {
        val chunks = arrayOfNulls<ByteArray>(total)
        val started = System.currentTimeMillis()
    }

    private val partials = HashMap<String, Partial>()

    fun dir(server: MinecraftServer, player: UUID): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("dexbuddy").resolve("photos").resolve(player.toString())

    fun count(server: MinecraftServer, player: UUID): Int = index(server, player).size()

    fun index(server: MinecraftServer, player: UUID): JsonArray {
        val f = dir(server, player).resolve("index.json")
        return try { if (Files.exists(f)) JsonParser.parseString(Files.readString(f)).asJsonArray else JsonArray() } catch (_: Exception) { JsonArray() }
    }

    /** Gallery requests from the phone: list your photos, fetch one, or delete one. */
    fun handle(player: ServerPlayer, action: String) {
        val server = player.server
        when {
            action == "list" -> sendList(player)
            action.startsWith("get:") -> {
                val id = action.removePrefix("get:")
                if (!ID.matches(id)) return
                // Your own photo, or a friend's post / anyone's profile picture.
                val own = dir(server, player.uuid).resolve("$id.jpg")
                val f = if (Files.exists(own)) own else SocialStore.ownerIfVisible(player.uuid, id)?.let { dir(server, it).resolve("$id.jpg") } ?: return
                val bytes = try { if (Files.exists(f)) Files.readAllBytes(f) else null } catch (_: Exception) { null } ?: return
                ServerPlayNetworking.send(player, dev.dexbuddy.net.PhotoDataPayload(id, bytes))
            }
            // The video behind a photo (its first frame), if it's a clip. Same rules as seeing the photo.
            action.startsWith("clip:") -> {
                val id = action.removePrefix("clip:")
                if (!ID.matches(id)) return
                // Clips are big: a few per few seconds is plenty for scrolling a feed.
                if (!dev.dexbuddy.RateLimit.allow(player.uuid, "clip", 4, 3_000)) { sendClip(player, id, null); return }
                val own = dir(server, player.uuid).resolve("$id.jpg")
                val owner = if (Files.exists(own)) player.uuid else SocialStore.ownerIfVisible(player.uuid, id)
                sendClip(player, id, owner?.let { dir(server, it).resolve("$id.clip") })
            }
            // A photo a friend sent you (or posted): a copy goes into your own gallery, yours to post or use.
            action.startsWith("save:") -> {
                val id = action.removePrefix("save:")
                if (!ID.matches(id)) return
                val owner = SocialStore.ownerIfVisible(player.uuid, id)?.takeIf { it != player.uuid } ?: return
                val src = dir(server, owner).resolve("$id.jpg")
                fun reply(msg: String) = ServerPlayNetworking.send(player, dev.dexbuddy.net.SocialNoticePayload(msg))
                val list = index(server, player.uuid)
                if (list.size() >= MAX_PHOTOS) { reply("Your gallery is full. Delete some photos first!"); return }
                val srcClip = dir(server, owner).resolve("$id.clip")
                val isClip = Files.exists(srcClip)
                if (isClip && clipCount(list) >= MAX_CLIPS) { reply("You have $MAX_CLIPS videos already. Delete some first!"); return }
                try {
                    val copy = UUID.randomUUID().toString()
                    val d = dir(server, player.uuid)
                    Files.createDirectories(d)
                    Files.copy(src, d.resolve("$copy.jpg"))
                    if (isClip) Files.copy(srcClip, d.resolve("$copy.clip"))
                    val from = index(server, owner).firstOrNull { it.asJsonObject.get("id")?.asString == id }?.asJsonObject
                    // No place or coordinates: where a friend took a photo (maybe their base) stays theirs.
                    list.add(JsonObject().apply {
                        addProperty("id", copy)
                        addProperty("time", System.currentTimeMillis())
                        addProperty("mode", if (isClip) "clip" else "saved")
                        from?.get("seconds")?.let { add("seconds", it) }
                        SocialStore.nameOf(owner)?.let { addProperty("from", it) }
                    })
                    Files.writeString(d.resolve("index.json"), gson.toJson(list))
                    sendList(player)
                    reply("Saved to your gallery!")
                } catch (e: Exception) {
                    DexBuddyMod.LOG.warn("[dexbuddy] couldn't save a friend's photo: {}", e.toString())
                    reply("Couldn't save that photo.")
                }
            }
            action.startsWith("delete:") -> {
                val id = action.removePrefix("delete:")
                if (!ID.matches(id)) return
                val d = dir(server, player.uuid)
                val list = index(server, player.uuid)
                val kept = JsonArray().also { out -> list.forEach { e -> if (e.asJsonObject.get("id")?.asString != id) out.add(e) } }
                try {
                    Files.deleteIfExists(d.resolve("$id.jpg"))
                    Files.deleteIfExists(d.resolve("$id.clip"))
                    if (Files.exists(d)) Files.writeString(d.resolve("index.json"), gson.toJson(kept))
                } catch (e: Exception) { DexBuddyMod.LOG.warn("[dexbuddy] couldn't delete photo: {}", e.toString()) }
                SocialStore.forgetPhoto(player.uuid, id)
                sendList(player)
            }
        }
    }

    private fun clipCount(list: JsonArray) = list.count { it.asJsonObject.get("mode")?.asString == "clip" }

    private fun sendList(player: ServerPlayer) =
        ServerPlayNetworking.send(player, dev.dexbuddy.net.GalleryListPayload(index(player.server, player.uuid).toString()))

    fun receive(player: ServerPlayer, p: PhotoChunkPayload) {
        val now = System.currentTimeMillis()
        partials.values.removeIf { now - it.started > 60_000 } // abandoned uploads
        val isClip = p.mode == "clip"
        if (!ID.matches(p.id) || p.total !in 1..(if (isClip) MAX_CLIP_CHUNKS else MAX_CHUNKS) || p.index !in 0 until p.total) return
        // At most two photos uploading at once per Trainer, so nobody can pile up half-sent photos in memory.
        if (p.id !in partials && partials.values.count { it.owner == player.uuid } >= 2) return
        val part = partials.getOrPut(p.id) { Partial(player.uuid, p.total, p.mode.take(16)) }
        if (part.owner != player.uuid || part.total != p.total) return
        part.chunks[p.index] = p.data
        if (part.chunks.any { it == null }) return
        partials.remove(p.id)

        val bytes = java.io.ByteArrayOutputStream(part.chunks.sumOf { it!!.size }).also { o -> part.chunks.forEach { o.write(it!!) } }.toByteArray()
        fun fail(why: String) = ServerPlayNetworking.send(player, PhotoSavedPayload(p.id, count(player.server, player.uuid), why))
        // A clip: checked frame by frame; its first frame becomes the photo everyone sees in lists.
        var clipSeconds = 0
        val photo: ByteArray = if (isClip) {
            val max = clipMaxSeconds()
            if (max <= 0) return fail("Video clips are turned off on this server.")
            if (bytes.size > MAX_CLIP_BYTES) return fail("That clip is too big.")
            val (fps, frames) = readClip(bytes) ?: return fail("That clip didn't record right.")
            if (frames.size > fps * max + fps) return fail("Clips can be up to $max seconds here.")
            clipSeconds = (frames.size + fps - 1) / fps
            frames[0]
        } else bytes
        // Only real JPEGs of a sane size.
        if (photo.size > MAX_BYTES || !saneJpeg(photo)) return fail("That photo didn't develop right.")
        val list = index(player.server, player.uuid)
        if (list.size() >= MAX_PHOTOS) return fail("Your gallery is full ($MAX_PHOTOS photos). Delete some first!")
        if (isClip && clipCount(list) >= MAX_CLIPS) return fail("You have $MAX_CLIPS videos already. Delete some first!")
        try {
            val d = dir(player.server, player.uuid)
            Files.createDirectories(d)
            Files.write(d.resolve("${p.id}.jpg"), photo)
            if (isClip) Files.write(d.resolve("${p.id}.clip"), bytes)
            list.add(JsonObject().apply {
                addProperty("id", p.id)
                addProperty("time", now)
                addProperty("mode", part.mode)
                if (isClip) addProperty("seconds", clipSeconds)
                addProperty("dim", player.level().dimension().location().toString())
                addProperty("x", player.blockX); addProperty("y", player.blockY); addProperty("z", player.blockZ)
            })
            Files.writeString(d.resolve("index.json"), gson.toJson(list))
            DexBuddyMod.LOG.info("[dexbuddy] saved photo {} for {} ({} KB, {} in gallery)", p.id, player.gameProfile.name, bytes.size / 1024, list.size())
            ServerPlayNetworking.send(player, PhotoSavedPayload(p.id, list.size(), ""))
        } catch (e: Exception) {
            DexBuddyMod.LOG.warn("[dexbuddy] couldn't save photo: {}", e.toString())
            fail("The server couldn't save that photo.")
        }
    }
}
