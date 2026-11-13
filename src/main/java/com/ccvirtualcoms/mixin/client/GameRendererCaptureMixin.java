package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame hooks for the camera capture pipeline.
 *
 * <ul>
 *   <li><b>HEAD of {@code GameRenderer#renderLevel}</b> - installs the one-frame camera override
 *       before the camera is set up.</li>
 *   <li><b>immediately after {@code LevelRenderer#renderLevel} returns</b> - the earliest moment the
 *       main render target holds a shader-composed image. Capturing there keeps the capture out of
 *       the hand/GUI passes.</li>
 * </ul>
 *
 * <p>The capture frame is hidden from the player by {@code PresentGuard} at the end of the frame
 * (see {@code CameraCapturePipeline#onFrameEnd()}), which puts the previous player frame back into
 * the main target before the buffer swap. No world re-render is needed.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ccvc$onFrameBegin(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        CameraCapturePipeline.get().onFrameBegin();
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
    private void ccvc$onAfterLevelRender(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        CameraCapturePipeline.get().onAfterLevelRender();
    }
}
