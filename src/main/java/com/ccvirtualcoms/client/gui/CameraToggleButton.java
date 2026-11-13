package com.ccvirtualcoms.client.gui;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A Synaxis-style on/off toggle: the {@code SMALL_BUTTON_GREEN}/{@code SMALL_BUTTON_RED} sprite
 * followed by the state label, on the standard light input background.
 */
public class CameraToggleButton extends AbstractWidget {

    private final Supplier<Boolean> state;
    private final Runnable onToggle;
    private final Supplier<Component> onLabel;
    private final Supplier<Component> offLabel;

    public CameraToggleButton(int x, int y, int width, int height, Component narration,
                              Supplier<Boolean> state, Supplier<Component> onLabel,
                              Supplier<Component> offLabel, Runnable onToggle) {
        super(x, y, width, height, narration);
        this.state = state;
        this.onToggle = onToggle;
        this.onLabel = onLabel;
        this.offLabel = offLabel;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        CameraGuiStyle.inputBackground(graphics, this.getX(), this.getY(), this.width, this.height);
        boolean on = this.state.get();
        CameraGuiStyle.icon(graphics, on ? CameraGuiStyle.TOGGLE_ON : CameraGuiStyle.TOGGLE_OFF,
                this.getX() + 4, this.getY() + (this.height - 13) / 2);
        if (this.isHoveredOrFocused()) {
            CameraGuiStyle.focusBorder(graphics, this.getX(), this.getY(), this.width, this.height);
        }
        Component label = on ? this.onLabel.get() : this.offLabel.get();
        String text = CameraGuiStyle.trim(Minecraft.getInstance().font, label.getString(),
                this.width - 22);
        graphics.drawString(Minecraft.getInstance().font, text, this.getX() + 17,
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
        this.onToggle.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
