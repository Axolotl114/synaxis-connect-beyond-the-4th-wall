package com.ccvirtualcoms.camera;

import java.util.Locale;

/**
 * The externally visible output state of a camera transmitter.
 *
 * <p>The values map onto the strings the GUI, the log and the Python client use:
 * {@code streaming}, {@code offline} and {@code driver_unavailable}.
 */
public enum CameraStreamStatus {
    /** The camera should not stream, but its virtual device (if any) keeps emitting an offline frame. */
    OFFLINE("offline"),
    /** Frames are being produced and pushed to the virtual device / MJPEG endpoint. */
    STREAMING("streaming"),
    /** The virtual camera driver is not installed, so the MJPEG HTTP fallback is the only output. */
    DRIVER_UNAVAILABLE("driver_unavailable");

    private final String serializedName;

    CameraStreamStatus(String serializedName) {
        this.serializedName = serializedName;
    }

    /** The lower-case name used in the GUI, the log and the Python client. */
    public String serializedName() {
        return this.serializedName;
    }

    /** Parses a name, falling back to {@link #OFFLINE}. */
    public static CameraStreamStatus byNameOrDefault(String name) {
        if (name != null) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (CameraStreamStatus status : values()) {
                if (status.serializedName.equals(normalized)) {
                    return status;
                }
            }
        }
        return OFFLINE;
    }
}
