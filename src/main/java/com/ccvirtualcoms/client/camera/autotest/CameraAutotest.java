package com.ccvirtualcoms.client.camera.autotest;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraResolution;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CameraPngDump;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.client.camera.stream.CameraStreamService;
import com.ccvirtualcoms.client.gui.CameraTabButton;
import com.ccvirtualcoms.client.gui.CameraTransmitterScreen;
import com.ccvirtualcoms.net.CameraConfigPayload;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Scripted client smoke test, enabled with {@code -Dcc_virtual_coms.autotest=1}, the
 * {@code CCVC_AUTOTEST=1} environment variable, or a {@code ccvc_autotest.flag} file in the game
 * directory.
 *
 * <p>Produces reproducible evidence without manual mouse input:
 * <ol>
 *   <li>finds a tracked camera and opens its configuration screen;</li>
 *   <li>captures one screenshot per GUI page by pressing the page tabs;</li>
 *   <li>cycles output variants (monochrome, monochrome + night vision, 1280x720) through the real
 *       client payload and writes the captured frame of each variant as a PNG;</li>
 *   <li>restores the original configuration.</li>
 * </ol>
 * Everything it does is logged under {@code [camera-autotest]}. It does nothing in normal play.
 */
public final class CameraAutotest {

    private static final String ENABLE_PROPERTY = "cc_virtual_coms.autotest";
    private static final int PAGES = 4;

    /** Full-window screenshots taken while streaming, to prove the player view stays stable. */
    private static final int STABILITY_SHOTS = 8;

    private static int ticks;
    private static boolean enabledLogged;
    private static boolean finished;
    private static int phase;
    private static int pageIndex;
    private static int variantIndex;
    private static int stabilityIndex;
    private static long deadlineTick;
    private static CapturedCamera subject;
    private static CameraConfig originalConfig;
    private static List<Variant> variants = List.of();

    private record Variant(String name, CameraConfig config) {
    }

    private static List<Variant> variantsFor(CameraConfig base) {
        return List.of(
                new Variant("bw", base.withColor(false)),
                new Variant("thermal", base.withColor(false).withNightVision(true)),
                new Variant("hd720", base.withResolution(new CameraResolution(1280, 720))));
    }

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

    /**
     * Logs every tracked camera's resolved world pose once per second, including whether it sits on
     * a Sable sub-level. Used to verify that the camera follows a moving/teleported sub-level.
     */
    private static void logPoses() {
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            var pose = camera.pose();
            CCVirtualComs.LOGGER.info(
                    "[camera-autotest] pose {} sublevel={} streaming={} pos=({},{},{}) yaw={} pitch={} roll={} detail={}",
                    camera.pos(), camera.onSubLevel(), camera.streaming(),
                    pose == null ? 0.0D : pose.x(), pose == null ? 0.0D : pose.y(),
                    pose == null ? 0.0D : pose.z(),
                    pose == null ? 0.0F : pose.yaw(), pose == null ? 0.0F : pose.pitch(),
                    pose == null ? 0.0F : pose.roll(), camera.poseDetail());
        }
    }

    private static boolean enabled() {
        if (Boolean.getBoolean(ENABLE_PROPERTY) || "1".equals(System.getenv("CCVC_AUTOTEST"))) {
            return true;
        }
        // File marker fallback: neither the JVM property nor the inherited environment reliably
        // reach the forked game process in the development launcher.
        return new File(Minecraft.getInstance().gameDirectory, "ccvc_autotest.flag").isFile();
    }

    private static void tick(Minecraft minecraft) {
        ticks++;
        if (ticks % 20 == 0) {
            logPoses();
        }
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
                    dumpFrames("initial");
                    subject = streamingCamera(minecraft);
                    if (subject == null) {
                        CCVirtualComs.LOGGER.warn("[camera-autotest] no streaming camera for variants");
                        finished = true;
                        return;
                    }
                    originalConfig = subject.config();
                    variants = variantsFor(originalConfig);
                    variantIndex = 0;
                    phase = 4;
                }
            }
            case 4 -> applyVariant(minecraft);
            case 5 -> {
                if (ticks >= deadlineTick) {
                    Variant variant = variants.get(variantIndex);
                    dumpEncodedFrame(subject, "variant_" + variant.name());
                    variantIndex++;
                    if (variantIndex < variants.size()) {
                        phase = 4;
                    } else {
                        restoreConfiguration(minecraft);
                        phase = 6;
                        deadlineTick = ticks + 40;
                    }
                }
            }
            case 6 -> {
                if (ticks >= deadlineTick) {
                    stabilityIndex = 0;
                    deadlineTick = ticks;
                    phase = 7;
                }
            }
            case 7 -> {
                if (ticks >= deadlineTick) {
                    String name = "flicker_check_" + stabilityIndex + ".png";
                    Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                            message -> {
                            });
                    stabilityIndex++;
                    if (stabilityIndex < STABILITY_SHOTS) {
                        deadlineTick = ticks + 3;
                    } else {
                        phase = 8;
                        deadlineTick = ticks + 20;
                    }
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
        makeCreative(minecraft);
        normalizeConfigurations();
        var clientCamera = com.ccvirtualcoms.client.CameraClientHooks.cameraAt(camera.pos());
        if (clientCamera != null) {
            minecraft.setScreen(new CameraTransmitterScreen(clientCamera));
        }
        pageIndex = 0;
        phase = 1;
        deadlineTick = ticks;
    }

    /**
     * Makes the test world deterministic: streaming cameras hold on, every other camera uses the
     * documented default. This also repairs configurations edited during earlier manual runs.
     */
    private static void normalizeConfigurations() {
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            CameraConfig config = camera.streaming()
                    ? CameraConfig.DEFAULT.withHoldOn(true)
                    : CameraConfig.DEFAULT;
            PacketDistributor.sendToServer(new CameraConfigPayload(camera.pos(), config));
        }
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

    private static void applyVariant(Minecraft minecraft) {
        Variant variant = variants.get(variantIndex);
        if (minecraft.level.getBlockEntity(subject.pos())
                instanceof com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity) {
            PacketDistributor.sendToServer(new CameraConfigPayload(subject.pos(), variant.config()));
            CCVirtualComs.LOGGER.info("[camera-autotest] requested variant {} ({}x{}, color={}, night={})",
                    variant.name(), variant.config().resolution().width(),
                    variant.config().resolution().height(), variant.config().color(),
                    variant.config().nightVision());
        }
        deadlineTick = ticks + 40;
        phase = 5;
    }

    private static void restoreConfiguration(Minecraft minecraft) {
        if (originalConfig != null && minecraft.level.getBlockEntity(subject.pos())
                instanceof com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity) {
            PacketDistributor.sendToServer(new CameraConfigPayload(subject.pos(), originalConfig));
            CCVirtualComs.LOGGER.info("[camera-autotest] restored original configuration");
        }
    }

    /** Puts the local test player into creative so building test rigs is not a survival chore. */
    private static void makeCreative(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            return;
        }
        var player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
        if (player != null && !player.isCreative()) {
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            CCVirtualComs.LOGGER.info("[camera-autotest] switched local test player to creative");
        }
    }

    private static CapturedCamera nearestCamera(Minecraft minecraft) {
        // Prefer a camera on a Sable sub-level: it exercises discovery, status routing and capture
        // for the plot-based positions, which the normal chunk sweep cannot see.
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            if (camera.onSubLevel()) {
                return camera;
            }
        }
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

    private static CapturedCamera streamingCamera(Minecraft minecraft) {
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            if (camera.streaming()) {
                return camera;
            }
        }
        return nearestCamera(minecraft);
    }

    private static void dumpFrames(String tag) {
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            dumpCameraFrame(camera, tag);
        }
    }

    private static void dumpCameraFrame(CapturedCamera camera, String tag) {
        CameraFrame frame = camera.latestFrame();
        if (frame == null) {
            CCVirtualComs.LOGGER.warn("[camera-autotest] camera {} has no frame yet for {} ({})",
                    camera.pos(), tag, camera.describe());
            return;
        }
        File file = new File(Minecraft.getInstance().gameDirectory,
                "screenshots/camera_" + tag + "_" + Long.toHexString(camera.key()) + ".png");
        try (NativeImage image = new NativeImage(frame.width(), frame.height(), false)) {
            CameraPngDump.copyPixels(frame, image);
            image.writeToFile(file);
        } catch (Exception failure) {
            CCVirtualComs.LOGGER.warn("[camera-autotest] writing {} failed: {}", file, failure.toString());
            return;
        }
        CCVirtualComs.LOGGER.info("[camera-autotest] camera {} {} frame {}x{} seq={} -> {}",
                camera.pos(), tag, frame.width(), frame.height(), frame.sequence(), file);
    }

    /** Writes the newest *encoded* MJPEG frame, which carries colour/monochrome/night processing. */
    private static void dumpEncodedFrame(CapturedCamera camera, String tag) {
        byte[] jpeg = CameraStreamService.get().latestEncodedFrame(camera.key());
        if (jpeg == null || jpeg.length == 0) {
            CCVirtualComs.LOGGER.warn("[camera-autotest] camera {} has no encoded frame for {} ({})",
                    camera.pos(), tag, camera.describe());
            return;
        }
        File file = new File(Minecraft.getInstance().gameDirectory,
                "screenshots/camera_" + tag + "_" + Long.toHexString(camera.key()) + ".jpg");
        try {
            Files.write(file.toPath(), jpeg);
        } catch (Exception failure) {
            CCVirtualComs.LOGGER.warn("[camera-autotest] writing {} failed: {}", file, failure.toString());
            return;
        }
        CCVirtualComs.LOGGER.info("[camera-autotest] camera {} {} encoded frame {} bytes -> {}",
                camera.pos(), tag, jpeg.length, file);
    }
}
