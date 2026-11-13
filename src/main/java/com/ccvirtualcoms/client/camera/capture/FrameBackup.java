package com.ccvirtualcoms.client.camera.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Saves the player's finished frame before a camera capture borrows the main render target, and
 * puts it back afterwards. The swap happens after {@code RenderFrameEvent.Post}, so the player
 * always sees their own frame; the camera view is only ever an intermediate image.
 *
 * <p>Colour only: depth/stencil are not read after the player frame is finished, and the next frame
 * clears them anyway.
 *
 * <p>Render thread only.
 */
public final class FrameBackup {

    private TextureTarget backup;
    private int width = -1;
    private int height = -1;
    private boolean hasFrame;

    /** {@code true} when a complete player frame has been saved. */
    public boolean hasFrame() {
        return this.hasFrame;
    }

    /** Copies the current main render target into the backup texture. */
    public void save(Minecraft minecraft) {
        RenderSystem.assertOnRenderThread();
        this.hasFrame = false;
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || main.frameBufferId < 0) {
            return;
        }
        ensure(main.width, main.height);
        if (this.backup == null) {
            return;
        }
        blit(main, this.backup);
        this.hasFrame = true;
    }

    /** Copies the saved frame back into the main render target. */
    public void restore(Minecraft minecraft) {
        RenderSystem.assertOnRenderThread();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || this.backup == null || !sized(main.width, main.height)) {
            if (main != null) {
                main.bindWrite(false);
            }
            return;
        }
        blit(this.backup, main);
        main.bindWrite(false);
    }

    /** Releases the backup texture; render thread only. */
    public void reset() {
        if (this.backup != null) {
            if (RenderSystem.isOnRenderThread()) {
                this.backup.destroyBuffers();
            }
            this.backup = null;
        }
        this.width = -1;
        this.height = -1;
        this.hasFrame = false;
    }

    private boolean sized(int frameWidth, int frameHeight) {
        return this.backup != null && this.width == frameWidth && this.height == frameHeight;
    }

    private void ensure(int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return;
        }
        if (sized(frameWidth, frameHeight)) {
            return;
        }
        if (this.backup != null) {
            this.backup.destroyBuffers();
        }
        this.width = frameWidth;
        this.height = frameHeight;
        this.backup = new TextureTarget(frameWidth, frameHeight, false, Minecraft.ON_OSX);
        this.backup.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    }

    private static void blit(RenderTarget source, RenderTarget destination) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GlStateManager._glBlitFrameBuffer(
                0, 0, source.width, source.height,
                0, 0, destination.width, destination.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
    }
}
