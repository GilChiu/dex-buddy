package dev.dexbuddy.client.entity

import com.mojang.blaze3d.vertex.PoseStack
import dev.dexbuddy.client.antics.Commands
import dev.dexbuddy.client.antics.Mood
import dev.dexbuddy.client.tts.VoicePlayer
import dev.dexbuddy.client.ui.QuizHud
import dev.dexbuddy.entity.DexBuddyEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws the companion in whichever body it has ([dev.dexbuddy.entity.Look]): it bobs in the air, blinks
 * now and then, talks with your voice's loudness, beams when petted, looks dizzy when punched, and
 * turns into the mystery Cobblemon during a "Who's that Cobblemon?" quiz.
 */
class DexBuddyRenderer(ctx: EntityRendererProvider.Context) :
    MobRenderer<DexBuddyEntity, LookEntityModel>(ctx, LookEntityModel(), 0.25f) {

    private fun isMine(e: DexBuddyEntity) = e.ownerId == Minecraft.getInstance().player?.uuid

    /** Only your own companion talks with your voice. */
    private fun isSpeaking(e: DexBuddyEntity): Boolean = VoicePlayer.speaking && isMine(e)

    private fun faceOf(e: DexBuddyEntity): Face {
        if (isSpeaking(e)) {
            val l = VoicePlayer.level
            return when { l > 0.35f -> Face.TALK2; l > 0.12f -> Face.TALK1; else -> Face.TALK0 }
        }
        if (e.happyTicks > 0) return Face.HAPPY
        if (e.reactTicks > 0) return if (isMine(e) && System.currentTimeMillis() - Commands.lastSpinAt < 2_000) Face.HAPPY else Face.BONK
        if (isMine(e) && Mood.state == Mood.State.SLEEPY) return Face.BLINK // dozing
        return if ((e.tickCount + e.id * 7) % 90 < 3) Face.BLINK else Face.IDLE
    }

    /**
     * The real (big, generated) texture only while drawing the companion in the world. Anything else that
     * asks, like a minimap drawing creature icons from their textures, gets a small face icon instead.
     */
    override fun getTextureLocation(e: DexBuddyEntity): ResourceLocation =
        if (drawing) LookTextures.get(LookModels.get(e.look), faceOf(e)) else MAP_ICON

    private var drawing = false

    override fun render(e: DexBuddyEntity, yaw: Float, partialTicks: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        model.current = LookModels.get(e.look)
        model.talking = isSpeaking(e)
        val level = if (model.talking) VoicePlayer.level else 0f
        pose.pushPose()
        val t = e.tickCount + partialTicks
        // Float: a slow bob (lazy when sleepy, bouncy when excited), hops in time with its voice, and happy hops.
        val mood = if (isMine(e)) Mood.state else Mood.State.HAPPY
        val (speed, height) = when {
            mood == Mood.State.SLEEPY -> 0.05 to 0.03
            mood == Mood.State.EXCITED -> 0.22 to 0.09
            else -> 0.1 to 0.06
        }
        var lift = sin(t * speed) * height + level * 0.05
        if (e.happyTicks > 0) lift += abs(sin(t * 0.5)) * 0.12
        pose.translate(0.0, lift, 0.0)
        val morph = if (isMine(e)) QuizHud.morph() else null
        noteMorph(e, morph)
        if (morph == null || !drawMorph(morph, t, partialTicks, pose, buffers, light)) {
            drawing = true
            try { super.render(e, yaw, partialTicks, pose, buffers, light) } finally { drawing = false }
        }
        pose.popPose()
    }

    companion object {
        private val MAP_ICON: ResourceLocation = dev.dexbuddy.DexBuddyMod.id("textures/entity/map_icon.png")
    }

    override fun scale(e: DexBuddyEntity, pose: PoseStack, partialTicks: Float) {
        pose.scale(0.55f, 0.55f, 0.55f)
    }

    // "Who's that Cobblemon?": your companion turns into the mystery Cobblemon, black until it's revealed.
    private var lastMorph: String? = null
    private var morphChangedAt = 0L
    private val morphState = com.cobblemon.mod.common.client.render.models.blockbench.FloatingState()

    private fun noteMorph(e: DexBuddyEntity, morph: QuizHud.Morph?) {
        val key = morph?.let { "${it.species.resourceIdentifier}:${it.revealed}" }
        if (key == lastMorph) return
        val wasForm = lastMorph?.substringBeforeLast(':')
        lastMorph = key
        morphChangedAt = System.currentTimeMillis()
        // Poof on turning into it and back, not on the silhouette filling in with color.
        if (wasForm != null && morph != null && wasForm == morph.species.resourceIdentifier.toString()) return
        val level = e.level()
        repeat(14) {
            val r = level.random
            level.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, e.x + (r.nextDouble() - 0.5) * 0.8, e.y + 0.1 + r.nextDouble() * 0.7,
                e.z + (r.nextDouble() - 0.5) * 0.8, (r.nextDouble() - 0.5) * 0.05, 0.03, (r.nextDouble() - 0.5) * 0.05)
        }
        level.playLocalSound(e.x, e.y, e.z, net.minecraft.sounds.SoundEvents.ILLUSIONER_MIRROR_MOVE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.6f, 1.4f, false)
    }

    private fun drawMorph(m: QuizHud.Morph, t: Float, partialTicks: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int): Boolean {
        // Pops in with a little overshoot.
        val since = ((System.currentTimeMillis() - morphChangedAt) / 250f).coerceIn(0f, 1f)
        val pop = if (since >= 1f) 1f else (since * 1.15f - 0.15f * since * since).coerceAtLeast(0.05f)
        val k = PokemonModelDraw.fitScale(m.species, 1.1f) * pop
        val color = if (m.revealed) -1 else 0xFF0A0A12.toInt()
        val glow = if (m.revealed) net.minecraft.client.renderer.LightTexture.FULL_BRIGHT else light
        pose.pushPose()
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(t * 2.5f)) // a slow turn so its shape reads from every side
        pose.scale(k, k, k)
        return try {
            PokemonModelDraw.draw(m.species, morphState, pose, buffers, glow, partialTicks, color)
            true
        } catch (ex: Throwable) {
            dev.dexbuddy.client.DexBuddyClient.LOG.warn("[dexbuddy] couldn't morph into {}: {}", m.species.name, ex.toString())
            false
        } finally {
            pose.popPose()
        }
    }
}
