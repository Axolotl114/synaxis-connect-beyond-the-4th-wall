package com.ccvirtualcoms.client.camera.dshow;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.client.camera.stream.CameraDriverStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side DirectShow device registration/installation state.
 *
 * <p>The native DLL exposes a fixed pool of {@code Synaxis Camera 1..4} device monikers. Registering
 * it writes {@code HKCR\CLSID\{filter}\InprocServer32} and the category instances under
 * {@code HKCR\CLSID\{CLSID_VideoInputDeviceCategory}\Instance\...}; both need administrator rights,
 * so installing is a single UAC-gated {@code regsvr32} call. This class:
 *
 * <ul>
 *   <li>probes the current state with read-only {@code reg query} calls (no admin needed) and
 *       publishes it to {@link CameraDriverStatus} for the MJPEG status JSON / GUI;</li>
 *   <li>offers {@link #install()}, {@link #uninstall()} and {@link #cleanupAll()} which run
 *       {@code regsvr32} through a PowerShell {@code Start-Process -Verb RunAs} so the user gets a
 *       normal Windows UAC prompt. Nothing is ever installed silently;</li>
 *   <li>reports precise failure reasons (UAC cancelled, access denied, DLL missing, stale
 *       registration pointing at an old path) so the GUI/log never has to say just "failed".</li>
 * </ul>
 *
 * <p>All installation work runs on a dedicated daemon thread; the client tick only schedules a
 * throttled probe. A dedicated server never touches this class (the whole {@code client.camera}
 * tree is referenced from client-only event subscribers).
 */
public final class DeviceRegistration {

    /** CLSID of the native filter (keep in sync with synaxis_cam_filter.cpp). */
    public static final String FILTER_CLSID = "{6A1F0B7E-3C62-4C61-9E11-5A4D1A7C0001}";

    /** CLSID_VideoInputDeviceCategory - the "video capture devices" DirectShow category. */
    public static final String VIDEO_INPUT_CATEGORY_CLSID = "{860BB310-5D01-11D0-BD3B-00A0C911CE86}";

    /** Number of device instances the native DLL registers (keep in sync with kDeviceCount). */
    public static final int DEVICE_COUNT = 4;

    private static final String INPROC_KEY = "HKCR\\CLSID\\" + FILTER_CLSID + "\\InprocServer32";
    private static final String INSTANCE_KEY = "HKCR\\CLSID\\" + VIDEO_INPUT_CATEGORY_CLSID + "\\Instance";

    private static final long PROBE_INTERVAL_NANOS = 2_000_000_000L;
    private static final long ELEVATED_TIMEOUT_SECONDS = 300L;

    private static final DeviceRegistration INSTANCE = new DeviceRegistration();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ccvc-camera-driver");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    private volatile long nextProbeNanos;
    private volatile CameraDriverStatus.State lastLoggedState;
    private volatile String lastLoggedDetail = "";

    private DeviceRegistration() {
    }

    public static DeviceRegistration get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ probing

    /** Schedules a throttled background probe; called from the client tick. */
    public void tick() {
        long now = System.nanoTime();
        if (now < this.nextProbeNanos || !this.probeInFlight.compareAndSet(false, true)) {
            return;
        }
        this.nextProbeNanos = now + PROBE_INTERVAL_NANOS;
        this.executor.execute(() -> {
            try {
                publish(probe());
            } catch (RuntimeException failure) {
                CCVirtualComs.LOGGER.warn("[camera] driver probe failed: {}", failure.toString());
                CameraDriverStatus.error("driver probe failed: " + failure.getMessage(),
                        NativeLibraryLocator.cachedPathOrEmpty());
            } finally {
                this.probeInFlight.set(false);
            }
        });
    }

    /** A synchronous probe, used by {@code /ccvc_cameras status}. */
    public Probe probe() {
        LocatedDll located = LocateResult.detect();
        if (located.dll() == null) {
            return new Probe(CameraDriverStatus.State.UNAVAILABLE,
                    located.reason() == null ? "native DLL not found" : located.reason(), "", 0);
        }
        String dllPath = located.dll().path().toString();

        if (!isWindows()) {
            return new Probe(CameraDriverStatus.State.UNAVAILABLE,
                    "the DirectShow virtual camera driver is Windows only", dllPath, 0);
        }

        Optional<String> registered = queryDefaultValue(INPROC_KEY);
        if (registered.isEmpty()) {
            return new Probe(CameraDriverStatus.State.UNAVAILABLE,
                    "driver not registered - run /ccvc_cameras install (needs one UAC prompt)", dllPath, 0);
        }

        String registeredPath = registered.get();
        if (!sameFile(registeredPath, dllPath)) {
            return new Probe(CameraDriverStatus.State.UNAVAILABLE,
                    "stale registration: registry points at " + registeredPath
                            + " but the current DLL is " + dllPath
                            + " - run /ccvc_cameras install to update", dllPath, 0);
        }

        int instances = countRegisteredInstances();
        if (instances <= 0) {
            return new Probe(CameraDriverStatus.State.UNAVAILABLE,
                    "COM class is registered but no Synaxis Camera device instance exists"
                            + " - run /ccvc_cameras install to re-register", dllPath, 0);
        }
        return new Probe(CameraDriverStatus.State.REGISTERED,
                "DirectShow devices registered (" + instances + " instances): "
                        + "Synaxis Camera 1.." + instances,
                dllPath, instances);
    }

    private void publish(Probe probe) {
        CameraDriverStatus.update(probe.state(), probe.detail(), probe.dllPath(), probe.instances());
        if (probe.state() != this.lastLoggedState || !Objects.equals(probe.detail(), this.lastLoggedDetail)) {
            this.lastLoggedState = probe.state();
            this.lastLoggedDetail = probe.detail();
            switch (probe.state()) {
                case REGISTERED -> CCVirtualComs.LOGGER.info("[camera] driver: {}", probe.detail());
                case UNAVAILABLE, ERROR -> CCVirtualComs.LOGGER.warn("[camera] driver unavailable: {}", probe.detail());
                case UNKNOWN -> {
                    // Not interesting.
                }
            }
        }
    }

    private static boolean sameFile(String registeredPath, String currentPath) {
        try {
            return Path.of(registeredPath.trim()).toAbsolutePath().normalize()
                    .equals(Path.of(currentPath).toAbsolutePath().normalize());
        } catch (RuntimeException invalidPath) {
            return registeredPath.trim().equalsIgnoreCase(currentPath);
        }
    }

    private static Optional<String> queryDefaultValue(String key) {
        String output = runReg("query", key, "/ve");
        if (output == null) {
            return Optional.empty();
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            int marker = trimmed.indexOf("REG_SZ");
            if (marker < 0) {
                continue;
            }
            String value = trimmed.substring(marker + "REG_SZ".length()).trim();
            if (!value.isEmpty()) {
                return Optional.of(stripQuotes(value));
            }
        }
        return Optional.empty();
    }

    private static int countRegisteredInstances() {
        String output = runReg("query", INSTANCE_KEY, "/s");
        if (output == null) {
            return 0;
        }
        String upper = output.toUpperCase(Locale.ROOT);
        String needle = FILTER_CLSID.toUpperCase(Locale.ROOT);
        int count = 0;
        int index = 0;
        while ((index = upper.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // ------------------------------------------------------------------ installation

    /** Result of an install/uninstall/cleanup request. */
    public record InstallResult(boolean success, String message) {
    }

    /** One probed driver state. */
    public record Probe(CameraDriverStatus.State state, String detail, String dllPath, int instances) {
    }

    /** Requests installation; the UAC prompt is shown by Windows. */
    public CompletableFuture<InstallResult> install() {
        return CompletableFuture.supplyAsync(() -> installBlocking(), this.executor);
    }

    /** Requests uninstallation (removes all Synaxis Camera devices); needs the UAC prompt. */
    public CompletableFuture<InstallResult> uninstall() {
        return CompletableFuture.supplyAsync(() -> runRegsvr32(false), this.executor);
    }

    /** Alias used by the {@code cleanup} command: unregister everything this DLL created. */
    public CompletableFuture<InstallResult> cleanupAll() {
        return CompletableFuture.supplyAsync(() -> runRegsvr32(false), this.executor);
    }

    private InstallResult installBlocking() {
        LocatedDll located = LocateResult.detect();
        if (located.dll() == null) {
            return new InstallResult(false, "native filter DLL is not available: " + located.reason());
        }
        if (!isWindows()) {
            return new InstallResult(false, "the DirectShow virtual camera driver is Windows only");
        }
        Probe before = probe();
        if (before.state() == CameraDriverStatus.State.REGISTERED) {
            return new InstallResult(true, "already registered: " + before.detail());
        }
        return runRegsvr32(true);
    }

    private InstallResult runRegsvr32(boolean install) {
        LocatedDll located = LocateResult.detect();
        if (located.dll() == null) {
            return new InstallResult(false, "native filter DLL is not available: " + located.reason());
        }
        Path dll = located.dll().path();
        String verb = install ? "/s" : "/u /s";
        String script = "$ErrorActionPreference='Stop'; "
                + "try { $p = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\\regsvr32.exe') "
                + "-ArgumentList '" + verb + "', '" + escapeSingleQuotes(dll.toString()) + "' "
                + "-Verb RunAs -Wait -PassThru; exit $p.ExitCode } "
                + "catch { exit 1223 }";
        int exitCode = runElevatedPowerShell(script);
        String action = install ? "registration" : "unregistration";
        if (exitCode == 0) {
            Probe after = probe();
            if (install && after.state() == CameraDriverStatus.State.REGISTERED) {
                return new InstallResult(true, "installed: " + after.detail());
            }
            if (!install && after.state() == CameraDriverStatus.State.UNAVAILABLE) {
                return new InstallResult(true, "uninstalled: " + after.detail());
            }
            return new InstallResult(false, "regsvr32 reported success but " + action
                    + " is not visible yet (" + after.detail() + "). Restart OBS/Zoom/browser to re-enumerate devices.");
        }
        if (exitCode == 1223) {
            return new InstallResult(false, "the UAC prompt was cancelled; " + action + " was not applied");
        }
        if (exitCode == 5) {
            return new InstallResult(false, "access denied - the process could not elevate: " + action);
        }
        return new InstallResult(false, "regsvr32 " + (install ? "/s" : "/u /s") + " failed with exit code " + exitCode);
    }

    private static int runElevatedPowerShell(String script) {
        List<String> command = List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", script);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), Charset.defaultCharset()).trim();
            }
            if (!process.waitFor(ELEVATED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            if (!output.isEmpty()) {
                CCVirtualComs.LOGGER.info("[camera] regsvr32 helper output: {}", output);
            }
            return process.exitValue();
        } catch (IOException failure) {
            CCVirtualComs.LOGGER.warn("[camera] could not start the elevation helper: {}", failure.toString());
            return -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String escapeSingleQuotes(String value) {
        return value.replace("'", "''");
    }

    // ------------------------------------------------------------------ process helpers

    private static @Nullable String runReg(String... arguments) {
        List<String> command = new ArrayList<>();
        String reg = System.getenv("SystemRoot");
        if (reg != null && !reg.isBlank()) {
            command.add(reg + "\\System32\\reg.exe");
        } else {
            command.add("reg.exe");
        }
        for (String argument : arguments) {
            command.add(argument);
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), Charset.defaultCharset());
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return output;
        } catch (IOException failure) {
            CCVirtualComs.LOGGER.debug("[camera] reg query failed: {}", failure.toString());
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record LocatedDll(@Nullable com.ccvirtualcoms.client.camera.dshow.NativeLibraryLocator.Located dll,
                              String reason) {
    }

    private static final class LocateResult {
        private static LocatedDll detect() {
            com.ccvirtualcoms.client.camera.dshow.NativeLibraryLocator.Located located =
                    com.ccvirtualcoms.client.camera.dshow.NativeLibraryLocator.locate();
            if (located == null) {
                return new LocatedDll(null, com.ccvirtualcoms.client.camera.dshow.NativeLibraryLocator.lastFailure());
            }
            return new LocatedDll(located, "");
        }
    }

    /** Files helper retained for callers that want to refresh a rebuilt DLL during development. */
    static boolean dllExists() {
        return Files.isRegularFile(Path.of(NativeLibraryLocator.DLL_NAME));
    }
}
