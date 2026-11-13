package com.ccvirtualcoms.client.camera.stream;

import java.util.Locale;

/**
 * Cross-workstream holder for the state of the native DirectShow layer.
 *
 * <p>The MJPEG fallback server is always available, but the virtual camera devices are provided by
 * a native DLL that has to be built (optional) and registered (needs one UAC prompt). The native
 * workstream probes that state and publishes it here; the stream service, the HTTP status endpoint
 * and the client command all read it. Keeping the holder in the stream package avoids a compile
 * dependency from the MJPEG code onto the native code, so the fallback can ship (and be tested)
 * before the native layer exists.
 *
 * <p>States:
 * <ul>
 *   <li>{@link State#REGISTERED} - the DLL is present and its devices are registered;</li>
 *   <li>{@link State#UNAVAILABLE} - the DLL or its registration is missing; the MJPEG fallback is
 *       the only output;</li>
 *   <li>{@link State#ERROR} - probing or installation failed, see {@link Snapshot#detail()};</li>
 *   <li>{@link State#UNKNOWN} - not probed yet.</li>
 * </ul>
 */
public final class CameraDriverStatus {

    /** Lifecycle state of the native virtual camera layer. */
    public enum State {
        UNKNOWN("unknown"),
        REGISTERED("registered"),
        UNAVAILABLE("unavailable"),
        ERROR("error");

        private final String serializedName;

        State(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        /** {@code true} only for the fully working state. */
        public boolean available() {
            return this == REGISTERED;
        }
    }

    /**
     * Immutable snapshot of the native driver state.
     *
     * @param state             lifecycle state
     * @param detail            human readable detail for GUI/log/JSON
     * @param dllPath           absolute path of the native DLL, or an empty string
     * @param registeredDevices number of registered {@code Synaxis Camera} device instances
     * @param checkedAtMillis   {@link System#currentTimeMillis()} of the probe
     */
    public record Snapshot(State state, String detail, String dllPath, int registeredDevices,
                           long checkedAtMillis) {

        public Snapshot {
            state = state == null ? State.UNKNOWN : state;
            detail = detail == null ? "" : detail;
            dllPath = dllPath == null ? "" : dllPath;
            registeredDevices = Math.max(0, registeredDevices);
        }

        /** {@code true} when the DirectShow devices are registered and selectable. */
        public boolean available() {
            return this.state.available();
        }
    }

    private static final Snapshot UNKNOWN = new Snapshot(State.UNKNOWN,
            "native virtual camera layer has not been probed yet", "", 0, 0L);

    private static volatile Snapshot current = UNKNOWN;

    private CameraDriverStatus() {
    }

    /** Publishes a new probe result. */
    public static void update(State state, String detail, String dllPath, int registeredDevices) {
        current = new Snapshot(state, detail, dllPath, registeredDevices, System.currentTimeMillis());
    }

    /** Publishes an error result. */
    public static void error(String detail, String dllPath) {
        update(State.ERROR, detail, dllPath, 0);
    }

    /** The current snapshot. */
    public static Snapshot get() {
        return current;
    }

    /** Convenience: {@code true} when the DirectShow devices are registered. */
    public static boolean available() {
        return current.available();
    }

    @Override
    public String toString() {
        Snapshot snapshot = current;
        return snapshot.state().serializedName() + " (" + snapshot.detail() + ")";
    }

    /** Lower-case rendering used by debug commands. */
    public static String describe() {
        Snapshot snapshot = current;
        return String.format(Locale.ROOT, "driver=%s devices=%d detail=%s",
                snapshot.state().serializedName(), snapshot.registeredDevices(), snapshot.detail());
    }
}
