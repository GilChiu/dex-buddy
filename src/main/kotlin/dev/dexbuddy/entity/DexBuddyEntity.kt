package dev.dexbuddy.entity

import dev.dexbuddy.DexBuddyMod
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LightBlock
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import java.util.Optional
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * The companion out in the world: a little floating Dex that follows its owner, can't be hurt, reacts
 * when punched, loves being petted and fed, can stay, glow, spin and play hide and seek, and goes back
 * into the owner's inventory on right-click or logout (keeping its friendship).
 */
class DexBuddyEntity(type: EntityType<out DexBuddyEntity>, level: Level) : PathfinderMob(type, level) {
    enum class Mode { FOLLOW, STAY, HIDING, PHOTO }

    companion object {
        val DATA_OWNER: EntityDataAccessor<Optional<UUID>> =
            SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.OPTIONAL_UUID)
        /** Ticks left of the "ouch" spin (also used for the "spin!" trick). */
        val DATA_REACT: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)
        /** Ticks left of a happy wiggle; [PET_TICKS] after petting, [FEED_TICKS] after a snack. */
        val DATA_HAPPY: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)
        val DATA_FRIENDSHIP: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)
        val DATA_MODE: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)
        val DATA_GLOW: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.BOOLEAN)
        /** Seconds of redstone charge left (the owner's, mirrored here so the client can show it). */
        val DATA_CHARGE: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)
        /** Which body style it has ([Look] ordinal). */
        val DATA_LOOK: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)
        /** Unlocked costumes, one bit per [Look] ordinal (the default look is always unlocked). */
        val DATA_COSTUMES: EntityDataAccessor<Int> = SynchedEntityData.defineId(DexBuddyEntity::class.java, EntityDataSerializers.INT)

        const val REACT_TICKS = 16
        const val PET_TICKS = 30
        const val FEED_TICKS = 50
        const val MAX_FRIENDSHIP = 255
        private const val TELEPORT_DISTANCE = 20.0
        private const val HIDE_SECONDS = 300
        private val BERRIES: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "berries"))

        fun attributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.FLYING_SPEED, 0.7)
            .add(Attributes.MOVEMENT_SPEED, 0.35)
            .add(Attributes.FOLLOW_RANGE, 48.0)

        /** Live companions by owner, so a player only ever has one out. Server thread only. */
        val active = HashMap<UUID, DexBuddyEntity>()

        /** The item the companion turns back into, carrying its friendship, look and unlocked costumes. */
        fun itemFor(friendship: Int, look: Look = Look.DEFAULT, costumes: Int = 0): ItemStack = ItemStack(DexBuddyMod.DEX_BUDDY).also { s ->
            val owned = costumeIds(costumes)
            if (friendship > 0 || look != Look.DEFAULT || owned.isNotEmpty()) s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply {
                if (friendship > 0) putInt("friendship", friendship)
                if (look != Look.DEFAULT) putString("look", look.id)
                if (owned.isNotEmpty()) putString("costumes", owned)
            }))
        }

        /** "classic,plush" style list of the unlocked costumes in [bits]. */
        fun costumeIds(bits: Int): String = Look.entries.filter { it != Look.DEFAULT && (bits shr it.ordinal) and 1 == 1 }.joinToString(",") { it.id }

        fun costumeBits(ids: String?): Int = ids.orEmpty().split(',').mapNotNull { Look.byId(it) }.fold(0) { acc, l -> acc or (1 shl l.ordinal) }

        fun costumesOf(stack: ItemStack): Int = costumeBits(stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("costumes"))

        /** A tidy companion name (letters, numbers, spaces, ' and -; up to 16 characters), or null. */
        fun cleanName(raw: String): String? {
            val n = raw.trim().replace(Regex("\\s+"), " ")
            return n.takeIf { it.length in 1..16 && Regex("^[\\p{L}\\p{N} '\\-]+$").matches(it) }
        }

        /** Every look's bit: what a Trainer can wear when the server unlocks all costumes. */
        val ALL_COSTUMES: Int = Look.entries.fold(0) { acc, l -> acc or (1 shl l.ordinal) }

        fun hasCostume(bits: Int, look: Look): Boolean = look == Look.DEFAULT || (bits shr look.ordinal) and 1 == 1

        fun friendshipOf(stack: ItemStack): Int =
            stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getInt("friendship") ?: 0

        fun lookOf(stack: ItemStack): Look =
            Look.byId(stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("look")) ?: Look.DEFAULT
    }

    private var stayPos: Vec3? = null
    private var lightPos: BlockPos? = null
    private var hideTicks = 0
    private var lastPetTick = -1000

    init {
        moveControl = FlyingMoveControl(this, 20, true)
        isNoGravity = true
        setPersistenceRequired()
    }

    var ownerId: UUID?
        get() = entityData.get(DATA_OWNER).orElse(null)
        set(v) { entityData.set(DATA_OWNER, Optional.ofNullable(v)) }

    val reactTicks: Int get() = entityData.get(DATA_REACT)
    val happyTicks: Int get() = entityData.get(DATA_HAPPY)
    val chargeSeconds: Int get() = entityData.get(DATA_CHARGE)
    private var nextGiftTick = -1
    var friendship: Int
        get() = entityData.get(DATA_FRIENDSHIP)
        set(v) { entityData.set(DATA_FRIENDSHIP, v.coerceIn(0, MAX_FRIENDSHIP)) }
    var mode: Mode
        get() = Mode.entries.getOrElse(entityData.get(DATA_MODE)) { Mode.FOLLOW }
        set(v) { entityData.set(DATA_MODE, v.ordinal) }
    var look: Look
        get() = Look.byOrdinal(entityData.get(DATA_LOOK))
        set(v) { entityData.set(DATA_LOOK, v.ordinal) }
    var costumeBits: Int
        get() = entityData.get(DATA_COSTUMES)
        set(v) { entityData.set(DATA_COSTUMES, v) }
    fun hasCostume(l: Look) = hasCostume(costumeBits, l)

    /** This companion as an item: name, friendship, look and costume collection all ride along. */
    fun toItem(): ItemStack = itemFor(friendship, look, costumeBits).also { s -> customName?.let { s.set(DataComponents.CUSTOM_NAME, it) } }

    /** Its own name, set by its Trainer ("your name is Sparky", /dexbuddy name, or an anvil). */
    private fun rename(raw: String): Boolean {
        val name = cleanName(raw) ?: return false
        customName = net.minecraft.network.chat.Component.literal(name)
        (level() as ServerLevel).sendParticles(ParticleTypes.HEART, x, y + bbHeight + 0.2, z, 5, 0.3, 0.2, 0.3, 0.0)
        entityData.set(DATA_HAPPY, PET_TICKS - 8)
        return true
    }
    var glowing: Boolean
        get() = entityData.get(DATA_GLOW)
        set(v) { entityData.set(DATA_GLOW, v); if (!v) clearLight() }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_OWNER, Optional.empty())
        builder.define(DATA_REACT, 0)
        builder.define(DATA_HAPPY, 0)
        builder.define(DATA_FRIENDSHIP, 0)
        builder.define(DATA_MODE, 0)
        builder.define(DATA_GLOW, false)
        builder.define(DATA_CHARGE, 0)
        builder.define(DATA_LOOK, Look.DEFAULT.ordinal)
        builder.define(DATA_COSTUMES, 0)
    }

    override fun createNavigation(level: Level): PathNavigation = FlyingPathNavigation(this, level).apply {
        setCanOpenDoors(false)
        setCanFloat(true)
        setCanPassDoors(true)
    }

    override fun registerGoals() {
        goalSelector.addGoal(1, FollowOwnerGoal(this))
        goalSelector.addGoal(2, StayGoal(this))
    }

    fun owner(): Player? = ownerId?.let { level().getPlayerByUUID(it) }

    override fun tick() {
        super.tick()
        if (level().isClientSide) return
        if (reactTicks > 0) entityData.set(DATA_REACT, reactTicks - 1)
        if (happyTicks > 0) {
            entityData.set(DATA_HAPPY, happyTicks - 1)
            if (happyTicks % 6 == 0) (level() as ServerLevel).sendParticles(ParticleTypes.HEART, x, y + bbHeight + 0.2, z, 1, 0.2, 0.1, 0.2, 0.0)
        }
        val id = ownerId ?: return
        if (active[id] !== this) {
            // Another one of this owner's companions took over (thrown again): this one goes away quietly.
            if (active[id]?.isAlive == true) { discard(); return }
            active[id] = this
        }
        val server = (level() as ServerLevel).server
        val owner = server.playerList.getPlayer(id) ?: return
        // A leftover copy (from before a crash, or an old save) while the real one is in your Dex: it fades away.
        if (!BuddyLink.isOut(owner)) {
            if (active[id] === this) active.remove(id)
            discard(); return
        }
        // Linked for good: a different dimension, or far away (even while staying or hiding), and it's back beside you.
        if (owner.level() != level()) { followToLevel(owner); return }
        if (mode != Mode.FOLLOW && mode != Mode.PHOTO && distanceTo(owner) > 100f) comeBack(owner)
        // Keep the Trainer's saved copy current (and adopt companions from before linking existed).
        if (tickCount % 20 == 0) BuddyLink.store(owner, this)
        if (mode != Mode.HIDING) lookControl.setLookAt(owner, 30f, 30f)
        if (glowing && tickCount % 4 == 0) updateLight()
        if (tickCount % 20 == 0) entityData.set(DATA_CHARGE, (dev.dexbuddy.games.Charge.remainingMs(id) / 1000).toInt())
        tickGifts(owner)
        if (tickCount % 200 == 0) checkMilestones(owner)
        if (mode == Mode.HIDING) tickHiding(owner)
        // Holds the camera spot long enough for a 15-second video (the client says "photo_done" sooner).
        if (mode == Mode.PHOTO && ++photoTicks > 20 * 25) { mode = Mode.FOLLOW; stayPos = null }
    }

    // ---- photographer ---------------------------------------------------------------------------------

    private var photoTicks = 0

    /**
     * Flies out in front of the owner to frame a shot: a close-up at face height, a portrait a few blocks
     * back, or a wide shot from up high. Stays put (looking at them) until the photo is taken.
     */
    private fun beginPhoto(owner: ServerPlayer, kind: String): Boolean {
        val (dist, up) = when (kind) { "close" -> 1.9 to 0.0; "wide" -> 8.0 to 2.6; else -> 3.0 to 0.1 }
        val yaw = Math.toRadians(owner.yRot.toDouble())
        val dir = Vec3(-sin(yaw), 0.0, cos(yaw))
        val eye = owner.eyePosition
        var camera = eye.add(dir.scale(dist)).add(0.0, up, 0.0)
        // Don't end up inside a wall: stop short of anything in the way.
        val hit = level().clip(ClipContext(eye, camera, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner))
        if (hit.type != HitResult.Type.MISS) camera = hit.location.subtract(camera.subtract(eye).normalize().scale(0.7))
        mode = Mode.PHOTO
        photoTicks = 0
        stayPos = camera.subtract(0.0, eyeHeight.toDouble(), 0.0)
        return true
    }

    /** The owner changed dimension: a fresh companion appears beside them there. */
    private fun followToLevel(owner: ServerPlayer) {
        BuddyLink.store(owner, this)
        val wasGlowing = glowing
        BuddyLink.spawn(owner)?.glowing = wasGlowing // spawn() retires this one
    }

    // ---- interaction ----------------------------------------------------------------------------------

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (player.uuid != ownerId) return InteractionResult.PASS
        if (level().isClientSide) return InteractionResult.SUCCESS
        val held = player.getItemInHand(hand)
        when {
            mode == Mode.HIDING -> found()
            held.item is CostumeItem -> wearNewCostume(player, held)
            held.`is`(Items.REDSTONE) || held.`is`(Items.REDSTONE_BLOCK) || held.`is`(BERRIES) -> feed(player, held)
            player.isShiftKeyDown && held.isEmpty -> { if (tickCount - lastPetTick > 40) (player as? ServerPlayer)?.let { BuddyLink.bump(it, "pets") }; pet() }
            else -> returnToOwner(player)
        }
        return InteractionResult.CONSUME
    }

    private fun pet() {
        entityData.set(DATA_HAPPY, PET_TICKS)
        // Friendship grows with care, not with spam-clicking: at most once every ten seconds.
        if (tickCount - lastPetTick > 200) { friendship += 1; lastPetTick = tickCount }
        level().playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.8f, 1.9f)
    }

    private fun feed(player: Player, food: ItemStack) {
        // Redstone charges it (gifts + 10% battle EXP while charged); berries are just a tasty treat.
        when {
            food.`is`(Items.REDSTONE_BLOCK) -> dev.dexbuddy.games.Charge.add(player.uuid, dev.dexbuddy.games.Charge.BLOCK_MS)
            food.`is`(Items.REDSTONE) -> dev.dexbuddy.games.Charge.add(player.uuid, dev.dexbuddy.games.Charge.DUST_MS)
        }
        entityData.set(DATA_CHARGE, (dev.dexbuddy.games.Charge.remainingMs(player.uuid) / 1000).toInt())
        if (nextGiftTick < 0) nextGiftTick = tickCount + 20 * 60 * (2 + random.nextInt(3)) // first find comes quickly
        if (food.`is`(BERRIES)) (player as? ServerPlayer)?.let { BuddyLink.bump(it, "berries") }
        if (!player.abilities.instabuild) food.shrink(1)
        entityData.set(DATA_HAPPY, FEED_TICKS)
        entityData.set(DATA_REACT, REACT_TICKS) // a happy little spin
        friendship += 3
        val l = level() as ServerLevel
        l.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + bbHeight / 2, z, 25, 0.3, 0.3, 0.3, 0.25)
        l.playSound(null, blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7f, 1.6f)
        l.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 0.8f, 1.8f)
    }

    // ---- costumes ----------------------------------------------------------------------------------

    /** A costume item given by hand: unlocked for good (the item is used up) and worn right away. */
    private fun wearNewCostume(player: Player, held: ItemStack) {
        val costume = (held.item as CostumeItem).look
        if (hasCostume(costume)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Your Dex Buddy already has the ${costume.displayName} costume"), true)
            return
        }
        unlockCostume(costume)
        if (!player.abilities.instabuild) held.shrink(1)
        changeLook(costume.id)
    }

    /** Adds a costume to the collection. Returns true if it was new. */
    fun unlockCostume(costume: Look): Boolean {
        if (hasCostume(costume)) return false
        costumeBits = costumeBits or (1 shl costume.ordinal)
        val l = level() as ServerLevel
        l.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y + 0.3, z, 30, 0.3, 0.4, 0.3, 0.25)
        l.playSound(null, blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.7f, 1.4f)
        return true
    }

    /** Costumes earned by playing: friendship 10 and 50, a 10-in-a-row quiz streak, 151 species caught. */
    private fun checkMilestones(owner: ServerPlayer) {
        // Server set to "all costumes unlocked": nothing to earn.
        if (dev.dexbuddy.proxy.ProxyServer.cfg.unlockAllCostumes) { costumeBits = ALL_COSTUMES; return }
        if (friendship >= 10) unlockCostume(Look.CLASSIC)
        if (friendship >= 50) unlockCostume(Look.PLUSH)
        if (dev.dexbuddy.games.QuizManager.bestStreak(owner.uuid) >= 10) unlockCostume(Look.BALL)
        if (!hasCostume(Look.FLIP)) {
            val caught = try {
                com.cobblemon.mod.common.Cobblemon.playerDataManager.getPokedexData(owner)
                    .getGlobalCalculatedValue(com.cobblemon.mod.common.api.pokedex.CaughtCount)
            } catch (_: Throwable) { 0 }
            if (caught >= 151) unlockCostume(Look.FLIP)
        }
        dev.dexbuddy.games.CostumeUnlocks.check(owner, this)
    }

    /** Back into the owner's inventory (or at their feet if it's full). */
    fun returnToOwner(player: Player) {
        val sp = player as? ServerPlayer ?: return
        BuddyLink.store(sp, this)
        BuddyLink.setOut(sp, false)
        val stack = BuddyLink.itemFor(sp)
        if (!player.inventory.add(stack)) player.drop(stack, false)
        level().playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.4f)
        if (active[player.uuid] === this) active.remove(player.uuid)
        discard()
    }

    /** While charged, every 8-12 minutes it "finds" something for its Trainer. */
    private fun tickGifts(owner: ServerPlayer) {
        if (!dev.dexbuddy.games.Charge.isCharged(owner.uuid) || mode != Mode.FOLLOW) return
        if (nextGiftTick < 0) { nextGiftTick = tickCount + 20 * 60 * (8 + random.nextInt(5)); return }
        if (tickCount < nextGiftTick) return
        nextGiftTick = tickCount + 20 * 60 * (8 + random.nextInt(5))
        // Once in a while (5%, from friendship 10) the find is a costume it doesn't have yet.
        val missing = DexBuddyMod.COSTUMES.filterKeys { !hasCostume(it) }.values.toList()
        // The Gothic Lolita costume only turns up at night (a 1 in 4 find).
        val lolita = !hasCostume(Look.GOTHIC_LOLITA) && dev.dexbuddy.games.CostumeUnlocks.isNight(level()) && random.nextFloat() < 0.25f
        val gift = if (lolita) ItemStack(DexBuddyMod.COSTUMES[Look.GOTHIC_LOLITA]!!)
            else if (friendship >= 10 && missing.isNotEmpty() && random.nextFloat() < 0.05f) ItemStack(missing[random.nextInt(missing.size)])
            else dev.dexbuddy.games.Charge.rollGift(friendship, java.util.Random(random.nextLong())) ?: return
        val key = gift.item.descriptionId
        val count = gift.count
        if (!owner.inventory.add(gift)) owner.drop(gift, false)
        entityData.set(DATA_HAPPY, PET_TICKS)
        val l = level() as ServerLevel
        l.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y + 0.3, z, 10, 0.3, 0.3, 0.3, 0.05)
        l.playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.8f, 1.2f)
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(owner, dev.dexbuddy.net.GiftPayload(key, count))
    }

    // ---- voice commands -------------------------------------------------------------------------------

    /** Runs a command the owner said out loud. Returns false if it doesn't apply right now. */
    fun command(cmd: String, owner: ServerPlayer): Boolean = when (cmd) {
        "follow" -> { mode = Mode.FOLLOW; stayPos = null; true }
        "stay" -> { mode = Mode.STAY; stayPos = position(); true }
        "glow_on" -> { glowing = true; true }
        "glow_off" -> { glowing = false; true }
        "spin" -> { entityData.set(DATA_REACT, REACT_TICKS); (level() as ServerLevel).sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.3, z, 12, 0.3, 0.2, 0.3, 0.1); true }
        "return" -> { returnToOwner(owner); true }
        "hide" -> hide(owner)
        "giveup" -> if (mode == Mode.HIDING) { comeBack(owner); true } else false
        "rename_clear" -> { customName = null; true }
        "photo_done" ->if (mode == Mode.PHOTO) { mode = Mode.FOLLOW; stayPos = null; true } else false
        else -> when {
            cmd.startsWith("look:") -> changeLook(cmd.removePrefix("look:"))
            cmd.startsWith("name:") -> rename(cmd.removePrefix("name:"))
            cmd.startsWith("photo:") && mode != Mode.HIDING -> beginPhoto(owner, cmd.removePrefix("photo:"))
            else -> false
        }
    }

    /** Swaps to another body style (one it has unlocked) with a puff of smoke. */
    private fun changeLook(id: String): Boolean {
        val next = Look.byId(id) ?: return false
        if (!hasCostume(next)) return false
        if (next == look) return true
        look = next
        val l = level() as ServerLevel
        l.sendParticles(ParticleTypes.POOF, x, y + 0.3, z, 14, 0.25, 0.25, 0.25, 0.03)
        l.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.3, z, 12, 0.3, 0.3, 0.3, 0.2)
        l.playSound(null, blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.NEUTRAL, 0.7f, 1.5f)
        entityData.set(DATA_HAPPY, PET_TICKS - 8) // a happy face, but short of what reads as a pet

        return true
    }

    // ---- light ------------------------------------------------------------------------------------------

    /** A glowing companion carries an invisible light block along with it (no extra mods needed). */
    private fun updateLight() {
        val here = blockPosition()
        if (here == lightPos) return
        clearLight()
        val state = level().getBlockState(here)
        if (state.isAir) {
            level().setBlock(here, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 13), 3)
            lightPos = here
        }
    }

    private fun clearLight() {
        val p = lightPos ?: return
        if (!level().isClientSide && level().getBlockState(p).`is`(Blocks.LIGHT)) level().removeBlock(p, false)
        lightPos = null
    }

    override fun remove(reason: RemovalReason) {
        if (!level().isClientSide) clearLight()
        super.remove(reason)
    }

    // ---- hide and seek ----------------------------------------------------------------------------------

    /** Flies off to a spot 12-28 blocks away that the owner can't see from where they stand. */
    private fun hide(owner: ServerPlayer): Boolean {
        val l = level() as ServerLevel
        val eye = owner.eyePosition
        repeat(60) {
            val angle = random.nextDouble() * Math.PI * 2
            val dist = 12 + random.nextDouble() * 16
            val x = owner.x + cos(angle) * dist
            val z = owner.z + sin(angle) * dist
            val top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
            val y = (top + random.nextInt(2)).toDouble()
            val pos = BlockPos.containing(x, y, z)
            if (!l.getBlockState(pos).isAir || !l.getBlockState(pos.above()).isAir) return@repeat
            val spot = Vec3(x, y + 0.3, z)
            val seen = l.clip(ClipContext(eye, spot, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, owner)).type == HitResult.Type.MISS
            if (seen) return@repeat
            mode = Mode.HIDING
            hideTicks = 0
            glowing = false
            l.sendParticles(ParticleTypes.POOF, this.x, this.y + 0.3, this.z, 10, 0.2, 0.2, 0.2, 0.02)
            teleportTo(spot.x, spot.y, spot.z)
            stayPos = spot
            return true
        }
        return false
    }

    private fun tickHiding(owner: ServerPlayer) {
        hideTicks++
        if (owner.level() == level() && owner.distanceTo(this) < 2.5f) { found(); return }
        if (hideTicks > HIDE_SECONDS * 20) comeBack(owner)
    }

    private fun found() {
        (owner() as? ServerPlayer)?.let { p ->
            BuddyLink.bump(p, "found")
            if (dev.dexbuddy.games.CostumeUnlocks.isNight(level())) BuddyLink.bump(p, "found_night")
        }
        mode = Mode.FOLLOW
        stayPos = null
        entityData.set(DATA_HAPPY, FEED_TICKS)
        entityData.set(DATA_REACT, REACT_TICKS)
        friendship += 2
        (level() as ServerLevel).sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y + 0.3, z, 15, 0.3, 0.3, 0.3, 0.1)
        level().playSound(null, blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.6f, 1.6f)
    }

    private fun comeBack(owner: ServerPlayer) {
        mode = Mode.FOLLOW
        stayPos = null
        if (owner.level() != level()) { followToLevel(owner); return }
        teleportTo(owner.x, owner.y + 1.6, owner.z)
    }

    // ---- toughness -----------------------------------------------------------------------------------

    /** Can't be hurt; a punch makes it spin and spark instead. /kill and the void still work. */
    override fun isInvulnerableTo(source: DamageSource): Boolean =
        !source.`is`(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)

    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if (!isInvulnerableTo(source)) return super.hurt(source, amount)
        if (!level().isClientSide && source.entity is Player && reactTicks == 0) {
            if (mode == Mode.HIDING && source.entity?.uuid == ownerId) { found(); return false }
            DexBuddyMod.LOG.info("[dexbuddy] companion hit by {} ({})", source.entity?.name?.string, source.msgId)
            entityData.set(DATA_REACT, REACT_TICKS)
            val l = level() as ServerLevel
            l.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + bbHeight / 2, z, 14, 0.25, 0.25, 0.25, 0.15)
            l.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1f, 1.6f)
            source.entity?.let { a ->
                val away = position().subtract(a.position()).normalize().scale(0.45)
                deltaMovement = Vec3(away.x, 0.25, away.z)
            }
        }
        return false
    }

    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun isPushable(): Boolean = false
    override fun causeFallDamage(fallDistance: Float, multiplier: Float, source: DamageSource): Boolean = false

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        ownerId?.let { tag.putUUID("Owner", it) }
        tag.putInt("Friendship", friendship)
        tag.putBoolean("Glow", glowing)
        tag.putString("Look", look.id)
        tag.putString("Costumes", costumeIds(costumeBits))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.hasUUID("Owner")) ownerId = tag.getUUID("Owner")
        friendship = tag.getInt("Friendship")
        entityData.set(DATA_GLOW, tag.getBoolean("Glow"))
        costumeBits = costumeBits(tag.getString("Costumes"))
        look = Look.byId(tag.getString("Look"))?.takeIf { hasCostume(it) } ?: Look.DEFAULT
    }

    // ---- movement goals -------------------------------------------------------------------------------

    /** Floats in front of and to the right of the owner; teleports there if it falls far behind. */
    private class FollowOwnerGoal(private val buddy: DexBuddyEntity) : Goal() {
        init { flags = EnumSet.of(Flag.MOVE, Flag.LOOK) }

        override fun canUse(): Boolean = buddy.mode == Mode.FOLLOW && (buddy.owner()?.let { !it.isSpectator } ?: false)
        override fun canContinueToUse(): Boolean = canUse()

        override fun tick() {
            val owner = buddy.owner() ?: return
            val yaw = Math.toRadians(owner.yRot.toDouble())
            // Minecraft: forward = (-sin yaw, cos yaw), right = (-cos yaw, -sin yaw).
            val t = buddy.tickCount / 20.0
            val tx = owner.x - sin(yaw) * 1.3 - cos(yaw) * 1.0 + sin(t) * 0.15
            val tz = owner.z + cos(yaw) * 1.3 - sin(yaw) * 1.0 + cos(t * 0.8) * 0.15
            val ty = owner.y + 1.35 + sin(t * 1.3) * 0.12
            val d = buddy.distanceToSqr(tx, ty, tz)
            when {
                d > TELEPORT_DISTANCE * TELEPORT_DISTANCE -> buddy.teleportTo(tx, ty, tz)
                d > 0.15 -> buddy.moveControl.setWantedPosition(tx, ty, tz, if (d > 25) 1.6 else 1.0)
            }
            buddy.lookControl.setLookAt(owner, 30f, 30f)
        }
    }

    /** Staying or hiding: hover in place with a little bob. */
    private class StayGoal(private val buddy: DexBuddyEntity) : Goal() {
        init { flags = EnumSet.of(Flag.MOVE) }

        override fun canUse(): Boolean = buddy.mode != Mode.FOLLOW && buddy.stayPos != null
        override fun canContinueToUse(): Boolean = canUse()

        override fun tick() {
            val p = buddy.stayPos ?: return
            // Holding still for a photo: no bob, and get there fast.
            val photo = buddy.mode == Mode.PHOTO
            val bob = if (photo) 0.0 else sin(buddy.tickCount / 15.0) * 0.08
            val d = buddy.distanceToSqr(p.x, p.y + bob, p.z)
            if (photo && d > 0.01) buddy.moveControl.setWantedPosition(p.x, p.y, p.z, if (d > 9) 2.0 else 1.0)
            else if (d > 0.05) buddy.moveControl.setWantedPosition(p.x, p.y + bob, p.z, 0.6)
        }
    }
}
