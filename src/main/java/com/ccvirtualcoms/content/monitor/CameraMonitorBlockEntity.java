package com.ccvirtualcoms.content.monitor;

import com.ccvirtualcoms.content.CCVirtualComsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Stores the selected camera for a merged monitor wall. */
public class CameraMonitorBlockEntity extends BlockEntity {

    private BlockPos cameraPos;
    private boolean enabled = true;
    private double projectorWidth = 3.0D;
    private double projectorHeight = 2.0D;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double yawDegrees;
    private double pitchDegrees;
    private int opacity = 100;

    private boolean screenDirty = true;
    private BlockPos screenMaster;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private int screenColumn;
    private int screenRow;

    private static final java.util.Set<CameraMonitorBlockEntity> CLIENT_RECEIVERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static java.util.Set<CameraMonitorBlockEntity> clientReceivers() {
        return CLIENT_RECEIVERS;
    }

    public CameraMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(CCVirtualComsRegistry.CAMERA_MONITOR_BE.get(), pos, state);
    }

    public @Nullable BlockPos cameraPos() {
        return this.cameraPos;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public double projectorWidth() { return this.projectorWidth; }
    public double projectorHeight() { return this.projectorHeight; }
    public double offsetX() { return this.offsetX; }
    public double offsetY() { return this.offsetY; }
    public double offsetZ() { return this.offsetZ; }
    public double yawDegrees() { return this.yawDegrees; }
    public double pitchDegrees() { return this.pitchDegrees; }
    public int opacity() { return this.opacity; }

    public void applyTransform(double width, double height, double dx, double dy, double dz,
                               double yaw, double pitch, int alpha) {
        this.projectorWidth = Math.max(0.4D, Math.min(64.0D, width));
        this.projectorHeight = Math.max(0.3D, Math.min(36.0D, height));
        this.offsetX = Math.max(-64.0D, Math.min(64.0D, dx));
        this.offsetY = Math.max(-64.0D, Math.min(64.0D, dy));
        this.offsetZ = Math.max(-64.0D, Math.min(64.0D, dz));
        this.yawDegrees = yaw;
        this.pitchDegrees = Math.max(-90.0D, Math.min(90.0D, pitch));
        this.opacity = Math.max(0, Math.min(100, alpha));
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    public void apply(BlockPos camera, boolean on) {
        this.cameraPos = camera;
        this.enabled = on;
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    // ---------------------------------------------------------------- screen merging
    public void markScreenDirty() {
        this.screenDirty = true;
    }

    public BlockPos screenMaster() {
        this.ensureScreen();
        return this.screenMaster;
    }

    public int screenWidth() {
        return this.screenWidth;
    }

    public int screenHeight() {
        return this.screenHeight;
    }

    public int screenColumn() {
        return this.screenColumn;
    }

    public int screenRow() {
        return this.screenRow;
    }

    /** The master block entity of this monitor wall (may be itself). */
    public @Nullable CameraMonitorBlockEntity master() {
        this.ensureScreen();
        if (this.screenMaster == null || this.level == null) {
            return null;
        }
        BlockEntity blockEntity = this.level.getBlockEntity(this.screenMaster);
        return blockEntity instanceof CameraMonitorBlockEntity monitor ? monitor : null;
    }

    private void ensureScreen() {
        if (!this.screenDirty || this.level == null) {
            return;
        }
        this.screenDirty = false;
        Direction facing = this.getBlockState().getValue(CameraMonitorBlock.FACING);
        Direction right = facing.getClockWise();
        java.util.Map<BlockPos, Boolean> seen = new java.util.HashMap<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(this.worldPosition);
        seen.put(this.worldPosition, true);
        int minA = 0;
        int maxA = 0;
        int minB = 0;
        int maxB = 0;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            int a = axisA(pos, right);
            int b = pos.getY();
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
            for (Direction direction : new Direction[]{
                    Direction.UP, Direction.DOWN, right, right.getOpposite()}) {
                BlockPos next = pos.relative(direction);
                if (seen.containsKey(next)) {
                    continue;
                }
                if (this.level.getBlockState(next).getBlock() == this.getBlockState().getBlock()
                        && this.level.getBlockState(next).getValue(CameraMonitorBlock.FACING) == facing) {
                    seen.put(next, true);
                    queue.add(next);
                }
            }
        }
        BlockPos master = null;
        for (BlockPos pos : seen.keySet()) {
            if (axisA(pos, right) == minA && pos.getY() == minB) {
                master = pos;
                break;
            }
        }
        this.screenMaster = master == null ? this.worldPosition : master;
        this.screenWidth = Math.max(1, maxA - minA + 1);
        this.screenHeight = Math.max(1, maxB - minB + 1);
        this.screenColumn = axisA(this.worldPosition, right) - minA;
        this.screenRow = maxB - this.worldPosition.getY();
    }

    private static int axisA(BlockPos pos, Direction right) {
        return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
    }

    // ---------------------------------------------------------------- persistence
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.cameraPos != null) {
            tag.putLong("CameraPos", this.cameraPos.asLong());
        }
        tag.putBoolean("Enabled", this.enabled);
        tag.putDouble("ScreenWidth", this.projectorWidth);
        tag.putDouble("ScreenHeight", this.projectorHeight);
        tag.putDouble("OffsetX", this.offsetX);
        tag.putDouble("OffsetY", this.offsetY);
        tag.putDouble("OffsetZ", this.offsetZ);
        tag.putDouble("Yaw", this.yawDegrees);
        tag.putDouble("Pitch", this.pitchDegrees);
        tag.putInt("Opacity", this.opacity);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.cameraPos = tag.contains("CameraPos") ? BlockPos.of(tag.getLong("CameraPos")) : null;
        this.enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        this.projectorWidth = tag.contains("ScreenWidth") ? tag.getDouble("ScreenWidth") : 3.0D;
        this.projectorHeight = tag.contains("ScreenHeight") ? tag.getDouble("ScreenHeight") : 2.0D;
        this.offsetX = tag.contains("OffsetX") ? tag.getDouble("OffsetX") : 0.0D;
        this.offsetY = tag.contains("OffsetY") ? tag.getDouble("OffsetY") : 0.0D;
        this.offsetZ = tag.contains("OffsetZ") ? tag.getDouble("OffsetZ") : 0.0D;
        this.yawDegrees = tag.contains("Yaw") ? tag.getDouble("Yaw") : 0.0D;
        this.pitchDegrees = tag.contains("Pitch") ? tag.getDouble("Pitch") : 0.0D;
        this.opacity = tag.contains("Opacity") ? tag.getInt("Opacity") : 100;
        this.screenDirty = true;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        this.saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        this.screenDirty = true;
        if (this.level != null && this.level.isClientSide()) {
            CLIENT_RECEIVERS.add(this);
        }
    }

    @Override
    public void setRemoved() {
        CLIENT_RECEIVERS.remove(this);
        super.setRemoved();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        this.screenDirty = true;
    }
}
