package dev.dexbuddy.games

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.math.ceil

/**
 * Feeding the companion redstone "charges" it. While charged it finds gifts for its Trainer now and
 * then, and the Trainer's Cobblemon earn 10% more battle EXP. Charge belongs to the Trainer, so it
 * survives picking the companion up and throwing it out again. Server thread.
 */
object Charge {
    /** Redstone dust: 5 minutes. A block of redstone: 45 minutes. Stacks up to 60 minutes. */
    const val DUST_MS = 5 * 60_000L
    const val BLOCK_MS = 45 * 60_000L
    private const val MAX_MS = 60 * 60_000L
    private const val EXP_BONUS = 1.10

    private val until = HashMap<UUID, Long>()

    fun add(player: UUID, ms: Long) {
        val now = System.currentTimeMillis()
        val base = maxOf(now, until[player] ?: 0L)
        until[player] = minOf(base + ms, now + MAX_MS)
    }

    fun remainingMs(player: UUID): Long = ((until[player] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0)
    fun isCharged(player: UUID): Boolean = remainingMs(player) > 0

    fun register() {
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.NORMAL) { e ->
            val owner = e.pokemon.getOwnerUUID() ?: return@subscribe
            if (e.source.isBattle() && isCharged(owner)) e.experience = ceil(e.experience * EXP_BONUS).toInt()
        }
    }

    // ---- gifts ------------------------------------------------------------------------------------

    private class Gift(val id: String, val weight: Int, val min: Int = 1, val max: Int = 1)

    /** Gift tables by friendship: better finds unlock as the bond grows. */
    private val tiers: List<Pair<Int, List<Gift>>> = listOf(
        0 to listOf(Gift("oran_berry", 10, 1, 3), Gift("pecha_berry", 8, 1, 3), Gift("cheri_berry", 8, 1, 3),
            Gift("red_apricorn", 4, 1, 2), Gift("blue_apricorn", 4, 1, 2), Gift("yellow_apricorn", 4, 1, 2), Gift("green_apricorn", 4, 1, 2),
            Gift("poke_ball", 10, 1, 3)),
        10 to listOf(Gift("great_ball", 8, 1, 2), Gift("sitrus_berry", 6, 1, 2), Gift("potion", 6, 1, 2), Gift("exp_candy_s", 5)),
        25 to listOf(Gift("ultra_ball", 6), Gift("exp_candy_m", 4), Gift("revive", 3),
            Gift("fire_stone", 1), Gift("water_stone", 1), Gift("thunder_stone", 1), Gift("leaf_stone", 1), Gift("moon_stone", 1)),
        50 to listOf(Gift("rare_candy", 2), Gift("exp_candy_l", 2)),
    )

    /** A random gift for this friendship level (Cobblemon items only; anything missing is skipped). */
    fun rollGift(friendship: Int, random: java.util.Random): ItemStack? {
        val pool = tiers.filter { friendship >= it.first }.flatMap { it.second }
            .mapNotNull { g -> item(g.id)?.let { it to g } }
        if (pool.isEmpty()) return null
        var r = random.nextInt(pool.sumOf { it.second.weight })
        for ((item, g) in pool) {
            r -= g.weight
            if (r < 0) return ItemStack(item, g.min + random.nextInt(g.max - g.min + 1))
        }
        return null
    }

    private fun item(id: String): Item? =
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", id)).orElse(null)
}
