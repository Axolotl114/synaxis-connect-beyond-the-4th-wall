package com.ccvirtualcoms.content.camera;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Flood-fill merge logic for camera monitors.
 *
 * <p>A screen is the 2D connected component of camera monitor blocks that share the same
 * {@link CameraMonitorBlock#FACING}. Only the four directions inside the screen plane are
 * traversed (up, down, and the two horizontal directions perpendicular to the facing); blocks
 * stacked in front of or behind the panel are separate monitors and never merge.
 *
 * <p>The component's bounding box is measured in "screen space": columns grow towards the viewer's
 * right (the facing direction rotated counter-clockwise) and rows grow upwards. Every block stores
 * the master position (the min-corner tile, i.e. the smallest row and then the smallest column) and
 * its own column/row, so the renderer can map one image across the whole wall without knowing
 * anything else about the shape.
 *
 * <p>For non-rectangular shapes the bounding box may contain holes; connected tiles still render
 * their correct portion of the image, and holes simply stay transparent (the frame model shows
 * through).
 *
 * <p>All methods are server-side only and idempotent; they never change block states, so they can
 * be called from placement, removal, neighbour and load callbacks without causing update loops.
 */
public final class CameraMonitorMultiblock {

    private CameraMonitorMultiblock() {
    }

    /** Recomputes the component containing {@code pos}, if it holds a camera monitor. */
    public static void rebuildAt(@Nullable Level level, BlockPos pos) {
        if (level == null || level.isClientSide() || pos == null || !level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CameraMonitorBlock)) {
            return;
        }
        rebuild(level, pos.immutable(), state.getValue(CameraMonitorBlock.FACING));
    }

    /**
     * Recomputes every component touching {@code pos}. Used when a block was removed: each
     * remaining neighbour may now belong to a smaller (or split) screen.
     */
    public static void rebuildAround(@Nullable Level level, BlockPos pos) {
        if (level == null || level.isClientSide() || pos == null) {
            return;
        }
        Set<BlockPos> starts = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            if (level.getBlockState(neighbour).getBlock() instanceof CameraMonitorBlock) {
                starts.add(neighbour);
            }
        }
        for (BlockPos start : starts) {
            rebuildAt(level, start);
        }
    }

    private static void rebuild(Level level, BlockPos start, Direction facing) {
        Direction right = facing.getCounterClockWise();
        List<BlockPos> members = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        visited.add(start);
        queue.add(start);

        int minColumn = 0;
        int maxColumn = 0;
        int minRow = 0;
        int maxRow = 0;

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            members.add(current);
            int column = columnOf(start, current, right);
            int row = current.getY() - start.getY();
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);

            addNeighbour(level, facing, current, Direction.UP, visited, queue);
            addNeighbour(level, facing, current, Direction.DOWN, visited, queue);
            addNeighbour(level, facing, current, right, visited, queue);
            addNeighbour(level, facing, current, right.getOpposite(), visited, queue);
        }

        int width = maxColumn - minColumn + 1;
        int height = maxRow - minRow + 1;

        // Master = min corner of the component. For a rectangular wall this is exactly the block at
        // (minColumn, minRow); for an L-shaped component that corner may be empty, so pick the member
        // that is closest to it (smallest row, then smallest column).
        BlockPos master = null;
        int masterRow = Integer.MAX_VALUE;
        int masterColumn = Integer.MAX_VALUE;
        for (BlockPos member : members) {
            int column = columnOf(start, member, right);
            int row = member.getY() - start.getY();
            if (row < masterRow || (row == masterRow && column < masterColumn)) {
                master = member;
                masterRow = row;
                masterColumn = column;
            }
        }
        if (master == null) {
            return;
        }

        CameraMonitorBlockEntity masterEntity = blockEntity(level, master);
        long cameraKey = masterEntity == null ? CameraMonitorBlockEntity.NO_CAMERA
                : masterEntity.cameraKey();
        boolean enabled = masterEntity == null || masterEntity.enabled();
        if (cameraKey == CameraMonitorBlockEntity.NO_CAMERA) {
            // Merging screens must not silently drop an existing selection: adopt the first
            // configured member when the new master has none.
            for (BlockPos member : members) {
                CameraMonitorBlockEntity entity = blockEntity(level, member);
                if (entity != null && entity.cameraKey() != CameraMonitorBlockEntity.NO_CAMERA) {
                    cameraKey = entity.cameraKey();
                    enabled = entity.enabled();
                    break;
                }
            }
        }

        for (BlockPos member : members) {
            CameraMonitorBlockEntity entity = blockEntity(level, member);
            if (entity == null) {
                continue;
            }
            int column = columnOf(start, member, right) - minColumn;
            int row = member.getY() - start.getY() - minRow;
            boolean changed = entity.applyScreenLayout(master, width, height, column, row);
            if (member.equals(master)) {
                changed |= entity.applyMonitorConfig(cameraKey, enabled);
            }
            if (changed) {
                entity.commit();
            }
        }
    }

    private static void addNeighbour(Level level, Direction facing, BlockPos from,
                                     Direction direction, Set<BlockPos> visited,
                                     Deque<BlockPos> queue) {
        BlockPos neighbour = from.relative(direction);
        if (!visited.add(neighbour)) {
            return;
        }
        if (!level.isLoaded(neighbour)) {
            return;
        }
        BlockState state = level.getBlockState(neighbour);
        if (!(state.getBlock() instanceof CameraMonitorBlock)) {
            return;
        }
        if (state.getValue(CameraMonitorBlock.FACING) != facing) {
            return;
        }
        queue.add(neighbour);
    }

    private static int columnOf(BlockPos origin, BlockPos pos, Direction right) {
        return (pos.getX() - origin.getX()) * right.getStepX()
                + (pos.getZ() - origin.getZ()) * right.getStepZ();
    }

    private static @Nullable CameraMonitorBlockEntity blockEntity(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof CameraMonitorBlockEntity monitor ? monitor : null;
    }
}
