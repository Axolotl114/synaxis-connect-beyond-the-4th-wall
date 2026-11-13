package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.camera.CameraStreamUrls;
import com.ccvirtualcoms.camera.api.CameraDeviceInfo;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.camera.api.CameraFrameSource;
import com.ccvirtualcoms.camera.api.CameraPose;
import com.ccvirtualcoms.camera.api.CameraStreamRegistry;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlock;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Per-camera client-side capture state.
 *
 * <p>One instance exists for every loaded {@link CameraTransmitterBlockEntity}. It owns the frame
 * source, the resolved camera pose and the registry entry, and it is only ever touched from the
 * client thread (the frame source itself is polled from encoder workers and is thread safe).
 *
 * <p>This class deliberately does not own the registry status text: the stream worker computes the
 * effective status (driver availability, frame freshness, encoder counters) and writes it back into
 * the registry. Re-registering therefore preserves the existing status/detail instead of resetting
 * it.
 */
public final class CapturedCamera {

    private final BlockPos pos;
    private final long key;

    private CameraConfig config = CameraConfig.DEFAULT;
    private String configSignature = "";
    private LiveFrameSource source;

    private String deviceName = "";
    private String streamUrl = "";
    private String statusDetail = "capture pipeline starting";
    private boolean streaming;

    private boolean dirty = true;
    @Nullable
    private CameraPoseResolver.ResolvedPose resolvedPose;
    private String poseDetail = "";
    private boolean onSubLevel;

    CapturedCamera(BlockPos pos) {
        this.pos = pos.immutable();
        this.key = pos.asLong();
    }

    // ------------------------------------------------------------------ identity

    /** Block position of the camera (plot space when on a sub-level). */
    public BlockPos pos() {
        return this.pos;
    }

    /** Packed block position used as the registry key. */
    public long key() {
        return this.key;
    }

    /** Current configuration snapshot. */
    public CameraConfig config() {
        return this.config;
    }

    /** The frame source published to the registry; {@code null} before the first refresh. */
    @Nullable
    public CameraFrameSource source() {
        return this.source;
    }

    /** Latest resolved pose, or {@code null} before the first refresh. */
    @Nullable
    public CameraPose pose() {
        return this.resolvedPose == null ? null : this.resolvedPose.pose();
    }

    /** Latest resolved transform (pose plus the rotation used for rendering), or {@code null}. */
    @Nullable
    public CameraPoseResolver.ResolvedPose resolvedPose() {
        return this.resolvedPose;
    }

    /** One-line pose diagnostics (sub-level name, axis modes, degradation reasons). */
    public String poseDetail() {
        return this.poseDetail;
    }

    /** {@code true} when the last pose was resolved against a Sable sub-level. */
    public boolean onSubLevel() {
        return this.onSubLevel;
    }

    /** {@code true} when the camera is currently allowed to produce frames. */
    public boolean streaming() {
        return this.streaming;
    }

    /** Human readable capture diagnostics for the debug command and the GUI. */
    public String describe() {
        LiveFrameSource frameSource = this.source;
        return "key=" + Long.toHexString(this.key)
                + " pos=" + this.pos.getX() + "," + this.pos.getY() + "," + this.pos.getZ()
                + " " + this.config.resolution().label() + "@" + this.config.fps() + "fps"
                + " mode=" + this.config.renderMode().serializedName()
                + " color=" + this.config.color()
                + " night=" + this.config.nightVision()
                + " streaming=" + this.streaming
                + " realFrames=" + (frameSource == null ? 0 : frameSource.realFrameCount())
                + " " + CameraCapturePipeline.get().statsOf(this.key)
                + " pose=[" + this.poseDetail + "]"
                + " sublevel=" + this.onSubLevel;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Refreshes configuration, trigger state, device information and pose from the block entity.
     *
     * <p>Called every client tick for every loaded camera, so it must stay allocation-light: the
     * frame source is only rebuilt when the configuration signature changes, and Sable's pose is
     * cached by the engine for a repeated partial tick.
     */
    void refresh(ClientLevel level, CameraTransmitterBlockEntity camera, float partialTick) {
        CameraConfig newConfig = camera.config() == null ? CameraConfig.DEFAULT : camera.config();
        String signature = newConfig.signature();
        if (this.source == null || !signature.equals(this.configSignature)) {
            if (this.source != null) {
                this.source.setActive(false);
            }
            this.source = new LiveFrameSource(newConfig.resolution(), newConfig.fps(),
                    newConfig.color(), newConfig.nightVision());
            this.config = newConfig;
            this.configSignature = signature;
            this.dirty = true;
        }

        String newDeviceName = camera.clientDeviceName();
        String newStreamUrl = camera.clientStreamUrl();
        if (newStreamUrl.isBlank()) {
            newStreamUrl = CameraStreamUrls.urlFor(this.key);
        }
        if (!newDeviceName.equals(this.deviceName) || !newStreamUrl.equals(this.streamUrl)) {
            this.deviceName = newDeviceName;
            this.streamUrl = newStreamUrl;
            this.dirty = true;
        }

        String newDetail = camera.statusDetail();
        if (!newDetail.equals(this.statusDetail)) {
            this.statusDetail = newDetail;
        }

        boolean wanted = camera.clientStatus() == CameraStreamStatus.STREAMING
                || (!newConfig.redstoneControl() && newConfig.holdOn())
                || CameraCaptureRuntime.get().forceProduce();
        if (wanted != this.streaming) {
            this.streaming = wanted;
            if (this.source != null) {
                this.source.setActive(wanted);
            }
            this.dirty = true;
        }

        Direction facing = level.getBlockState(this.pos)
                .getOptionalValue(CameraTransmitterBlock.FACING)
                .orElse(Direction.NORTH);
        this.resolvedPose = CameraPoseResolver.resolve(level, this.pos, facing,
                camera.eulerAngles(), newConfig, partialTick);
        this.poseDetail = this.resolvedPose.detail();
        this.onSubLevel = this.resolvedPose.onSubLevel();
    }

    /** Forces a registry re-publication on the next tick (used by debug toggles). */
    void markDirty() {
        this.dirty = true;
    }

    /** Publishes the registry entry when something changed; cheap no-op otherwise. */
    void publishIfDirty() {
        if (!this.dirty || this.source == null) {
            return;
        }
        this.dirty = false;
        CameraStreamRegistry.Entry previous = CameraStreamRegistry.get(this.key);
        CameraStreamStatus status = previous == null
                ? CameraStreamStatus.OFFLINE
                : previous.status();
        String detail = previous == null || previous.detail() == null || previous.detail().isBlank()
                ? this.statusDetail
                : previous.detail();
        int slot = parseDeviceSlot(this.deviceName);
        boolean driverAvailable = slot >= 0;
        CameraDeviceInfo device = new CameraDeviceInfo(
                slot,
                this.deviceName,
                driverAvailable,
                this.streamUrl,
                driverAvailable ? "" : "virtual camera driver not registered; MJPEG fallback only");
        CameraStreamRegistry.register(new CameraStreamRegistry.Entry(
                this.key, this.config, this.source, device, status, detail));
    }

    /** Polled by the stream worker (encoder thread); {@code null} while the camera is idle. */
    @Nullable
    public CameraFrame latestFrame() {
        LiveFrameSource frameSource = this.source;
        return frameSource == null ? null : frameSource.latestFrame();
    }

    /** Publishes a real captured frame; called on the render thread by the pipeline. */
    public void acceptCapturedFrame(CameraFrame frame) {
        LiveFrameSource frameSource = this.source;
        if (frameSource != null) {
            frameSource.publish(frame);
        }
    }

    /**
     * Forces one frame for the debug dump: a fresh real frame when capture works, otherwise a
     * synthetic frame so the dump can still prove the file-writing path.
     */
    @Nullable
    public CameraFrame forceFrame() {
        LiveFrameSource frameSource = this.source;
        if (frameSource == null) {
            return null;
        }
        CameraFrame real = frameSource.latestFrame();
        if (real != null) {
            return real;
        }
        return frameSource.placeholder().produceNow();
    }

    /** Releases any render resources; currently only the synthetic source. */
    void dispose() {
        CameraCapturePipeline.get().forget(this.key);
        if (this.source != null) {
            this.source.setActive(false);
            this.source = null;
        }
        CameraStreamRegistry.remove(this.key);
    }

    /** Parses the numeric suffix of "Synaxis Camera 3" into a zero-based device slot. */
    private static int parseDeviceSlot(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        int space = normalized.lastIndexOf(' ');
        if (space > 0 && space + 1 < normalized.length()) {
            try {
                return Math.max(0, Integer.parseInt(normalized.substring(space + 1)) - 1);
            } catch (NumberFormatException ignored) {
                // Fall through to a generic slot.
            }
        }
        return 0;
    }
}
