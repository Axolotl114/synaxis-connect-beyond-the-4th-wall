package com.ccvirtualcoms.camera.api;

/**
 * The world-space pose of a camera receiver at a given render moment.
 *
 * <p>Angles are Minecraft-style degrees: yaw around the world Y axis (0 = south, 90 = west),
 * pitch positive downward, roll clockwise around the view axis.
 *
 * @param x     camera eye position, world X
 * @param y     camera eye position, world Y
 * @param z     camera eye position, world Z
 * @param yaw   view yaw in degrees
 * @param pitch view pitch in degrees
 * @param roll  view roll in degrees
 */
public record CameraPose(double x, double y, double z, float yaw, float pitch, float roll) {

    /** Parses Euler angles from the {@code (yaw, pitch, roll)} triple without a position. */
    public static CameraPose atOrigin(float yaw, float pitch, float roll) {
        return new CameraPose(0.0D, 0.0D, 0.0D, yaw, pitch, roll);
    }
}
