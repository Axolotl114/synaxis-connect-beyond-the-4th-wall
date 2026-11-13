package com.ccvirtualcoms.camera;

import java.util.Locale;

/**
 * How the redstone input turns the camera stream on and off.
 *
 * <p>The input strength is a vanilla redstone signal 0-15 (or a Create wireless redstone link
 * signal when both frequency slots are filled) and the GUI configures a threshold 0-15.
 *
 * <ul>
 *   <li>{@link #HOLD}: the stream is on while {@code strength > threshold} and off otherwise.</li>
 *   <li>{@link #TOGGLE}: each rising edge across the threshold ({@code <= threshold} to
 *       {@code > threshold}) flips the stream state.</li>
 * </ul>
 */
public enum CameraRedstoneMode {
    HOLD("hold"),
    TOGGLE("toggle");

    private final String serializedName;

    CameraRedstoneMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** The lower-case name used in NBT and on the wire. */
    public String serializedName() {
        return this.serializedName;
    }

    /** Parses a name, falling back to {@link #HOLD}. */
    public static CameraRedstoneMode byNameOrDefault(String name) {
        if (name != null) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (CameraRedstoneMode mode : values()) {
                if (mode.serializedName.equals(normalized)) {
                    return mode;
                }
            }
        }
        return HOLD;
    }

    /** The next value in the GUI cycle. */
    public CameraRedstoneMode cycle() {
        return this == HOLD ? TOGGLE : HOLD;
    }
}
