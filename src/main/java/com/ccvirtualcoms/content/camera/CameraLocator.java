package com.ccvirtualcoms.content.camera;

import com.ccvirtualcoms.CCVirtualComs;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Locates camera transmitters that live on a Sable sub-level.
 *
 * <p>Sable keeps sub-level blocks in plot chunks that are not part of the normal chunk storage, so
 * {@code level.getBlockEntity(pos)} only finds cameras in the main world. Server payloads and client
 * status updates therefore have to look inside the sub-level plots as well; this class is the single
 * place that knows how to do it.
 *
 * <p>Everything is defensive: if Sable is missing or a plot API misbehaves, the lookup simply falls
 * back to the main level.
 */
public final class CameraLocator {

    private CameraLocator() {
    }

    /** Finds a camera at {@code pos} in the main level or in any loaded Sable sub-level. */
    public static @Nullable CameraTransmitterBlockEntity find(@Nullable Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockEntity direct = level.getBlockEntity(pos);
        if (direct instanceof CameraTransmitterBlockEntity camera) {
            return camera;
        }
        try {
            for (SubLevel subLevel : subLevels(level)) {
                EmbeddedPlotLevelAccessor accessor = subLevel.getPlot().getEmbeddedLevelAccessor();
                BlockEntity blockEntity = accessor.getBlockEntity(pos);
                if (blockEntity instanceof CameraTransmitterBlockEntity camera) {
                    return camera;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            CCVirtualComs.LOGGER.debug("[camera] sub-level camera lookup failed at {}: {}", pos,
                    failure.toString());
        }
        return null;
    }

    /** {@code true} when {@code pos} belongs to a Sable sub-level plot. */
    public static boolean inSubLevel(@Nullable Level level, BlockPos pos) {
        return containingSubLevel(level, pos) != null;
    }

    /** The Sable sub-level containing the plot position {@code pos}, or {@code null}. */
    public static @Nullable SubLevelAccess containingSubLevel(@Nullable Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            return SableCompanion.INSTANCE.getContaining(level, pos);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** Every loaded Sable sub-level of {@code level} (empty when there are none). */
    public static List<SubLevel> subLevels(@Nullable Level level) {
        if (level == null) {
            return List.of();
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container instanceof ServerSubLevelContainer server) {
                return new ArrayList<>(server.getAllSubLevels());
            }
            if (container instanceof ClientSubLevelContainer client) {
                return new ArrayList<>(client.getAllSubLevels());
            }
        } catch (RuntimeException | LinkageError failure) {
            CCVirtualComs.LOGGER.debug("[camera] sub-level container lookup failed: {}",
                    failure.toString());
        }
        return List.of();
    }

    /** Player-facing world position of a camera position (plot position projected out of Sable). */
    public static Vec3 worldPosition(@Nullable Level level, BlockPos pos) {
        Vec3 local = Vec3.atCenterOf(pos);
        if (level == null) {
            return local;
        }
        try {
            if (SableCompanion.INSTANCE.getContaining(level, pos) != null) {
                return SableCompanion.INSTANCE.projectOutOfSubLevel(level, local);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to the local position.
        }
        return local;
    }

    /** Convenience used by the capture runtime: skip sub-levels far outside the view distance. */
    public static boolean subLevelNear(Level level, SubLevel subLevel, Vec3 observer, double maxDistance) {
        try {
            Vec3 center = SableCompanion.INSTANCE.projectOutOfSubLevel(level,
                    Vec3.atCenterOf(subLevel.getPlot().getCenterBlock()));
            return center.distanceToSqr(observer) <= maxDistance * maxDistance;
        } catch (RuntimeException | LinkageError failure) {
            return true;
        }
    }

    /** The server level owning this camera, or {@code null} when it is not on the server. */
    public static @Nullable ServerLevel serverLevelOf(CameraTransmitterBlockEntity camera) {
        return camera.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;
    }
}
