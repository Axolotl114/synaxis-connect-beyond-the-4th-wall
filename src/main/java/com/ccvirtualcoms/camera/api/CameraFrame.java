package com.ccvirtualcoms.camera.api;

/**
 * One captured camera frame, in tightly packed RGBA (byte order R, G, B, A, row-major, top row
 * first).
 *
 * <p>Frames are immutable, so they can be handed between the render thread, the encoder thread and
 * the transport threads without copying or locking. A frame is dropped rather than queued when the
 * consumer is busy; the pipeline never blocks the render thread on an old frame.
 *
 * @param width        frame width in pixels
 * @param height       frame height in pixels
 * @param sequence     monotonically increasing capture counter, used to detect drops
 * @param captureNanos {@link System#nanoTime()} at capture
 * @param rgba         packed RGBA pixels, {@code width * height * 4} bytes
 */
public record CameraFrame(int width, int height, int sequence, long captureNanos, byte[] rgba) {

    public CameraFrame {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size " + width + "x" + height);
        }
        if (rgba == null || rgba.length != width * height * 4) {
            throw new IllegalArgumentException("RGBA buffer does not match " + width + "x" + height);
        }
    }

    /** Approximate size of the frame in bytes. */
    public int byteSize() {
        return this.rgba.length;
    }

    /** Reads one pixel's red/green/blue/alpha components, 0-255. */
    public int red(int x, int y) {
        return this.rgba[(y * this.width + x) * 4] & 0xFF;
    }

    /** Green channel of the pixel at (x, y). */
    public int green(int x, int y) {
        return this.rgba[(y * this.width + x) * 4 + 1] & 0xFF;
    }

    /** Blue channel of the pixel at (x, y). */
    public int blue(int x, int y) {
        return this.rgba[(y * this.width + x) * 4 + 2] & 0xFF;
    }

    /** Alpha channel of the pixel at (x, y). */
    public int alpha(int x, int y) {
        return this.rgba[(y * this.width + x) * 4 + 3] & 0xFF;
    }
}
