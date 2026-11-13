package com.ccvirtualcoms.client;

import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import com.ccvirtualcoms.net.CameraStatusPayload;
import net.minecraft.client.Minecraft;

/**
 * Client-only handler for {@link CameraStatusPayload}.
 *
 * <p>Kept out of the payload class itself: the payload is loaded on the dedicated server during
 * payload registration, and a direct reference to {@code net.minecraft.client.Minecraft} there
 * would make the server crash with "invalid dist DEDICATED_SERVER".
 */
public final class CameraStatusClientHooks {

    private CameraStatusClientHooks() {
    }

    /** Applies a server status snapshot to the client-side block entity. */
    public static void handle(CameraStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (minecraft.level.getBlockEntity(payload.pos()) instanceof CameraTransmitterBlockEntity camera) {
            camera.applyStatusSnapshot(payload.config(), payload.status(), payload.deviceName(),
                    payload.streamUrl(), payload.redstoneSignal(), payload.revision(), payload.detail());
        }
    }
}
