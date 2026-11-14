package com.ccvirtualcoms.client.monitor;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Draws all receiver overlays after block entities so they cover CC's monitor screen. */
public final class ReceiverOverlay {

    private ReceiverOverlay() {
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (CameraMonitorBlockEntity receiver : CameraMonitorBlockEntity.clientReceivers()) {
            if (receiver.isRemoved() || receiver.getLevel() == null) {
                continue;
            }
            PoseStack pose = new PoseStack();
            pose.mulPose(event.getCamera().rotation());
            pose.translate(receiver.getBlockPos().getX() - camera.x,
                    receiver.getBlockPos().getY() - camera.y,
                    receiver.getBlockPos().getZ() - camera.z);
            CameraMonitorRenderer.drawOverlay(receiver, pose, buffers, 0);
        }
        buffers.endBatch();
    }
}
