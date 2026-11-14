package com.ccvirtualcoms.client.monitor;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import com.mojang.math.Axis;

/** Draws the projector's floating camera screen in the air. */
public class CameraMonitorRenderer implements BlockEntityRenderer<CameraMonitorBlockEntity> {

    private static final int TEX = 512;
    private static final Map<Long, DynamicTexture> TEXTURES = new HashMap<>();
    private static final Map<Long, Long> FRAMES = new HashMap<>();

    public CameraMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraMonitorBlockEntity projector, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (!projector.enabled() || projector.cameraPos() == null) {
            return;
        }
        CapturedCamera camera = CameraCaptureRuntime.get().camera(projector.cameraPos().asLong());
        CameraFrame frame = camera == null ? null : camera.latestFrame();
        CameraConfig config = camera == null ? CameraConfig.DEFAULT : camera.config();
        float width = (float) projector.projectorWidth();
        float height = (float) projector.projectorHeight();
        ResourceLocation texture = upload(projector, frame, config, width / height, projector.opacity());
        BlockState state = projector.getBlockState();
        Direction facing = state.getOptionalValue(CameraMonitorBlock.FACING).orElse(Direction.SOUTH);
        pose.pushPose();
        pose.translate(0.5F + (float) projector.offsetX(),
                0.5F + (float) projector.offsetY(),
                0.5F + (float) projector.offsetZ());
        // block facing: the screen's local +Z is the outward normal
        switch (facing) {
            case SOUTH -> { }
            case NORTH -> pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            case EAST -> pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
            case WEST -> pose.mulPose(Axis.YP.rotationDegrees(90.0F));
            default -> { }
        }
        pose.mulPose(Axis.YP.rotationDegrees((float) projector.yawDegrees()));
        pose.mulPose(Axis.XP.rotationDegrees((float) projector.pitchDegrees()));
        Matrix4f matrix = pose.last().pose();
        float halfW = width / 2.0F;
        float halfH = height / 2.0F;
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(texture));
        int fullLight = LightTexture.FULL_BRIGHT;
        vertex(consumer, matrix, -halfW, halfH, 0.0F, 0.0F, 0.0F, fullLight, overlay);
        vertex(consumer, matrix, halfW, halfH, 0.0F, 1.0F, 0.0F, fullLight, overlay);
        vertex(consumer, matrix, halfW, -halfH, 0.0F, 1.0F, 1.0F, fullLight, overlay);
        vertex(consumer, matrix, -halfW, -halfH, 0.0F, 0.0F, 1.0F, fullLight, overlay);
        pose.popPose();
    }

    private static ResourceLocation upload(CameraMonitorBlockEntity projector, CameraFrame frame,
                                          CameraConfig config, double aspect, int opacity) {
        long key = projector.getBlockPos().asLong();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID,
                "projector/" + Long.toHexString(key));
        DynamicTexture texture = TEXTURES.get(key);
        if (texture == null) {
            texture = new DynamicTexture(TEX, TEX, true);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            TEXTURES.put(key, texture);
            FRAMES.put(key, Long.MIN_VALUE);
        }
        long signature = (frame == null ? -1L : frame.sequence()) * 1000L + opacity;
        Long previous = FRAMES.get(key);
        if (previous == null || previous != signature) {
            FRAMES.put(key, signature);
            NativeImage image = texture.getPixels();
            if (image != null) {
                fill(image, frame, config, aspect, opacity);
                texture.upload();
            }
        }
        return id;
    }

    private static void fill(NativeImage image, CameraFrame frame, CameraConfig config,
                             double aspect, int opacity) {
        int alpha = Math.max(0, Math.min(255, (int) Math.round(opacity * 2.55D)));
        int background = alpha << 24;
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                image.setPixelRGBA(x, y, background);
            }
        }
        if (frame == null) {
            return;
        }
        double imageAspect = (double) frame.width() / (double) frame.height();
        double drawWidth = imageAspect > aspect ? 1.0D : imageAspect / aspect;
        double drawHeight = imageAspect > aspect ? aspect / imageAspect : 1.0D;
        int offsetX = (int) Math.round((1.0D - drawWidth) * TEX / 2.0D);
        int offsetY = (int) Math.round((1.0D - drawHeight) * TEX / 2.0D);
        int width = Math.max(1, (int) Math.round(drawWidth * TEX));
        int height = Math.max(1, (int) Math.round(drawHeight * TEX));
        boolean colour = config.color();
        boolean green = !colour && config.green();
        boolean nightVision = config.nightVision();
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
                if (!colour) {
                    int luma = (r * 77 + g * 150 + b * 29) >> 8;
                    if (green) {
                        int channel = nightVision
                                ? Math.min(255, (int) Math.round(Math.pow(luma / 255.0D, 0.72D) * 255.0D * 2.2D))
                                : Math.min(255, (int) Math.round(luma * 1.15D));
                        r = channel * 42 / 255;
                        g = channel;
                        b = channel * 30 / 255;
                    } else if (nightVision) {
                        int white = Math.min(255, (int) Math.round(
                                Math.pow(luma / 255.0D, 0.78D) * 255.0D * 2.6D));
                        if (luma >= 190) {
                            white = Math.min(255, white + 35);
                        }
                        r = Math.min(255, white + 6);
                        g = white;
                        b = Math.max(0, white - 8);
                    } else {
                        r = g = b = luma;
                    }
                }
                image.setPixelRGBA(offsetX + x, offsetY + y, (alpha << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z,
                               float u, float v, int light, int overlay) {
        consumer.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setLight(light).setOverlay(overlay).setNormal(0.0F, 0.0F, 1.0F);
    }
}
