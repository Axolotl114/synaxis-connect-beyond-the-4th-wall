package com.ccvirtualcoms.client.camera.dshow;

import com.ccvirtualcoms.camera.CameraStreamUrls;
import com.ccvirtualcoms.camera.api.CameraStreamRegistry;
import com.ccvirtualcoms.client.camera.stream.CameraDriverStatus;
import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client command {@code /ccvc_cameras ...} for the virtual camera fallback.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code status} - driver state, DLL path, registered device count, frame bridge state,
 *       fallback URL and one line per active camera;</li>
 *   <li>{@code install} - runs {@code regsvr32 /s <dll>} elevated (one UAC prompt) and reports the
 *       exact result; the call is asynchronous so the game does not freeze while the prompt is up;</li>
 *   <li>{@code uninstall} - removes the {@code Synaxis Camera} devices;</li>
 *   <li>{@code cleanup} - alias for uninstall, intended as the "clean up all Synaxis Camera
 *       registrations" escape hatch.</li>
 * </ul>
 *
 * <p>Output goes to the chat, prefixed with {@code [cameras]}, so a normal player can see why an
 * external program does not list the camera and what to do about it. Nothing is installed without
 * the user accepting the Windows UAC prompt.
 */
@EventBusSubscriber(modid = "cc_virtual_coms", value = Dist.CLIENT)
public final class CameraNativeCommand {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ccvc-camera-command");
        thread.setDaemon(true);
        return thread;
    });

    private CameraNativeCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("ccvc_cameras")
                .then(Commands.literal("status").executes(context -> reportStatus()))
                .then(Commands.literal("install").executes(context -> install()))
                .then(Commands.literal("uninstall").executes(context -> uninstall()))
                .then(Commands.literal("cleanup").executes(context -> cleanup())));
    }

    // ------------------------------------------------------------------ subcommands

    private static int reportStatus() {
        DeviceRegistration.Probe probe = DeviceRegistration.get().probe();
        CameraDriverStatus.Snapshot snapshot = CameraDriverStatus.get();

        chat("=== CC Synaxis Virtual Coms: camera devices ===");
        chat("driver state: " + probe.state().serializedName() + "  (" + probe.detail() + ")");
        if (!probe.dllPath().isBlank()) {
            chat("registered dll: " + probe.dllPath());
        }
        chat("devices: " + probe.instances() + "/" + DeviceRegistration.DEVICE_COUNT
                + "   active clients: " + VirtualCameraTransport.get().clientCount());
        if (probe.state() == CameraDriverStatus.State.REGISTERED) {
            chat("device names: Synaxis Camera 1.." + probe.instances()
                    + " (external apps: OBS/Zoom/browser select the device directly)");
        } else {
            chat("driver unavailable: " + probe.detail());
            chat("fallback MJPEG URL: " + CameraStreamUrls.statusUrl());
            chat("run /ccvc_cameras install to enable the devices (needs one UAC prompt)");
        }
        chat("fallback server: " + com.ccvirtualcoms.client.camera.stream.CameraStreamService.get().describe());
        chat("native frame bridge: " + VirtualCameraTransport.get().describe());

        List<CameraStreamRegistry.Entry> entries = new ArrayList<>(CameraStreamRegistry.all());
        entries.sort(Comparator.comparingLong(CameraStreamRegistry.Entry::key));
        if (entries.isEmpty()) {
            chat("no camera transmitters registered in this client yet");
        } else {
            for (CameraStreamRegistry.Entry entry : entries) {
                String key = Long.toHexString(entry.key());
                String name = entry.device() != null ? entry.device().deviceName() : "";
                if (name.isBlank()) {
                    name = "camera " + key;
                }
                String status = entry.status() == null ? "offline" : entry.status().serializedName();
                chat("#" + key + " " + name + " [" + status + "] " + CameraStreamUrls.urlFor(entry.key()));
                if (entry.detail() != null && !entry.detail().isBlank()) {
                    chat("    " + entry.detail());
                }
            }
        }
        return 1;
    }

    private static int install() {
        if (!isWindows()) {
            chat("DirectShow virtual cameras are Windows only; the MJPEG fallback still works.");
            return 0;
        }
        chat("requesting administrator permission for the virtual camera driver...");
        DeviceRegistration.get().install()
                .whenComplete((result, failure) -> chat(failure != null
                        ? "install failed: " + failure
                        : (result.success() ? "install OK: " : "install failed: ") + result.message()));
        return 1;
    }

    private static int uninstall() {
        chat("requesting administrator permission to unregister the virtual cameras...");
        DeviceRegistration.get().uninstall()
                .whenComplete((result, failure) -> chat(failure != null
                        ? "uninstall failed: " + failure
                        : (result.success() ? "uninstall OK: " : "uninstall failed: ") + result.message()));
        return 1;
    }

    private static int cleanup() {
        chat("cleaning up all Synaxis Camera registrations (UAC prompt may appear)...");
        DeviceRegistration.get().cleanupAll()
                .whenComplete((result, failure) -> chat(failure != null
                        ? "cleanup failed: " + failure
                        : (result.success() ? "cleanup OK: " : "cleanup failed: ") + result.message()));
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static void chat(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        String text = "[cameras] " + message;
        minecraft.execute(() -> {
            if (minecraft.gui != null) {
                minecraft.gui.getChat().addMessage(Component.literal(text));
            }
        });
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Doesn't actually need a server; kept for symmetry with server command helpers. */
    private static void unused(CompletableFuture<?> future) {
        if (future != null) {
            EXECUTOR.execute(() -> future.isDone());
        }
    }
}
