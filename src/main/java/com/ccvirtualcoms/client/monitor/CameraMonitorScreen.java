package com.ccvirtualcoms.client.monitor;

import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.client.gui.CameraCycleButton;
import com.ccvirtualcoms.client.gui.CameraToggleButton;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Configuration screen for one merged camera-monitor wall. */
public class CameraMonitorScreen extends Screen {

    private final CameraMonitorBlockEntity monitor;
    private final List<BlockPos> cameras = new ArrayList<>();
    private int selected = -1;
    private boolean enabled;
    private BlockPos masterPos;

    public CameraMonitorScreen(CameraMonitorBlockEntity monitor) {
        super(Component.translatable("cc_virtual_coms.monitor.title"));
        this.monitor = monitor;
    }

    @Override
    protected void init() {
        CameraMonitorBlockEntity master = this.monitor.master();
        CameraMonitorBlockEntity target = master == null ? this.monitor : master;
        this.masterPos = target.getBlockPos();
        this.enabled = target.enabled();
        this.cameras.clear();
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            this.cameras.add(camera.pos());
        }
        this.cameras.sort(Comparator.comparingLong(BlockPos::asLong));
        if (target.cameraPos() != null) {
            this.selected = this.cameras.indexOf(target.cameraPos());
        }
        int centerX = this.width / 2;
        int y = this.height / 2 - 40;
        this.addRenderableWidget(new CameraCycleButton(centerX - 100, y, 200, 20, Component.empty(),
                this::cameraLabel, this::cycleCamera));
        this.addRenderableWidget(new CameraToggleButton(centerX - 100, y + 28, 200, 20,
                Component.empty(), () -> this.enabled,
                () -> Component.translatable("cc_virtual_coms.monitor.on"),
                () -> Component.translatable("cc_virtual_coms.monitor.off"), this::toggle));
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> this.onClose()).bounds(centerX - 100, y + 60, 200, 20).build());
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

    private void send() {
        BlockPos camera = this.selected >= 0 && this.selected < this.cameras.size()
                ? this.cameras.get(this.selected) : null;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.ccvirtualcoms.net.MonitorConfigPayload(this.masterPos, camera != null,
                        camera == null ? 0L : camera.asLong(), this.enabled));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int x0 = this.width / 2 - 120;
        int y0 = this.height / 2 - 60;
        graphics.fill(x0, y0, x0 + 240, y0 + 130, 0xF23C3C44);
        graphics.fill(x0, y0, x0 + 240, y0 + 24, 0xFF2E2E36);
        graphics.drawString(this.font, this.title, x0 + 8, y0 + 8, 0xFFF2F2F5, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, Component.translatable("cc_virtual_coms.monitor.hint"),
                x0 + 8, y0 + 112, 0xFFC2C2CA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
