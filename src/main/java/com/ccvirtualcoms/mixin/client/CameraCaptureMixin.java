package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline;
import com.ccvirtualcoms.client.camera.capture.CaptureCameraAccess;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the capture pipeline access to {@link Camera}'s protected transform setters, and applies the
 * one-frame camera override used by the main-target capture path.
 *
 * <p>The override is read from {@link CameraCapturePipeline} at the end of {@code Camera#setup}, so
 * the game's own camera object carries the capture pose while the frame is rendered. That matters
 * for shader packs: Iris' uniforms ({@code cameraPosition}, {@code gbufferModelView}, ...) are all
 * derived from the real camera, so overriding a separate camera object would render with the
 * player's position and produce wrong lighting/fog.
 *
 * <p>The override is non-null for exactly the frames the pipeline selected, and is cleared before
 * the hand/GUI passes; the next frame's {@code setup} restores the player camera automatically.
 */
@Mixin(Camera.class)
public abstract class CameraCaptureMixin implements CaptureCameraAccess {

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Override
    public void ccvc$setPosition(Vec3 position) {
        this.setPosition(position);
    }

    @Override
    public void ccvc$setRotation(float yaw, float pitch) {
        this.setRotation(yaw, pitch);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void ccvc$applyCaptureOverride(BlockGetter level, Entity entity, boolean detached,
                                           boolean thirdPersonReverse, float partialTick,
                                           CallbackInfo callbackInfo) {
        CameraCapturePipeline.CameraOverride override = CameraCapturePipeline.get().cameraOverride();
        if (override != null) {
            this.setPosition(override.position());
            this.setRotation(override.yaw(), override.pitch());
        }
    }
}
