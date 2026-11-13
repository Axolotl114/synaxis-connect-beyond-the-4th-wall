package com.ccvirtualcoms.client.camera.dshow;

import com.ccvirtualcoms.CCVirtualComs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Locates (and, when packaged in the jar, extracts) the native DirectShow filter DLL.
 *
 * <p>Search order:
 * <ol>
 *   <li>{@code -Dcc_virtual_coms.camera.nativeDll=<absolute path>} (developer override);</li>
 *   <li>the DLL packaged in the mod jar as {@code /native/synaxis_cam_filter.dll} - it is copied
 *       to {@code %LOCALAPPDATA%\cc_virtual_coms\native\} so the registry always points at a stable
 *       path (a DLL extracted to a random temp directory would break registration);</li>
 *   <li>a development build in {@code .build/native/} relative to the working directory or two
 *       levels up (the dev client runs from {@code run/client}, the repo root is {@code ../..}).</li>
 * </ol>
 *
 * <p>The DLL is a build artifact and is deliberately not committed to the repository: the native
 * source and {@code native/virtual_camera/build.ps1} live in git, the DLL does not. When the release
 * jar does not contain the DLL (e.g. a source-only build without Visual Studio), the driver is
 * reported as unavailable and the MJPEG fallback remains the only output - never a silent failure.
 */
public final class NativeLibraryLocator {

    /** Name of the native DirectShow filter DLL. */
    public static final String DLL_NAME = "synaxis_cam_filter.dll";

    /** Classpath location of the DLL inside the mod jar. */
    public static final String RESOURCE_PATH = "/native/" + DLL_NAME;

    /** System property for a developer override of the DLL path. */
    public static final String DLL_PROPERTY = "cc_virtual_coms.camera.nativeDll";

    /** A located DLL file. */
    public record Located(Path path, String sha256, String source) {
    }

    private static final Object LOCK = new Object();
    private static volatile Located cached;
    private static volatile String lastFailure = "not probed yet";

    private NativeLibraryLocator() {
    }

    /** Locates the DLL, returning {@code null} and recording the reason when it cannot be found. */
    public static @Nullable Located locate() {
        Located current = cached;
        if (current != null && Files.isRegularFile(current.path())) {
            return current;
        }
        synchronized (LOCK) {
            if (cached != null && Files.isRegularFile(cached.path())) {
                return cached;
            }
            try {
                Located located = locateUncached();
                if (located != null) {
                    cached = located;
                    lastFailure = "";
                    CCVirtualComs.LOGGER.info("[camera] native filter DLL: {} (source: {}, sha256: {})",
                            located.path(), located.source(), located.sha256());
                }
                return located;
            } catch (IOException failure) {
                lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                CCVirtualComs.LOGGER.warn("[camera] native filter DLL unavailable: {}", lastFailure);
                return null;
            }
        }
    }

    /** Why the DLL could not be located (or an empty string when it was). */
    public static String lastFailure() {
        return lastFailure;
    }

    /** Absolute path of the cached DLL, or an empty string when nothing was located yet. */
    public static String cachedPathOrEmpty() {
        Located located = cached;
        return located == null ? "" : located.path().toString();
    }

    /** Clears the cached location, e.g. after the file was rebuilt during development. */
    public static void invalidate() {
        cached = null;
    }

    private static @Nullable Located locateUncached() throws IOException {
        String override = System.getProperty(DLL_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            if (Files.isRegularFile(path)) {
                return new Located(path.toAbsolutePath(), sha256(path), "system property");
            }
            lastFailure = "the path in -D" + DLL_PROPERTY + " does not exist: " + path;
        }

        Located extracted = extractFromJar();
        if (extracted != null) {
            return extracted;
        }

        for (Path candidate : developmentCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return new Located(candidate.toAbsolutePath(), sha256(candidate), "development build");
            }
        }

        if (lastFailure.isBlank() || "not probed yet".equals(lastFailure)) {
            lastFailure = "no native DLL packaged in the jar and no .build/native/synaxis_cam_filter.dll; "
                    + "build the native layer with native/virtual_camera/build.ps1 or the optional Gradle task";
        }
        return null;
    }

    private static @Nullable Located extractFromJar() throws IOException {
        InputStream resource = NativeLibraryLocator.class.getResourceAsStream(RESOURCE_PATH);
        if (resource == null) {
            return null;
        }
        byte[] bytes;
        try (InputStream input = resource) {
            bytes = input.readAllBytes();
        }
        String resourceHash = sha256(bytes);
        Path targetDirectory = localAppData().resolve("cc_virtual_coms").resolve("native");
        Path target = targetDirectory.resolve(DLL_NAME);

        if (Files.isRegularFile(target) && resourceHash.equalsIgnoreCase(sha256(Files.readAllBytes(target)))) {
            return new Located(target, resourceHash, "jar resource (cached)");
        }

        Files.createDirectories(targetDirectory);
        Path temporary = Files.createTempFile(targetDirectory, DLL_NAME, ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inUse) {
                throw new IOException("cannot update " + target
                        + " while another program has the DLL loaded; close OBS/Zoom/browser and retry", inUse);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Located(target, resourceHash, "jar resource (extracted)");
    }

    private static Path localAppData() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData);
        }
        return Path.of(System.getProperty("user.home"), "AppData", "Local");
    }

    private static Iterable<Path> developmentCandidates() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return java.util.List.of(
                workingDirectory.resolve(".build").resolve("native").resolve(DLL_NAME),
                workingDirectory.resolve("..").resolve("..").resolve(".build").resolve("native").resolve(DLL_NAME),
                workingDirectory.resolve("..").resolve(".build").resolve("native").resolve(DLL_NAME));
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes)).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    /** Short "path (hash)" description for the status command. */
    public static String describe() {
        Located located = cached;
        if (located == null) {
            return "native DLL: not found (" + lastFailure + ")";
        }
        return "native DLL: " + located.path() + " [" + located.sha256().substring(0, 12) + "...]";
    }

    /** Ensures the parent directory of an extracted DLL exists (used by tests). */
    static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** Copy helper used by the optional installer path when the DLL is provided out of band. */
    static void copy(Path source, Path destination) throws IOException {
        ensureParent(destination);
        try (OutputStream output = Files.newOutputStream(destination)) {
            Files.copy(source, output);
        }
    }
}
