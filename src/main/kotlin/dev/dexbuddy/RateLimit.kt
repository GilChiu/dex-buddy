package dev.dexbuddy

import java.util.UUID

/**
 * Per-player limits on how often the server does something for a client (so a modified client can't
 * flood the server or other players). Server thread.
 */
object RateLimit {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** True if [player] may do [kind] now: at most [max] times per [perMs]. Counts this one when allowed. */
    fun allow(player: UUID, kind: String, max: Int, perMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.getOrPut("$player:$kind") { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > perMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(now)
        return true
    }

    fun forget(player: UUID) { val p = "$player:"; hits.keys.removeIf { it.startsWith(p) } }
}
