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
 * <p>Two points are needed:
 *
 * <ul>
 *   <li><b>HEAD of {@code GameRenderer#renderLevel}</b> — before the camera is set up, so a
 *       main-target capture can install its one-frame camera override.</li>
 *   <li><b>immediately after {@code LevelRenderer#renderLevel} returns</b> — the earliest moment the
 *       main render target holds the final image. This is deliberately <em>not</em> the NeoForge
 *       {@code RenderLevelStageEvent.AFTER_LEVEL} stage: Iris injects its
 *       {@code finalizeLevelRendering} (which composites into the main target) at the {@code RETURN}
 *       of {@code LevelRenderer#renderLevel}, i.e. after that stage has already fired, so capturing
 *       there would read a half-finished frame (or black).</li>
 * </ul>
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
