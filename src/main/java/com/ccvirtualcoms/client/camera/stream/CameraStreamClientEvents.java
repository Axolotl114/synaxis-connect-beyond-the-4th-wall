package com.ccvirtualcoms.client.camera.stream;

import com.ccvirtualcoms.CCVirtualComs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client bootstrap for the MJPEG fallback pipeline.
 *
 * <p>The stream service is started lazily from the client tick rather than from mod construction
 * for two reasons: the render/capture side may not have registered any camera yet, and a failed
 * HTTP bind must not abort game startup. {@link CameraStreamService#tick()} is cheap after the
 * first successful start (a single volatile read).
 */
@EventBusSubscriber(modid = CCVirtualComs.MOD_ID, value = Dist.CLIENT)
public final class CameraStreamClientEvents {

    private CameraStreamClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        CameraStreamService.get().tick();
    }
}
