package dev.dexbuddy.proxy

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.entity.BuddyLink
import dev.dexbuddy.net.ProxyRequestPayload
import dev.dexbuddy.net.ProxyResponsePayload
import dev.dexbuddy.net.ServerFeaturesPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * "Server key mode": players without their own AI key can talk to their companion through the server,
 * which sends their requests on with the server owner's key. Off until the owner turns it on in
 * config/dexbuddy-server.json. Only the three routes the mod uses are allowed, with per-player limits.
 * The key never leaves the server.
 */
object ProxyServer {
    class Config {
        /** Lend your key to players who don't have one. */
        var enabled = false
        /** Your provider key (Groq's free key works). Never sent to players. */
        var apiKey = ""
        var baseUrl = "https://api.groq.com/openai/v1"
        /** Per player, per minute. */
        var chatPerMinute = 20
        var voiceClipsPerMinute = 12
        /** Longest reply (tokens) a player can ask for. */
        var maxTokens = 700
        /**
         * The chat model players use on your key, e.g. "openai/gpt-oss-120b". Set it if your key is paid, so
         * nobody can pick a pricier model. Blank = whatever the player's game asks for.
         */
        var model = ""
        /**
         * true: every costume is free to wear from the start. false: costumes are earned (friendship,
         * catches, battles, seasons...), found by the companion, or handed out as costume items.
         */
        var unlockAllCostumes = true
        /** Longest video clip a player can record with the Dex camera, in seconds (0 turns clips off). */
        var clipMaxSeconds = 15
        /**
         * Dex's music player (YouTube and SoundCloud). Off by default: songs are streamed from those services by
         * each player's own game, under their terms. Set true to let players use it on this server.
         */
        var musicEnabled = false
    }

    var cfg = Config(); private set
    val available: Boolean get() = cfg.enabled && cfg.apiKey.isNotBlank()

    private val ROUTES = setOf("GET /models", "POST /chat/completions", "POST /audio/transcriptions")
    private const val MAX_BODY = 2_500_000 // a minute of voice is ~2 MB

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_2).build()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "dexbuddy-proxy").apply { isDaemon = true } }

    private class Partial(val total: Int, val route: String, val contentType: String) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var size = 0
        val started = System.currentTimeMillis()
    }

    private val partials = ConcurrentHashMap<String, Partial>()
    private val recent = ConcurrentHashMap<String, MutableList<Long>>() // "uuid:kind" -> times
    private val running = ConcurrentHashMap<UUID, Int>()

    fun load() {
        val f = FabricLoader.getInstance().configDir.resolve("dexbuddy-server.json")
        cfg = try {
            val c = if (Files.exists(f)) gson.fromJson(Files.readString(f), Config::class.java) ?: Config() else Config()
            // Written back so settings added in an update show up in the file with their defaults.
            Files.createDirectories(f.parent); Files.writeString(f, gson.toJson(c))
            c
        } catch (e: Exception) {
            DexBuddyMod.LOG.warn("[dexbuddy] couldn't read dexbuddy-server.json: {}", e.javaClass.simpleName); Config()
        }
        DexBuddyMod.LOG.info("[dexbuddy] server key mode: {}, all costumes unlocked: {}", if (available) "on" else "off", cfg.unlockAllCostumes)
    }

    fun announce(p: ServerPlayer) {
        if (ServerPlayNetworking.canSend(p, ServerFeaturesPayload.TYPE)) ServerPlayNetworking.send(p, ServerFeaturesPayload(available))
    }

    // ---- requests -----------------------------------------------------------------------------------

    fun receive(p: ServerPlayer, r: ProxyRequestPayload) {
        val key = "${p.uuid}:${r.id}"
        val now = System.currentTimeMillis()
        partials.values.removeIf { now - it.started > 60_000 }
        if (r.route !in ROUTES || r.total !in 0..(MAX_BODY / ProxyRequestPayload.CHUNK + 1)) return fail(p, r.id, "not allowed")
        if (r.total == 0) return start(p, r.id, r.route, r.contentType, ByteArray(0))
        if (r.index !in 0 until r.total) return
        if (!partials.containsKey(key) && partials.keys.count { it.startsWith("${p.uuid}:") } >= 2) return fail(p, r.id, "too many at once")
        val part = partials.getOrPut(key) { Partial(r.total, r.route, r.contentType) }
        if (part.total != r.total || part.chunks[r.index] != null) return
        part.chunks[r.index] = r.data; part.size += r.data.size
        if (part.size > MAX_BODY) { partials.remove(key); return fail(p, r.id, "too big") }
        if (part.chunks.any { it == null }) return
        partials.remove(key)
        val body = java.io.ByteArrayOutputStream(part.size).also { o -> part.chunks.forEach { o.write(it!!) } }.toByteArray()
        start(p, r.id, part.route, part.contentType, body)
    }

    private fun allowedNow(p: ServerPlayer, kind: String, perMinute: Int): Boolean {
        val list = recent.getOrPut("${p.uuid}:$kind") { ArrayList() }
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeIf { now - it > 60_000 }
            if (list.size >= perMinute) return false
            list += now
        }
        return true
    }

    private fun start(p: ServerPlayer, id: Int, route: String, contentType: String, body0: ByteArray) {
        if (!available) return fail(p, id, "this server doesn't lend its key")
        if (!BuddyLink.owns(p)) return fail(p, id, "you need a Dex Buddy")
        val ok = when (route) {
            "POST /chat/completions" -> allowedNow(p, "chat", cfg.chatPerMinute)
            "POST /audio/transcriptions" -> allowedNow(p, "voice", cfg.voiceClipsPerMinute)
            else -> allowedNow(p, "models", 10)
        }
        if (!ok) return head(p, id, 429, "20") // the client rests and tries again, like a provider limit
        if ((running[p.uuid] ?: 0) >= 2) return head(p, id, 429, "3")
        // Keep replies to a sensible size whatever the client asks for.
        val body = if (route == "POST /chat/completions") try {
            val o = JsonParser.parseString(String(body0, Charsets.UTF_8)).asJsonObject
            val asked = o.get("max_tokens")?.asInt ?: cfg.maxTokens
            o.addProperty("max_tokens", minOf(asked, cfg.maxTokens))
            if (cfg.model.isNotBlank()) o.addProperty("model", cfg.model.trim())
            // Only one reply per request, whatever the client asked for, and the newer token limit capped too.
            o.remove("n")
            if (o.has("max_completion_tokens")) o.addProperty("max_completion_tokens", minOf(o.get("max_completion_tokens").asInt, cfg.maxTokens))
            o.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) { return fail(p, id, "bad request") } else body0
        running.merge(p.uuid, 1, Int::plus)
        pool.execute {
            try {
                val (method, path) = route.split(' ', limit = 2)
                val b = HttpRequest.newBuilder(URI.create(cfg.baseUrl.trimEnd('/') + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
                if (contentType.isNotBlank()) b.header("Content-Type", contentType)
                if (path == "/chat/completions") b.header("Accept", "text/event-stream")
                val req = if (method == "GET") b.GET().build() else b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                head(p, id, res.statusCode(), res.headers().firstValue("retry-after").orElse(""))
                res.body().use { input ->
                    val buf = ByteArray(16_384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (p.hasDisconnected()) return@execute
                        send(p, ProxyResponsePayload(id, ProxyResponsePayload.DATA, 0, "", buf.copyOf(n)))
                    }
                }
                send(p, ProxyResponsePayload(id, ProxyResponsePayload.END, 0, "", ByteArray(0)))
            } catch (e: Exception) {
                fail(p, id, "the AI service couldn't be reached")
                DexBuddyMod.LOG.warn("[dexbuddy] proxied request failed: {}", e.javaClass.simpleName)
            } finally {
                running.computeIfPresent(p.uuid) { _, n -> (n - 1).takeIf { it > 0 } }
            }
        }
    }

    private fun head(p: ServerPlayer, id: Int, status: Int, retryAfter: String) =
        send(p, ProxyResponsePayload(id, ProxyResponsePayload.HEAD, status, retryAfter.take(32), ByteArray(0)))

    private fun fail(p: ServerPlayer, id: Int, why: String) =
        send(p, ProxyResponsePayload(id, ProxyResponsePayload.ERROR, 0, why, ByteArray(0)))

    private fun send(p: ServerPlayer, payload: ProxyResponsePayload) {
        if (!p.hasDisconnected()) ServerPlayNetworking.send(p, payload)
    }

    fun forget(p: ServerPlayer) {
        partials.keys.removeIf { it.startsWith("${p.uuid}:") }
        recent.keys.removeIf { it.startsWith("${p.uuid}:") }
    }
}
