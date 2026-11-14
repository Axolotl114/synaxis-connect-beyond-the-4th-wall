package com.ccvirtualcoms.content.camera;

import com.ccvirtualcoms.content.CCVirtualComsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Block entity of one camera monitor tile.
 *
 * <p>Every tile stores its place inside the merged screen:
 *
 * <ul>
 *   <li>{@link #screenOrigin()}: the master (min-corner) block of the connected component;</li>
 *   <li>{@link #screenWidth()} / {@link #screenHeight()}: the component size in blocks;</li>
 *   <li>{@link #column()} / {@link #row()}: this tile's position inside that component.</li>
 * </ul>
 *
 * <p>The screen configuration (the selected camera and the on/off state) lives on the master only.
 * Tiles are recomputed by {@link CameraMonitorMultiblock} on load, placement and neighbour changes;
 * the result is persisted so a client that has not received a layout update yet still renders
 * something sane.
 *
 * <p>The selected camera is stored as the packed {@link BlockPos#asLong()} of the camera
 * transmitter; {@code 0} means "no camera selected".
 */
public class CameraMonitorBlockEntity extends BlockEntity {

    // ------------------------------------------------------------------ NBT keys

    private static final String TAG_ORIGIN = "ScreenOrigin";
    private static final String TAG_WIDTH = "ScreenWidth";
    private static final String TAG_HEIGHT = "ScreenHeight";
    private static final String TAG_COLUMN = "ScreenColumn";
    private static final String TAG_ROW = "ScreenRow";
    private static final String TAG_CAMERA = "Camera";
    private static final String TAG_ENABLED = "DisplayOn";

    /** Packed camera position meaning "nothing selected". */
    public static final long NO_CAMERA = 0L;

    // ------------------------------------------------------------------ state

    private BlockPos screenOrigin;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private int column;
    private int row;

    private long cameraKey = NO_CAMERA;
    private boolean enabled = true;

    public CameraMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(CCVirtualComsRegistry.CAMERA_MONITOR_BE.get(), pos, state);
        this.screenOrigin = pos.immutable();
    }

    // ------------------------------------------------------------------ screen layout

    /** The master (min-corner) block of this monitor's merged screen. */
    public BlockPos screenOrigin() {
        return this.screenOrigin;
    }

    /** Width of the merged screen in blocks (at least 1). */
    public int screenWidth() {
        return this.screenWidth;
    }

    /** Height of the merged screen in blocks (at least 1). */
    public int screenHeight() {
        return this.screenHeight;
    }

    /** Column of this tile inside the merged screen, 0 = viewer's left edge. */
    public int column() {
        return this.column;
    }

    /** Row of this tile inside the merged screen, 0 = bottom edge. */
    public int row() {
        return this.row;
    }

    /** {@code true} when this tile is the master holding the screen configuration. */
    public boolean isMaster() {
        return this.screenOrigin.equals(this.worldPosition);
    }

    /**
     * The master block entity of this screen, or {@code null} when it is not loaded (or not synced
     * to this client yet). Returns {@code this} for the master itself.
     */
    public @Nullable CameraMonitorBlockEntity master() {
        if (this.isMaster()) {
            return this;
        }
        Level level = this.getLevel();
        if (level == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(this.screenOrigin);
        return blockEntity instanceof CameraMonitorBlockEntity monitor && !monitor.isRemoved()
                ? monitor
                : null;
    }

    /** The master when it is available, otherwise this tile (used as a safe fallback). */
    public CameraMonitorBlockEntity masterOrSelf() {
        CameraMonitorBlockEntity master = this.master();
        return master == null ? this : master;
    }

    /**
     * Applies the result of a component flood fill.
     *
     * @return {@code true} when something changed and the block entity needs to be marked dirty
     *         and synchronised
     */
    public boolean applyScreenLayout(BlockPos origin, int width, int height, int newColumn,
                                     int newRow) {
        BlockPos safeOrigin = origin == null ? this.worldPosition : origin.immutable();
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        int safeColumn = Math.max(0, Math.min(safeWidth - 1, newColumn));
        int safeRow = Math.max(0, Math.min(safeHeight - 1, newRow));
        if (safeOrigin.equals(this.screenOrigin)
                && safeWidth == this.screenWidth
                && safeHeight == this.screenHeight
                && safeColumn == this.column
                && safeRow == this.row) {
            return false;
        }
        this.screenOrigin = safeOrigin;
        this.screenWidth = safeWidth;
        this.screenHeight = safeHeight;
        this.column = safeColumn;
        this.row = safeRow;
        return true;
    }

    // ------------------------------------------------------------------ screen configuration

    /** Packed camera position shown on this screen, or {@link #NO_CAMERA}. */
    public long cameraKey() {
        return this.cameraKey;
    }

    /** Whether the screen is switched on. */
    public boolean enabled() {
        return this.enabled;
    }

    /** Server side: replaces the configuration of this (master) tile. */
    public boolean applyMonitorConfig(long newCameraKey, boolean newEnabled) {
        if (newCameraKey == this.cameraKey && newEnabled == this.enabled) {
            return false;
        }
        this.cameraKey = newCameraKey;
        this.enabled = newEnabled;
        return true;
    }

    /** Persists the change and pushes the block entity data to watching clients. */
    public void commit() {
        this.setChanged();
        Level level = this.getLevel();
        if (level != null) {
            BlockState state = this.getBlockState();
            level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        Level level = this.getLevel();
        if (level != null && !level.isClientSide()) {
            // Also covers the placement path: the block entity is created after Block#onPlace, so
            // this is the first point where the new tile can take part in the flood fill.
            CameraMonitorMultiblock.rebuildAt(level, this.worldPosition);
        }
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong(TAG_ORIGIN, this.screenOrigin.asLong());
        tag.putInt(TAG_WIDTH, this.screenWidth);
        tag.putInt(TAG_HEIGHT, this.screenHeight);
        tag.putInt(TAG_COLUMN, this.column);
        tag.putInt(TAG_ROW, this.row);
        tag.putLong(TAG_CAMERA, this.cameraKey);
        tag.putBoolean(TAG_ENABLED, this.enabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_ORIGIN)) {
            this.screenOrigin = BlockPos.of(tag.getLong(TAG_ORIGIN));
        }
        this.screenWidth = Math.max(1, tag.getInt(TAG_WIDTH));
        this.screenHeight = Math.max(1, tag.getInt(TAG_HEIGHT));
        this.column = Math.max(0, Math.min(this.screenWidth - 1, tag.getInt(TAG_COLUMN)));
        this.row = Math.max(0, Math.min(this.screenHeight - 1, tag.getInt(TAG_ROW)));
        this.cameraKey = tag.getLong(TAG_CAMERA);
        this.enabled = !tag.contains(TAG_ENABLED) || tag.getBoolean(TAG_ENABLED);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
