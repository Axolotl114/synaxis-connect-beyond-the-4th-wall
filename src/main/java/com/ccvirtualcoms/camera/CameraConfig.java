package com.ccvirtualcoms.camera;

import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * The complete, server-authoritative configuration of one camera transmitter.
 *
 * <p>This is a pure value object: it never mutates in place. The GUI sends a full snapshot to the
 * server, the server validates it through {@link #sanitized()}, stores it, and mirrors it back to
 * every watching client. Keeping the snapshot declarative makes the two sides trivially consistent.
 *
 * <p><b>Trigger priority</b> (highest first; also documented in the GUI tooltip):
 * <ol>
 *   <li>Synaxis {@code enable} terminal</li>
 *   <li>redstone (vanilla neighbour signal, or Create wireless redstone link when both frequency
 *       slots are filled)</li>
 *   <li>the GUI "hold on / hold off" state</li>
 * </ol>
 * When {@link #redstoneControl()} is {@code false} the GUI hold state is the sole authority. When
 * it is {@code true} the Synaxis terminal or redstone may take over:
 * <pre>
 * control=off, enable=any,  redstone=any   -&gt; hold state
 * control=on,  enable=true, redstone=any   -&gt; on
 * control=on,  enable=false, redstone=on   -&gt; on
 * control=on,  enable=false, redstone=off  -&gt; off
 * </pre>
 *
 * @param resolution        output frame size
 * @param fps               output frame rate, one of {@link CameraResolution#FPS_VALUES}
 * @param color             {@code true} for colour, {@code false} for monochrome
 * @param nightVision       extra brightness/gamma gain; monochrome + night vision is thermal-like
 * @param redstoneControl   whether the circuit/redstone trigger source may take over
 * @param holdOn            the GUI hold state (only used when {@link #redstoneControl} is off)
 * @param redstoneMode      hold or toggle semantics for the redstone input
 * @param redstoneThreshold signal strength threshold, 0-15
 * @param frequencyA        first Create wireless redstone frequency item, or empty
 * @param frequencyB        second Create wireless redstone frequency item, or empty
 * @param yawMode           interpretation of the yaw component of {@code euler_angle}
 * @param pitchMode         interpretation of the pitch component of {@code euler_angle}
 * @param rollMode          interpretation of the roll component of {@code euler_angle}
 * @param offsetX           camera offset from the block centre, sub-level local X
 * @param offsetY           camera offset from the block centre, sub-level local Y
 * @param offsetZ           camera offset from the block centre, sub-level local Z
 * @param renderMode        shader pipeline preference
 */
public record CameraConfig(
        CameraResolution resolution,
        int fps,
        boolean color,
        boolean nightVision,
        boolean redstoneControl,
        boolean holdOn,
        CameraRedstoneMode redstoneMode,
        int redstoneThreshold,
        ItemStack frequencyA,
        ItemStack frequencyB,
        CameraAxisMode yawMode,
        CameraAxisMode pitchMode,
        CameraAxisMode rollMode,
        double offsetX,
        double offsetY,
        double offsetZ,
        CameraRenderMode renderMode,
        int fov) {

    /** Maximum local position offset on any axis, in blocks. */
    public static final double MAX_OFFSET = 16.0D;

    /** Vertical field of view limits for the camera, in degrees. */
    public static final int MIN_FOV = 30;
    public static final int MAX_FOV = 120;
    public static final int DEFAULT_FOV = 70;

    /** The configuration a freshly placed camera starts with. */
    public static final CameraConfig DEFAULT = new CameraConfig(
            CameraResolution.DEFAULT,
            30,
            true,
            false,
            false,
            false,
            CameraRedstoneMode.HOLD,
            0,
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            CameraAxisMode.WORLD_STABLE,
            CameraAxisMode.WORLD_STABLE,
            CameraAxisMode.WORLD_STABLE,
            0.0D,
            0.0D,
            0.0D,
            CameraRenderMode.FOLLOW_PLAYER,
            DEFAULT_FOV);

    public CameraConfig {
        resolution = resolution == null ? CameraResolution.DEFAULT : resolution;
        fps = clampFps(fps);
        redstoneMode = redstoneMode == null ? CameraRedstoneMode.HOLD : redstoneMode;
        redstoneThreshold = Math.max(0, Math.min(15, redstoneThreshold));
        frequencyA = normaliseFrequency(frequencyA);
        frequencyB = normaliseFrequency(frequencyB);
        yawMode = yawMode == null ? CameraAxisMode.WORLD_STABLE : yawMode;
        pitchMode = pitchMode == null ? CameraAxisMode.WORLD_STABLE : pitchMode;
        rollMode = rollMode == null ? CameraAxisMode.WORLD_STABLE : rollMode;
        offsetX = clampOffset(offsetX);
        offsetY = clampOffset(offsetY);
        offsetZ = clampOffset(offsetZ);
        renderMode = renderMode == null ? CameraRenderMode.FOLLOW_PLAYER : renderMode;
        fov = clampFov(fov);
    }

    /** {@code true} when either wireless redstone frequency slot is filled. */
    public boolean hasWirelessFrequency() {
        return !this.frequencyA.isEmpty() || !this.frequencyB.isEmpty();
    }

    /** {@code true} when the redstone input should be read at all. */
    public boolean usesRedstoneInput() {
        return this.redstoneControl;
    }

    /** The stream state implied by the GUI hold setting alone. */
    public boolean holdState() {
        return this.holdOn;
    }

    /**
     * Applies server-side validation to a client-supplied snapshot.
     *
     * <p>Resolution and frame rate are clamped together so a custom size can never exceed the
     * per-camera pixel budget. Item stacks are copied and reduced to one item so a crafted client
     * cannot duplicate frequency items through the configuration packet.
     */
    public CameraConfig sanitized() {
        int safeFps = clampFps(this.fps);
        CameraResolution safeResolution = CameraResolution.sanitizedForFps(
                this.resolution.width(), this.resolution.height(), safeFps);
        return new CameraConfig(
                safeResolution,
                safeFps,
                this.color,
                this.nightVision,
                this.redstoneControl,
                this.holdOn,
                this.redstoneMode,
                this.redstoneThreshold,
                normaliseFrequency(this.frequencyA),
                normaliseFrequency(this.frequencyB),
                this.yawMode,
                this.pitchMode,
                this.rollMode,
                clampOffset(this.offsetX),
                clampOffset(this.offsetY),
                clampOffset(this.offsetZ),
                this.renderMode,
                clampFov(this.fov));
    }

    // ------------------------------------------------------------------ copy helpers

    public CameraConfig withResolution(CameraResolution value) {
        return new CameraConfig(value, this.fps, this.color, this.nightVision, this.redstoneControl,
                this.holdOn, this.redstoneMode, this.redstoneThreshold, this.frequencyA, this.frequencyB,
                this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY, this.offsetZ,
                this.renderMode, this.fov);
    }

    public CameraConfig withFps(int value) {
        return new CameraConfig(this.resolution, clampFps(value), this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, this.redstoneThreshold, this.frequencyA,
                this.frequencyB, this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY,
                this.offsetZ, this.renderMode, this.fov);
    }

    public CameraConfig withFov(int value) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, this.redstoneThreshold,
                this.frequencyA, this.frequencyB, this.yawMode, this.pitchMode, this.rollMode,
                this.offsetX, this.offsetY, this.offsetZ, this.renderMode, clampFov(value));
    }

    public CameraConfig withColor(boolean value) {
        return new CameraConfig(this.resolution, this.fps, value, this.nightVision, this.redstoneControl,
                this.holdOn, this.redstoneMode, this.redstoneThreshold, this.frequencyA, this.frequencyB,
                this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY, this.offsetZ,
                this.renderMode, this.fov);
    }

    public CameraConfig withNightVision(boolean value) {
        return new CameraConfig(this.resolution, this.fps, this.color, value, this.redstoneControl,
                this.holdOn, this.redstoneMode, this.redstoneThreshold, this.frequencyA, this.frequencyB,
                this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY, this.offsetZ,
                this.renderMode, this.fov);
    }

    public CameraConfig withRedstoneControl(boolean value) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision, value,
                this.holdOn, this.redstoneMode, this.redstoneThreshold, this.frequencyA, this.frequencyB,
                this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY, this.offsetZ,
                this.renderMode, this.fov);
    }

    public CameraConfig withHoldOn(boolean value) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, value, this.redstoneMode, this.redstoneThreshold, this.frequencyA,
                this.frequencyB, this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY,
                this.offsetZ, this.renderMode, this.fov);
    }

    public CameraConfig withRedstoneMode(CameraRedstoneMode value) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, value, this.redstoneThreshold, this.frequencyA,
                this.frequencyB, this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY,
                this.offsetZ, this.renderMode, this.fov);
    }

    public CameraConfig withRedstoneThreshold(int value) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, value, this.frequencyA,
                this.frequencyB, this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY,
                this.offsetZ, this.renderMode, this.fov);
    }

    public CameraConfig withFrequencies(ItemStack a, ItemStack b) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, this.redstoneThreshold, a, b,
                this.yawMode, this.pitchMode, this.rollMode, this.offsetX, this.offsetY, this.offsetZ,
                this.renderMode, this.fov);
    }

    public CameraConfig withAxisModes(CameraAxisMode yaw, CameraAxisMode pitch, CameraAxisMode roll) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, this.redstoneThreshold,
                this.frequencyA, this.frequencyB, yaw, pitch, roll, this.offsetX, this.offsetY,
                this.offsetZ, this.renderMode, this.fov);
    }

    public CameraConfig withOffset(double x, double y, double z) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, this.redstoneThreshold,
                this.frequencyA, this.frequencyB, this.yawMode, this.pitchMode, this.rollMode,
                x, y, z, this.renderMode, this.fov);
    }

    public CameraConfig withRenderMode(CameraRenderMode value) {
        return new CameraConfig(this.resolution, this.fps, this.color, this.nightVision,
                this.redstoneControl, this.holdOn, this.redstoneMode, this.redstoneThreshold,
                this.frequencyA, this.frequencyB, this.yawMode, this.pitchMode, this.rollMode,
                this.offsetX, this.offsetY, this.offsetZ, value, this.fov);
    }

    // ------------------------------------------------------------------ NBT

    private static final String TAG_RESOLUTION = "Resolution";
    private static final String TAG_FPS = "Fps";
    private static final String TAG_COLOR = "Color";
    private static final String TAG_NIGHT_VISION = "NightVision";
    private static final String TAG_REDSTONE_CONTROL = "RedstoneControl";
    private static final String TAG_HOLD_ON = "HoldOn";
    private static final String TAG_REDSTONE_MODE = "RedstoneMode";
    private static final String TAG_REDSTONE_THRESHOLD = "RedstoneThreshold";
    private static final String TAG_FREQUENCY_A = "FrequencyA";
    private static final String TAG_FREQUENCY_B = "FrequencyB";
    private static final String TAG_YAW_MODE = "YawMode";
    private static final String TAG_PITCH_MODE = "PitchMode";
    private static final String TAG_ROLL_MODE = "RollMode";
    private static final String TAG_OFFSET_X = "OffsetX";
    private static final String TAG_OFFSET_Y = "OffsetY";
    private static final String TAG_OFFSET_Z = "OffsetZ";
    private static final String TAG_RENDER_MODE = "RenderMode";
    private static final String TAG_FOV = "Fov";

    /** Serialises the configuration, including the two frequency item stacks. */
    public CompoundTag toTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        CompoundTag resolutionTag = new CompoundTag();
        resolutionTag.putInt("W", this.resolution.width());
        resolutionTag.putInt("H", this.resolution.height());
        tag.put(TAG_RESOLUTION, resolutionTag);
        tag.putInt(TAG_FPS, this.fps);
        tag.putBoolean(TAG_COLOR, this.color);
        tag.putBoolean(TAG_NIGHT_VISION, this.nightVision);
        tag.putBoolean(TAG_REDSTONE_CONTROL, this.redstoneControl);
        tag.putBoolean(TAG_HOLD_ON, this.holdOn);
        tag.putString(TAG_REDSTONE_MODE, this.redstoneMode.serializedName());
        tag.putInt(TAG_REDSTONE_THRESHOLD, this.redstoneThreshold);
        // Empty stacks are simply omitted: an empty ItemStack tag cannot always be
        // encoded back to SNBT by vanilla data commands.
        if (!this.frequencyA.isEmpty()) {
            tag.put(TAG_FREQUENCY_A, this.frequencyA.save(registries));
        }
        if (!this.frequencyB.isEmpty()) {
            tag.put(TAG_FREQUENCY_B, this.frequencyB.save(registries));
        }
        tag.putString(TAG_YAW_MODE, this.yawMode.serializedName());
        tag.putString(TAG_PITCH_MODE, this.pitchMode.serializedName());
        tag.putString(TAG_ROLL_MODE, this.rollMode.serializedName());
        tag.putDouble(TAG_OFFSET_X, this.offsetX);
        tag.putDouble(TAG_OFFSET_Y, this.offsetY);
        tag.putDouble(TAG_OFFSET_Z, this.offsetZ);
        tag.putString(TAG_RENDER_MODE, this.renderMode.serializedName());
        tag.putInt(TAG_FOV, this.fov);
        return tag;
    }

    /** Reads a configuration tag; missing or malformed fields fall back to {@link #DEFAULT}. */
    public static CameraConfig fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return DEFAULT;
        }
        CameraConfig fallback = DEFAULT;
        CompoundTag resolutionTag = tag.getCompound(TAG_RESOLUTION);
        int width = resolutionTag.contains("W") ? resolutionTag.getInt("W") : fallback.resolution.width();
        int height = resolutionTag.contains("H") ? resolutionTag.getInt("H") : fallback.resolution.height();
        return new CameraConfig(
                new CameraResolution(width, height),
                tag.contains(TAG_FPS) ? tag.getInt(TAG_FPS) : fallback.fps,
                !tag.contains(TAG_COLOR) || tag.getBoolean(TAG_COLOR),
                tag.contains(TAG_NIGHT_VISION) && tag.getBoolean(TAG_NIGHT_VISION),
                tag.contains(TAG_REDSTONE_CONTROL) && tag.getBoolean(TAG_REDSTONE_CONTROL),
                tag.contains(TAG_HOLD_ON) && tag.getBoolean(TAG_HOLD_ON),
                CameraRedstoneMode.byNameOrDefault(tag.getString(TAG_REDSTONE_MODE)),
                tag.contains(TAG_REDSTONE_THRESHOLD) ? tag.getInt(TAG_REDSTONE_THRESHOLD) : fallback.redstoneThreshold,
                readStack(tag, TAG_FREQUENCY_A, registries),
                readStack(tag, TAG_FREQUENCY_B, registries),
                CameraAxisMode.byNameOrDefault(tag.getString(TAG_YAW_MODE)),
                CameraAxisMode.byNameOrDefault(tag.getString(TAG_PITCH_MODE)),
                CameraAxisMode.byNameOrDefault(tag.getString(TAG_ROLL_MODE)),
                tag.contains(TAG_OFFSET_X) ? tag.getDouble(TAG_OFFSET_X) : 0.0D,
                tag.contains(TAG_OFFSET_Y) ? tag.getDouble(TAG_OFFSET_Y) : 0.0D,
                tag.contains(TAG_OFFSET_Z) ? tag.getDouble(TAG_OFFSET_Z) : 0.0D,
                CameraRenderMode.byNameOrDefault(tag.getString(TAG_RENDER_MODE)),
                tag.contains(TAG_FOV) ? tag.getInt(TAG_FOV) : fallback.fov);
    }

    private static ItemStack readStack(CompoundTag parent, String key, HolderLookup.Provider registries) {
        if (!parent.contains(key, CompoundTag.TAG_COMPOUND)) {
            return ItemStack.EMPTY;
        }
        return normaliseFrequency(ItemStack.parseOptional(registries, parent.getCompound(key)));
    }

    // ------------------------------------------------------------------ validation helpers

    private static int clampFps(int value) {
        for (int allowed : CameraResolution.FPS_VALUES) {
            if (allowed == value) {
                return allowed;
            }
        }
        return 30;
    }

    private static int clampFov(int value) {
        return Math.max(MIN_FOV, Math.min(MAX_FOV, value));
    }

    private static double clampOffset(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, value));
    }

    private static ItemStack normaliseFrequency(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copyWithCount(1);
        return copy;
    }

    /** A short signature used to detect real configuration changes cheaply. */
    public String signature() {
        return String.join("|", String.valueOf(this.resolution.width()), String.valueOf(this.resolution.height()),
                String.valueOf(this.fps), String.valueOf(this.color), String.valueOf(this.nightVision),
                String.valueOf(this.redstoneControl), String.valueOf(this.holdOn),
                this.redstoneMode.serializedName(), String.valueOf(this.redstoneThreshold),
                stackSignature(this.frequencyA), stackSignature(this.frequencyB),
                this.yawMode.serializedName(), this.pitchMode.serializedName(), this.rollMode.serializedName(),
                Double.toString(this.offsetX), Double.toString(this.offsetY), Double.toString(this.offsetZ),
                this.renderMode.serializedName());
    }

    /**
     * Content-aware signature of a frequency item.
     *
     * <p>Comparing only {@code isEmpty()} would make "replace frequency A with a different item"
     * look like an unchanged configuration, so the item id, count and component/data hash are all
     * part of the signature.
     */
    private static String stackSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return stack.getCount() + "x" + stack.getItem() + "#" + stack.getComponents().hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CameraConfig config && Objects.equals(this.signature(), config.signature());
    }

    @Override
    public int hashCode() {
        return this.signature().hashCode();
    }
}
