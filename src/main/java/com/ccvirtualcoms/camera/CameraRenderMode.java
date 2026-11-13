package com.ccvirtualcoms.camera;

import java.util.Locale;

/**
 * Whether the camera view is rendered through the active shader pipeline.
 *
 * <p>Iris builds its final image into the main render target, so a self-contained off-screen
 * render target only produces correct content with the vanilla pipeline. The three values below
 * describe the user's intent; the renderer always records the actually used strategy in the GUI
 * status line and the log, never silently renders a black frame.
 */
public enum CameraRenderMode {
    /** Prefer the shader pipeline when shaders are active, otherwise use vanilla rendering. */
    FOLLOW_PLAYER("follow"),
    /** Always render without shaders (own off-screen target; fast and independent). */
    OFF("off"),
    /** Try to render through the shader pipeline even if the player has shaders disabled. */
    ON("on");

    private final String serializedName;

    CameraRenderMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** The lower-case name used in NBT and on the wire. */
    public String serializedName() {
        return this.serializedName;
    }

    /** Parses a name, falling back to {@link #FOLLOW_PLAYER}. */
    public static CameraRenderMode byNameOrDefault(String name) {
        if (name != null) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (CameraRenderMode mode : values()) {
                if (mode.serializedName.equals(normalized)) {
                    return mode;
                }
            }
        }
        return FOLLOW_PLAYER;
    }

    /** The next value in the GUI cycle. */
    public CameraRenderMode cycle() {
        return switch (this) {
            case FOLLOW_PLAYER -> OFF;
            case OFF -> ON;
            case ON -> FOLLOW_PLAYER;
        };
    }
}
