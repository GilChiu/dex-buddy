package dev.dexbuddy.client.llm

import dev.dexbuddy.net.ProxyRequestPayload
import dev.dexbuddy.net.ProxyResponsePayload
import dev.dexbuddy.net.ServerFeaturesPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the companion reaches the AI: straight to the provider with your own key, or, when you have no key
 * and the server lends one ("server key mode"), through the server. Either way the caller gets the
 * status and a stream of the reply. Worker threads only (except the packet handlers).
 */
object Provider {
    class Response(val status: Int, val retryAfter: String?, val body: InputStream)

    /** Set when the server says it lends its key. */
    @Volatile var serverKey = false
        private set

    /** Use the server's key: you have none of your own and this server offers one. */
    fun viaServer(ownKey: String, baseUrl: String): Boolean {
        val local = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
        return ownKey.isBlank() && !local && serverKey && canSend()
    }

    private fun canSend() = try { ClientPlayNetworking.canSend(ProxyRequestPayload.TYPE) } catch (_: Exception) { false }

    fun request(baseUrl: String, key: String, method: String, path: String, contentType: String?, body: ByteArray?, timeoutSeconds: Long): Response {
        if (viaServer(key, baseUrl)) return throughServer(method, path, contentType.orEmpty(), body ?: ByteArray(0), timeoutSeconds)
        val b = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(timeoutSeconds))
        if (key.isNotBlank()) b.header("Authorization", "Bearer ${key.trim()}")
        if (contentType != null) b.header("Content-Type", contentType)
        if (path == "/chat/completions") b.header("Accept", "text/event-stream")
        val req = if (method == "GET") b.GET().build() else b.POST(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0))).build()
        val res = LlmClient.http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        return Response(res.statusCode(), res.headers().firstValue("retry-after").orElse(null), res.body())
    }

    // ---- through the server -------------------------------------------------------------------------

    private class Call {
        val head = CompletableFuture<Pair<Int, String>>()
        val data = LinkedBlockingQueue<ByteArray>()
    }

    private val ids = AtomicInteger(1)
    private val calls = ConcurrentHashMap<Int, Call>()
    private val EOF = ByteArray(0)

    private fun throughServer(method: String, path: String, contentType: String, body: ByteArray, timeoutSeconds: Long): Response {
        val id = ids.getAndIncrement()
        val call = Call().also { calls[id] = it }
        val route = "$method $path"
        val total = if (body.isEmpty()) 0 else (body.size + ProxyRequestPayload.CHUNK - 1) / ProxyRequestPayload.CHUNK
        Minecraft.getInstance().execute {
            if (total == 0) ClientPlayNetworking.send(ProxyRequestPayload(id, 0, 0, route, contentType, ByteArray(0)))
            else for (i in 0 until total) {
                val part = body.copyOfRange(i * ProxyRequestPayload.CHUNK, minOf(body.size, (i + 1) * ProxyRequestPayload.CHUNK))
                ClientPlayNetworking.send(ProxyRequestPayload(id, i, total, route, contentType, part))
            }
        }
        val (status, retry) = try {
            call.head.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            calls.remove(id)
            throw IOException((e.cause ?: e).message ?: "the server didn't answer")
        }
        return Response(status, retry.ifBlank { null }, QueueStream(call, id, timeoutSeconds))
    }

    /** The reply's bytes as they arrive from the server. */
    private class QueueStream(val call: Call, val id: Int, val timeoutSeconds: Long) : InputStream() {
        private var cur: ByteArray = ByteArray(0)
        private var pos = 0
        private var done = false

        private fun fill(): Boolean {
            while (pos >= cur.size) {
                if (done) return false
                val next = call.data.poll(timeoutSeconds, TimeUnit.SECONDS) ?: run { calls.remove(id); throw IOException("the reply stopped coming") }
                if (next === EOF) { done = true; calls.remove(id); return false }
                cur = next; pos = 0
            }
            return true
        }

        override fun read(): Int = if (fill()) cur[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n); pos += n
            return n
        }

        override fun close() { calls.remove(id) }
    }

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ServerFeaturesPayload.TYPE) { p, _ -> serverKey = p.serverKey }
        ClientPlayNetworking.registerGlobalReceiver(ProxyResponsePayload.TYPE) { p, _ ->
            val call = calls[p.id] ?: return@registerGlobalReceiver
            when (p.kind) {
                ProxyResponsePayload.HEAD -> call.head.complete(p.status to p.info)
                ProxyResponsePayload.DATA -> call.data.add(p.data)
                ProxyResponsePayload.END -> call.data.add(EOF)
                else -> { call.head.completeExceptionally(IOException(p.info)); call.data.add(EOF) }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            serverKey = false
            calls.values.forEach { it.head.completeExceptionally(IOException("disconnected")); it.data.add(EOF) }
            calls.clear()
        }
    }
}
