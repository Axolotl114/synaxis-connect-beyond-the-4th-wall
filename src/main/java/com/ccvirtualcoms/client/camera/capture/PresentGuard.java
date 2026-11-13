package com.ccvirtualcoms.client.camera.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Keeps the capture frame off the player's screen without re-rendering the world.
 *
 * <p>With a shader pack the camera view has to be rendered into the main target. Rather than
 * re-rendering the player view afterwards (expensive and still visibly different for one frame),
 * this keeps a copy of the last fully presented frame and, on a capture frame, blits that copy back
 * into the main target after the GUI is drawn and before the window buffer swap. The player simply
 * sees the previous frame for that one frame - no camera flash - while the capture already holds
 * the shader-composed camera image.
 *
 * <p>Render thread only.
 */
public final class PresentGuard {

    private TextureTarget backup;
    private int width = -1;
    private int height = -1;
    private boolean valid;

    /**
     * Called once per frame after the GUI has been drawn, immediately before the buffer swap.
     *
     * @param minecraft        client instance
     * @param capturedMainTarget whether this frame rendered and captured the main render target
     */
    public void afterFrame(Minecraft minecraft, boolean capturedMainTarget) {
        RenderSystem.assertOnRenderThread();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || main.frameBufferId < 0) {
            return;
        }
        ensureBackup(main.width, main.height);
        if (this.backup == null) {
            return;
        }
        if (capturedMainTarget) {
            // The main target currently holds the camera view. Present the last player frame instead.
            if (this.valid) {
                blit(this.backup, main);
            }
            main.bindWrite(false);
        } else {
            blit(main, this.backup);
            this.valid = true;
            main.bindWrite(false);
        }
    }

    /** {@code true} when a previous full frame is available to present on a capture frame. */
    public boolean hasBackup() {
        return this.valid;
    }

    /** Releases the backup texture; render thread only. */
    public void reset() {
        if (this.backup != null) {
            if (RenderSystem.isOnRenderThread()) {
                this.backup.destroyBuffers();
            }
            this.backup = null;
        }
        this.valid = false;
        this.width = -1;
        this.height = -1;
    }

    private void ensureBackup(int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return;
        }
        if (this.backup != null && this.width == frameWidth && this.height == frameHeight) {
            return;
        }
        if (this.backup != null) {
            this.backup.destroyBuffers();
        }
        this.width = frameWidth;
        this.height = frameHeight;
        this.backup = new TextureTarget(frameWidth, frameHeight, false, Minecraft.ON_OSX);
        this.backup.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        this.valid = false;
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
