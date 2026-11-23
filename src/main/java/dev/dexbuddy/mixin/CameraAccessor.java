package dev.dexbuddy.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The camera's eased eye height, so a companion-view video frame starts at the companion's eyes right away. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("eyeHeight") float dexbuddy$getEyeHeight();
    @Accessor("eyeHeight") void dexbuddy$setEyeHeight(float v);
    @Accessor("eyeHeightOld") float dexbuddy$getEyeHeightOld();
    @Accessor("eyeHeightOld") void dexbuddy$setEyeHeightOld(float v);
}
