package com.ccvirtualcoms.client;

import com.ccvirtualcoms.client.gui.CameraTransmitterScreen;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Client-side entry points for the camera transmitter screen. */
public final class CameraClientHooks {

    private CameraClientHooks() {
    }

    /** Opens the configuration screen for the camera at {@code pos}. */
    public static void openConfigScreen(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (minecraft.level.getBlockEntity(pos) instanceof CameraTransmitterBlockEntity camera) {
            minecraft.setScreen(new CameraTransmitterScreen(camera));
        }
    }

    /** Returns the camera block entity at {@code pos}, if the client level has one. */
    public static @Nullable CameraTransmitterBlockEntity cameraAt(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(pos) instanceof CameraTransmitterBlockEntity camera
                ? camera
                : null;
    }
}
