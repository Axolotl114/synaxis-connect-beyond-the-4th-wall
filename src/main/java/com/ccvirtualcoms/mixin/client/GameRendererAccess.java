package com.ccvirtualcoms.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the capture pipeline hide the first-person hand while it borrows the main target. */
@Mixin(GameRenderer.class)
public interface GameRendererAccess {

    @Accessor("renderHand")
    boolean ccvc$isRenderHand();

    @Accessor("renderHand")
    void ccvc$setRenderHand(boolean renderHand);
}
