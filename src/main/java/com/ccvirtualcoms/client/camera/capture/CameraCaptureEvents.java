package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client-side entry points for the camera capture pipeline.
 *
 * <p>This is the only class the game hooks into; everything else in this package is plain Java
 * driven from here. Registration is annotation based ({@link EventBusSubscriber}), so no line in
 * {@code CCVirtualComs} has to change.
 *
 * <p>Threading: every {@code @SubscribeEvent} method below runs on the Minecraft client (render)
 * thread. Frames are handed to consumers through {@link com.ccvirtualcoms.camera.api.CameraFrame}
 * immutables, never through shared mutable state.
 */
@EventBusSubscriber(modid = CCVirtualComs.MOD_ID, value = Dist.CLIENT)
public final class CameraCaptureEvents {

    private CameraCaptureEvents() {
    }

    /** Scans for placed cameras and refreshes their registry entries; cheap and throttled inside. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        CameraCaptureRuntime.get().clientTick();
        com.ccvirtualcoms.client.camera.autotest.CameraAutotest.tickIfEnabled();
    }

    /**
     * Gives the capture pipeline the exact partial tick the game is rendering with.
     *
     * <p>The same value is passed to Sable's {@code renderPose(partialTick)}, so the camera and the
     * ship geometry use the identical interpolated pose in a frame.
     */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        CameraCaptureRuntime.get().renderFrame(
                event.getPartialTick().getGameTimeDeltaPartialTick(true));
    }

    /** Drops every camera when the client leaves a world (registry is per-level). */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            CameraCaptureRuntime.get().levelUnloaded();
        }
    }

    /** Registers {@code /ccvc_camera_debug} so the capture pipeline can be verified in game. */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CameraDebugCommand.register(event.getDispatcher());
    }
}
