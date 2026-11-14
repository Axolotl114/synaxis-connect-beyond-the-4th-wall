package com.ccvirtualcoms.client.gui;

import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.content.camera.CameraMonitorBlockEntity;
import com.ccvirtualcoms.net.CameraMonitorPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Configuration screen of a camera monitor.
 *
 * <p>Styled after {@link CameraTransmitterScreen} and reusing {@link CameraGuiStyle}, but much
 * smaller: a camera monitor has exactly two settings.
 *
 * <ul>
 *   <li>the camera whose live feed is shown, chosen from the cameras the client capture runtime has
 *       discovered (plus a "None" entry that shows a black screen);</li>
 *   <li>the display on/off state of the whole merged screen.</li>
 * </ul>
 *
 * <p>Both settings belong to the screen's master block, so the screen is opened for the master and
 * every change is sent as one {@link CameraMonitorPayload}. The server validates the distance,
 * stores the values in the master block entity and syncs them back; the GUI itself never writes
 * world state.
 */
public class CameraMonitorScreen extends Screen {

    /** Camera rows that fit into the content area below the four header rows. */
    private static final int ENTRY_ROWS = 6;

    /** How often the camera list is rescanned, in ticks. */
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private static final Component TITLE = Component.translatable("cc_virtual_coms.monitor.title");

    /** One selectable camera row. */
    private record Entry(long key, Component label, Component tooltip) {
    }

    private final BlockPos pos;

    private long selectedCamera;
    private boolean enabled = true;

    /** {@code true} once this screen sent a change, which disables adopting server snapshots. */
    private boolean localEdits;

    private int page;
    private int refreshTicks;

    private int left;
    private int top;

    private final List<Entry> entries = new ArrayList<>();
    private List<Long> knownCameraKeys = List.of();

    @Nullable
    private CameraActionButton previousButton;
    @Nullable
    private CameraActionButton nextButton;

    public CameraMonitorScreen(BlockPos pos) {
        super(TITLE);
        this.pos = pos.immutable();
        CameraMonitorBlockEntity monitor = this.monitor();
        if (monitor != null) {
            this.selectedCamera = monitor.cameraKey();
            this.enabled = monitor.enabled();
        }
    }

    // ------------------------------------------------------------------ state

    /** The master block entity of this screen, or {@code null} when it is not loaded. */
    @Nullable
    private CameraMonitorBlockEntity monitor() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        var blockEntity = minecraft.level.getBlockEntity(this.pos);
        return blockEntity instanceof CameraMonitorBlockEntity monitor ? monitor : null;
    }

    private void select(long cameraKey) {
        if (this.selectedCamera == cameraKey) {
            return;
        }
        this.selectedCamera = cameraKey;
        this.send();
        this.rebuildWidgets();
    }

    private void togglePower() {
        this.enabled = !this.enabled;
        this.send();
    }

    private void send() {
        this.localEdits = true;
        CameraMonitorBlockEntity monitor = this.monitor();
        BlockPos target = monitor == null ? this.pos : monitor.getBlockPos();
        PacketDistributor.sendToServer(new CameraMonitorPayload(target, this.selectedCamera,
                this.enabled));
    }

    // ------------------------------------------------------------------ screen lifecycle

    @Override
    protected void init() {
        super.init();
        this.left = Math.max(2, (this.width - CameraGuiStyle.PANEL_WIDTH) / 2);
        this.top = Math.max(2, (this.height - CameraGuiStyle.PANEL_HEIGHT) / 2);
        this.refreshEntries();
        this.page = Math.min(this.page, Math.max(0, this.pageCount() - 1));

        this.addPowerToggle();
        this.addPagerButtons();
        this.addEntryButtons();
        this.addDoneButton();
    }

    @Override
    public void tick() {
        super.tick();
        CameraMonitorBlockEntity monitor = this.monitor();
        if (monitor == null) {
            this.onClose();
            return;
        }
        if (!this.localEdits && (monitor.cameraKey() != this.selectedCamera
                || monitor.enabled() != this.enabled)) {
            this.selectedCamera = monitor.cameraKey();
            this.enabled = monitor.enabled();
            this.rebuildWidgets();
            return;
        }
        this.refreshTicks++;
        if (this.refreshTicks >= REFRESH_INTERVAL_TICKS) {
            this.refreshTicks = 0;
            List<Long> keys = this.currentCameraKeys();
            if (!keys.equals(this.knownCameraKeys)) {
                this.rebuildWidgets();
            }
        }
    }

    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        this.rebuildWidgets();
    }

    // ------------------------------------------------------------------ widget setup

    private void addPowerToggle() {
        CameraToggleButton toggle = new CameraToggleButton(
                this.controlX(), this.rowY(1), CameraGuiStyle.CONTROL_WIDTH,
                CameraGuiStyle.ROW_HEIGHT,
                Component.translatable("cc_virtual_coms.monitor.field.power"),
                () -> this.enabled,
                () -> Component.translatable("cc_virtual_coms.monitor.option.on"),
                () -> Component.translatable("cc_virtual_coms.monitor.option.off"),
                this::togglePower);
        toggle.setTooltip(Tooltip.create(
                Component.translatable("cc_virtual_coms.monitor.tooltip.power")));
        this.addRenderableWidget(toggle);
    }

    private void addPagerButtons() {
        int y = this.rowY(3) + 1;
        int x = this.contentX() + CameraGuiStyle.CONTENT_WIDTH - 54;
        this.previousButton = new CameraActionButton(x, y, 26, 11,
                Component.translatable("cc_virtual_coms.monitor.page.previous"),
                () -> Component.literal("<"), () -> this.changePage(-1));
        this.previousButton.setTooltip(Tooltip.create(
                Component.translatable("cc_virtual_coms.monitor.tooltip.previous")));
        this.nextButton = new CameraActionButton(x + 28, y, 26, 11,
                Component.translatable("cc_virtual_coms.monitor.page.next"),
                () -> Component.literal(">"), () -> this.changePage(1));
        this.nextButton.setTooltip(Tooltip.create(
                Component.translatable("cc_virtual_coms.monitor.tooltip.next")));
        this.previousButton.active = this.page > 0;
        this.nextButton.active = this.page < this.pageCount() - 1;
        this.addRenderableWidget(this.previousButton);
        this.addRenderableWidget(this.nextButton);
    }

    private void addEntryButtons() {
        int first = this.page * ENTRY_ROWS;
        for (int index = 0; index < ENTRY_ROWS; index++) {
            int entryIndex = first + index;
            if (entryIndex >= this.entries.size()) {
                break;
            }
            Entry entry = this.entries.get(entryIndex);
            EntryButton button = new EntryButton(this.contentX(), this.rowY(4 + index),
                    CameraGuiStyle.CONTENT_WIDTH, CameraGuiStyle.ROW_HEIGHT, entry);
            this.addRenderableWidget(button);
        }
    }

    private void addDoneButton() {
        CameraActionButton done = new CameraActionButton(
                this.left + CameraGuiStyle.PANEL_WIDTH - CameraGuiStyle.BOTTOM_STRIP_X
                        - CameraGuiStyle.ACTION_BUTTON_WIDTH,
                this.top + CameraGuiStyle.BOTTOM_STRIP_Y,
                CameraGuiStyle.ACTION_BUTTON_WIDTH, CameraGuiStyle.ACTION_BUTTON_HEIGHT,
                Component.translatable("cc_virtual_coms.monitor.button.done"),
                () -> Component.translatable("cc_virtual_coms.monitor.button.done"),
                this::onClose);
        done.setTooltip(Tooltip.create(
                Component.translatable("cc_virtual_coms.monitor.tooltip.done")));
        this.addRenderableWidget(done);
    }

    private void changePage(int delta) {
        int next = this.page + delta;
        if (next < 0 || next >= this.pageCount()) {
            return;
        }
        this.page = next;
        this.rebuildWidgets();
    }

    private int pageCount() {
        return Math.max(1, (this.entries.size() + ENTRY_ROWS - 1) / ENTRY_ROWS);
    }

    // ------------------------------------------------------------------ camera list

    private void refreshEntries() {
        this.entries.clear();
        this.entries.add(new Entry(CameraMonitorBlockEntity.NO_CAMERA,
                Component.translatable("cc_virtual_coms.monitor.value.none"),
                Component.translatable("cc_virtual_coms.monitor.tooltip.none")));

        List<CapturedCamera> cameras = new ArrayList<>(CameraCaptureRuntime.get().cameras());
        cameras.sort(Comparator
                .comparingInt((CapturedCamera camera) -> camera.streaming() ? 0 : 1)
                .thenComparingLong(CapturedCamera::key));
        for (CapturedCamera camera : cameras) {
            this.entries.add(new Entry(camera.key(), this.cameraLabel(camera.key()),
                    this.cameraTooltip(camera)));
        }
        this.knownCameraKeys = this.currentCameraKeys();
        if (this.selectedCamera != CameraMonitorBlockEntity.NO_CAMERA
                && cameras.stream().noneMatch(camera -> camera.key() == this.selectedCamera)) {
            // Keep the configured camera visible so it can still be cleared or kept.
            this.entries.add(1, new Entry(this.selectedCamera,
                    Component.translatable("cc_virtual_coms.monitor.value.camera_missing"),
                    Component.translatable("cc_virtual_coms.monitor.tooltip.camera_missing")));
        }
    }

    private List<Long> currentCameraKeys() {
        List<Long> keys = new ArrayList<>();
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            keys.add(camera.key());
        }
        keys.sort(Long::compare);
        return keys;
    }

    private Component cameraLabel(long key) {
        if (key == CameraMonitorBlockEntity.NO_CAMERA) {
            return Component.translatable("cc_virtual_coms.monitor.value.none");
        }
        CapturedCamera camera = CameraCaptureRuntime.get().camera(key);
        if (camera == null) {
            return Component.translatable("cc_virtual_coms.monitor.value.camera_missing");
        }
        BlockPos cameraPos = camera.pos();
        return Component.translatable("cc_virtual_coms.monitor.entry",
                cameraPos.getX(), cameraPos.getY(), cameraPos.getZ(),
                Component.translatable(camera.streaming()
                        ? "cc_virtual_coms.monitor.status.online"
                        : "cc_virtual_coms.monitor.status.offline"));
    }

    private Component cameraTooltip(CapturedCamera camera) {
        BlockPos cameraPos = camera.pos();
        return Component.translatable("cc_virtual_coms.monitor.tooltip.entry",
                cameraPos.getX(), cameraPos.getY(), cameraPos.getZ(),
                Component.translatable(camera.streaming()
                        ? "cc_virtual_coms.monitor.status.online"
                        : "cc_virtual_coms.monitor.status.offline"));
    }

    /** Label of the currently selected camera, used by the "Camera" summary row. */
    private Component selectedCameraLabel() {
        return this.cameraLabel(this.selectedCamera);
    }

    private Component powerLabel() {
        return Component.translatable(this.enabled
                ? "cc_virtual_coms.monitor.option.on"
                : "cc_virtual_coms.monitor.option.off");
    }

    // ------------------------------------------------------------------ geometry

    private int contentX() {
        return this.left + CameraGuiStyle.CONTENT_X;
    }

    private int controlX() {
        return this.left + CameraGuiStyle.CONTROL_X;
    }

    private int rowY(int row) {
        return this.top + CameraGuiStyle.CONTENT_Y + row * CameraGuiStyle.ROW_PITCH;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        CameraGuiStyle.drawWindow(graphics, this.left, this.top);

        for (int row = 0; row <= 9; row++) {
            int y = this.rowY(row);
            graphics.fill(this.left + CameraGuiStyle.CONTENT_X - 4, y,
                    this.left + CameraGuiStyle.CONTENT_X + CameraGuiStyle.CONTENT_WIDTH + 4,
                    y + CameraGuiStyle.ROW_HEIGHT,
                    row % 2 == 0 ? CameraGuiStyle.COLOR_ROW_EVEN : CameraGuiStyle.COLOR_ROW_ODD);
        }

        CameraGuiStyle.hintBubble(graphics,
                this.left + CameraGuiStyle.SUMMARY_X,
                this.top + CameraGuiStyle.SUMMARY_Y,
                CameraGuiStyle.SUMMARY_WIDTH,
                CameraGuiStyle.SUMMARY_HEIGHT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderHeader(graphics);
        this.renderSummary(graphics);
    }

    private void renderHeader(GuiGraphics graphics) {
        Component title = TITLE.copy().withStyle(ChatFormatting.BOLD);
        CameraGuiStyle.label(graphics, this.font, title, this.left + 10,
                this.top + CameraGuiStyle.TAB_BAR_HEIGHT / 2 - 4);
        CameraGuiStyle.icon(graphics, CameraGuiStyle.ICON_TOOL_DOWN,
                this.left + CameraGuiStyle.PANEL_WIDTH - 10 - CameraGuiStyle.TAB_SIZE,
                this.top + CameraGuiStyle.TAB_Y);

        CameraMonitorBlockEntity monitor = this.monitor();
        int width = monitor == null ? 1 : Math.max(1, monitor.screenWidth());
        int height = monitor == null ? 1 : Math.max(1, monitor.screenHeight());

        CameraGuiStyle.label(graphics, this.font, this.label("field.screen"), this.contentX(),
                this.rowY(0) + CameraGuiStyle.rowTextOffset());
        CameraGuiStyle.statusText(graphics, this.font,
                Component.translatable("cc_virtual_coms.monitor.value.screen", width, height),
                this.controlX(), this.rowY(0) + CameraGuiStyle.rowTextOffset());

        CameraGuiStyle.label(graphics, this.font, this.label("field.power"), this.contentX(),
                this.rowY(1) + CameraGuiStyle.rowTextOffset());

        CameraGuiStyle.label(graphics, this.font, this.label("field.camera"), this.contentX(),
                this.rowY(2) + CameraGuiStyle.rowTextOffset());
        String camera = CameraGuiStyle.trim(this.font, this.selectedCameraLabel().getString(),
                CameraGuiStyle.CONTROL_WIDTH);
        CameraGuiStyle.statusText(graphics, this.font, Component.literal(camera),
                this.controlX(), this.rowY(2) + CameraGuiStyle.rowTextOffset());

        CameraGuiStyle.label(graphics, this.font, this.label("field.cameras"), this.contentX(),
                this.rowY(3) + CameraGuiStyle.rowTextOffset());
        Component page = Component.translatable("cc_virtual_coms.monitor.page",
                this.page + 1, this.pageCount());
        int pageX = this.controlX() + CameraGuiStyle.CONTROL_WIDTH - this.font.width(page) - 58;
        CameraGuiStyle.muted(graphics, this.font, page, pageX,
                this.rowY(3) + CameraGuiStyle.rowTextOffset());
    }

    private void renderSummary(GuiGraphics graphics) {
        CameraMonitorBlockEntity monitor = this.monitor();
        int width = monitor == null ? 1 : Math.max(1, monitor.screenWidth());
        int height = monitor == null ? 1 : Math.max(1, monitor.screenHeight());
        Component summary = Component.translatable("cc_virtual_coms.monitor.summary",
                width, height, this.powerLabel());
        String text = CameraGuiStyle.trim(this.font, summary.getString(),
                CameraGuiStyle.SUMMARY_WIDTH - 16);
        graphics.drawString(this.font, text,
                this.left + CameraGuiStyle.SUMMARY_X + 8,
                this.top + CameraGuiStyle.SUMMARY_Y + 5,
                CameraGuiStyle.COLOR_VALUE, false);
    }

    private Component label(String key) {
        return Component.translatable("cc_virtual_coms.monitor." + key).withStyle(ChatFormatting.BOLD);
    }

    // ------------------------------------------------------------------ camera row widget

    /** One selectable row of the camera list. */
    private final class EntryButton extends AbstractWidget {

        private final Entry entry;

        private EntryButton(int x, int y, int width, int height, Entry entry) {
            super(x, y, width, height, entry.label());
            this.entry = entry;
            this.setTooltip(Tooltip.create(entry.tooltip()));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            CameraGuiStyle.selectorBackground(graphics, this.getX(), this.getY(),
                    this.width, this.height);
            boolean selected = CameraMonitorScreen.this.selectedCamera == this.entry.key();
            if (selected) {
                CameraGuiStyle.focusBorder(graphics, this.getX(), this.getY(),
                        this.width, this.height);
                CameraGuiStyle.icon(graphics, CameraGuiStyle.SMALL_SELECTION,
                        this.getX() + 3, this.getY() + (this.height - 13) / 2);
            }
            String text = CameraGuiStyle.trim(Minecraft.getInstance().font,
                    this.entry.label().getString(), this.width - 20);
            graphics.drawString(Minecraft.getInstance().font, text, this.getX() + 16,
                    this.getY() + (this.height - 8) / 2,
                    CameraGuiStyle.COLOR_VALUE, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.active || !this.visible || button != 0) {
                return false;
            }
            if (mouseX < this.getX() || mouseX >= this.getX() + this.width
                    || mouseY < this.getY() || mouseY >= this.getY() + this.height) {
                return false;
            }
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            CameraMonitorScreen.this.select(this.entry.key());
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
