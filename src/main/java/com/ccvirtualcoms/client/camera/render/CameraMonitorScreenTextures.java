package com.ccvirtualcoms.client.camera.render;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Per-screen dynamic screen textures.
 *
 * <p>One {@link DynamicTexture} exists for every merged monitor screen, keyed by the packed position
 * of the screen's master. The camera frame is letterboxed into the texture (aspect fit with black
 * bars) so the renderer can map the square texture across the wall without distorting the picture.
 *
 * <p>Everything here runs on the render thread: the block entity renderer creates and updates the
 * textures, and disconnection cleanup is bounced back to the render thread through
 * {@link RenderSystem#recordRenderCall}.
 *
 * <p>Updates are throttled and latest-wins: a new frame is only copied when the previous upload is a
 * few ticks old, so several monitors on one wall never upload more often than the display needs.
 */
public final class CameraMonitorScreenTextures {

    /** Edge length of every screen texture, in pixels. */
    public static final int TEXTURE_SIZE = 512;

    /** Minimum time between two uploads of the same screen, in nanoseconds. */
    private static final long MIN_UPLOAD_INTERVAL_NANOS = 50_000_000L;

    /** Opaque black, used for the letterbox bars and the "no signal" screen. */
    private static final int OPAQUE_BLACK = 0xFF000000;

    private static final Map<Long, ScreenTexture> TEXTURES = new HashMap<>();

    private CameraMonitorScreenTextures() {
    }

    /** Returns the (lazily created) texture of one screen. */
    public static ScreenTexture get(long screenKey) {
        ScreenTexture texture = TEXTURES.get(screenKey);
        if (texture == null || !texture.isValid()) {
            if (texture != null) {
                texture.dispose();
            }
            texture = new ScreenTexture(screenKey);
            TEXTURES.put(screenKey, texture);
        }
        return texture;
    }

    /** Releases every screen texture; called when the client leaves a world. */
    public static void clearAll() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(CameraMonitorScreenTextures::clearAll);
            return;
        }
        for (ScreenTexture texture : TEXTURES.values()) {
            texture.dispose();
        }
        TEXTURES.clear();
    }

    /**
     * One screen texture.
     *
     * <p>The camera image is drawn into a centred rectangle whose aspect ratio matches the merged
     * wall: when the wall is wider than the camera image the rectangle is full width and shorter
     * than the texture (and the rest stays black), and vice versa.
     */
    public static final class ScreenTexture {

        private final ResourceLocation id;
        private final DynamicTexture texture;

        private int layoutWidth = -1;
        private int layoutHeight = -1;
        private int imageX;
        private int imageY;
        private int imageWidth;
        private int imageHeight;

        private CameraFrame lastFrame;
        private long lastUploadNanos;
        private boolean blank = true;

        private ScreenTexture(long screenKey) {
            this.id = ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID,
                    "dynamic/camera_monitor_" + Long.toHexString(screenKey));
            this.texture = new DynamicTexture(TEXTURE_SIZE, TEXTURE_SIZE, true);
            Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
            this.blackOut();
            this.texture.upload();
        }

        /** Resource location to hand to a render type. */
        public ResourceLocation id() {
            return this.id;
        }

        /** {@code false} after the texture manager closed the texture (resource reload/world exit). */
        public boolean isValid() {
            return this.texture.getPixels() != null;
        }

        /**
         * Copies a camera frame into this texture.
         *
         * @param frame       the latest RGBA frame of the selected camera
         * @param screenWidth width of the merged screen in blocks
         * @param screenHeight height of the merged screen in blocks
         */
        public void update(CameraFrame frame, int screenWidth, int screenHeight) {
            if (frame == null) {
                this.clear();
                return;
            }
            NativeImage pixels = this.texture.getPixels();
            if (pixels == null) {
                return;
            }
            int safeWidth = Math.max(1, screenWidth);
            int safeHeight = Math.max(1, screenHeight);
            long now = System.nanoTime();
            boolean layoutChanged = safeWidth != this.layoutWidth || safeHeight != this.layoutHeight;
            if (!layoutChanged && frame == this.lastFrame) {
                return;
            }
            if (!layoutChanged && now - this.lastUploadNanos < MIN_UPLOAD_INTERVAL_NANOS) {
                return;
            }

            this.layoutWidth = safeWidth;
            this.layoutHeight = safeHeight;
            int previousX = this.imageX;
            int previousY = this.imageY;
            int previousWidth = this.imageWidth;
            int previousHeight = this.imageHeight;
            this.computeImageRect(frame.width(), frame.height());
            if (layoutChanged || previousX != this.imageX || previousY != this.imageY
                    || previousWidth != this.imageWidth || previousHeight != this.imageHeight) {
                this.blackOut();
            }

            byte[] rgba = frame.rgba();
            int frameWidth = frame.width();
            int frameHeight = frame.height();
            for (int y = 0; y < this.imageHeight; y++) {
                int sourceY = (int) ((long) y * frameHeight / this.imageHeight);
                int sourceRow = sourceY * frameWidth * 4;
                for (int x = 0; x < this.imageWidth; x++) {
                    int sourceX = (int) ((long) x * frameWidth / this.imageWidth);
                    int index = sourceRow + sourceX * 4;
                    int red = rgba[index] & 0xFF;
                    int green = rgba[index + 1] & 0xFF;
                    int blue = rgba[index + 2] & 0xFF;
                    int alpha = rgba[index + 3] & 0xFF;
                    pixels.setPixelRGBA(this.imageX + x, this.imageY + y,
                            (alpha << 24) | (blue << 16) | (green << 8) | red);
                }
            }
            this.texture.upload();
            this.lastFrame = frame;
            this.lastUploadNanos = now;
            this.blank = false;
        }

        /** Switches the screen to opaque black (disabled, no camera or no frame). */
        public void clear() {
            if (this.blank) {
                return;
            }
            NativeImage pixels = this.texture.getPixels();
            if (pixels == null) {
                return;
            }
            this.blackOut();
            this.texture.upload();
            this.blank = true;
            this.lastFrame = null;
            this.lastUploadNanos = System.nanoTime();
        }

        private void computeImageRect(int frameWidth, int frameHeight) {
            float wallAspect = (float) this.layoutWidth / (float) this.layoutHeight;
            float frameAspect = (float) frameWidth / (float) frameHeight;
            if (frameAspect >= wallAspect) {
                // Camera image is wider than the wall: fit the width, bars above and below.
                this.imageWidth = TEXTURE_SIZE;
                this.imageHeight = Math.max(1,
                        Math.round(TEXTURE_SIZE * wallAspect / frameAspect));
            } else {
                // Camera image is taller than the wall: fit the height, bars left and right.
                this.imageHeight = TEXTURE_SIZE;
                this.imageWidth = Math.max(1,
                        Math.round(TEXTURE_SIZE * frameAspect / wallAspect));
            }
            this.imageX = (TEXTURE_SIZE - this.imageWidth) / 2;
            this.imageY = (TEXTURE_SIZE - this.imageHeight) / 2;
        }

        private void blackOut() {
            NativeImage pixels = this.texture.getPixels();
            if (pixels != null) {
                pixels.fillRect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE, OPAQUE_BLACK);
            }
        }

        private void dispose() {
            Minecraft.getInstance().getTextureManager().release(this.id);
            this.texture.close();
        }
    }

    /** Releases the dynamic screen textures when the client disconnects from a world. */
    @EventBusSubscriber(modid = CCVirtualComs.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.GAME)
    public static final class ClientEvents {

        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            clearAll();
        }
    }
}
