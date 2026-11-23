package dev.dexbuddy.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Before each frame: if the companion is filming and a video frame is due, the world is drawn once from
 * its eyes and grabbed, then the normal frame (from your eyes) is drawn over it. You keep your own view.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererRenderMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void dexbuddy$companionVideoFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) dev.dexbuddy.client.photo.ClipRecorder.companionFrame((GameRenderer) (Object) this, deltaTracker);
    }
}
