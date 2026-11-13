package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the player FOV with the camera's configured FOV while a capture frame is rendered. */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void ccvc$captureFov(Camera camera, float partialTick, boolean usePlayerFov,
                                 CallbackInfoReturnable<Double> callbackInfo) {
        int fov = CameraCapturePipeline.get().captureFov();
        if (fov > 0) {
            callbackInfo.setReturnValue((double) fov);
        }
    }
}
