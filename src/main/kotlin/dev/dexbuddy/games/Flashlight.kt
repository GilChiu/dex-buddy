package dev.dexbuddy.games

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LightBlock
import java.util.UUID

/**
 * The phone's flashlight: an invisible light block that follows the Trainer's head (no other mods
 * needed). Works with or without the companion out. Server thread.
 */
object Flashlight {
    private class Lit(var level: ServerLevel? = null, var pos: BlockPos? = null)

    private val on = HashMap<UUID, Lit>()

    fun isOn(p: ServerPlayer) = on.containsKey(p.uuid)

    fun set(p: ServerPlayer, enable: Boolean) {
        if (enable) on.getOrPut(p.uuid) { Lit() } else on.remove(p.uuid)?.let { clear(it) }
    }

    fun tick(server: MinecraftServer) {
        val gone = ArrayList<UUID>()
        for ((id, lit) in on) {
            val p = server.playerList.getPlayer(id)
            if (p == null || !p.isAlive) { clear(lit); gone += id; continue }
            val here = BlockPos.containing(p.eyePosition)
            if (lit.level === p.serverLevel() && lit.pos == here) continue
            clear(lit)
            val level = p.serverLevel()
            if (level.getBlockState(here).isAir) {
                level.setBlock(here, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3)
                lit.level = level; lit.pos = here
            }
        }
        gone.forEach { on.remove(it) }
    }

    private fun clear(lit: Lit) {
        val l = lit.level; val p = lit.pos
        if (l != null && p != null && l.getBlockState(p).`is`(Blocks.LIGHT)) l.removeBlock(p, false)
        lit.level = null; lit.pos = null
    }

    fun off(p: ServerPlayer) = set(p, false)

    /** Server stopping: leave no light blocks behind. */
    fun clearAll() { on.values.forEach { clear(it) }; on.clear() }
}
