package com.ccvirtualcoms.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat Synaxis circuit-board style button: light face, bevelled edges, dark shadowless text. */
public class SynaxisBoardButton extends Button {

    protected SynaxisBoardButton(int x, int y, int width, int height, Component message,
                                 OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public static Builder flat(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused();
        int face = !this.active ? 0xFF6E6E78 : hovered ? 0xFFD2D2DC : 0xFFBCBCC6;
        int light = !this.active ? 0xFF8A8A94 : 0xFFE8E8F0;
        int dark = !this.active ? 0xFF55555E : 0xFF8E8E99;
        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        int h = this.height;
        graphics.fill(x, y, x + w, y + h, face);
        graphics.fill(x, y, x + w, y + 1, light);
        graphics.fill(x, y, x + 1, y + h, light);
        graphics.fill(x, y + h - 1, x + w, y + h, dark);
        graphics.fill(x + w - 1, y, x + w, y + h, dark);
        String text = this.getMessage().getString();
        int color = this.active ? 0xFF202028 : 0xFF9A9AA4;
        graphics.drawString(Minecraft.getInstance().font, text,
                x + (w - Minecraft.getInstance().font.width(text)) / 2,
                y + (h - 8) / 2, color, false);
    }

    /** Minimal builder so existing {@code Button.builder(...).bounds(...).build()} chains work. */
    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder tooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
            return this;
        }

        public SynaxisBoardButton build() {
            return new SynaxisBoardButton(this.x, this.y, this.width, this.height,
                    this.message, this.onPress);
        }
    }
}
