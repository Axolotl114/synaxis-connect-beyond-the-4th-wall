package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.camera.api.CameraPose;
import com.mojang.brigadier.CommandDispatcher;
import java.io.File;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Client command used to verify the capture pipeline without external tooling.
 *
 * <pre>
 *   /ccvc_camera_debug              short status of every camera
 *   /ccvc_camera_debug status       same as above, pose + frame counters
 *   /ccvc_camera_debug pose         resolved world pose of every camera
 *   /ccvc_camera_debug dump         force one frame per camera and write it as a PNG
 *   /ccvc_camera_debug force        toggle "produce frames regardless of trigger state"
 * </pre>
 *
 * <p>Feedback goes to chat and to the log, so a headless run still records it.
 */
public final class CameraDebugCommand {

    /** Command name, kept short because it is typed often during development. */
    public static final String COMMAND_NAME = "ccvc_camera_debug";

    private CameraDebugCommand() {
    }

    /** Registers the command with the client dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND_NAME)
                .executes(context -> status())
                .then(Commands.literal("status").executes(context -> status()))
                .then(Commands.literal("pose").executes(context -> pose()))
                .then(Commands.literal("dump").executes(context -> dump()))
                .then(Commands.literal("force").executes(context -> force())));
    }

    private static int status() {
        CameraCaptureRuntime runtime = CameraCaptureRuntime.get();
        message("capture: " + runtime.describe()
                + " registry=" + com.ccvirtualcoms.camera.api.CameraStreamRegistry.size());
        for (CapturedCamera camera : runtime.cameras()) {
            message("  " + camera.describe());
        }
        if (runtime.size() == 0) {
            message("  (no camera transmitter loaded in this level; place one or move closer)");
        }
        return 1;
    }

    private static int pose() {
        CameraCaptureRuntime runtime = CameraCaptureRuntime.get();
        for (CapturedCamera camera : runtime.cameras()) {
            CameraPose pose = camera.pose();
            if (pose == null) {
                message("  " + Long.toHexString(camera.key()) + " pose=<not resolved yet>");
                continue;
            }
            message(String.format(java.util.Locale.ROOT,
                    "  %s pos=(%.3f, %.3f, %.3f) yaw=%.2f pitch=%.2f roll=%.2f sublevel=%s [%s]",
                    Long.toHexString(camera.key()), pose.x(), pose.y(), pose.z(), pose.yaw(),
                    pose.pitch(), pose.roll(), camera.onSubLevel(), camera.poseDetail()));
        }
        return 1;
    }

    private static int dump() {
        CameraCaptureRuntime runtime = CameraCaptureRuntime.get();
        List<CameraCaptureRuntime.CameraFrameDumpEntry> entries = runtime.dumpFrames();
        if (entries.isEmpty()) {
            message("capture dump: no camera produced a frame (runtime size=" + runtime.size() + ")");
            return 0;
        }
        for (CameraCaptureRuntime.CameraFrameDumpEntry entry : entries) {
            CapturedCamera camera = entry.camera();
            CameraFrame frame = entry.frame();
            File file = CameraPngDump.write(camera.key(), frame);
            @Nullable CameraPose pose = camera.pose();
            message("capture dump: " + (file == null ? "<write failed>" : file.getAbsolutePath())
                    + " (" + frame.width() + "x" + frame.height() + " seq=" + frame.sequence()
                    + ", pose=" + describePose(pose) + ")");
        }
        return 1;
    }

    private static int force() {
        boolean enabled = CameraCaptureRuntime.get().toggleForceProduce();
        message("capture force mode: " + (enabled ? "ON (frames produce regardless of trigger)" : "OFF"));
        return 1;
    }

    private static String describePose(@Nullable CameraPose pose) {
        if (pose == null) {
            return "<none>";
        }
        return String.format(java.util.Locale.ROOT, "(%.3f,%.3f,%.3f) yaw=%.2f pitch=%.2f roll=%.2f",
                pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), pose.roll());
    }

    private static void message(String text) {
        CCVirtualComs.LOGGER.info("[camera] {}", text);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal("[camera] " + text));
        }
    }
}
