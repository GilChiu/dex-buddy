package dev.dexbuddy.client.entity

import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.tts.Sfx
import dev.dexbuddy.client.tts.Tts
import dev.dexbuddy.client.ui.Subtitles
import dev.dexbuddy.entity.DexBuddyEntity
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/** Client side of the floating companion: rendering, and its little reactions when you punch it. */
object CompanionClient {
    private val ouchLines = listOf(
        "Hey! That tickles!", "Bzzt! Watch the screen!", "Ow ow ow, my circuits!", "Whoa! Was that a high five?",
        "Hey! I'm on your team, remember?", "Eep! Easy there, Trainer!", "Ha! Missed my antenna!",
    )

    private var lastReact = 0

    fun register() {
        EntityRendererRegistry.register(DexBuddyMod.DEX_BUDDY_ENTITY) { ctx -> DexBuddyRenderer(ctx) }
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
    }

    /** Your own companion if it's out and loaded near you. */
    fun mine(player: Player, range: Double = 48.0): DexBuddyEntity? =
        player.level().getEntitiesOfClass(DexBuddyEntity::class.java, player.boundingBox.inflate(range)) { it.ownerId == player.uuid }
            .minByOrNull { it.distanceToSqr(player) }

    private var nameTick = 0

    private fun tick(mc: Minecraft) {
        if (nameTick++ % 20 == 0) refreshName(mc)
        val player = mc.player ?: return
        val buddy = mine(player) ?: run { lastReact = 0; return }
        val react = buddy.reactTicks
        // A new spin that wasn't a trick or a happy snack: it got punched.
        val trick = System.currentTimeMillis() - dev.dexbuddy.client.antics.Commands.lastSpinAt < 2_000
        if (react > lastReact && react >= DexBuddyEntity.REACT_TICKS - 2 && buddy.happyTicks == 0 && !trick) {
            dev.dexbuddy.client.antics.Speaker.say("punched", "Bzzt! " + ouchLines.random(), 4_000, urgent = true)
        }
        lastReact = react
    }

    /**
     * What your companion is called: the name you gave your Dex (out in the world, or the item you're
     * carrying), else the config's default. Refreshed once a second; read it anywhere.
     */
    @Volatile var companionName: String = "Volty"
        private set

    private fun refreshName(mc: Minecraft) {
        val player = mc.player
        val own = player?.let { mine(it) }?.customName?.string
            ?: player?.let { dev.dexbuddy.client.context.GameContext.heldDexBuddy() }
                ?.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME)?.string
        companionName = own?.let { DexBuddyEntity.cleanName(it) } ?: BuddyConfig.settings.companionName.ifBlank { "Volty" }
    }
}
