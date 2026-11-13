package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The only class in this mod that touches Sable's client API.
 *
 * <p>It copies the render pose into a plain {@link SubLevelSnapshot} so callers never have to link
 * against Sable. Sable is a hard dependency of this mod, but keeping the touch point this small
 * means a future Sable API change is a one-file fix, and an unexpectedly missing companion library
 * degrades to the block-position fallback instead of crashing the client.
 *
 * <p>The pose used is {@code renderPose(partialTick)}: Sable interpolates
 * {@code lastPose -> logicalPose} with the same partial tick the game renders with, so the camera
 * and the ship geometry agree inside a frame. The engine itself reuses its cached pose when the
 * partial tick repeats, which makes this call cheap.
 */
public final class SableCameraAdapter {

    private static final String SABLE_MOD_ID = "sable";
    /** Set after the first linkage failure so a broken Sable cannot spam the log every frame. */
    private static volatile boolean broken;

    private static boolean reportFailureOnce;

    private SableCameraAdapter() {
    }

    /**
     * Returns the render pose of the sub-level that contains {@code pos}, or {@code null} when the
     * block is in ordinary world space.
     *
     * <p>Sable resolves sub-levels per chunk ({@code getContaining(Level, Vec3i)} shifts the packed
     * position down to chunk coordinates), which is sufficient here: a placed camera always lives
     * in the plot chunk of its sub-level.
     *
     * @param level      the client level the camera is in
     * @param pos        the camera block position (plot coordinates when on a sub-level)
     * @param partialTick render partial tick, in {@code [0, 1]}
     * @return a defensive snapshot, or {@code null}
     */
    public static @Nullable SubLevelSnapshot snapshot(ClientLevel level, BlockPos pos,
                                                      float partialTick) {
        if (broken || !isSableLoaded()) {
            return null;
        }
        try {
            // The *Client overloads read the client level themselves (SableDistUtil.getClientLevel),
            // so only the position is passed; BlockPos is a Vec3i and selects getContainingClient(Vec3i).
            ClientSubLevel subLevel = Sable.HELPER.getContainingClient(pos);
            if (subLevel == null) {
                return null;
            }
            Pose3dc pose = subLevel.renderPose(partialTick);
            return new SubLevelSnapshot(
                    subLevel.getName(),
                    new Vector3d(pose.position()),
                    new Quaterniond(pose.orientation()),
                    new Vector3d(pose.rotationPoint()),
                    new Vector3d(pose.scale()));
        } catch (Throwable failure) {
            broken = true;
            if (!reportFailureOnce) {
                reportFailureOnce = true;
                CCVirtualComs.LOGGER.warn(
                        "[camera] Sable sub-level lookup is unavailable; falling back to block-space "
                                + "camera poses. {}", failure.toString());
            }
            return null;
        }
    }

    /** {@code true} when Sable is installed and its API has not failed to link. */
    public static boolean isAvailable() {
        return !broken && isSableLoaded();
    }

    /** {@code true} when the Sable mod is present; safe to call from any thread. */
    public static boolean isSableLoaded() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded(SABLE_MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
