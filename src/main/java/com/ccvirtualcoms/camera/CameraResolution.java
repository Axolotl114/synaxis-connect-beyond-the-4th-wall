package com.ccvirtualcoms.camera;

import java.util.List;

/**
 * The output frame size of a camera transmitter.
 *
 * <p>The GUI offers a fixed set of well-known sizes (both 4:3 and 16:9) plus a custom
 * width/height entry. Everything is normalised through {@link #sanitized()} so the server can
 * trust a client snapshot without trusting the client.
 *
 * @param width  frame width in pixels
 * @param height frame height in pixels
 */
public record CameraResolution(int width, int height) {

    /** Lower bound for either dimension. Odd sizes are rejected by the JPEG encoder path. */
    public static final int MIN_DIMENSION = 64;

    /** Upper bound for either dimension. */
    public static final int MAX_WIDTH = 1920;

    /** Upper bound for the height. 4:3 presets go up to 1440. */
    public static final int MAX_HEIGHT = 1440;

    /**
     * The budget cap, expressed as {@code width * height * fps}. 1280x720 at 30 fps is the
     * reference workload; anything above it is clamped by the server.
     */
    public static final long PIXEL_BUDGET_PER_SECOND = 1280L * 720L * 30L;

    /** 4:3 presets offered by the GUI. */
    public static final List<CameraResolution> PRESETS_4_3 = List.of(
            new CameraResolution(480, 360),
            new CameraResolution(640, 480),
            new CameraResolution(800, 600),
            new CameraResolution(960, 720),
            new CameraResolution(1024, 768),
            new CameraResolution(1280, 960),
            new CameraResolution(1440, 1080),
            new CameraResolution(1600, 1200),
            new CameraResolution(1920, 1440));

    /** 16:9 presets offered by the GUI. */
    public static final List<CameraResolution> PRESETS_16_9 = List.of(
            new CameraResolution(640, 360),
            new CameraResolution(854, 480),
            new CameraResolution(960, 540),
            new CameraResolution(1280, 720),
            new CameraResolution(1600, 900),
            new CameraResolution(1920, 1080));

    /** Every preset, 4:3 first, in GUI order. */
    public static final List<CameraResolution> PRESETS = java.util.stream.Stream
            .concat(PRESETS_4_3.stream(), PRESETS_16_9.stream())
            .toList();

    /** The default output size. */
    public static final CameraResolution DEFAULT = new CameraResolution(640, 480);

    public CameraResolution {
        width = Math.max(MIN_DIMENSION, Math.min(MAX_WIDTH, width & ~1));
        height = Math.max(MIN_DIMENSION, Math.min(MAX_HEIGHT, height & ~1));
    }

    /** The frame rate values the GUI exposes. */
    public static final int[] FPS_VALUES = {15, 20, 24, 30, 45, 60};

    /** {@code width * height * fps}, the cost metric used by the global frame budget. */
    public long pixelRate(int fps) {
        return (long) this.width * this.height * Math.max(1, fps);
    }

    /** {@code true} when this size fits the per-camera pixel budget at {@code fps}. */
    public boolean fitsBudget(int fps) {
        return this.pixelRate(fps) <= PIXEL_BUDGET_PER_SECOND;
    }

    /** The closest preset to this size, or {@code null} for a custom size. */
    public CameraResolution presetOrNull() {
        for (CameraResolution preset : PRESETS) {
            if (preset.equals(this)) {
                return preset;
            }
        }
        return null;
    }

    /** {@code true} when this is one of the fixed GUI presets. */
    public boolean isPreset() {
        return this.presetOrNull() != null;
    }

    /** A human readable "640x480" label. */
    public String label() {
        return this.width + "x" + this.height;
    }

    /** Clamps a requested size into the legal range. */
    public static CameraResolution sanitized(int width, int height) {
        return new CameraResolution(width, height);
    }

    /**
     * Clamps a requested size into the legal range.
     *
     * <p>The {@code width * height * fps} budget is enforced by the frame scheduler (which reduces
     * the effective frame rate and logs the degradation) rather than by silently shrinking the
     * user's chosen resolution; this method only guarantees that the stored size is legal and
     * even.
     */
    public static CameraResolution sanitizedForFps(int width, int height, int fps) {
        return new CameraResolution(width, height);
    }
}
