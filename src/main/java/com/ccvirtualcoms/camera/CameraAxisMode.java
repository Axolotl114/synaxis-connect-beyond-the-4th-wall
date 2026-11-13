package com.ccvirtualcoms.camera;

import java.util.Locale;

/**
 * How one Euler angle component of a camera transmitter is interpreted.
 *
 * <ul>
 *   <li>{@link #WORLD_STABLE}: the component is an absolute world-space angle. If the block sits
 *       on a Sable sub-level, the camera still uses the absolute world angle, so the view does not
 *       follow the sub-level's rotation on this axis.</li>
 *   <li>{@link #FOLLOW_SUBLEVEL}: the component is a local angle relative to the sub-level. The
 *       rendered camera composes it with the sub-level's pose, so the view turns with the ship.</li>
 * </ul>
 *
 * <p>When the block is not inside a sub-level, {@link #FOLLOW_SUBLEVEL} degrades to
 * {@link #WORLD_STABLE} because there is no sub-level rotation to follow.
 */
public enum CameraAxisMode {
    WORLD_STABLE("world"),
    FOLLOW_SUBLEVEL("follow");

    private final String serializedName;

    CameraAxisMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** The lower-case name used in NBT and on the wire. */
    public String serializedName() {
        return this.serializedName;
    }

    /** Parses a name, falling back to {@link #WORLD_STABLE}. */
    public static CameraAxisMode byNameOrDefault(String name) {
        if (name != null) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (CameraAxisMode mode : values()) {
                if (mode.serializedName.equals(normalized)) {
                    return mode;
                }
            }
        }
        return WORLD_STABLE;
    }

    /** The next value in the GUI cycle. */
    public CameraAxisMode cycle() {
        return this == WORLD_STABLE ? FOLLOW_SUBLEVEL : WORLD_STABLE;
    }
}
