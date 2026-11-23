package dev.dexbuddy.client.ui

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.mojang.math.Axis
import dev.dexbuddy.client.DexBuddyClient
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * "Who's that Cobblemon?": the real model drawn as a black silhouette while the question is open, then
 * in full color (with its name) for a few seconds when it's revealed.
 */
object QuizHud {
    private var species: Species? = null
    private var renderable: RenderablePokemon? = null
    private var state = FloatingState()
    private var open = false
    private var revealUntil = 0L

    /** Client thread: a quiz line arrived. */
    fun update(speciesId: String, isOpen: Boolean, reveal: Boolean) {
        val sp = if (speciesId.isBlank()) null else ResourceLocation.tryParse(speciesId)?.let { PokemonSpecies.getByIdentifier(it) }
        if (sp != null && sp != species) {
            species = sp
            renderable = RenderablePokemon(sp, emptySet(), ItemStack.EMPTY)
            state = FloatingState()
        }
        open = isOpen && sp != null
        revealUntil = if (reveal && sp != null) System.currentTimeMillis() + 6_000 else 0L
        if (!open && !reveal) species = null
    }

    class Morph(val species: Species, val revealed: Boolean)

    /** What your companion should look like right now, or null to look like itself. */
    fun morph(): Morph? {
        val sp = species ?: return null
        val revealing = System.currentTimeMillis() < revealUntil
        return if (open || revealing) Morph(sp, revealing) else null
    }

    fun render(g: GuiGraphics, delta: DeltaTracker) {
        val now = System.currentTimeMillis()
        val revealing = now < revealUntil
        if (!open && !revealing) return
        val r = renderable ?: return
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        // Your companion is out: it becomes the mystery Cobblemon itself, so no card needed.
        val player = mc.player
        if (player != null && dev.dexbuddy.client.entity.CompanionClient.mine(player, 32.0) != null) return

        // A card on the left side of the screen.
        val w = 110
        val h = 130
        val x = 12
        val y = (g.guiHeight() - h) / 2
        g.fill(x, y, x + w, y + h, 0xC0101820.toInt())
        g.fill(x, y, x + w, y + 2, 0xFFFFB347.toInt())
        val title = if (revealing) species?.translatedName?.string ?: "?" else "Who's that Cobblemon?"
        g.drawCenteredString(mc.font, title, x + w / 2, y + 6, if (revealing) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())

        val pose = g.pose()
        pose.pushPose()
        pose.translate((x + w / 2).toDouble(), (y + h - 22).toDouble(), 100.0)
        pose.scale(1f, 1f, -1f)
        val spin = (now % 12_000L) / 12_000f * 360f
        val partial = delta.getGameTimeDeltaPartialTick(false)
        val c = if (revealing) 1f else 0f // black silhouette until it's revealed
        try {
            drawProfilePokemon(
                r, pose, Axis.YP.rotationDegrees(spin), PoseType.PROFILE, state, partial, 34f,
                applyProfileTransform = true, applyBaseScale = true, r = c, g = c, b = c, a = 1f,
            )
        } catch (e: Throwable) {
            DexBuddyClient.LOG.warn("[dexbuddy] couldn't draw quiz Cobblemon: {}", e.toString())
            open = false; revealUntil = 0L
        }
        pose.popPose()
        if (!revealing) g.drawCenteredString(mc.font, "Say your guess!", x + w / 2, y + h - 12, 0xFFB0BEC5.toInt())
    }
}
