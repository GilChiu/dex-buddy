package dev.dexbuddy.client

import dev.dexbuddy.net.SpawnAnswerPayload
import dev.dexbuddy.net.SpawnQueryPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks the server for spawn data. Call [request] from a worker thread only: the answer arrives on the
 * client thread, so waiting there would deadlock.
 */
object SpawnClient {
    private val nextId = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<String>>()

    const val OFFLINE = "UNAVAILABLE: the spawn scanner can't connect here (this world's server doesn't run Dex Buddy). Say so in character; do not guess spawns."

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(SpawnAnswerPayload.TYPE) { payload, _ ->
            pending.remove(payload.requestId)?.complete(payload.text)
        }
    }

    fun available(): Boolean = try { ClientPlayNetworking.canSend(SpawnQueryPayload.TYPE) } catch (_: Exception) { false }

    /** [species] blank = what spawns where the Trainer stands. */
    fun request(species: String, timeoutMs: Long = 3000): String {
        if (!available()) return OFFLINE
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<String>()
        pending[id] = future
        Minecraft.getInstance().execute { ClientPlayNetworking.send(SpawnQueryPayload(id, species.take(64))) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            "UNAVAILABLE: the spawn scanner didn't answer in time."
        } finally {
            pending.remove(id)
        }
    }
}
