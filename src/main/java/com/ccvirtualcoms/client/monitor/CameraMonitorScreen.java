package com.ccvirtualcoms.client.monitor;

import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.client.gui.CameraCycleButton;
import com.ccvirtualcoms.client.gui.CameraGuiStyle;
import com.ccvirtualcoms.client.gui.CameraToggleButton;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Synaxis-style configuration screen for one video projector. */
public class CameraMonitorScreen extends Screen {

    private static final String[] FIELD_LABELS = {"W", "H", "X", "Y", "Z", "Yaw", "Pitch", "Alpha"};

    private final CameraMonitorBlockEntity projector;
    private final List<BlockPos> cameras = new ArrayList<>();
    private final EditBox[] fields = new EditBox[8];
    private int selected = -1;
    private boolean enabled;
    private BlockPos targetPos;
    private int left;
    private int top;

    public CameraMonitorScreen(CameraMonitorBlockEntity projector) {
        super(Component.translatable("cc_virtual_coms.monitor.title"));
        this.projector = projector;
    }

    @Override
    protected void init() {
        CameraMonitorBlockEntity target = this.projector;
        this.targetPos = target.getBlockPos();
        this.enabled = target.enabled();
        this.left = Math.max(2, (this.width - CameraGuiStyle.PANEL_WIDTH) / 2);
        this.top = Math.max(2, (this.height - CameraGuiStyle.PANEL_HEIGHT) / 2);
        this.cameras.clear();
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            this.cameras.add(camera.pos());
        }
        this.cameras.sort(Comparator.comparingLong(BlockPos::asLong));
        if (target.cameraPos() != null) {
            this.selected = this.cameras.indexOf(target.cameraPos());
        }
        int controlX = this.left + CameraGuiStyle.CONTROL_X;
        int controlWidth = CameraGuiStyle.CONTROL_WIDTH;
        this.addRenderableWidget(new CameraCycleButton(controlX, this.rowY(0), controlWidth,
                CameraGuiStyle.ROW_HEIGHT, Component.empty(), this::cameraLabel, this::cycleCamera));
        this.addRenderableWidget(new CameraToggleButton(controlX, this.rowY(1), controlWidth,
                CameraGuiStyle.ROW_HEIGHT, Component.empty(), () -> this.enabled,
                () -> Component.translatable("cc_virtual_coms.monitor.on"),
                () -> Component.translatable("cc_virtual_coms.monitor.off"), this::toggle));
        String[] values = {
                Double.toString(this.projector.projectorWidth()),
                Double.toString(this.projector.projectorHeight()),
                Double.toString(this.projector.offsetX()),
                Double.toString(this.projector.offsetY()),
                Double.toString(this.projector.offsetZ()),
                Double.toString(this.projector.yawDegrees()),
                Double.toString(this.projector.pitchDegrees()),
                Integer.toString(this.projector.opacity())
        };
        for (int i = 0; i < this.fields.length; i++) {
            EditBox box = new EditBox(this.font, controlX + 2, this.rowY(2 + i) + 1,
                    controlWidth - 4, CameraGuiStyle.INPUT_HEIGHT - 2, Component.empty());
            box.setBordered(false);
            box.setTextColor(CameraGuiStyle.COLOR_VALUE);
            box.setValue(values[i]);
            this.fields[i] = box;
            this.addRenderableWidget(box);
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> this.onClose()).bounds(controlX, this.top + CameraGuiStyle.PANEL_HEIGHT - 24,
                controlWidth, CameraGuiStyle.ROW_HEIGHT).build());
    }

    private int rowY(int row) {
        return this.top + CameraGuiStyle.CONTENT_Y + row * CameraGuiStyle.ROW_PITCH;
    }

    private Component cameraLabel() {
        if (this.selected < 0 || this.selected >= this.cameras.size()) {
            return Component.translatable("cc_virtual_coms.monitor.none");
        }
        return Component.literal("Camera " + this.cameras.get(this.selected).toShortString());
    }

    private void cycleCamera(int direction) {
        if (this.cameras.isEmpty()) {
            return;
        }
        this.selected = Math.floorMod(this.selected + (direction >= 0 ? 1 : -1), this.cameras.size());
        this.send();
    }

    private void toggle() {
        this.enabled = !this.enabled;
        this.send();
    }

    private double field(int index, double fallback) {
        try {
            return Double.parseDouble(this.fields[index].getValue());
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private void send() {
        BlockPos camera = this.selected >= 0 && this.selected < this.cameras.size()
                ? this.cameras.get(this.selected) : null;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.ccvirtualcoms.net.MonitorConfigPayload(this.targetPos, camera != null,
                        camera == null ? 0L : camera.asLong(), this.enabled,
                        this.field(0, this.projector.projectorWidth()),
                        this.field(1, this.projector.projectorHeight()),
                        this.field(2, this.projector.offsetX()),
                        this.field(3, this.projector.offsetY()),
                        this.field(4, this.projector.offsetZ()),
                        this.field(5, this.projector.yawDegrees()),
                        this.field(6, this.projector.pitchDegrees()),
                        (int) Math.round(this.field(7, this.projector.opacity()))));
    }

    @Override
    public void onClose() {
        this.send();
        super.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        CameraGuiStyle.drawWindow(graphics, this.left, this.top);
        CameraGuiStyle.label(graphics, this.font, this.title, this.left + CameraGuiStyle.CONTENT_X,
                this.top + 10);
        for (int i = 0; i < this.fields.length; i++) {
            int y = this.rowY(2 + i);
            CameraGuiStyle.muted(graphics, this.font,
                    Component.literal(FIELD_LABELS[i]),
                    this.left + CameraGuiStyle.CONTENT_X, y + 2);
            CameraGuiStyle.numberInputBackground(graphics,
                    this.left + CameraGuiStyle.CONTROL_X, y,
                    CameraGuiStyle.CONTROL_WIDTH, CameraGuiStyle.INPUT_HEIGHT);
        }
        CameraGuiStyle.muted(graphics, this.font,
                Component.translatable("cc_virtual_coms.monitor.camera"),
                this.left + CameraGuiStyle.CONTENT_X, this.rowY(0) + 2);
        CameraGuiStyle.muted(graphics, this.font,
                Component.translatable("cc_virtual_coms.monitor.display"),
                this.left + CameraGuiStyle.CONTENT_X, this.rowY(1) + 2);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0101018);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
