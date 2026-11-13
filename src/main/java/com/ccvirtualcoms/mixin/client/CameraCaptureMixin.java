package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.CaptureCameraAccess;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Gives the off-screen renderer access to {@link Camera}'s protected transform setters.
 *
 * <p>The camera object is private to the capture renderer; the player's camera is never modified.
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

}
