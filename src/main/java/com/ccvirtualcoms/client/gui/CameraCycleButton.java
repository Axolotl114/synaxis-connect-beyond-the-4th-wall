package com.ccvirtualcoms.client.gui;

import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A Synaxis-style cycle button used for enumerations.
 *
 * <p>Left click advances to the next value, right click goes back, which keeps the control usable
 * without opening a popup list (Vanilla has no drop-down widget and the Synaxis selector is an
 * ldlib2 component we do not depend on at this layer). The displayed label is supplied on every
 * frame, so it always reflects the working configuration without rebuilding widgets.
 */
public class CameraCycleButton extends AbstractWidget {

    private final Supplier<Component> valueLabel;
    private final IntConsumer onCycle;
    private final boolean selectorStyle;

    public CameraCycleButton(int x, int y, int width, int height, Component narration,
                             Supplier<Component> valueLabel, IntConsumer onCycle) {
        this(x, y, width, height, narration, valueLabel, onCycle, true);
    }

    public CameraCycleButton(int x, int y, int width, int height, Component narration,
                             Supplier<Component> valueLabel, IntConsumer onCycle,
                             boolean selectorStyle) {
        super(x, y, width, height, narration);
        this.valueLabel = valueLabel;
        this.onCycle = onCycle;
        this.selectorStyle = selectorStyle;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.selectorStyle) {
            CameraGuiStyle.selectorBackground(graphics, this.getX(), this.getY(),
                    this.width, this.height);
        } else {
            CameraGuiStyle.inputBackground(graphics, this.getX(), this.getY(),
                    this.width, this.height);
        }
        if (this.isHoveredOrFocused()) {
            CameraGuiStyle.focusBorder(graphics, this.getX(), this.getY(), this.width, this.height);
        }
        Component label = this.valueLabel.get();
        String text = CameraGuiStyle.trim(Minecraft.getInstance().font, label.getString(),
                this.width - 8);
        int textY = this.getY() + (this.height - 8) / 2;
        graphics.drawString(Minecraft.getInstance().font, text, this.getX() + 4, textY,
                this.active ? CameraGuiStyle.COLOR_VALUE : CameraGuiStyle.COLOR_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible || (button != 0 && button != 1)) {
            return false;
        }
        if (mouseX < this.getX() || mouseX >= this.getX() + this.width
                || mouseY < this.getY() || mouseY >= this.getY() + this.height) {
            return false;
        }
        this.playDownSound(Minecraft.getInstance().getSoundManager());
        this.onCycle.accept(button == 1 ? -1 : 1);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
