package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.camera.CameraResolution;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.camera.api.CameraFrameSource;
import org.jetbrains.annotations.Nullable;

/**
 * A synthetic frame source used until the render pipeline lands.
 *
 * <p>It exists to prove the whole chain (registry -> stream worker -> MJPEG -> external consumer)
 * without touching OpenGL: the frames are a moving gradient with a scanning bar and a small
 * sequence counter rendered along the bottom edge, so a consumer can see that frames advance and
 * whether any were dropped.
 *
 * <p>Colour and night-vision are intentionally <em>not</em> applied here: the encoding layer owns
 * them (see {@code MjpegEncoder#lookDescription}), so the render side only ever produces plain
 * RGBA. The fields are still carried so diagnostics can report what the encoder should do.
 *
 * <p>Production is poll driven: {@link #latestFrame()} generates a frame when the configured
 * interval elapsed, so an idle camera costs nothing. {@link #produceNow()} forces a frame for the
 * debug dump command.
 */
public final class PlaceholderFrameSource implements CameraFrameSource {

    /** How long after the last poll the source still counts as producing. */
    private static final long PRODUCING_WINDOW_NANOS = 2_000_000_000L;

    private final int width;
    private final int height;
    private final int fps;
    private final boolean color;
    private final boolean nightVision;

    private volatile boolean active;
    private volatile CameraFrame latest;
    private volatile long lastProduceNanos;
    private volatile long lastPollNanos;

    private int sequence;
    private long producedFrames;

    public PlaceholderFrameSource(CameraResolution resolution, int fps, boolean color,
                                  boolean nightVision) {
        CameraResolution safe = resolution == null ? CameraResolution.DEFAULT : resolution;
        this.width = safe.width();
        this.height = safe.height();
        this.fps = Math.max(1, Math.min(240, fps));
        this.color = color;
        this.nightVision = nightVision;
    }

    /** Enables or disables production; consumers keep the last frame while idle. */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** {@code true} when the camera is allowed to produce frames. */
    public boolean isActive() {
        return this.active;
    }

    /** Configured (or currently effective) frame rate. */
    public int fps() {
        return this.fps;
    }

    /** Configured colour flag; the encoder applies it. */
    public boolean color() {
        return this.color;
    }

    /** Configured night-vision flag; the encoder applies it. */
    public boolean nightVision() {
        return this.nightVision;
    }

    /** Number of frames generated so far (diagnostics). */
    public long producedFrames() {
        return this.producedFrames;
    }

    /** {@link System#nanoTime()} of the last produced frame, or {@code 0}. */
    public long lastProduceNanos() {
        return this.lastProduceNanos;
    }

    @Override
    public @Nullable CameraFrame latestFrame() {
        long now = System.nanoTime();
        this.lastPollNanos = now;
        if (!this.active) {
            return null;
        }
        CameraFrame frame = this.latest;
        long interval = 1_000_000_000L / this.fps;
        if (frame == null || now - this.lastProduceNanos >= interval) {
            frame = produce(now);
        }
        return frame;
    }

    /** Forces one frame regardless of the frame rate, for the debug dump. */
    public CameraFrame produceNow() {
        return produce(System.nanoTime());
    }

    @Override
    public boolean isProducing() {
        return this.active && this.latest != null
                && System.nanoTime() - this.lastProduceNanos < PRODUCING_WINDOW_NANOS;
    }

    @Override
    public String unavailableReason() {
        if (!this.active) {
            return "camera idle: the server reports the stream as offline";
        }
        if (this.latest == null) {
            return "waiting for the first capture frame";
        }
        return "";
    }

    private synchronized CameraFrame produce(long nowNanos) {
        byte[] rgba = renderGradient(nowNanos);
        CameraFrame frame = new CameraFrame(this.width, this.height, ++this.sequence, nowNanos, rgba);
        this.latest = frame;
        this.lastProduceNanos = nowNanos;
        this.producedFrames++;
        return frame;
    }

    /**
     * Cheap synthetic image: a diagonal colour ramp that scrolls, a bright scanning bar, and a
     * bottom-edge progress bar showing {@code sequence % 64}.
     */
    private byte[] renderGradient(long nowNanos) {
        int w = this.width;
        int h = this.height;
        byte[] data = new byte[w * h * 4];
        long millis = nowNanos / 1_000_000L;
        int phase = (int) ((millis / 8L) & 0xFF);
        int xStep = Math.max(1, 255 / Math.max(1, w));
        int yStep = Math.max(1, 255 / Math.max(1, h));
        int barX = (int) ((millis / 16L) % Math.max(1, w));
        int barWidth = Math.max(1, w / 160);
        int healthRows = Math.min(4, h);
        int healthEnd = h - healthRows;
        int healthBlocks = this.sequence % 64;
        int barValue = w <= 0 ? 0 : (int) ((long) healthBlocks * w / 64L);

        for (int y = 0; y < h; y++) {
            int index = y * w * 4;
            if (y >= healthEnd) {
                for (int x = 0; x < w; x++) {
                    byte value = (byte) (x < barValue ? 0xE0 : 0x30);
                    data[index++] = value;
                    data[index++] = value;
                    data[index++] = value;
                    data[index++] = (byte) 0xFF;
                }
                continue;
            }
            int rowBase = (y * yStep + phase) & 0xFF;
            for (int x = 0; x < w; x++) {
                int v = (rowBase + x * xStep) & 0xFF;
                int r;
                int g;
                int b;
                if (v < 85) {
                    r = 0;
                    g = v * 3;
                    b = 255 - v * 3;
                } else if (v < 170) {
                    r = (v - 85) * 3;
                    g = 255;
                    b = 255 - (v - 85) * 3;
                } else {
                    r = 255;
                    g = 255 - (v - 170) * 3;
                    b = 0;
                }
                if (x >= barX && x < barX + barWidth) {
                    r = 255;
                    g = 255;
                    b = 255;
                }
                data[index++] = (byte) r;
                data[index++] = (byte) g;
                data[index++] = (byte) b;
                data[index++] = (byte) 0xFF;
            }
        }
        return data;
    }

    @Override
    public String toString() {
        return "PlaceholderFrameSource[" + this.width + "x" + this.height + "@" + this.fps
                + "fps active=" + this.active + " seq=" + this.sequence + "]";
    }
}
