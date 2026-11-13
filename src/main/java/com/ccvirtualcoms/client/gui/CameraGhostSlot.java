package com.ccvirtualcoms.client.gui;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A "ghost" item slot for the two Create wireless redstone frequency stacks.
 *
 * <p>The camera GUI is a plain {@link net.minecraft.client.gui.screens.Screen}, not a container
 * screen, so items are copied in without ever entering or leaving the player's inventory:
 *
 * <ul>
 *   <li>left click copies one item from the carried stack (falling back to the main hand, then the
 *       off hand) into the slot;</li>
 *   <li>right click (or left click with an empty hand) clears the slot;</li>
 *   <li>the copy is always reduced to a single item, and the server re-copies and clamps it again
 *       when it validates the configuration snapshot.</li>
 * </ul>
 */
public class CameraGhostSlot extends AbstractWidget {

    private final Supplier<ItemStack> stack;
    private final Consumer<ItemStack> onSet;
    private final Runnable onClear;
    private final int slotSize;

    public CameraGhostSlot(int x, int y, int slotSize, Component narration,
                           Supplier<ItemStack> stack, Consumer<ItemStack> onSet, Runnable onClear) {
        super(x, y, slotSize, slotSize, narration);
        this.stack = stack;
        this.onSet = onSet;
        this.onClear = onClear;
        this.slotSize = slotSize;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHovered();
        CameraGuiStyle.itemSlot(graphics, this.getX(), this.getY(), this.slotSize, hovered);
        ItemStack current = this.stack.get();
        if (current == null || current.isEmpty()) {
            String plus = "+";
            int width = Minecraft.getInstance().font.width(plus);
            graphics.drawString(Minecraft.getInstance().font, plus,
                    this.getX() + (this.slotSize - width) / 2,
                    this.getY() + (this.slotSize - 8) / 2,
                    hovered ? CameraGuiStyle.COLOR_SLOT_HOVER : CameraGuiStyle.COLOR_MUTED, false);
        } else {
            graphics.renderItem(current, this.getX(), this.getY());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible) {
            return false;
        }
        if (mouseX < this.getX() || mouseX >= this.getX() + this.width
                || mouseY < this.getY() || mouseY >= this.getY() + this.height) {
            return false;
        }
        if (button == 1) {
            this.onClear.run();
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }
        if (button != 0) {
            return false;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        ItemStack source = player.containerMenu.getCarried();
        if (source.isEmpty()) {
            source = player.getMainHandItem();
        }
        if (source.isEmpty()) {
            source = player.getOffhandItem();
        }
        if (source.isEmpty()) {
            this.onClear.run();
        } else {
            this.onSet.accept(source.copyWithCount(1));
        }
        this.playDownSound(Minecraft.getInstance().getSoundManager());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
