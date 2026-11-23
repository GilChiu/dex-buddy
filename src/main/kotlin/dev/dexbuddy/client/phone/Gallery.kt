package dev.dexbuddy.client.phone

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.net.GalleryListPayload
import dev.dexbuddy.net.GalleryRequestPayload
import dev.dexbuddy.net.PhotoDataPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Your photo gallery as the phone sees it: the list comes from the server, and each photo is fetched
 * the first time it's on screen and kept as a texture (the most recent few dozen). Client thread.
 */
object Gallery {
    class Entry(val id: String, val time: Long, val mode: String, val dim: String, val x: Int, val y: Int, val z: Int)

    var entries: List<Entry> = emptyList()
        private set
    var loaded = false
        private set

    private const val KEEP = 48
    private val textures = LinkedHashMap<String, ResourceLocation>(16, 0.75f, true)
    private val requested = HashSet<String>()

    val available: Boolean get() = try { ClientPlayNetworking.canSend(GalleryRequestPayload.TYPE) } catch (_: Exception) { false }

    // ---- video clips ----------------------------------------------------------------------------------

    /** A clip being played: its JPEG frames, decoded one at a time into a single texture. */
    private class Clip(val fps: Int, val frames: List<ByteArray>) {
        var tex: DynamicTexture? = null
        var loc: ResourceLocation? = null
        var shown = -1
        var started = System.currentTimeMillis()
    }
    private val clips = LinkedHashMap<String, Clip>(8, 0.75f, true)
    /** Photos known not to be clips, and clip downloads in progress. */
    private val notClips = HashSet<String>()
    private val clipParts = HashMap<String, Array<ByteArray?>>()
    private val clipAsked = HashSet<String>()

    /** Whether [id] is a clip, as far as this client knows yet (null: unknown, asks the server). */
    fun isClip(id: String): Boolean? {
        if (clips.containsKey(id) || entries.any { it.id == id && it.mode == "clip" }) return true
        if (id in notClips) return false
        askClip(id)
        return null
    }

    /** Asks the server for the video behind a photo (once; it answers "not a clip" for photos). */
    private fun askClip(id: String) {
        if (available && clipAsked.add(id)) ClientPlayNetworking.send(GalleryRequestPayload("clip:$id"))
    }

    /** The frame of clip [id] to show now (looping), or null when it isn't a clip or hasn't arrived yet. */
    fun clipTexture(id: String): ResourceLocation? {
        val c = clips[id] ?: run { if (id !in notClips) askClip(id); return null }
        val i = (((System.currentTimeMillis() - c.started) * c.fps / 1000) % c.frames.size).toInt()
        if (i != c.shown) {
            try {
                val img = safeRead(c.frames[i]) ?: return c.loc
                val tex = c.tex ?: DynamicTexture(img.width, img.height, false).also { t ->
                    val loc = DexBuddyMod.id("clip/${id}")
                    Minecraft.getInstance().textureManager.register(loc, t)
                    c.tex = t; c.loc = loc
                }
                val ni = tex.pixels ?: return c.loc
                for (y in 0 until minOf(img.height, ni.height)) for (x in 0 until minOf(img.width, ni.width)) {
                    val rgb = img.getRGB(x, y)
                    ni.setPixelRGBA(x, y, (0xFF shl 24) or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF))
                }
                tex.upload()
                c.shown = i
            } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] clip frame failed: {}", e.toString()) }
        }
        return c.loc
    }

    private fun onClip(p: dev.dexbuddy.net.ClipDataPayload) {
        // Whatever the server sends, only sane clips are read (sizes checked before anything is allocated).
        if (!ID.matches(p.id)) return
        if (p.total == 0) { notClips += p.id; return }
        if (p.total !in 1..60) return
        if (clipParts.size > 8 && p.id !in clipParts) clipParts.clear()
        val parts = clipParts.getOrPut(p.id) { arrayOfNulls(p.total) }
        if (parts.size != p.total || p.index !in parts.indices) return
        parts[p.index] = p.data
        if (parts.any { it == null }) return
        clipParts.remove(p.id)
        val bytes = java.io.ByteArrayOutputStream().also { o -> parts.forEach { o.write(it!!) } }.toByteArray()
        try {
            val buf = java.nio.ByteBuffer.wrap(bytes)
            if (bytes.size < 12 || String(bytes, 0, 4) != "DXC1") return
            buf.position(4)
            val fps = buf.int; val count = buf.int
            if (count !in 1..2000) return
            val frames = ArrayList<ByteArray>(count)
            repeat(count) {
                if (buf.remaining() < 4) return
                val n = buf.int
                if (n !in 4..400_000 || buf.remaining() < n) return
                frames += ByteArray(n).also { buf.get(it) }
            }
            clips[p.id] = Clip(fps.coerceIn(1, 30), frames)
            // Keep a few clips at most.
            while (clips.size > 4) {
                val oldest = clips.keys.first()
                clips.remove(oldest)?.loc?.let { Minecraft.getInstance().textureManager.release(it) }
            }
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] bad clip: {}", e.toString()) }
    }

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(dev.dexbuddy.net.ClipDataPayload.TYPE) { p, _ -> onClip(p) }
        ClientPlayNetworking.registerGlobalReceiver(GalleryListPayload.TYPE) { p, _ -> onList(p.json) }
        ClientPlayNetworking.registerGlobalReceiver(PhotoDataPayload.TYPE) { p, _ -> onPhoto(p.id, p.data) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { clear() } }
    }

    fun refresh() { if (available) ClientPlayNetworking.send(GalleryRequestPayload("list")) }

    /** Copies a friend's photo (sent to you, or posted) into your own gallery. */
    fun save(id: String) { if (available) { ClientPlayNetworking.send(GalleryRequestPayload("save:$id")); dev.dexbuddy.client.antics.Tricks.used("save_photo") } }

    fun delete(id: String) {
        if (!available) return
        entries = entries.filter { it.id != id }
        textures.remove(id)?.let { Minecraft.getInstance().textureManager.release(it) }
        ClientPlayNetworking.send(GalleryRequestPayload("delete:$id"))
    }

    /** The photo's texture if it's here; otherwise asks for it and returns null for now. */
    fun texture(id: String): ResourceLocation? {
        textures[id]?.let { return it }
        if (available && requested.add(id)) ClientPlayNetworking.send(GalleryRequestPayload("get:$id"))
        return null
    }

    private fun onList(json: String) {
        entries = try {
            JsonParser.parseString(json).asJsonArray.map { e ->
                val o = e.asJsonObject
                Entry(o.get("id").asString, o.get("time")?.asLong ?: 0L, o.get("mode")?.asString ?: "", o.get("dim")?.asString ?: "",
                    o.get("x")?.asInt ?: 0, o.get("y")?.asInt ?: 0, o.get("z")?.asInt ?: 0)
            }.sortedByDescending { it.time }
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] bad gallery list: {}", e.toString()); emptyList() }
        loaded = true
    }

    private val ID = Regex("^[a-f0-9-]{36}$")

    /**
     * Decodes a photo only if its header says it's a JPEG of a sane size: a tiny file that unpacks into a
     * giant picture (an "image bomb") from another player could otherwise run the game out of memory.
     */
    private fun safeRead(data: ByteArray): java.awt.image.BufferedImage? {
        if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return null
        return ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { iis ->
            val r = ImageIO.getImageReadersByFormatName("jpeg").next()
            try {
                r.input = iis
                if (r.getWidth(0) !in 1..2048 || r.getHeight(0) !in 1..2048) null else r.read(0)
            } finally { r.dispose() }
        }
    }

    private fun onPhoto(id: String, data: ByteArray) {
        if (!ID.matches(id)) return
        Thread({
            try {
                val img = safeRead(data) ?: return@Thread
                val ni = NativeImage(img.width, img.height, false)
                for (y in 0 until img.height) for (x in 0 until img.width) {
                    val rgb = img.getRGB(x, y)
                    ni.setPixelRGBA(x, y, (0xFF shl 24) or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF))
                }
                Minecraft.getInstance().execute {
                    val loc = DexBuddyMod.id("gallery/$id")
                    Minecraft.getInstance().textureManager.register(loc, DynamicTexture(ni))
                    textures[id] = loc
                    requested.remove(id)
                    // Forget the oldest ones beyond what's worth keeping on the GPU.
                    while (textures.size > KEEP) {
                        val oldest = textures.keys.first()
                        textures.remove(oldest)?.let { Minecraft.getInstance().textureManager.release(it) }
                    }
                }
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] couldn't open photo {}: {}", id, e.toString())
            }
        }, "dexbuddy-gallery").apply { isDaemon = true }.start()
    }

    private fun clear() {
        textures.values.forEach { Minecraft.getInstance().textureManager.release(it) }
        textures.clear(); requested.clear(); entries = emptyList(); loaded = false
        clips.values.forEach { c -> c.loc?.let { Minecraft.getInstance().textureManager.release(it) } }
        clips.clear(); notClips.clear(); clipParts.clear(); clipAsked.clear()
    }
}
