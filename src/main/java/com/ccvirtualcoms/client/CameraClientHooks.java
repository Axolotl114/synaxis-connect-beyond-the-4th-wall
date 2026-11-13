package com.ccvirtualcoms.client;

import com.ccvirtualcoms.client.gui.CameraTransmitterScreen;
import com.ccvirtualcoms.content.camera.CameraLocator;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Client-side entry points for the camera transmitter screen. */
public final class CameraClientHooks {

    private CameraClientHooks() {
    }

    /** Opens the configuration screen for the camera at {@code pos} (main level or Sable plot). */
    public static void openConfigScreen(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        CameraTransmitterBlockEntity camera = CameraLocator.find(minecraft.level, pos);
        if (camera != null) {
            minecraft.setScreen(new CameraTransmitterScreen(camera));
        }
    }

    /** Returns the camera block entity at {@code pos}, if the client has one. */
    public static @Nullable CameraTransmitterBlockEntity cameraAt(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return CameraLocator.find(minecraft.level, pos);
    }
}
