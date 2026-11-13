package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * Writes a {@link CameraFrame} to a PNG under {@code screenshots/}, for manual verification.
 *
 * <p>Debug only: it runs on the client thread, allocates a full {@link NativeImage} and writes a
 * file, so it must never be part of the capture path. The base directory is the game directory, so
 * a development run lands in {@code run/client/screenshots/}.
 */
public final class CameraPngDump {

    private CameraPngDump() {
    }

    /**
     * Writes one frame as {@code camera_<key>_<sequence>.png}.
     *
     * @return the written file, or {@code null} on failure
     */
    public static @Nullable File write(long key, CameraFrame frame) {
        if (frame == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        File directory = new File(minecraft.gameDirectory, "screenshots");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            CCVirtualComs.LOGGER.warn("[camera] Cannot create screenshot directory {}", directory);
            return null;
        }
        File output = new File(directory,
                "camera_" + Long.toHexString(key) + "_" + frame.sequence() + ".png");
        NativeImage image = new NativeImage(frame.width(), frame.height(), false);
        try {
            copyPixels(frame, image);
            image.writeToFile(output);
            return output;
        } catch (IOException | RuntimeException failure) {
            CCVirtualComs.LOGGER.warn("[camera] Failed to write {}", output, failure);
            return null;
        } finally {
            image.close();
        }
    }

    /**
     * Copies tightly packed RGBA bytes into an {@link NativeImage}.
     *
     * <p>{@code NativeImage#setPixelRGBA} takes a packed ABGR value (the memory layout of the image
     * is little-endian RGBA), so the channels are swapped here. Debug frames are not performance
     * critical, so a per-pixel loop is fine.
     */
    public static void copyPixels(CameraFrame frame, NativeImage image) {
        byte[] rgba = frame.rgba();
        int width = frame.width();
        int height = frame.height();
        for (int y = 0; y < height; y++) {
            int row = y * width * 4;
            for (int x = 0; x < width; x++) {
                int index = row + x * 4;
                int red = rgba[index] & 0xFF;
                int green = rgba[index + 1] & 0xFF;
                int blue = rgba[index + 2] & 0xFF;
                int alpha = rgba[index + 3] & 0xFF;
                image.setPixelRGBA(x, y, (alpha << 24) | (blue << 16) | (green << 8) | red);
            }
        }
    }
}
