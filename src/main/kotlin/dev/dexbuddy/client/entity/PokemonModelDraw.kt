package dev.dexbuddy.client.entity

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext
import com.cobblemon.mod.common.client.render.models.blockbench.repository.VaryingModelRepository
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.Species
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture

/** Draws a Cobblemon's real model in the world (feet at the origin), the way Cobblemon draws its own. */
object PokemonModelDraw {
    /** How many blocks tall-or-wide the drawn model should be, whatever the species' real size. */
    fun fitScale(species: Species, target: Float): Float {
        val hb = species.standardForm.hitbox
        return target / maxOf(hb.height(), hb.width(), 0.2f)
    }

    fun draw(species: Species, state: PosableState, pose: PoseStack, buffers: MultiBufferSource, light: Int, partialTicks: Float, color: Int) {
        val id = species.resourceIdentifier
        state.currentAspects = emptySet()
        val model = VaryingModelRepository.getPoser(id, state)
        val texture = VaryingModelRepository.getTexture(id, state)
        val ctx = RenderContext()
        model.context = ctx
        VaryingModelRepository.getTextureNoSubstitute(id, state)?.let { ctx.put(RenderContext.TEXTURE, it) }
        ctx.put(RenderContext.SCALE, species.getForm(state.currentAspects).baseScale)
        ctx.put(RenderContext.SPECIES, id)
        ctx.put(RenderContext.ASPECTS, state.currentAspects)
        ctx.put(RenderContext.RENDER_STATE, RenderContext.RenderState.WORLD)
        ctx.put(RenderContext.POSABLE_STATE, state)
        ctx.put(RenderContext.DO_QUIRKS, true)
        state.currentModel = model
        state.setPoseToFirstSuitable(PoseType.HOVER)
        state.updatePartialTicks(partialTicks)

        pose.pushPose()
        // Same flip as a living entity's model.
        pose.scale(-1f, -1f, 1f)
        pose.translate(0.0, -1.501, 0.0)
        model.applyAnimations(null, state, 0f, 0f, 0f, 0f, 0f)
        val buffer = buffers.getBuffer(RenderType.entityCutout(texture))
        model.withLayerContext(buffers, state, VaryingModelRepository.getLayers(id, state)) {
            model.render(ctx, pose, buffer, light, OverlayTexture.NO_OVERLAY, color)
        }
        model.setDefault()
        pose.popPose()
    }
}
