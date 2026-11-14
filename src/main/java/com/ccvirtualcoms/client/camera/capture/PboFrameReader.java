package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.camera.api.CameraFrame;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;

/**
 * Asynchronous frame readback through two pixel buffer objects.
 *
 * <p>The naive readback ({@code glReadPixels} into a client array, {@code glGetTexImage}, or
 * {@link com.mojang.blaze3d.platform.NativeImage#downloadTexture}) stalls the render thread until
 * the GPU has finished the frame. Instead this class:
 *
 * <ol>
 *   <li>issues {@code glReadPixels} with {@link GL21#GL_PIXEL_PACK_BUFFER} bound, so the driver
 *       writes the pixels into a buffer object asynchronously;</li>
 *   <li>inserts a fence right after, recording when the copy completed on the GPU;</li>
 *   <li>polls later frames with a zero timeout and only maps the buffer once the fence signalled
 *       ({@code glClientWaitSync(..., 0L, 0L)});</li>
 *   <li>copies the mapped bytes row-flipped into a {@link CameraFrame}, because OpenGL's origin is
 *       bottom-left and frames are top-first.</li>
 * </ol>
 *
 * <p>Two buffers are enough: one in flight while the other is being mapped. The render thread never
 * blocks; a camera whose readback is still pending simply skips its next capture.
 *
 * <p>Render thread only.
 */
public final class PboFrameReader implements AutoCloseable {

    /** Number of pixel buffers (one in flight, one being drained). */
    private static final int BUFFER_COUNT = 2;

    private final int width;
    private final int height;
    private final int byteSize;

    private final int[] buffers = new int[BUFFER_COUNT];
    private final long[] fences = new long[BUFFER_COUNT];
    private final boolean[] inFlight = new boolean[BUFFER_COUNT];
    private final byte[][] framePool = new byte[3][];
    private int framePoolCursor;

    private int cursor;
    private boolean closed;
    private long issuedReads;
    private long completedReads;
    private long skippedReads;

    public PboFrameReader(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid readback size " + width + "x" + height);
        }
        RenderSystem.assertOnRenderThread();
        this.width = width;
        this.height = height;
        this.byteSize = width * height * 4;
        for (int index = 0; index < BUFFER_COUNT; index++) {
            this.buffers[index] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[index]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, this.byteSize, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /** Width of the readback rectangle in pixels. */
    public int width() {
        return this.width;
    }

    /** Height of the readback rectangle in pixels. */
    public int height() {
        return this.height;
    }

    /** Number of readbacks issued since creation. */
    public long issuedReads() {
        return this.issuedReads;
    }

    /** Number of readbacks completed and handed to the pipeline. */
    public long completedReads() {
        return this.completedReads;
    }

    /** Number of readbacks skipped because both buffers were still in flight. */
    public long skippedReads() {
        return this.skippedReads;
    }

    /** {@code true} while at least one buffer is waiting for its fence. */
    public boolean isBusy() {
        for (boolean pending : this.inFlight) {
            if (pending) {
                return true;
            }
        }
        return false;
    }

    /**
     * Issues an asynchronous read of {@code frameBufferId}'s colour attachment.
     *
     * <p>Returns {@code false} when both buffers are still in flight; the caller should skip this
     * frame instead of waiting.
     */
    public boolean requestFramebufferRead(int frameBufferId) {
        RenderSystem.assertOnRenderThread();
        if (this.closed) {
            return false;
        }
        int index = this.freeSlot();
        if (index < 0) {
            this.skippedReads++;
            return false;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[index]);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36160, frameBufferId);
        GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36160, 0);
        this.fences[index] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        this.inFlight[index] = true;
        this.issuedReads++;
        return true;
    }

    /**
     * Polls the pending fences without blocking.
     *
     * @param sequence     sequence number to stamp on the returned frame
     * @param captureNanos {@link System#nanoTime()} of the capture request
     * @return a top-first RGBA frame, or {@code null} when nothing is ready yet
     */
    public @Nullable CameraFrame poll(int sequence, long captureNanos) {
        RenderSystem.assertOnRenderThread();
        if (this.closed) {
            return null;
        }
        for (int index = 0; index < BUFFER_COUNT; index++) {
            if (!this.inFlight[index]) {
                continue;
            }
            long fence = this.fences[index];
            if (fence == 0L) {
                this.inFlight[index] = false;
                continue;
            }
            int status = GL32.glClientWaitSync(fence, 0, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                // Still pending (GL_TIMEOUT_EXPIRED) or failed; leave it in flight and try later.
                continue;
            }
            GL32.glDeleteSync(fence);
            this.fences[index] = 0L;
            this.inFlight[index] = false;
            CameraFrame frame = drain(index, sequence, captureNanos);
            if (frame != null) {
                this.completedReads++;
                return frame;
            }
        }
        return null;
    }

    private @Nullable CameraFrame drain(int index, int sequence, long captureNanos) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[index]);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        if (mapped == null) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            return null;
        }
        try {
            return flipToFrame(mapped, sequence, captureNanos);
        } finally {
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    /** Converts bottom-up RGBA bytes into a top-first RGBA {@link CameraFrame}. */
    private CameraFrame flipToFrame(ByteBuffer mapped, int sequence, long captureNanos) {
        byte[] rgba = this.framePool[this.framePoolCursor];
        if (rgba == null || rgba.length != this.byteSize) {
            rgba = new byte[this.byteSize];
            this.framePool[this.framePoolCursor] = rgba;
        }
        this.framePoolCursor = (this.framePoolCursor + 1) % this.framePool.length;
        int rowBytes = this.width * 4;
        for (int y = 0; y < this.height; y++) {
            int sourceRow = (this.height - 1 - y) * rowBytes;
            int destRow = y * rowBytes;
            for (int x = 0; x < rowBytes; x += 4) {
                int source = sourceRow + x;
                int dest = destRow + x;
                rgba[dest] = mapped.get(source);
                rgba[dest + 1] = mapped.get(source + 1);
                rgba[dest + 2] = mapped.get(source + 2);
                // The main target's alpha channel is often 0; frames are opaque.
                rgba[dest + 3] = (byte) 0xFF;
            }
        }
        return new CameraFrame(this.width, this.height, sequence, captureNanos, rgba);
    }

    private int freeSlot() {
        for (int attempt = 0; attempt < BUFFER_COUNT; attempt++) {
            int index = this.cursor;
            this.cursor = (this.cursor + 1) % BUFFER_COUNT;
            if (!this.inFlight[index]) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        for (int index = 0; index < BUFFER_COUNT; index++) {
            if (this.fences[index] != 0L) {
                GL32.glDeleteSync(this.fences[index]);
                this.fences[index] = 0L;
            }
            if (this.buffers[index] != 0) {
                GL15.glDeleteBuffers(this.buffers[index]);
                this.buffers[index] = 0;
            }
            this.inFlight[index] = false;
        }
    }

    @Override
    public String toString() {
        return "PboFrameReader[" + this.width + "x" + this.height + " issued=" + this.issuedReads
                + " completed=" + this.completedReads + " skipped=" + this.skippedReads + "]";
    }
}
