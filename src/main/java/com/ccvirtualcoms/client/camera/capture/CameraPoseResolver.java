package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.camera.CameraAxisMode;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.api.CameraPose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Resolves the world-space pose of one camera transmitter for the current frame.
 *
 * <p>Two situations are handled:
 *
 * <ul>
 *   <li><b>On a Sable sub-level.</b> The block position is plot-space, so the eye point (block
 *       centre + configured offset + a small clearance along the lens face) is transformed with the
 *       sub-level's interpolated render pose. Each Euler axis independently chooses between an
 *       absolute world angle ({@link CameraAxisMode#WORLD_STABLE}) and a local angle composed with
 *       the sub-level orientation ({@link CameraAxisMode#FOLLOW_SUBLEVEL}).</li>
 *   <li><b>Ordinary world space.</b> The block centre is used directly, the configured offset is in
 *       world axes, and every axis is absolute; {@code FOLLOW_SUBLEVEL} degrades to
 *       {@code WORLD_STABLE} because there is no sub-level rotation to follow.</li>
 * </ul>
 *
 * <p>The mixed-axis case is inherently approximate: Euler angles are not independent axes, so a
 * camera with, say, a world-stable yaw and a sub-level-relative pitch is resolved by decomposing
 * both candidate orientations and then selecting the requested component from each. That is
 * deterministic, matches the per-axis wording of the configuration, and is stable away from the
 * pitch poles (where the decomposition is clamped, like every Quake-style camera).
 */
public final class CameraPoseResolver {

    /** Distance from the block centre to the lens plane, in blocks. */
    public static final double DEFAULT_LENS_CLEARANCE = 0.5D;

    /** System property to override the clearance for debugging. */
    public static final String LENS_CLEARANCE_PROPERTY = "cc_virtual_coms.camera.lensClearance";

    private CameraPoseResolver() {
    }

    /**
     * The resolved camera transform for one frame.
     *
     * @param pose        public pose: eye position and Minecraft-style Euler angles
     * @param rotation    camera-to-world rotation, including roll; used by render paths that build
     *                    their own model view matrix
     * @param onSubLevel  {@code true} when a Sable sub-level pose was applied
     * @param subLevelName sub-level name for diagnostics, empty when not on one
     * @param detail      one-line explanation of the resolution, for the GUI/log
     */
    public record ResolvedPose(CameraPose pose, Quaterniond rotation, boolean onSubLevel,
                               String subLevelName, String detail) {

        public ResolvedPose {
            rotation = rotation == null ? new Quaterniond() : rotation;
            subLevelName = subLevelName == null ? "" : subLevelName;
            detail = detail == null ? "" : detail;
        }

        /** {@code true} when the rotation carries a non-zero roll. */
        public boolean hasRoll() {
            return this.pose != null && Math.abs(this.pose.roll()) > 1.0E-3F;
        }
    }

    /** Resolves the pose, never throwing; a failure falls back to the static block pose. */
    public static ResolvedPose resolve(ClientLevel level, BlockPos pos, Direction facing,
                                       double[] eulerAngles, CameraConfig config,
                                       float partialTick) {
        double[] angles = eulerAngles == null || eulerAngles.length < 3
                ? new double[] {0.0D, 0.0D, 0.0D}
                : eulerAngles;
        float yaw = CameraTransformMath.normalizeYaw((float) angles[0]);
        float pitch = clampPitch((float) angles[1]);
        float roll = CameraTransformMath.normalizeYaw((float) angles[2]);

        double clearance = lensClearance();
        Direction lens = facing == null ? Direction.NORTH : facing;
        Vector3d lensAxis = new Vector3d(lens.getStepX(), lens.getStepY(), lens.getStepZ());

        Vector3d localPoint = new Vector3d(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D);
        if (config != null) {
            localPoint.add(config.offsetX(), config.offsetY(), config.offsetZ());
        }
        localPoint.add(lensAxis.mul(clearance));

        SubLevelSnapshot subLevel = safeSnapshot(level, pos, partialTick);
        if (subLevel == null) {
            Quaterniond rotation = CameraTransformMath.cameraRotation(yaw, pitch, roll);
            CameraPose pose = new CameraPose(localPoint.x, localPoint.y, localPoint.z, yaw, pitch, roll);
            String detail = SableCameraAdapter.isAvailable()
                    ? "no sub-level at this block; static world pose"
                    : "static world pose (Sable sub-levels unavailable)";
            return new ResolvedPose(pose, rotation, false, "", detail);
        }

        Vector3d world = CameraTransformMath.localToWorld(subLevel, localPoint, new Vector3d());
        Quaterniond localRotation = CameraTransformMath.cameraRotation(yaw, pitch, roll);
        Quaterniond followRotation = new Quaterniond(subLevel.orientation()).mul(localRotation);
        float[] follow = CameraTransformMath.eulerFromRotation(followRotation, new float[3]);
        float[] absolute = CameraTransformMath.eulerFromRotation(localRotation, new float[3]);

        boolean yawFollow = config != null && config.yawMode() == CameraAxisMode.FOLLOW_SUBLEVEL;
        boolean pitchFollow = config != null && config.pitchMode() == CameraAxisMode.FOLLOW_SUBLEVEL;
        boolean rollFollow = config != null && config.rollMode() == CameraAxisMode.FOLLOW_SUBLEVEL;

        float finalYaw = yawFollow ? follow[0] : absolute[0];
        float finalPitch = pitchFollow ? follow[1] : absolute[1];
        float finalRoll = rollFollow ? follow[2] : absolute[2];
        Quaterniond finalRotation = CameraTransformMath.cameraRotation(finalYaw, finalPitch, finalRoll);
        CameraPose pose = new CameraPose(world.x, world.y, world.z, finalYaw, finalPitch,
                CameraTransformMath.normalizeYaw(finalRoll));

        String axes = "yaw=" + modeName(yawFollow) + " pitch=" + modeName(pitchFollow)
                + " roll=" + modeName(rollFollow);
        String detail = "sub-level '" + subLevel.name() + "' (" + axes + ")";
        return new ResolvedPose(pose, finalRotation, true, subLevel.name(), detail);
    }

    private static SubLevelSnapshot safeSnapshot(ClientLevel level, BlockPos pos, float partialTick) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            return SableCameraAdapter.snapshot(level, pos, partialTick);
        } catch (Throwable ignored) {
            // SableCameraAdapter already logs once; a missing companion library must not break capture.
            return null;
        }
    }

    private static String modeName(boolean follow) {
        return follow ? "follow" : "world";
    }

    private static float clampPitch(float pitch) {
        return Math.max(-89.9F, Math.min(89.9F, pitch));
    }

    private static double lensClearance() {
        String raw = System.getProperty(LENS_CLEARANCE_PROPERTY);
        if (raw != null) {
            try {
                double value = Double.parseDouble(raw.trim());
                if (Double.isFinite(value) && value >= 0.0D && value <= 4.0D) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the default.
            }
        }
        return DEFAULT_LENS_CLEARANCE;
    }
}
