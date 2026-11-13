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
 * Exposes {@link Camera}'s protected setters to the capture renderer and moves the player's camera
 * to the capture pose for exactly the one frame that is rendered into the main target and then
 * replaced by the saved player frame before the buffer swap.
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
