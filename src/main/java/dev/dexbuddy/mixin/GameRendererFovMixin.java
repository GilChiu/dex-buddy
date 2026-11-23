package dev.dexbuddy.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zooms the camera in while the Dex Buddy is scanning (hold right-click with it). */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void dexbuddy$scanZoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (!useFovSetting) return; // leave the held-item view alone
        double f = dev.dexbuddy.client.scan.ScanClient.fovFactor(partialTick);
        if (f < 0.999) cir.setReturnValue(cir.getReturnValue() * f);
    }
}
