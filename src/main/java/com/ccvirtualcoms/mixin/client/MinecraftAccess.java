package com.ccvirtualcoms.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Temporary main-render-target redirection used while rendering one camera frame. */
@Mixin(Minecraft.class)
public interface MinecraftAccess {

    @Accessor("mainRenderTarget")
    RenderTarget ccvc$getMainRenderTarget();

    @Accessor("mainRenderTarget")
    void ccvc$setMainRenderTarget(RenderTarget target);
}
