package com.ccvirtualcoms.client.gui;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** A small Synaxis-style text button (selector background + centred dark label). */
public class CameraActionButton extends AbstractWidget {

    private final Runnable onPress;
    private final Supplier<Component> label;

    public CameraActionButton(int x, int y, int width, int height, Component narration,
                              Supplier<Component> label, Runnable onPress) {
        super(x, y, width, height, narration);
        this.label = label;
        this.onPress = onPress;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        CameraGuiStyle.selectorBackground(graphics, this.getX(), this.getY(), this.width, this.height);
        if (this.isHoveredOrFocused()) {
            CameraGuiStyle.focusBorder(graphics, this.getX(), this.getY(), this.width, this.height);
        }
        Component text = this.label.get();
        int textX = this.getX() + (this.width - Minecraft.getInstance().font.width(text)) / 2;
        graphics.drawString(Minecraft.getInstance().font, text, textX,
                this.getY() + (this.height - 8) / 2,
                this.active ? CameraGuiStyle.COLOR_VALUE : CameraGuiStyle.COLOR_MUTED, false);
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
        this.onPress.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
