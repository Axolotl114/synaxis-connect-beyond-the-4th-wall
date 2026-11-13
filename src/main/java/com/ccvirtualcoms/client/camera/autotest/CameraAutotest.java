package com.ccvirtualcoms.client.camera.autotest;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CameraPngDump;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.client.gui.CameraTransmitterScreen;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Scripted client smoke test, enabled with {@code -Dcc_virtual_coms.autotest=1} or the
 * {@code CCVC_AUTOTEST=1} environment variable.
 *
 * <p>Used by the development workflow to produce reproducible evidence without manual mouse input:
 * once a level is loaded it finds a tracked camera, opens its configuration screen, captures a
 * screenshot of the GUI, forces the capture pipeline on, and writes the newest captured frame for
 * every camera as a PNG. Everything it does is also logged under {@code [camera-autotest]}.
 *
 * <p>It is driven explicitly from {@code CameraCaptureEvents.onClientTick} because annotation based
 * event-bus scanning of this auxiliary class proved unreliable in the development environment. It
 * does nothing in normal play.
 */
public final class CameraAutotest {

    private static final String ENABLE_PROPERTY = "cc_virtual_coms.autotest";

    private static int ticks;
    private static boolean enabledLogged;
    private static boolean finished;
    private static int phase;
    private static long deadlineTick;

    private CameraAutotest() {
    }

    /** Called once per client tick from the main camera capture event handler. */
    public static void tickIfEnabled() {
        if (!enabled()) {
            return;
        }
        if (!enabledLogged) {
            enabledLogged = true;
            CCVirtualComs.LOGGER.info("[camera-autotest] enabled (property {} / env CCVC_AUTOTEST)",
                    ENABLE_PROPERTY);
        }
        if (finished) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        try {
            tick(minecraft);
        } catch (RuntimeException failure) {
            CCVirtualComs.LOGGER.error("[camera-autotest] failed", failure);
            finished = true;
        }
    }

    private static boolean enabled() {
        if (Boolean.getBoolean(ENABLE_PROPERTY) || "1".equals(System.getenv("CCVC_AUTOTEST"))) {
            return true;
        }
        // File marker fallback, because neither the JVM property nor the inherited
        // environment reliably reach the forked game process in the development launcher.
        return new File(Minecraft.getInstance().gameDirectory, "ccvc_autotest.flag").isFile();
    }

    private static void tick(Minecraft minecraft) {
        ticks++;
        // Give chunks and the capture runtime a few seconds to settle after joining a world.
        if (ticks < 120) {
            return;
        }
        switch (phase) {
            case 0 -> {
                CapturedCamera camera = nearestCamera(minecraft);
                if (camera == null) {
                    CCVirtualComs.LOGGER.warn("[camera-autotest] no tracked camera near player at {}",
                            minecraft.player.blockPosition());
                    finished = true;
                    return;
                }
                CCVirtualComs.LOGGER.info("[camera-autotest] found camera at {} ({}); opening GUI",
                        camera.pos(), camera.describe());
                if (minecraft.level.getBlockEntity(camera.pos())
                        instanceof com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity be) {
                    minecraft.setScreen(new CameraTransmitterScreen(be));
                }
                // Force the capture pipeline on so a real frame is available even when the
                // server-side trigger resolves to offline.
                CameraCaptureRuntime.get().toggleForceProduce();
                phase = 1;
                deadlineTick = ticks + 15;
            }
            case 1 -> {
                if (ticks >= deadlineTick) {
                    File screenshot = new File(minecraft.gameDirectory, "screenshots/camera_gui_autotest.png");
                    Screenshot.grab(minecraft.gameDirectory, "camera_gui_autotest.png",
                            minecraft.getMainRenderTarget(),
                            message -> CCVirtualComs.LOGGER.info("[camera-autotest] screenshot: {}",
                                    message.getString()));
                    CCVirtualComs.LOGGER.info("[camera-autotest] requested GUI screenshot -> {}", screenshot);
                    phase = 2;
                    deadlineTick = ticks + 20;
                }
            }
            case 2 -> {
                if (ticks >= deadlineTick) {
                    minecraft.setScreen(null);
                    dumpFrames();
                    phase = 3;
                    deadlineTick = ticks + 20;
                }
            }
            default -> {
                if (ticks >= deadlineTick) {
                    CCVirtualComs.LOGGER.info("[camera-autotest] DONE; runtime {}",
                            CameraCaptureRuntime.get().describe());
                    finished = true;
                }
            }
        }
    }

    private static CapturedCamera nearestCamera(Minecraft minecraft) {
        CapturedCamera best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            double distance = minecraft.player.blockPosition().distSqr(camera.pos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = camera;
            }
        }
        return best;
    }

    private static void dumpFrames() {
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            CameraFrame frame = camera.latestFrame();
            if (frame == null) {
                CCVirtualComs.LOGGER.warn("[camera-autotest] camera {} has no frame yet ({})",
                        camera.pos(), camera.describe());
                continue;
            }
            File file = CameraPngDump.write(camera.key(), frame);
            CCVirtualComs.LOGGER.info("[camera-autotest] camera {} frame {}x{} seq={} -> {}",
                    camera.pos(), frame.width(), frame.height(), frame.sequence(), file);
        }
    }
}
