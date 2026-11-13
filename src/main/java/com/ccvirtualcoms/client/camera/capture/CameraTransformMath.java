package com.ccvirtualcoms.client.camera.capture;

import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pure math for turning a camera block's Euler angles and its sub-level pose into a world-space
 * camera transform.
 *
 * <p>Kept free of Sable and Minecraft client types (other than {@link Mth}) so it is unit-testable
 * and so class loading never pulls in optional mods.
 *
 * <h2>Minecraft camera convention (1.21.1)</h2>
 * <pre>
 *   Q_cam = Ry(pi - yawRad) * Rx(-pitchRad) * Rz(rollRad)       // camera -> world
 *   forward = Q_cam * (0, 0, -1)                                // yaw 0 = south (+Z)
 * </pre>
 * This mirrors {@code net.minecraft.client.Camera#setRotation}, which builds
 * {@code rotationYXZ(pi - yaw, -pitch, 0)}; the game camera has no roll, so roll is composed here
 * on the local view axis and can only be honoured by render paths that build their own model view
 * matrix.
 */
public final class CameraTransformMath {

    /** Degrees to radians. */
    private static final double DEG_TO_RAD = Math.PI / 180.0D;

    /** Radians to degrees. */
    private static final double RAD_TO_DEG = 180.0D / Math.PI;

    /** Local camera axis that looks forward. */
    private static final Vector3dc FORWARD = new Vector3d(0.0D, 0.0D, -1.0D);

    private CameraTransformMath() {
    }

    /**
     * Builds the camera-to-world rotation from Minecraft-style Euler angles.
     *
     * @param yawDeg   yaw in degrees, 0 = south, 90 = west
     * @param pitchDeg pitch in degrees, positive = looking down
     * @param rollDeg  roll in degrees around the view axis, positive = clockwise
     * @return a new quaternion, never {@code null}
     */
    public static Quaterniond cameraRotation(float yawDeg, float pitchDeg, float rollDeg) {
        Quaterniond rotation = new Quaterniond().rotationYXZ(
                Math.PI - yawDeg * DEG_TO_RAD,
                -pitchDeg * DEG_TO_RAD,
                0.0D);
        if (rollDeg != 0.0F) {
            rotation.mul(new Quaterniond().rotateZ(rollDeg * DEG_TO_RAD));
        }
        return rotation;
    }

    /**
     * Decomposes a camera-to-world rotation back into Minecraft-style Euler angles.
     *
     * <p>Yaw and pitch come from the forward vector, which roll never moves; roll is then read
     * relative to the no-roll frame. The decomposition is singular at pitch = +/-90 degrees, so
     * pitch is clamped just short of the pole (Quake-style cameras have the same limitation).
     *
     * @param rotation camera-to-world rotation
     * @param dest     optional array of at least three floats to write into
     * @return {@code {yaw, pitch, roll}} in degrees
     */
    public static float[] eulerFromRotation(Quaterniondc rotation, float[] dest) {
        float[] out = dest != null && dest.length >= 3 ? dest : new float[3];
        Vector3d forward = rotation.transform(new Vector3d(FORWARD), new Vector3d());
        double yaw = Math.atan2(-forward.x, forward.z) * RAD_TO_DEG;
        double clampedY = Mth.clamp(forward.y, -1.0D, 1.0D);
        double pitch = -Math.asin(clampedY) * RAD_TO_DEG;
        // Near the poles the pitch clamp changes the forward vector slightly; recompute yaw from the
        // clamped frame below so the returned triple reproduces the same clamped orientation.
        if (pitch > 89.9D) {
            pitch = 89.9D;
        } else if (pitch < -89.9D) {
            pitch = -89.9D;
        }
        Quaterniond noRoll = cameraRotation((float) yaw, (float) pitch, 0.0F);
        Vector3d up = rotation.transform(new Vector3d(0.0D, 1.0D, 0.0D), new Vector3d());
        Vector3d upNoRoll = noRoll.transform(new Vector3d(0.0D, 1.0D, 0.0D), new Vector3d());
        Vector3d rightNoRoll = noRoll.transform(new Vector3d(1.0D, 0.0D, 0.0D), new Vector3d());
        double roll = Math.atan2(-up.dot(rightNoRoll), up.dot(upNoRoll)) * RAD_TO_DEG;
        out[0] = normalizeYaw((float) yaw);
        out[1] = (float) pitch;
        out[2] = (float) roll;
        return out;
    }

    /**
     * Maps a point that is fixed in a sub-level's local space into world space.
     *
     * <p>Mirrors {@code Pose3dc#transformPosition}:
     * {@code world = position + orientation * ((local - rotationPoint) * scale)}.
     */
    public static Vector3d localToWorld(SubLevelSnapshot subLevel, Vector3dc local, Vector3d dest) {
        Vector3d offset = new Vector3d(local).sub(subLevel.rotationPoint());
        offset.mul(subLevel.scale());
        subLevel.orientation().transform(offset);
        return dest.set(offset).add(subLevel.position());
    }

    /** Normalises an angle in degrees into {@code (-180, 180]}. */
    public static float normalizeYaw(float degrees) {
        float value = degrees % 360.0F;
        if (value > 180.0F) {
            value -= 360.0F;
        } else if (value <= -180.0F) {
            value += 360.0F;
        }
        return value;
    }
}
