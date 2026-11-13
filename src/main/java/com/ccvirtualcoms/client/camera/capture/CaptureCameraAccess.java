package com.ccvirtualcoms.client.camera.capture;

import net.minecraft.world.phys.Vec3;

/**
 * Implemented on {@link net.minecraft.client.Camera} by {@code CameraCaptureMixin}.
 *
 * <p>{@code Camera#setPosition(Vec3)} and {@code Camera#setRotation(float, float)} are protected, so
 * the capture pipeline reaches them through this interface instead of reflection or an access
 * transformer. When the mixin is not loaded the cast fails, which the pipeline treats as "camera
 * override unavailable" and degrades to the placeholder source rather than crashing.
 */
public interface CaptureCameraAccess {

    /** Sets the camera position, in world coordinates. */
    void ccvc$setPosition(Vec3 position);

    /** Sets the camera rotation in Minecraft degrees: yaw around Y, pitch positive downward. */
    void ccvc$setRotation(float yaw, float pitch);

    void ccvc$setRotation(float yaw, float pitch, float roll);
}
