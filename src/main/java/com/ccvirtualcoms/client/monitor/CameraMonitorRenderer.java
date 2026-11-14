package com.ccvirtualcoms.client.monitor;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlock;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/** Draws the merged monitor wall's live camera picture. */
public class CameraMonitorRenderer implements BlockEntityRenderer<CameraMonitorBlockEntity> {

    private static final int TEX = 256;
    private static final Map<Long, DynamicTexture> TEXTURES = new HashMap<>();
    private static final Map<Long, Long> FRAMES = new HashMap<>();

    public CameraMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraMonitorBlockEntity monitor, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        CameraMonitorBlockEntity master = monitor.master();
        if (master == null) {
            master = monitor;
        }
        int width = monitor.screenWidth();
        int height = monitor.screenHeight();
        int column = monitor.screenColumn();
        int row = monitor.screenRow();
        CameraFrame frame = null;
        BlockPos cameraPos = master.cameraPos();
        if (master.enabled() && cameraPos != null) {
            CapturedCamera camera = CameraCaptureRuntime.get().camera(cameraPos.asLong());
            if (camera != null) {
                frame = camera.latestFrame();
            }
        }
        ResourceLocation textureId = upload(master.getBlockPos(), frame, width, height);
        BlockState state = monitor.getBlockState();
        Direction facing = state.getValue(CameraMonitorBlock.FACING);
        pose.pushPose();
        pose.translate(0.5F, 0.5F, 0.5F);
        switch (facing) {
            case SOUTH -> { }
            case NORTH -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
            case EAST -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90.0F));
            case WEST -> pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(270.0F));
            default -> { }
        }
        pose.translate(-0.5F, -0.5F, -0.5F);
        float u0 = (float) column / width;
        float u1 = (float) (column + 1) / width;
        float v0 = (float) row / height;
        float v1 = (float) (row + 1) / height;
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(textureId));
        float z = 1.0F;
        float inset = 0.0625F;
        vertex(consumer, matrix, inset, 1.0F - inset, z, u0, v0, light, overlay);
        vertex(consumer, matrix, 1.0F - inset, 1.0F - inset, z, u1, v0, light, overlay);
        vertex(consumer, matrix, 1.0F - inset, inset, z, u1, v1, light, overlay);
        vertex(consumer, matrix, inset, inset, z, u0, v1, light, overlay);
        pose.popPose();
    }

    private static ResourceLocation upload(BlockPos master, CameraFrame frame, int screenWidth,
                                          int screenHeight) {
        long key = master.asLong();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID,
                "monitor/" + Long.toHexString(key));
        DynamicTexture texture = TEXTURES.get(key);
        if (texture == null) {
            texture = new DynamicTexture(TEX, TEX, true);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            TEXTURES.put(key, texture);
            FRAMES.put(key, Long.MIN_VALUE);
        }
        long sequence = frame == null ? -1L : frame.sequence();
        Long previous = FRAMES.get(key);
        if (previous == null || previous != sequence) {
            FRAMES.put(key, sequence);
            NativeImage image = texture.getPixels();
            if (image != null) {
                fill(image, frame, screenWidth, screenHeight);
                texture.upload();
            }
        }
        return id;
    }

    private static void fill(NativeImage image, CameraFrame frame, int screenWidth, int screenHeight) {
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                image.setPixelRGBA(x, y, 0xFF000000);
            }
        }
        if (frame == null) {
            return;
        }
        double screenAspect = (double) screenWidth / (double) screenHeight;
        double imageAspect = (double) frame.width() / (double) frame.height();
        double drawWidth = imageAspect > screenAspect ? 1.0D : imageAspect / screenAspect;
        double drawHeight = imageAspect > screenAspect ? screenAspect / imageAspect : 1.0D;
        int offsetX = (int) Math.round((1.0D - drawWidth) * TEX / 2.0D);
        int offsetY = (int) Math.round((1.0D - drawHeight) * TEX / 2.0D);
        int width = Math.max(1, (int) Math.round(drawWidth * TEX));
        int height = Math.max(1, (int) Math.round(drawHeight * TEX));
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(frame.height() - 1, y * frame.height() / height);
            int rowBase = sourceY * frame.width() * 4;
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(frame.width() - 1, x * frame.width() / width);
                int index = rowBase + sourceX * 4;
                byte[] rgba = frame.rgba();
                int r = rgba[index] & 0xFF;
                int g = rgba[index + 1] & 0xFF;
                int b = rgba[index + 2] & 0xFF;
                image.setPixelRGBA(offsetX + x, offsetY + y, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z,
                               float u, float v, int light, int overlay) {
        consumer.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setLight(light).setOverlay(overlay).setNormal(0.0F, 0.0F, 1.0F);
    }
}
