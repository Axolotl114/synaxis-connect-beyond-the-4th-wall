package com.ccvirtualcoms.client.gui;

import com.ccvirtualcoms.camera.CameraAxisMode;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraRedstoneMode;
import com.ccvirtualcoms.camera.CameraRenderMode;
import com.ccvirtualcoms.camera.CameraResolution;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import com.ccvirtualcoms.net.CameraConfigPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The Synaxis-styled configuration screen of a camera transmitter.
 *
 * <p>Four tabs cover the whole specification:
 *
 * <ol>
 *   <li><b>Video output</b>: resolution presets plus a validated custom size, frame rate, colour /
 *       monochrome and night vision;</li>
 *   <li><b>Trigger</b>: trigger source (circuit / redstone), hold state, redstone mode (hold /
 *       toggle), threshold 0-15 and the two Create wireless redstone frequency slots;</li>
 *   <li><b>Camera</b>: per-axis world-stable / follow-sub-level mode for yaw, pitch and roll, the
 *       live Euler angle readout and the local offset XYZ;</li>
 *   <li><b>Render &amp; status</b>: shader preference and the live status block (stream state,
 *       device name, fallback URL, redstone strength, revision, device slot, detail).</li>
 * </ol>
 *
 * <p>The screen is declarative and the server is authoritative. Every edit produces a complete
 * {@link CameraConfig} snapshot that is sent as a {@link CameraConfigPayload}; sends are merged
 * inside a 100 ms window so dragging a slider or typing a number does not flood the server. Nothing
 * is persisted client-side. When the server bumps the configuration revision the working copy is
 * re-adopted, which is also how server-side clamping (resolution budget, offsets, item counts) is
 * reflected back into the GUI.
 *
 * <p>The panel palette, geometry and the five reused PNGs follow Synaxis' own GUI (see
 * {@link CameraGuiStyle} and the research note {@code .research/camera_gui.md}).
 */
public class CameraTransmitterScreen extends Screen {

    /** How long the screen may keep batching edits before it flushes a configuration snapshot. */
    private static final long SEND_INTERVAL_MS = 100L;

    private static final Component TITLE = Component.translatable("cc_virtual_coms.camera.title");

    private static final Predicate<String> DIGITS = value -> value.matches("[0-9]{0,4}");
    private static final Predicate<String> THRESHOLD_DIGITS = value -> value.matches("[0-9]{0,2}");
    private static final Predicate<String> SIGNED_NUMBER =
            value -> value.matches("-?[0-9]{0,3}(\\.[0-9]{0,3})?");

    /** The four tabs, in display order. */
    private enum Page {
        VIDEO("video"),
        TRIGGER("trigger"),
        CAMERA("camera"),
        RENDER_STATUS("render_status");

        private final String key;

        Page(String key) {
            this.key = key;
        }

        Component title() {
            return Component.translatable("cc_virtual_coms.camera.page." + this.key);
        }

        Component tooltip() {
            return Component.translatable("cc_virtual_coms.camera.tooltip.page." + this.key);
        }
    }

    private record InputSurface(EditBox box, int x, int y, int width, int height) {
    }

    private record LabelRow(int row, String labelKey) {
    }

    private record ReadonlyRow(int row, Supplier<Component> value, Supplier<Component> tooltip) {
    }

    private record Suffix(int row, String text) {
    }

    private final BlockPos pos;

    /** Working copy of the configuration; the server remains authoritative. */
    private CameraConfig config = CameraConfig.DEFAULT;

    /** Whether the custom width / height fields (rather than a preset) are active. */
    private boolean customResolution;

    private Page page = Page.VIDEO;

    private int left;
    private int top;
    private int rowCount;
    private int lastServerRevision = -1;
    private boolean dirty;
    private long lastEditAt;
    private boolean suppressEdit;
    private boolean initialStatusRequested;

    private final List<InputSurface> inputSurfaces = new ArrayList<>();
    private final List<LabelRow> labelRows = new ArrayList<>();
    private final List<ReadonlyRow> readonlyRows = new ArrayList<>();
    private final List<Suffix> suffixes = new ArrayList<>();

    @Nullable
    private EditBox widthBox;
    @Nullable
    private EditBox heightBox;

    private String widthDraft = "";
    private String heightDraft = "";
    private String thresholdDraft = "";
    private String fovDraft = "";
    private String yawDraft = "0";
    private String pitchDraft = "0";
    private String rollDraft = "0";
    private String offsetXDraft = "";
    private String offsetYDraft = "";
    private String offsetZDraft = "";

    public CameraTransmitterScreen(CameraTransmitterBlockEntity camera) {
        super(TITLE);
        this.pos = camera.getBlockPos();
        this.adopt(camera.config());
        this.lastServerRevision = camera.revision();
    }

    // ------------------------------------------------------------------ state

    /** The camera block entity, or {@code null} when it has been removed or unloaded. */
    @Nullable
    private CameraTransmitterBlockEntity camera() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        // Must go through the locator: a camera on a Sable sub-level lives in a plot chunk, not in
        // the main level's block entity storage.
        return com.ccvirtualcoms.content.camera.CameraLocator.find(minecraft.level, this.pos);
    }

    private void adopt(CameraConfig server) {
        this.config = server;
        this.customResolution = !server.resolution().isPreset();
        this.widthDraft = Integer.toString(server.resolution().width());
        this.heightDraft = Integer.toString(server.resolution().height());
        this.thresholdDraft = Integer.toString(server.redstoneThreshold());
        this.fovDraft = Integer.toString(server.fov());
        this.offsetXDraft = formatOffset(server.offsetX());
        this.offsetYDraft = formatOffset(server.offsetY());
        this.offsetZDraft = formatOffset(server.offsetZ());
    }

    private void markChanged(CameraConfig next) {
        this.config = next;
        this.dirty = true;
        this.lastEditAt = Util.getMillis();
    }

    private void flush() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        PacketDistributor.sendToServer(new CameraConfigPayload(this.pos, this.config));
    }

    private void flushIfDue() {
        if (this.dirty && Util.getMillis() - this.lastEditAt >= SEND_INTERVAL_MS) {
            this.flush();
        }
    }

    // ------------------------------------------------------------------ screen lifecycle

    @Override
    protected void init() {
        super.init();
        var initialCamera = this.camera();
        if (initialCamera != null) {
            double[] euler = initialCamera.eulerAngles();
            this.yawDraft = Integer.toString((int) Math.round(euler[0]));
            this.pitchDraft = Integer.toString((int) Math.round(euler[1]));
            this.rollDraft = Integer.toString((int) Math.round(euler[2]));
        }
        this.left = Math.max(2, (this.width - CameraGuiStyle.PANEL_WIDTH) / 2);
        this.top = Math.max(2, (this.height - CameraGuiStyle.PANEL_HEIGHT) / 2);

        this.inputSurfaces.clear();
        this.labelRows.clear();
        this.readonlyRows.clear();
        this.suffixes.clear();
        this.rowCount = 0;
        this.widthBox = null;
        this.heightBox = null;

        this.addTabs();
        this.addDoneButton();

        switch (this.page) {
            case VIDEO -> this.buildVideoPage();
            case TRIGGER -> this.buildTriggerPage();
            case CAMERA -> this.buildCameraPage();
            case RENDER_STATUS -> this.buildRenderStatusPage();
        }

        this.updateEditorStates();
        this.syncEditors();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        // Rebuild so the window stays centred after a GUI scale / window size change.
        this.rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        CameraTransmitterBlockEntity camera = this.camera();
        if (camera == null) {
            this.onClose();
            return;
        }
        int revision = camera.revision();
        // The server pushes a status snapshot on load and periodically, so nothing is sent on open.
        // Sending our (possibly stale) snapshot here used to race with another client or with a
        // configuration change and could silently revert the server state.
        this.initialStatusRequested = true;
        if (revision != this.lastServerRevision) {
            this.lastServerRevision = revision;
            // A revision change means the server accepted a snapshot (ours or somebody else's).
            // Only re-adopt while we have no pending local edits, so typing is never interrupted.
            if (!this.dirty) {
                CameraConfig server = camera.config();
                if (!server.equals(this.config)) {
                    this.adopt(server);
                    this.updateEditorStates();
                    this.syncEditors();
                }
            }
        }
        this.flushIfDue();
    }

    @Override
    public void onClose() {
        this.flush();
        super.onClose();
    }

    @Override
    public void removed() {
        this.flush();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ shared layout helpers

    private int rowY(int row) {
        return this.top + CameraGuiStyle.CONTENT_Y + row * CameraGuiStyle.ROW_PITCH;
    }

    private int labelX() {
        return this.left + CameraGuiStyle.CONTENT_X;
    }

    private int controlX() {
        return this.left + CameraGuiStyle.CONTROL_X;
    }

    private void addRowLabel(int row, String labelKey) {
        this.rowCount = Math.max(this.rowCount, row + 1);
        this.labelRows.add(new LabelRow(row, labelKey));
    }

    private void addReadonly(int row, String labelKey, Supplier<Component> value,
                             Supplier<Component> tooltip) {
        this.addRowLabel(row, labelKey);
        this.readonlyRows.add(new ReadonlyRow(row, value, tooltip));
    }

    private void addSuffix(int row, String text) {
        this.suffixes.add(new Suffix(row, text));
    }

    private CameraCycleButton addCycle(int row, String labelKey, Supplier<Component> value,
                                       IntConsumer onCycle, Tooltip tooltip) {
        this.addRowLabel(row, labelKey);
        CameraCycleButton button = new CameraCycleButton(this.controlX(), this.rowY(row),
                CameraGuiStyle.CONTROL_WIDTH, CameraGuiStyle.ROW_HEIGHT, Component.empty(),
                value, onCycle);
        button.setTooltip(tooltip);
        this.addRenderableWidget(button);
        return button;
    }

    private CameraToggleButton addToggle(int row, String labelKey, Supplier<Boolean> state,
                                         Supplier<Component> onLabel, Supplier<Component> offLabel,
                                         Runnable onToggle, Tooltip tooltip) {
        this.addRowLabel(row, labelKey);
        CameraToggleButton button = new CameraToggleButton(this.controlX(), this.rowY(row),
                CameraGuiStyle.CONTROL_WIDTH, CameraGuiStyle.ROW_HEIGHT, Component.empty(),
                state, onLabel, offLabel, onToggle);
        button.setTooltip(tooltip);
        this.addRenderableWidget(button);
        return button;
    }

    private EditBox addEditor(int row, String draft, int controlOffset, int width,
                              int maxLength, Predicate<String> filter, Consumer<String> responder,
                              Tooltip tooltip) {
        int surfaceX = this.controlX() + controlOffset;
        int surfaceY = this.rowY(row) + 1;
        EditBox box = new EditBox(this.font, surfaceX + 4, surfaceY + 1, width - 8,
                CameraGuiStyle.INPUT_HEIGHT, Component.empty());
        box.setBordered(false);
        box.setTextColor(CameraGuiStyle.COLOR_VALUE);
        box.setTextColorUneditable(CameraGuiStyle.COLOR_MUTED);
        box.setMaxLength(maxLength);
        box.setFilter(filter);
        box.setValue(draft);
        box.setResponder(value -> {
            if (this.suppressEdit) {
                return;
            }
            responder.accept(value);
        });
        box.setTooltip(tooltip);
        this.addRenderableWidget(box);
        this.inputSurfaces.add(new InputSurface(box, surfaceX, surfaceY, width,
                CameraGuiStyle.INPUT_HEIGHT + 1));
        return box;
    }

    private void setEditorValue(@Nullable EditBox box, String value) {
        if (box == null || box.isFocused() || value.equals(box.getValue())) {
            return;
        }
        this.suppressEdit = true;
        box.setValue(value);
        this.suppressEdit = false;
    }

    private void syncEditors() {
        this.setEditorValue(this.widthBox, this.widthDraft);
        this.setEditorValue(this.heightBox, this.heightDraft);
    }

    private void updateEditorStates() {
        if (this.widthBox != null) {
            this.widthBox.setEditable(this.customResolution);
        }
        if (this.heightBox != null) {
            this.heightBox.setEditable(this.customResolution);
        }
    }

    // ------------------------------------------------------------------ chrome

    private void addTabs() {
        Page[] pages = Page.values();
        for (int index = 0; index < pages.length; index++) {
            Page target = pages[index];
            CameraTabButton tab = new CameraTabButton(
                    this.left + CameraGuiStyle.TAB_X + index * CameraGuiStyle.TAB_STEP,
                    this.top + CameraGuiStyle.TAB_Y, target.title(), target == this.page,
                    () -> this.switchPage(target));
            tab.setTooltip(Tooltip.create(target.tooltip()));
            this.addRenderableWidget(tab);
        }
    }

    private void addDoneButton() {
        CameraActionButton done = new CameraActionButton(
                this.left + CameraGuiStyle.PANEL_WIDTH - CameraGuiStyle.BOTTOM_STRIP_X
                        - CameraGuiStyle.ACTION_BUTTON_WIDTH,
                this.top + CameraGuiStyle.BOTTOM_STRIP_Y,
                CameraGuiStyle.ACTION_BUTTON_WIDTH, CameraGuiStyle.ACTION_BUTTON_HEIGHT,
                Component.translatable("cc_virtual_coms.camera.button.done"),
                () -> Component.translatable("cc_virtual_coms.camera.button.done"),
                () -> {
                    this.flush();
                    this.onClose();
                });
        done.setTooltip(Tooltip.create(
                Component.translatable("cc_virtual_coms.camera.tooltip.done")));
        this.addRenderableWidget(done);
    }

    private void switchPage(Page target) {
        if (target == this.page) {
            return;
        }
        this.flush();
        this.page = target;
        this.rebuildWidgets();
    }

    // ------------------------------------------------------------------ page: video output

    private void buildVideoPage() {
        this.addCycle(0, "cc_virtual_coms.camera.field.resolution", this::resolutionLabel,
                this::cycleResolution,
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.resolution")));

        this.addRowLabel(1, "cc_virtual_coms.camera.field.custom_width");
        this.widthBox = this.addEditor(1, this.widthDraft, 0, CameraGuiStyle.CONTROL_WIDTH, 4,
                DIGITS, this::onWidthChanged,
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.custom_size")));

        this.addRowLabel(2, "cc_virtual_coms.camera.field.custom_height");
        this.heightBox = this.addEditor(2, this.heightDraft, 0, CameraGuiStyle.CONTROL_WIDTH, 4,
                DIGITS, this::onHeightChanged,
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.custom_size")));

        this.addCycle(3, "cc_virtual_coms.camera.field.fps", this::fpsLabel,
                this::cycleFps,
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.fps")));

        this.addToggle(4, "cc_virtual_coms.camera.field.color",
                () -> this.config.color(),
                () -> Component.translatable("cc_virtual_coms.camera.option.color.color"),
                () -> Component.translatable("cc_virtual_coms.camera.option.color.mono"),
                () -> this.markChanged(this.config.withColor(!this.config.color())),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.color")));

        this.addRowLabel(6, "cc_virtual_coms.camera.field.fov");
        this.addEditor(6, this.fovDraft, 0, 40, 3, DIGITS, this::onFovChanged,
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.fov")));
        this.addSuffix(6, "°");

        this.addToggle(5, "cc_virtual_coms.camera.field.night_vision",
                () -> this.config.nightVision(),
                () -> Component.translatable("cc_virtual_coms.camera.option.on"),
                () -> Component.translatable("cc_virtual_coms.camera.option.off"),
                () -> this.markChanged(this.config.withNightVision(!this.config.nightVision())),
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.night_vision")));
    }

    private Component resolutionLabel() {
        String suffix = this.customResolution ? " *" : "";
        return Component.literal(this.config.resolution().label() + suffix);
    }

    private void cycleResolution(int direction) {
        List<CameraResolution> presets = CameraResolution.PRESETS;
        int index;
        if (this.customResolution) {
            index = presets.size();
        } else {
            int presetIndex = presets.indexOf(this.config.resolution());
            index = presetIndex < 0 ? presets.size() : presetIndex;
        }
        int next = Math.floorMod(index + direction, presets.size() + 1);
        if (next == presets.size()) {
            // Entering custom mode keeps the current size as the starting point.
            this.customResolution = true;
            this.widthDraft = Integer.toString(this.config.resolution().width());
            this.heightDraft = Integer.toString(this.config.resolution().height());
        } else {
            this.customResolution = false;
            CameraResolution preset = presets.get(next);
            this.widthDraft = Integer.toString(preset.width());
            this.heightDraft = Integer.toString(preset.height());
            this.markChanged(this.config.withResolution(preset));
        }
        this.updateEditorStates();
        this.syncEditors();
    }

    private void onWidthChanged(String value) {
        this.widthDraft = value;
        if (value.isEmpty()) {
            return;
        }
        int width = this.parseOrDefault(value, this.config.resolution().width());
        int height = this.parseOrDefault(this.heightDraft, this.config.resolution().height());
        this.customResolution = true;
        this.updateEditorStates();
        this.markChanged(this.config.withResolution(CameraResolution.sanitized(width, height)));
    }

    private void onEulerChanged(int axis, String value) {
        if (axis == 0) {
            this.yawDraft = value;
        } else if (axis == 1) {
            this.pitchDraft = value;
        } else {
            this.rollDraft = value;
        }
        if (value.isEmpty()) {
            return;
        }
        double yaw = this.parseOrDefault(this.yawDraft, 0);
        double pitch = this.parseOrDefault(this.pitchDraft, 0);
        double roll = this.parseOrDefault(this.rollDraft, 0);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.ccvirtualcoms.net.CameraEulerPayload(this.pos, yaw, pitch, roll));
    }

    private void onFovChanged(String value) {
        this.fovDraft = value;
        if (value.isEmpty()) {
            return;
        }
        int fov = this.parseOrDefault(value, this.config.fov());
        this.markChanged(this.config.withFov(fov));
        com.ccvirtualcoms.CCVirtualComs.LOGGER.info("[camera-gui] fov changed: text={} parsed={} config.fov={}",
                value, fov, this.config.fov());
        // Send immediately: the debounced tick flush used to miss FOV-only edits, so the value
        // only took effect when another field changed and re-sent the whole config.
        this.dirty = false;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.ccvirtualcoms.net.CameraConfigPayload(this.pos, this.config));
        // FOV is a single value the user expects to see applied immediately; bypass the debounce.
        this.flush();
    }

    private void onHeightChanged(String value) {
        this.heightDraft = value;
        if (value.isEmpty()) {
            return;
        }
        int width = this.parseOrDefault(this.widthDraft, this.config.resolution().width());
        int height = this.parseOrDefault(value, this.config.resolution().height());
        this.customResolution = true;
        this.updateEditorStates();
        this.markChanged(this.config.withResolution(CameraResolution.sanitized(width, height)));
    }

    private Component fpsLabel() {
        return Component.literal(this.config.fps() + " fps");
    }

    private void cycleFps(int direction) {
        int[] values = CameraResolution.FPS_VALUES;
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == this.config.fps()) {
                index = i;
                break;
            }
        }
        int next = Math.floorMod(index + direction, values.length);
        this.markChanged(this.config.withFps(values[next]));
    }

    // ------------------------------------------------------------------ page: trigger

    private void buildTriggerPage() {
        this.addToggle(0, "cc_virtual_coms.camera.field.trigger_source",
                () -> this.config.redstoneControl(),
                () -> Component.translatable("cc_virtual_coms.camera.option.trigger.circuit"),
                () -> Component.translatable("cc_virtual_coms.camera.option.trigger.manual"),
                () -> this.markChanged(this.config.withRedstoneControl(
                        !this.config.redstoneControl())),
                priorityTooltip("cc_virtual_coms.camera.tooltip.trigger_source"));

        this.addToggle(1, "cc_virtual_coms.camera.field.hold",
                () -> this.config.holdOn(),
                () -> Component.translatable("cc_virtual_coms.camera.option.hold.on"),
                () -> Component.translatable("cc_virtual_coms.camera.option.hold.off"),
                () -> this.markChanged(this.config.withHoldOn(!this.config.holdOn())),
                priorityTooltip("cc_virtual_coms.camera.tooltip.hold"));

        this.addCycle(2, "cc_virtual_coms.camera.field.redstone_mode", () -> switch (this.config
                        .redstoneMode()) {
                    case HOLD -> Component.translatable(
                            "cc_virtual_coms.camera.option.redstone.hold");
                    case TOGGLE -> Component.translatable(
                            "cc_virtual_coms.camera.option.redstone.toggle");
                }, direction -> this.markChanged(this.config.withRedstoneMode(
                        this.config.redstoneMode().cycle())),
                priorityTooltip("cc_virtual_coms.camera.tooltip.redstone_mode"));

        this.addRowLabel(3, "cc_virtual_coms.camera.field.threshold");
        this.addEditor(3, this.thresholdDraft, 0, 40, 2, THRESHOLD_DIGITS,
                this::onThresholdChanged,
                priorityTooltip("cc_virtual_coms.camera.tooltip.threshold"));
        this.addSuffix(3, "/ 15");


        this.addReadonly(6, "cc_virtual_coms.camera.field.redstone_signal",
                () -> Component.literal(this.redstoneSignal() + " / 15"),
                () -> Component.translatable(
                        "cc_virtual_coms.camera.tooltip.redstone_signal"));
    }

    private void addGhostSlot(int row, Supplier<ItemStack> stack, Consumer<ItemStack> onSet,
                              Runnable onClear, String tooltipKey) {
        CameraGhostSlot slot = new CameraGhostSlot(this.controlX(), this.rowY(row) - 1, 16,
                Component.empty(), stack, onSet, onClear);
        slot.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        this.addRenderableWidget(slot);
    }

    private void onThresholdChanged(String value) {
        this.thresholdDraft = value;
        if (value.isEmpty()) {
            return;
        }
        this.markChanged(this.config.withRedstoneThreshold(this.parseOrDefault(value,
                this.config.redstoneThreshold())));
    }

    private int redstoneSignal() {
        CameraTransmitterBlockEntity camera = this.camera();
        return camera == null ? 0 : camera.redstoneSignal();
    }

    // ------------------------------------------------------------------ page: camera

    private void buildCameraPage() {
        this.addCycle(0, "cc_virtual_coms.camera.field.yaw_mode",
                () -> axisModeLabel(this.config.yawMode()),
                direction -> this.cycleAxisMode(0),
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.axis_mode")));
        this.addCycle(1, "cc_virtual_coms.camera.field.pitch_mode",
                () -> axisModeLabel(this.config.pitchMode()),
                direction -> this.cycleAxisMode(1),
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.axis_mode")));
        this.addCycle(2, "cc_virtual_coms.camera.field.roll_mode",
                () -> axisModeLabel(this.config.rollMode()),
                direction -> this.cycleAxisMode(2),
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.axis_mode")));

        this.addRowLabel(3, "Yaw");
        this.addEditor(3, this.yawDraft, 0, 60, 7, SIGNED_NUMBER,
                value -> this.onEulerChanged(0, value),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.euler")));
        this.addRowLabel(4, "Pitch");
        this.addEditor(4, this.pitchDraft, 0, 60, 7, SIGNED_NUMBER,
                value -> this.onEulerChanged(1, value),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.euler")));
        this.addRowLabel(5, "Roll");
        this.addEditor(5, this.rollDraft, 0, 60, 7, SIGNED_NUMBER,
                value -> this.onEulerChanged(2, value),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.euler")));

        this.addRowLabel(6, "cc_virtual_coms.camera.field.offset_x");
        this.addEditor(6, this.offsetXDraft, 0, CameraGuiStyle.CONTROL_WIDTH, 7, SIGNED_NUMBER,
                value -> this.onOffsetChanged(0, value),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.offset")));
        this.addRowLabel(7, "cc_virtual_coms.camera.field.offset_y");
        this.addEditor(7, this.offsetYDraft, 0, CameraGuiStyle.CONTROL_WIDTH, 7, SIGNED_NUMBER,
                value -> this.onOffsetChanged(1, value),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.offset")));
        this.addRowLabel(8, "cc_virtual_coms.camera.field.offset_z");
        this.addEditor(8, this.offsetZDraft, 0, CameraGuiStyle.CONTROL_WIDTH, 7, SIGNED_NUMBER,
                value -> this.onOffsetChanged(2, value),
                Tooltip.create(Component.translatable("cc_virtual_coms.camera.tooltip.offset")));
    }

    private static Component axisModeLabel(CameraAxisMode mode) {
        return switch (mode) {
            case WORLD_STABLE -> Component.translatable(
                    "cc_virtual_coms.camera.option.axis.world");
            case FOLLOW_SUBLEVEL -> Component.translatable(
                    "cc_virtual_coms.camera.option.axis.follow");
        };
    }

    private void cycleAxisMode(int axis) {
        CameraAxisMode yaw = this.config.yawMode();
        CameraAxisMode pitch = this.config.pitchMode();
        CameraAxisMode roll = this.config.rollMode();
        switch (axis) {
            case 0 -> yaw = yaw.cycle();
            case 1 -> pitch = pitch.cycle();
            default -> roll = roll.cycle();
        }
        this.markChanged(this.config.withAxisModes(yaw, pitch, roll));
    }

    private Component eulerLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        if (camera == null) {
            return Component.translatable("cc_virtual_coms.camera.value.none");
        }
        double[] angles = camera.eulerAngles();
        return Component.literal(String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                angles[0], angles[1], angles[2]));
    }

    private void onOffsetChanged(int axis, String value) {
        if (axis == 0) {
            this.offsetXDraft = value;
        } else if (axis == 1) {
            this.offsetYDraft = value;
        } else {
            this.offsetZDraft = value;
        }
        if (value.isEmpty() || value.equals("-") || value.endsWith(".")) {
            return;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return;
        }
        if (!Double.isFinite(parsed)) {
            return;
        }
        double x = this.config.offsetX();
        double y = this.config.offsetY();
        double z = this.config.offsetZ();
        switch (axis) {
            case 0 -> x = parsed;
            case 1 -> y = parsed;
            default -> z = parsed;
        }
        this.markChanged(this.config.withOffset(x, y, z));
    }

    // ------------------------------------------------------------------ page: render + status

    private void buildRenderStatusPage() {
        this.addCycle(0, "cc_virtual_coms.camera.field.render_mode",
                () -> renderModeLabel(this.config.renderMode()),
                direction -> this.markChanged(this.config.withRenderMode(
                        this.config.renderMode().cycle())),
                Tooltip.create(Component.translatable(
                        "cc_virtual_coms.camera.tooltip.render_mode")));

        this.addReadonly(1, "cc_virtual_coms.camera.field.stream_status",
                this::streamStatusLabel,
                () -> Component.translatable("cc_virtual_coms.camera.tooltip.stream_status"));
        this.addReadonly(2, "cc_virtual_coms.camera.field.device_name",
                this::deviceNameLabel,
                () -> Component.translatable("cc_virtual_coms.camera.tooltip.device_name"));
        this.addReadonly(3, "cc_virtual_coms.camera.field.fallback_url",
                this::fallbackUrlLabel,
                this::fallbackUrlTooltip);
        this.addReadonly(4, "cc_virtual_coms.camera.field.redstone_signal",
                () -> Component.literal(this.redstoneSignal() + " / 15"),
                () -> Component.translatable(
                        "cc_virtual_coms.camera.tooltip.redstone_signal"));
        this.addReadonly(5, "cc_virtual_coms.camera.field.enable_input",
                this::enableInputLabel,
                () -> Component.translatable("cc_virtual_coms.camera.tooltip.enable_input"));
        this.addReadonly(6, "cc_virtual_coms.camera.field.revision",
                this::revisionLabel,
                () -> Component.translatable("cc_virtual_coms.camera.tooltip.revision"));
        this.addReadonly(7, "cc_virtual_coms.camera.field.device_slot",
                this::deviceSlotLabel,
                () -> Component.translatable("cc_virtual_coms.camera.tooltip.device_slot"));
        this.addReadonly(8, "cc_virtual_coms.camera.field.detail",
                this::detailLabel,
                this::detailTooltip);
    }

    private static Component renderModeLabel(CameraRenderMode mode) {
        return switch (mode) {
            case FOLLOW_PLAYER -> Component.translatable(
                    "cc_virtual_coms.camera.option.render.follow");
            case OFF -> Component.translatable("cc_virtual_coms.camera.option.render.off");
            case ON -> Component.translatable("cc_virtual_coms.camera.option.render.on");
        };
    }

    private Component streamStatusLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        CameraStreamStatus status = camera == null ? CameraStreamStatus.OFFLINE
                : camera.clientStatus();
        return Component.translatable(
                "cc_virtual_coms.camera.status." + status.serializedName());
    }

    private Component deviceNameLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        String name = camera == null ? "" : camera.clientDeviceName();
        return name == null || name.isBlank()
                ? Component.translatable("cc_virtual_coms.camera.value.none")
                : Component.literal(name);
    }

    private Component fallbackUrlLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        String url = camera == null ? "" : camera.clientStreamUrl();
        return url == null || url.isBlank()
                ? Component.translatable("cc_virtual_coms.camera.value.none")
                : Component.literal(url);
    }

    private Component fallbackUrlTooltip() {
        CameraTransmitterBlockEntity camera = this.camera();
        String url = camera == null ? "" : camera.clientStreamUrl();
        return url == null || url.isBlank()
                ? Component.translatable("cc_virtual_coms.camera.tooltip.fallback_url")
                : Component.literal(url);
    }

    private Component enableInputLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        boolean enabled = camera != null && camera.enableInput();
        return Component.translatable(enabled
                ? "cc_virtual_coms.camera.option.on"
                : "cc_virtual_coms.camera.option.off");
    }

    private Component revisionLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        return Component.literal("rev " + (camera == null ? 0 : camera.revision()));
    }

    private Component deviceSlotLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        int slot = camera == null ? -1 : camera.deviceSlot();
        return slot < 0
                ? Component.translatable("cc_virtual_coms.camera.value.none")
                : Component.literal("slot " + (slot + 1));
    }

    private Component detailLabel() {
        CameraTransmitterBlockEntity camera = this.camera();
        String detail = camera == null ? "" : camera.statusDetail();
        return detail == null || detail.isBlank()
                ? Component.translatable("cc_virtual_coms.camera.value.none")
                : Component.literal(detail);
    }

    private Component detailTooltip() {
        CameraTransmitterBlockEntity camera = this.camera();
        String detail = camera == null ? "" : camera.statusDetail();
        return detail == null || detail.isBlank()
                ? Component.translatable("cc_virtual_coms.camera.tooltip.detail")
                : Component.literal(detail);
    }

    // ------------------------------------------------------------------ tooltips

    private static Tooltip priorityTooltip(String key) {
        Component text = Component.translatable(key).copy()
                .append("\n\n")
                .append(Component.translatable("cc_virtual_coms.camera.tooltip.priority"));
        return Tooltip.create(text);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        CameraGuiStyle.drawWindow(graphics, this.left, this.top);

        // Zebra stripes behind the rows.
        for (int row = 0; row < this.rowCount; row++) {
            int y = this.rowY(row);
            graphics.fill(this.left + CameraGuiStyle.CONTENT_X - 4, y,
                    this.left + CameraGuiStyle.CONTENT_X + CameraGuiStyle.CONTENT_WIDTH + 4,
                    y + CameraGuiStyle.ROW_HEIGHT,
                    row % 2 == 0 ? CameraGuiStyle.COLOR_ROW_EVEN : CameraGuiStyle.COLOR_ROW_ODD);
        }

        // Static backgrounds of the text inputs. The EditBox itself is drawn unbordered so the
        // Synaxis light input style shows through.
        for (InputSurface surface : this.inputSurfaces) {
            CameraGuiStyle.numberInputBackground(graphics, surface.x(), surface.y(), surface.width(),
                    surface.height());
            if (surface.box().isFocused()) {
                CameraGuiStyle.focusBorder(graphics, surface.x(), surface.y(), surface.width(),
                        surface.height());
            }
        }

        // Summary hint bubble (Synaxis' single-line background) and the Done button share the strip
        // just above the decorative bottom border.
        CameraGuiStyle.hintBubble(graphics,
                this.left + CameraGuiStyle.SUMMARY_X,
                this.top + CameraGuiStyle.SUMMARY_Y,
                CameraGuiStyle.SUMMARY_WIDTH,
                CameraGuiStyle.SUMMARY_HEIGHT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderLabelsAndValues(graphics);
        this.renderPageTitle(graphics);
        this.renderSummary(graphics, mouseX, mouseY);
        this.renderReadonlyTooltip(mouseX, mouseY);
    }

    private void renderLabelsAndValues(GuiGraphics graphics) {
        for (LabelRow label : this.labelRows) {
            Component text = Component.translatable(label.labelKey())
                    .withStyle(ChatFormatting.BOLD);
            CameraGuiStyle.label(graphics, this.font, text, this.labelX(),
                    this.rowY(label.row()) + CameraGuiStyle.rowTextOffset());
        }
        for (ReadonlyRow row : this.readonlyRows) {
            String text = CameraGuiStyle.trim(this.font, row.value().get().getString(),
                    CameraGuiStyle.CONTROL_WIDTH);
            CameraGuiStyle.statusText(graphics, this.font, Component.literal(text),
                    this.controlX(), this.rowY(row.row()) + CameraGuiStyle.rowTextOffset());
        }
        for (Suffix suffix : this.suffixes) {
            CameraGuiStyle.muted(graphics, this.font, Component.literal(suffix.text()),
                    this.controlX() + 44, this.rowY(suffix.row()) + CameraGuiStyle.rowTextOffset());
        }
    }

    private void renderPageTitle(GuiGraphics graphics) {
        Component title = this.page.title().copy().withStyle(ChatFormatting.BOLD);
        int x = this.left + CameraGuiStyle.PANEL_WIDTH - 10 - this.font.width(title);
        CameraGuiStyle.label(graphics, this.font, title, x,
                this.top + CameraGuiStyle.TAB_BAR_HEIGHT / 2 - 4);
    }

    private void renderSummary(GuiGraphics graphics, int mouseX, int mouseY) {
        CameraTransmitterBlockEntity camera = this.camera();
        int revision = camera == null ? 0 : camera.revision();
        int signal = camera == null ? 0 : camera.redstoneSignal();
        Component status = this.streamStatusLabel();
        Component summary = Component.translatable("cc_virtual_coms.camera.summary",
                revision, status, signal);
        String text = CameraGuiStyle.trim(this.font, summary.getString(),
                CameraGuiStyle.SUMMARY_WIDTH - 16);
        graphics.drawString(this.font, text,
                this.left + CameraGuiStyle.SUMMARY_X + 8,
                this.top + CameraGuiStyle.SUMMARY_Y + 5,
                CameraGuiStyle.COLOR_VALUE, false);
        if (this.isOverSummary(mouseX, mouseY)) {
            this.setTooltipForNextRenderPass(summary);
        }
    }

    private boolean isOverSummary(int mouseX, int mouseY) {
        int x = this.left + CameraGuiStyle.SUMMARY_X;
        int y = this.top + CameraGuiStyle.SUMMARY_Y;
        return mouseX >= x && mouseX < x + CameraGuiStyle.SUMMARY_WIDTH
                && mouseY >= y && mouseY < y + CameraGuiStyle.SUMMARY_HEIGHT;
    }

    private void renderReadonlyTooltip(int mouseX, int mouseY) {
        for (ReadonlyRow row : this.readonlyRows) {
            int y = this.rowY(row.row());
            if (mouseX >= this.controlX() && mouseX < this.controlX()
                    + CameraGuiStyle.CONTROL_WIDTH
                    && mouseY >= y && mouseY < y + CameraGuiStyle.ROW_HEIGHT) {
                this.setTooltipForNextRenderPass(row.tooltip().get());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private int parseOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String formatOffset(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
