package com.ccvirtualcoms.client.gui;

import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Placeholder camera configuration screen.
 *
 * <p>The full screen (video output / trigger / camera / render+status pages, Synaxis-styled panel)
 * is implemented by the GUI workstream. This skeleton exists so the block, payload and status
 * plumbing can be built and tested end to end first.
 */
public class CameraTransmitterScreen extends Screen {

    private final CameraTransmitterBlockEntity camera;

    public CameraTransmitterScreen(CameraTransmitterBlockEntity camera) {
        super(Component.translatable("cc_virtual_coms.camera.title"));
        this.camera = camera;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        button -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int x = this.width / 2 - 150;
        int y = 32;
        graphics.drawString(this.font, this.title, x, y, 0xFFFFFF, false);
        y += 16;
        graphics.drawString(this.font,
                Component.literal("state: " + this.camera.describeState()), x, y, 0xA0A0A0, false);
        y += 12;
        graphics.drawString(this.font,
                Component.literal("device: " + this.camera.clientDeviceName()), x, y, 0xA0A0A0, false);
        y += 12;
        graphics.drawString(this.font,
                Component.literal("stream: " + this.camera.clientStreamUrl()), x, y, 0xA0A0A0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
