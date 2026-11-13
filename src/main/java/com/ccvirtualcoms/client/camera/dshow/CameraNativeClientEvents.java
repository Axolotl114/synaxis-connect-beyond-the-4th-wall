package com.ccvirtualcoms.client.camera.dshow;

import com.ccvirtualcoms.CCVirtualComs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client bootstrap for the native virtual camera workstream.
 *
 * <p>Both tasks are lazy and cheap: the driver probe is throttled to one read-only {@code reg query}
 * every couple of seconds on a worker thread, and the frame bridge only binds its loopback port on
 * the first tick. Nothing here runs on a dedicated server.
 */
@EventBusSubscriber(modid = CCVirtualComs.MOD_ID, value = Dist.CLIENT)
public final class CameraNativeClientEvents {

    private CameraNativeClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        DeviceRegistration.get().tick();
        VirtualCameraTransport.get().tick();
    }
}
