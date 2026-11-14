package com.ccvirtualcoms.mixin.client;

import com.ccvirtualcoms.client.camera.capture.EntityMaskCollector;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records entity screen rectangles for the night-vision mask during the real camera render. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityMaskMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"))
    private void ccvc$recordEntityMask(Entity entity, double x, double y, double z, float yaw,
                                       float partialTick, PoseStack pose, MultiBufferSource buffers,
                                       int packedLight, CallbackInfo callbackInfo) {
        if (!EntityMaskCollector.active()) {
            return;
        }
        net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance()
                .gameRenderer.getMainCamera();
        EntityMaskCollector.record(entity, pose.last().pose(),
                RenderSystem.getProjectionMatrix(), camera.getPosition(), partialTick);
    }
}
