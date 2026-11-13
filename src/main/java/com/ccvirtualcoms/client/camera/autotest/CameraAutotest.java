package com.ccvirtualcoms.client.camera.autotest;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CameraPngDump;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.client.gui.CameraTabButton;
import com.ccvirtualcoms.client.gui.CameraTransmitterScreen;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Scripted client smoke test, enabled with {@code -Dcc_virtual_coms.autotest=1}, the
 * {@code CCVC_AUTOTEST=1} environment variable, or a {@code ccvc_autotest.flag} file in the game
 * directory.
 *
 * <p>Used by the development workflow to produce reproducible evidence without manual mouse input:
 * once a level is loaded it finds a tracked camera, opens its configuration screen, captures one
 * screenshot per GUI page by pressing the page tabs, forces the capture pipeline on, and writes the
 * newest captured frame for every camera as a PNG. Everything it does is logged under
 * {@code [camera-autotest]}.
 *
 * <p>Driven explicitly from {@code CameraCaptureEvents.onClientTick}; it does nothing in normal
 * play.
 */
public final class CameraAutotest {

    private static final String ENABLE_PROPERTY = "cc_virtual_coms.autotest";
    private static final int PAGES = 4;

    private static int ticks;
    private static boolean enabledLogged;
    private static boolean finished;
    private static int phase;
    private static int pageIndex;
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
        // File marker fallback, because neither the JVM property nor the inherited environment
        // reliably reach the forked game process in the development launcher.
        return new File(Minecraft.getInstance().gameDirectory, "ccvc_autotest.flag").isFile();
    }

    private static void tick(Minecraft minecraft) {
        ticks++;
        if (ticks < 120) {
            return;
        }
        switch (phase) {
            case 0 -> start(minecraft);
            case 1 -> selectPage(minecraft, pageIndex);
            case 2 -> capturePage(minecraft);
            case 3 -> {
                if (ticks >= deadlineTick) {
                    minecraft.setScreen(null);
                    dumpFrames();
                    phase = 4;
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

    private static void start(Minecraft minecraft) {
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
        // Force the capture pipeline on so a real frame is available even when the server-side
        // trigger resolves to offline.
        CameraCaptureRuntime.get().toggleForceProduce();
        pageIndex = 0;
        phase = 1;
        deadlineTick = ticks;
    }

    private static void selectPage(Minecraft minecraft, int index) {
        if (index > 0) {
            int current = 0;
            for (GuiEventListener child : minecraft.screen.children()) {
                if (child instanceof CameraTabButton tab) {
                    if (current == index) {
                        tab.mouseClicked(tab.getX() + 1, tab.getY() + 1, 0);
                        break;
                    }
                    current++;
                }
            }
        }
        deadlineTick = ticks + 8;
        phase = 2;
    }

    private static void capturePage(Minecraft minecraft) {
        if (ticks < deadlineTick) {
            return;
        }
        String name = pageIndex == 0 ? "camera_gui_autotest.png" : "camera_gui_page" + pageIndex + ".png";
        File file = new File(minecraft.gameDirectory, "screenshots/" + name);
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                message -> CCVirtualComs.LOGGER.info("[camera-autotest] screenshot: {}",
                        message.getString()));
        CCVirtualComs.LOGGER.info("[camera-autotest] requested page {} screenshot -> {}",
                pageIndex, file);
        pageIndex++;
        if (pageIndex < PAGES) {
            phase = 1;
        } else {
            deadlineTick = ticks + 15;
            phase = 3;
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
