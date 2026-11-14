package com.ccvirtualcoms.client;

import com.ccvirtualcoms.client.gui.CameraMonitorScreen;
import com.ccvirtualcoms.content.camera.CameraMonitorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Client-side entry point for the camera monitor screen. */
public final class CameraMonitorClientHooks {

    private CameraMonitorClientHooks() {
    }

    /** Opens the configuration screen of the screen that contains {@code pos}. */
    public static void openScreen(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);
        if (blockEntity instanceof CameraMonitorBlockEntity monitor) {
            CameraMonitorBlockEntity master = monitor.masterOrSelf();
            minecraft.setScreen(new CameraMonitorScreen(master.getBlockPos()));
        }
    }
}
