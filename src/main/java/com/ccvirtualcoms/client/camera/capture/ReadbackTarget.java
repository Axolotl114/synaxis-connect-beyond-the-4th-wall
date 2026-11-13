package com.ccvirtualcoms.client.camera.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A small colour-only render target used to squeeze the main frame buffer down to capture size.
 *
 * <p>With Iris installed the final image only ever exists in the main render target, so the only
 * shader-correct capture path is: blit the main target into a small target on the GPU, then read the
 * small target back asynchronously. Doing the downscale with {@code glBlitFramebuffer} costs far less
 * than reading a full-window frame (up to 8 MiB) over PCIe per capture.
 *
 * <p>The source rectangle is aspect-cropped (never stretched), so captures keep the camera's field
 * of view proportions.
 *
 * <p>Render thread only.
 */
public final class ReadbackTarget implements AutoCloseable {

    private final TextureTarget target;
    private final int width;
    private final int height;
    private boolean closed;

    public ReadbackTarget(int width, int height) {
        RenderSystem.assertOnRenderThread();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.target = new TextureTarget(this.width, this.height, false, Minecraft.ON_OSX);
        this.target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    }

    /** Width of the downscaled target. */
    public int width() {
        return this.width;
    }

    /** Height of the downscaled target. */
    public int height() {
        return this.height;
    }

    /** Frame buffer id used by {@link PboFrameReader#requestFramebufferRead(int)}. */
    public int frameBufferId() {
        return this.target.frameBufferId;
    }

    /**
     * Blits (and aspect-crops) {@code source} into this target.
     *
     * @return {@code true} when the blit was issued
     */
    public boolean blitFrom(RenderTarget source) {
        if (this.closed || source == null) {
            return false;
        }
        RenderSystem.assertOnRenderThread();
        int sourceWidth = source.width;
        int sourceHeight = source.height;
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false;
        }
        // Aspect-crop the source so the capture is neither stretched nor letterboxed.
        double destinationAspect = (double) this.width / (double) this.height;
        double sourceAspect = (double) sourceWidth / (double) sourceHeight;
        int cropX = 0;
        int cropY = 0;
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        if (sourceAspect > destinationAspect) {
            cropWidth = (int) Math.round(sourceHeight * destinationAspect);
            cropX = (sourceWidth - cropWidth) / 2;
        } else if (sourceAspect < destinationAspect) {
            cropHeight = (int) Math.round(sourceWidth / destinationAspect);
            cropY = (sourceHeight - cropHeight) / 2;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(
                cropX, cropY, cropX + cropWidth, cropY + cropHeight,
                0, 0, this.width, this.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        return true;
    }

    /** Releases the GPU resources; must run on the render thread. */
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (RenderSystem.isOnRenderThread()) {
            this.target.destroyBuffers();
        }
    }

    @Override
    public String toString() {
        return "ReadbackTarget[" + this.width + "x" + this.height + "]";
    }
}
