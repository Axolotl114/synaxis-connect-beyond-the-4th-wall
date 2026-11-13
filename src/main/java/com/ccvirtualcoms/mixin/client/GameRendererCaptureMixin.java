package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the camera capture right after the player's world has been rendered and before the hand and
 * HUD passes. The pipeline saves that player frame, renders the camera view, copies it out and puts
 * the player frame back, so the capture contains no hand/HUD and the player sees a normal frame.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ccvc$beginCaptureFrame(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        CameraCapturePipeline.get().beginFrame();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel("
                            + "Lnet/minecraft/client/DeltaTracker;"
                            + "ZLnet/minecraft/client/Camera;"
                            + "Lnet/minecraft/client/renderer/GameRenderer;"
                            + "Lnet/minecraft/client/renderer/LightTexture;"
                            + "Lorg/joml/Matrix4f;"
                            + "Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER))
    private void ccvc$afterWorldRender(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        CameraCapturePipeline.get().afterWorldRender();
    }
}
