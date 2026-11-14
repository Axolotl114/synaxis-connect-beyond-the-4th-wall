package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides everything except entities while the entity-only mask pass renders. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMaskMixin {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void ccvc$skipTerrain(CallbackInfo callbackInfo) {
        if (CameraCapturePipeline.get().isMaskPass()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void ccvc$skipSky(CallbackInfo callbackInfo) {
        if (CameraCapturePipeline.get().isMaskPass()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void ccvc$skipClouds(CallbackInfo callbackInfo) {
        if (CameraCapturePipeline.get().isMaskPass()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true, require = 0)
    private void ccvc$skipWeather(CallbackInfo callbackInfo) {
        if (CameraCapturePipeline.get().isMaskPass()) {
            callbackInfo.cancel();
        }
    }
}
