package dev.dexbuddy.client.entity

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.tts.VoicePlayer
import dev.dexbuddy.entity.DexBuddyEntity
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * The Dex Buddy item is drawn as the same 3D body as the companion (whatever look the item carries), so
 * the hotbar, your hand and the big on-screen Dex all match. Its face blinks, and talks while it speaks.
 */
object LookItemRenderer : BuiltinItemRendererRegistry.DynamicItemRenderer {
    fun register() {
        BuiltinItemRendererRegistry.INSTANCE.register(DexBuddyMod.DEX_BUDDY, this)
        DexBuddyMod.COSTUMES.values.forEach { BuiltinItemRendererRegistry.INSTANCE.register(it, this) }
    }

    override fun render(stack: ItemStack, mode: ItemDisplayContext, pose: PoseStack, buffers: MultiBufferSource, light: Int, overlay: Int) {
        // A costume shows the look it unlocks (smiling, never talking); the Dex Buddy shows the look it carries.
        val costume = (stack.item as? dev.dexbuddy.entity.CostumeItem)?.look
        val lm = LookModels.get(costume ?: DexBuddyEntity.lookOf(stack))
        val now = System.currentTimeMillis()
        val talking = costume == null && VoicePlayer.speaking
        val face = when {
            costume != null -> Face.HAPPY
            talking -> VoicePlayer.level.let { l -> if (l > 0.35f) Face.TALK2 else if (l > 0.12f) Face.TALK1 else Face.TALK0 }
            now % 4_500 < 150 -> Face.BLINK
            else -> Face.IDLE
        }
        val root = lm.root
        root.xRot = 0f; root.yRot = 0f; root.zRot = 0f
        lm.animate(AnimState(now / 1000f, talking, false))

        pose.pushPose()
        // Item space is a 0..1 block; the model is built like a mob (y down, front at -z, centered 1 block below its origin).
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(180f)) // face the front toward the viewer
        pose.scale(-1f, -1f, 1f)
        pose.translate(0.0, -1.0, 0.0)
        val buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(LookTextures.get(lm, face)))
        root.render(pose, buffer, light, overlay)
        pose.popPose()
    }
}
