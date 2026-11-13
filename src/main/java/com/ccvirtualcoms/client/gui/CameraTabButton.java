package com.ccvirtualcoms.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A Synaxis-style icon-only page tab ({@code TOOL}/{@code TOOL_DOWN} sprites).
 *
 * <p>The tooltip is the page name, exactly like Synaxis, so an icon-only strip still tells the
 * player what each tab opens.
 */
public class CameraTabButton extends AbstractWidget {

    private final boolean selected;
    private final Runnable onPress;

    public CameraTabButton(int x, int y, Component narration, boolean selected, Runnable onPress) {
        super(x, y, CameraGuiStyle.TAB_SIZE, CameraGuiStyle.TAB_SIZE, narration);
        this.selected = selected;
        this.onPress = onPress;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.isHovered()) {
            graphics.fill(this.getX() - 1, this.getY() - 1,
                    this.getX() + this.width + 1, this.getY() + this.height + 1, 0x22FFFFFF);
        }
        CameraGuiStyle.icon(graphics,
                this.selected ? CameraGuiStyle.ICON_TOOL_DOWN : CameraGuiStyle.ICON_TOOL,
                this.getX(), this.getY());
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
