package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.camera.CameraResolution;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.camera.api.CameraFrameSource;
import org.jetbrains.annotations.Nullable;

/**
 * The frame source published to the registry.
 *
 * <p>Real captured frames win; until the render pipeline produces its first frame (or whenever the
 * selected path is degraded and explicitly allows it) the synthetic
 * {@link PlaceholderFrameSource} keeps the whole transport chain testable end to end.
 *
 * <p>Once a real frame has been produced, the placeholder is never mixed in again: a stalled capture
 * reports "capture stalled" through {@link #unavailableReason()} instead of silently showing
 * synthetic pixels, so failures stay visible.
 *
 * <p>{@link #publish(CameraFrame)} is called from the render thread; {@link #latestFrame()} is polled
 * from encoder workers. All mutable state is volatile.
 */
public final class LiveFrameSource implements CameraFrameSource {

    /** A real frame stays "fresh" for this long; afterwards the camera reports a stall. */
    private static final long REAL_FRAME_TIMEOUT_NANOS = 1_500_000_000L;

    private final PlaceholderFrameSource placeholder;

    private volatile CameraFrame realFrame;
    private volatile long realFrameNanos;
    private volatile long realFrameCount;
    private volatile String degradation = "";
    private volatile boolean allowPlaceholder = true;

    public LiveFrameSource(CameraResolution resolution, int fps, boolean color, boolean nightVision) {
        this.placeholder = new PlaceholderFrameSource(resolution, fps, color, nightVision);
    }

    /** Enables or disables production (real frames are still accepted while idle). */
    public void setActive(boolean active) {
        this.placeholder.setActive(active);
    }

    /** {@code true} when the camera is allowed to produce frames. */
    public boolean isActive() {
        return this.placeholder.isActive();
    }

    /** Publishes a real captured frame (render thread). */
    public void publish(CameraFrame frame) {
        if (frame == null) {
            return;
        }
        this.realFrame = frame;
        this.realFrameNanos = System.nanoTime();
        this.realFrameCount++;
    }

    /**
     * Marks the selected capture path as degraded and explains why; the reason is surfaced through
     * {@link #unavailableReason()} and therefore the GUI / MJPEG status detail.
     */
    public void setDegradation(String reason) {
        this.degradation = reason == null ? "" : reason;
    }

    /** The current degradation reason, or an empty string. */
    public String degradation() {
        return this.degradation;
    }

    /** Disables synthetic fallback frames (used once real capture works). */
    public void setAllowPlaceholder(boolean allow) {
        this.allowPlaceholder = allow;
    }

    /** Number of real frames published. */
    public long realFrameCount() {
        return this.realFrameCount;
    }

    /** {@link System#nanoTime()} of the last real frame, or {@code 0}. */
    public long lastRealFrameNanos() {
        return this.realFrameNanos;
    }

    /** The synthetic source, exposed for diagnostics and the debug dump. */
    PlaceholderFrameSource placeholder() {
        return this.placeholder;
    }

    @Override
    public @Nullable CameraFrame latestFrame() {
        CameraFrame frame = this.realFrame;
        if (frame != null && System.nanoTime() - this.realFrameNanos < REAL_FRAME_TIMEOUT_NANOS) {
            return frame;
        }
        if (this.realFrameCount == 0L && this.allowPlaceholder) {
            return this.placeholder.latestFrame();
        }
        return null;
    }

    @Override
    public boolean isProducing() {
        CameraFrame frame = this.realFrame;
        if (frame != null && System.nanoTime() - this.realFrameNanos < REAL_FRAME_TIMEOUT_NANOS) {
            return true;
        }
        return this.realFrameCount == 0L && this.allowPlaceholder && this.placeholder.isProducing();
    }

    @Override
    public String unavailableReason() {
        if (!this.degradation.isBlank()) {
            return this.degradation;
        }
        if (!this.placeholder.isActive()) {
            return this.placeholder.unavailableReason();
        }
        if (this.realFrameCount == 0L) {
            return "waiting for the first captured frame (placeholder frames shown)";
        }
        return "capture stalled: no frame for "
                + (System.nanoTime() - this.realFrameNanos) / 1_000_000L + " ms";
    }

    @Override
    public String toString() {
        return "LiveFrameSource[real=" + this.realFrameCount
                + " degradation=" + (this.degradation.isBlank() ? "-" : this.degradation) + "]";
    }
}
