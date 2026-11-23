package dev.dexbuddy.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the Dex is scanning, the phone has left your hand: don't draw the hand or the held Dex. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void dexbuddy$hideWhileScanning(float partialTicks, PoseStack poseStack, MultiBufferSource.BufferSource buffer,
                                            LocalPlayer player, int light, CallbackInfo ci) {
        if (dev.dexbuddy.client.scan.ScanClient.hideHands()) ci.cancel();
    }
}
