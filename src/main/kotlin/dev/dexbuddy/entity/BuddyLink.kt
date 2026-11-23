package dev.dexbuddy.entity

import dev.dexbuddy.DexBuddyMod
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Each Trainer has exactly one companion, linked to them for good. Its data (name, friendship, look,
 * costumes) lives on the player, saved with them and kept through death, so it can never be lost or
 * wiped. A Dex Buddy item and the floating companion are just ways of carrying it:
 *  - an unlinked Dex links to the first Trainer who carries it (and becomes *their* companion);
 *  - you can only carry one, and never someone else's;
 *  - if it's out it stays out (back beside you after logging in, respawning, teleporting far away);
 *  - your Dex stays with you when you die.
 * Server thread.
 */
object BuddyLink {
    val DATA: AttachmentType<CompoundTag> = AttachmentRegistry.builder<CompoundTag>()
        .persistent(CompoundTag.CODEC)
        .copyOnDeath()
        .buildAndRegister(DexBuddyMod.id("companion"))

    private fun data(p: ServerPlayer): CompoundTag = p.getAttached(DATA)?.copy() ?: CompoundTag()
    private fun put(p: ServerPlayer, tag: CompoundTag) { p.setAttached(DATA, tag) }
    private fun edit(p: ServerPlayer, f: (CompoundTag) -> Unit) = put(p, data(p).also(f))

    fun owns(p: ServerPlayer) = data(p).getBoolean("owned")

    /**
     * The costumes this Trainer can wear: all of them when the server says so (the default), otherwise
     * the ones they've earned. Only earned ones are ever saved, so switching the setting off later
     * leaves everyone with what they really unlocked.
     */
    fun wearable(earned: Int): Int = if (dev.dexbuddy.proxy.ProxyServer.cfg.unlockAllCostumes) DexBuddyEntity.ALL_COSTUMES else earned
    private fun costumes(d: CompoundTag) = wearable(d.getInt("costumes"))
    fun isOut(p: ServerPlayer) = data(p).getBoolean("out")
    fun setOut(p: ServerPlayer, out: Boolean) = edit(p) { it.putBoolean("out", out) }

    // ---- the item ---------------------------------------------------------------------------------

    fun ownerOf(stack: ItemStack): UUID? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.takeIf { it.hasUUID("owner") }?.getUUID("owner")

    /** A Dex Buddy item showing this Trainer's companion, linked to them. */
    fun itemFor(p: ServerPlayer): ItemStack {
        val d = data(p)
        val stack = DexBuddyEntity.itemFor(d.getInt("friendship"), Look.byId(d.getString("look")) ?: Look.DEFAULT, costumes(d))
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        tag.putUUID("owner", p.uuid)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        d.getString("name").takeIf { it.isNotBlank() }?.let { stack.set(DataComponents.CUSTOM_NAME, Component.literal(it)) }
        return stack
    }

    /** First time this Trainer carries a Dex: it becomes theirs, keeping whatever that Dex already had. */
    fun bind(p: ServerPlayer, from: ItemStack) {
        if (owns(p)) return
        edit(p) { d ->
            d.putBoolean("owned", true)
            d.putInt("friendship", DexBuddyEntity.friendshipOf(from))
            d.putString("look", DexBuddyEntity.lookOf(from).id)
            if (!dev.dexbuddy.proxy.ProxyServer.cfg.unlockAllCostumes) d.putInt("costumes", DexBuddyEntity.costumesOf(from))
            from.get(DataComponents.CUSTOM_NAME)?.string?.let { DexBuddyEntity.cleanName(it) }?.let { d.putString("name", it) }
        }
    }

    /** Saves the companion's current state onto its Trainer. */
    fun store(p: ServerPlayer, b: DexBuddyEntity) = edit(p) { d ->
        d.putBoolean("owned", true)
        d.putInt("friendship", b.friendship)
        d.putString("look", b.look.id)
        if (!dev.dexbuddy.proxy.ProxyServer.cfg.unlockAllCostumes) d.putInt("costumes", b.costumeBits)
        val name = b.customName?.string?.let { DexBuddyEntity.cleanName(it) }
        if (name != null) d.putString("name", name) else d.remove("name")
    }

    /** Changing costume while it's tucked away in the Dex (only looks you've unlocked). */
    fun setLook(p: ServerPlayer, look: Look): Boolean {
        if (!DexBuddyEntity.hasCostume(costumes(data(p)), look)) return false
        edit(p) { it.putString("look", look.id) }
        return true
    }

    /** Counters for costume unlocks (berries fed, pets, nights slept...), kept with the companion's data. */
    fun stat(p: ServerPlayer, key: String): Int = data(p).getCompound("stats").getInt(key)

    fun bump(p: ServerPlayer, key: String, n: Int = 1): Int {
        var v = 0
        edit(p) { d -> val s = d.getCompound("stats"); v = s.getInt(key) + n; s.putInt(key, v); d.put("stats", s) }
        return v
    }

    /** Naming your companion while it's tucked away in the Dex. */
    fun rename(p: ServerPlayer, name: String) = edit(p) { it.putString("name", name) }

    // ---- the companion ----------------------------------------------------------------------------

    /** Lets the companion out beside its Trainer (or at [at]), built from their saved data. */
    fun spawn(p: ServerPlayer, at: net.minecraft.world.phys.Vec3? = null): DexBuddyEntity? {
        DexBuddyEntity.active.remove(p.uuid)?.let { if (!it.isRemoved) it.discard() }
        val level = p.serverLevel()
        val b = DexBuddyMod.DEX_BUDDY_ENTITY.create(level) ?: return null
        val d = data(p)
        b.ownerId = p.uuid
        b.friendship = d.getInt("friendship")
        b.costumeBits = costumes(d)
        b.look = Look.byId(d.getString("look"))?.takeIf { b.hasCostume(it) } ?: Look.DEFAULT
        d.getString("name").takeIf { it.isNotBlank() }?.let { b.customName = Component.literal(it) }
        val pos = at ?: p.position().add(-sin(Math.toRadians(p.yRot.toDouble())) * 1.3, 1.4, cos(Math.toRadians(p.yRot.toDouble())) * 1.3)
        b.moveTo(pos.x, pos.y, pos.z, p.yRot + 180f, 0f)
        DexBuddyEntity.active[p.uuid] = b
        level.addFreshEntity(b)
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.3, pos.z, 20, 0.3, 0.3, 0.3, 0.2)
        level.playSound(null, b.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1f, 1.5f)
        setOut(p, true)
        DexBuddyMod.LOG.info("[dexbuddy] {}'s companion is out in {} at {}", p.gameProfile.name, level.dimension().location(), b.blockPosition())
        return b
    }

    // ---- the once-a-second check --------------------------------------------------------------------

    /**
     * Keeps the one-companion rule: links an unlinked Dex, hands back other Trainers' Dexes, removes
     * extra copies, keeps the carried Dex showing the latest look/name, and brings a missing companion
     * back beside its Trainer.
     */
    fun check(p: ServerPlayer) {
        val inv = p.inventory
        var kept = -1
        for (slot in 0 until inv.containerSize) {
            val s = inv.getItem(slot)
            if (!s.`is`(DexBuddyMod.DEX_BUDDY)) continue
            val owner = ownerOf(s)
            when {
                owner != null && owner != p.uuid -> {
                    inv.setItem(slot, ItemStack.EMPTY)
                    val real = p.server.playerList.getPlayer(owner)
                    if (real != null && !isOut(real) && !carries(real)) real.inventory.add(real.let { itemFor(it) })
                    tell(p, "That Dex Buddy belongs to someone else, so it went home.")
                }
                isOut(p) || kept >= 0 -> {
                    if (!owns(p)) bind(p, s)
                    inv.setItem(slot, ItemStack.EMPTY)
                    tell(p, if (isOut(p)) "Your companion is already out with you!" else "You can only carry one Dex Buddy. Yours is one of a kind!")
                }
                else -> {
                    bind(p, s)
                    kept = slot
                    val fresh = itemFor(p)
                    if (!ItemStack.isSameItemSameComponents(s, fresh)) inv.setItem(slot, fresh.also { it.count = 1 })
                }
            }
        }
        // Out, but gone (unloaded far away, killed by the void...): back beside you.
        if (isOut(p)) {
            val b = DexBuddyEntity.active[p.uuid]
            if (b == null || b.isRemoved || !b.isAlive) spawn(p)
        }
    }

    private fun carries(p: ServerPlayer) = (0 until p.inventory.containerSize).any { p.inventory.getItem(it).`is`(DexBuddyMod.DEX_BUDDY) }

    private fun tell(p: ServerPlayer, msg: String) = p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.YELLOW), true)

    // ---- death -------------------------------------------------------------------------------------

    /** Dying: the Dex isn't dropped (it's restored on respawn). */
    fun onDeath(p: ServerPlayer) {
        var had = false
        for (slot in 0 until p.inventory.containerSize) {
            if (p.inventory.getItem(slot).`is`(DexBuddyMod.DEX_BUDDY)) { p.inventory.setItem(slot, ItemStack.EMPTY); had = true }
        }
        if (had) edit(p) { it.putBoolean("restore", true) }
        DexBuddyMod.LOG.info("[dexbuddy] {} died (kept their Dex: {})", p.gameProfile.name, had)
    }

    fun onRespawn(p: ServerPlayer) {
        if (!data(p).getBoolean("restore")) return
        edit(p) { it.remove("restore") }
        if (!isOut(p)) p.inventory.add(itemFor(p))
        DexBuddyMod.LOG.info("[dexbuddy] {} respawned; Dex back in their inventory", p.gameProfile.name)
    }

    /** Admin: a fresh start (no companion, no saved data). */
    fun reset(p: ServerPlayer) {
        DexBuddyEntity.active.remove(p.uuid)?.let { if (!it.isRemoved) it.discard() }
        for (slot in 0 until p.inventory.containerSize) if (p.inventory.getItem(slot).`is`(DexBuddyMod.DEX_BUDDY)) p.inventory.setItem(slot, ItemStack.EMPTY)
        p.removeAttached(DATA)
    }
}
