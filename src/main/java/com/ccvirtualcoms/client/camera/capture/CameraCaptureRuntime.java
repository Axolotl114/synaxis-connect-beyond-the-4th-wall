package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraPose;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side capture supervisor.
 *
 * <p>Responsibilities:
 *
 * <ol>
 *   <li>discover placed {@link CameraTransmitterBlockEntity} instances in the client level and keep
 *       one {@link CapturedCamera} per camera;</li>
 *   <li>refresh configuration, trigger state and pose once per client tick;</li>
 *   <li>publish each camera into
 *       {@link com.ccvirtualcoms.camera.api.CameraStreamRegistry} so the MJPEG/transport side can
 *       consume frames;</li>
 *   <li>remember the frame's partial tick so camera poses match the geometry the game renders.</li>
 * </ol>
 *
 * <p>Discovery is a chunk sweep instead of a chunk-load listener: the client keeps roughly
 * {@code (2r+1)^2} chunks loaded, a sweep every 10 ticks is negligible, and it cannot miss a camera
 * whose chunk was loaded before this mod's listeners were registered. Cameras outside the client
 * view distance are intentionally not streamed, because their chunks are not loaded and therefore
 * cannot be rendered anyway.
 *
 * <p>Everything here runs on the client thread. The frame sources handed to the registry are thread
 * safe for the encoder workers.
 */
public final class CameraCaptureRuntime {

    private static final CameraCaptureRuntime INSTANCE = new CameraCaptureRuntime();

    /** Ticks between chunk sweeps (10 ticks = twice per second). */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** Upper bound for the chunk sweep radius, independent of the render distance setting. */
    private static final int MAX_SCAN_RADIUS = 48;

    private final Map<Long, CapturedCamera> cameras = new LinkedHashMap<>();

    private int tickCounter;
    private long scanCount;
    private boolean forceProduce;
    private float partialTick = 1.0F;

    private CameraCaptureRuntime() {
    }

    /** The singleton. */
    public static CameraCaptureRuntime get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ tick / frame drivers

    /** Called once per client tick: discovery, refresh, registry publication. */
    public void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            if (!this.cameras.isEmpty()) {
                this.levelUnloaded();
            }
            return;
        }
        this.tickCounter++;
        if (this.tickCounter % SCAN_INTERVAL_TICKS == 0) {
            this.scan(minecraft, level);
        }
        this.refreshAll(level);
    }

    /**
     * Called once per rendered frame with the frame's partial tick.
     *
     * <p>The render-thread capture work itself is triggered by the {@code GameRenderer} mixin
     * (frame begin / after level render); this hook closes the frame by polling finished readbacks
     * and publishing them.
     */
    public void renderFrame(float partialTick) {
        if (Float.isFinite(partialTick) && partialTick >= 0.0F && partialTick <= 1.0F) {
            this.partialTick = partialTick;
        }
        CameraCapturePipeline.get().onFrameEnd();
    }

    /** Drops every camera and its registry entry when the client leaves the level. */
    public void levelUnloaded() {
        for (CapturedCamera camera : this.cameras.values()) {
            camera.dispose();
        }
        this.cameras.clear();
        this.forceProduce = false;
        this.tickCounter = 0;
        CameraCapturePipeline.get().reset();
    }

    // ------------------------------------------------------------------ discovery

    private void scan(Minecraft minecraft, ClientLevel level) {
        BlockPos center = minecraft.player == null
                ? BlockPos.ZERO
                : minecraft.player.blockPosition();
        int radius = Math.min(MAX_SCAN_RADIUS,
                Math.max(1, minecraft.options.getEffectiveRenderDistance() + 1));
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof CameraTransmitterBlockEntity camera) {
                        this.cameras.computeIfAbsent(camera.getBlockPos().asLong(),
                                key -> new CapturedCamera(camera.getBlockPos()));
                    }
                }
            }
        }
        this.scanCount++;
    }

    private void refreshAll(ClientLevel level) {
        Iterator<Map.Entry<Long, CapturedCamera>> iterator = this.cameras.entrySet().iterator();
        while (iterator.hasNext()) {
            CapturedCamera captured = iterator.next().getValue();
            BlockEntity blockEntity = level.getBlockEntity(captured.pos());
            if (!(blockEntity instanceof CameraTransmitterBlockEntity camera)
                    || camera.isRemoved()) {
                captured.dispose();
                iterator.remove();
                continue;
            }
            try {
                captured.refresh(level, camera, this.partialTick);
                captured.publishIfDirty();
            } catch (RuntimeException failure) {
                CCVirtualComs.LOGGER.warn("[camera] Failed to refresh camera {}: {}",
                        captured.pos(), failure.toString());
                captured.dispose();
                iterator.remove();
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    /** Every tracked camera, in discovery order, as an immutable snapshot. */
    public Collection<CapturedCamera> cameras() {
        return List.copyOf(this.cameras.values());
    }

    /** A camera by packed block position, or {@code null}. */
    @Nullable
    public CapturedCamera camera(long key) {
        return this.cameras.get(key);
    }

    /** Latest resolved pose of a camera, or {@code null}. */
    @Nullable
    public CameraPose poseOf(long key) {
        CapturedCamera camera = this.cameras.get(key);
        return camera == null ? null : camera.pose();
    }

    /** The partial tick of the frame currently being rendered. */
    public float partialTick() {
        return this.partialTick;
    }

    /** {@code true} while the debug force mode produces frames regardless of trigger state. */
    public boolean forceProduce() {
        return this.forceProduce;
    }

    /** Toggles the debug force mode and returns the new value. */
    public boolean toggleForceProduce() {
        this.forceProduce = !this.forceProduce;
        for (CapturedCamera camera : this.cameras.values()) {
            camera.markDirty();
        }
        return this.forceProduce;
    }

    /** Number of chunk sweeps performed since the level loaded. */
    public long scanCount() {
        return this.scanCount;
    }

    /** Number of tracked cameras. */
    public int size() {
        return this.cameras.size();
    }

    /** Frames produced since the level loaded, summed over all cameras. */
    public long totalFrames() {
        long total = 0L;
        for (CapturedCamera camera : this.cameras.values()) {
            if (camera.source() instanceof PlaceholderFrameSource source) {
                total += source.producedFrames();
            }
        }
        return total;
    }

    /** Human readable one-liner for the debug command and the status log. */
    public String describe() {
        return "cameras=" + this.cameras.size() + " frames=" + this.totalFrames()
                + " scans=" + this.scanCount + " partialTick=" + this.partialTick
                + " force=" + this.forceProduce;
    }

    /** Debug helper: forces a fresh frame on every camera and returns the produced frames. */
    public List<CameraFrameDumpEntry> dumpFrames() {
        List<CameraFrameDumpEntry> dumped = new ArrayList<>();
        for (CapturedCamera camera : this.cameras.values()) {
            var frame = camera.forceFrame();
            if (frame != null) {
                dumped.add(new CameraFrameDumpEntry(camera, frame));
            }
        }
        return dumped;
    }

    /** Pairing of a camera and the frame it produced for a debug dump. */
    public record CameraFrameDumpEntry(CapturedCamera camera,
                                       com.ccvirtualcoms.camera.api.CameraFrame frame) {
    }
}
