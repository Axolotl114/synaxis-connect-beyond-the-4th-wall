package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame hooks for the camera capture pipeline.
 *
 * <p>Two points are needed:
 *
 * <ul>
 *   <li><b>HEAD of {@code GameRenderer#renderLevel}</b> - before the camera is set up, so a
 *       main-target capture can install its one-frame camera override.</li>
 *   <li><b>immediately after {@code LevelRenderer#renderLevel} returns</b> - the earliest moment the
 *       main render target holds the final image (Iris composites into it before returning). This
 *       is deliberately not the NeoForge {@code RenderLevelStageEvent.AFTER_LEVEL} stage.</li>
 * </ul>
 *
 * <p>When the pipeline captured the main target (shader pack active), the camera override is
 * visible in this frame. To keep the player from seeing the camera view flicker, the player's
 * camera is restored and the level is rendered once more into the main target before the hand/GUI
 * passes. The capture itself already happened, so the extra render only affects what is on screen.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LightTexture lightTexture;

    @Shadow
    @Final
    private net.minecraft.client.Camera mainCamera;

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
    private void ccvc$onAfterLevelRender(DeltaTracker deltaTracker, CallbackInfo callbackInfo,
                                         @Local(ordinal = 0) boolean blockOutline,
                                         @Local(ordinal = 0) Matrix4f projectionMatrix,
                                         @Local(ordinal = 1) Matrix4f poseMatrix) {
        if (!CameraCapturePipeline.get().onAfterLevelRender()) {
            return;
        }
        // The capture rendered the world with the camera's pose. Re-render the player's view with
        // the real camera so the frame that is presented to the player is not the capture frame.
        Entity entity = this.minecraft.getCameraEntity() == null
                ? this.minecraft.player
                : this.minecraft.getCameraEntity();
        if (entity == null || this.minecraft.level == null) {
            return;
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        this.mainCamera.setup(this.minecraft.level, entity,
                !this.minecraft.options.getCameraType().isFirstPerson(),
                this.minecraft.options.getCameraType().isMirrored(), partialTick);
        this.minecraft.levelRenderer.renderLevel(deltaTracker, blockOutline, this.mainCamera,
                (GameRenderer) (Object) this, this.lightTexture, poseMatrix, projectionMatrix);
    }
}
