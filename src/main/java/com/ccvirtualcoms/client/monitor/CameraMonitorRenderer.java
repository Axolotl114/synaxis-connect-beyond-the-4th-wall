package com.ccvirtualcoms.client.monitor;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix4f;

/** Draws the selected camera image onto the adjacent CC advanced monitor wall. */
public class CameraMonitorRenderer implements BlockEntityRenderer<CameraMonitorBlockEntity> {

    private static final int TEX = 256;
    private static final ResourceLocation MONITOR =
            ResourceLocation.fromNamespaceAndPath("computercraft", "monitor_advanced");
    private static final Map<Long, DynamicTexture> TEXTURES = new HashMap<>();
    private static final Map<Long, Long> FRAMES = new HashMap<>();

    public CameraMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraMonitorBlockEntity receiver, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        // The overlay is drawn from RenderLevelStageEvent.AFTER_BLOCK_ENTITIES so that it covers
        // the CC monitor's own (orange "no computer") screen.
    }

    /** Draws the receiver's camera image onto its adjacent CC monitor wall. */
    public static void drawOverlay(CameraMonitorBlockEntity receiver, PoseStack pose,
                                   MultiBufferSource buffers, int overlay) {
        Level level = receiver.getLevel();
        if (level == null || !receiver.enabled() || receiver.cameraPos() == null) {
            return;
        }
        BlockPos monitorPos = findMonitor(level, receiver.getBlockPos());
        if (monitorPos == null) {
            return;
        }
        BlockState state = level.getBlockState(monitorPos);
        Direction facing = facingOf(state);
        int[] wall = measureWall(level, monitorPos, state, facing);
        int width = wall[0];
        int height = wall[1];
        int column = wall[2];
        int row = wall[3];
        CapturedCamera camera = CameraCaptureRuntime.get().camera(receiver.cameraPos().asLong());
        CameraFrame frame = camera == null ? null : camera.latestFrame();
        CameraConfig config = camera == null ? CameraConfig.DEFAULT : camera.config();
        ResourceLocation texture = upload(receiver.getBlockPos(), frame, config, width, height);
        pose.pushPose();
        BlockPos delta = monitorPos.subtract(receiver.getBlockPos());
        pose.translate(delta.getX(), delta.getY(), delta.getZ());
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(texture));
        float inset = 0.0625F;
        float x0 = (column == 0 ? inset : 0.0F);
        float x1 = (column == width - 1 ? 1.0F - inset : 1.0F);
        float y0 = (row == height - 1 ? inset : 0.0F);
        float y1 = (row == 0 ? 1.0F - inset : 1.0F);
        float u0 = (float) column / width;
        float u1 = (float) (column + 1) / width;
        float v0 = (float) row / height;
        float v1 = (float) (row + 1) / height;
        int fullLight = LightTexture.FULL_BRIGHT;
        switch (facing) {
            case SOUTH -> {
                vertex(consumer, matrix, x0, y1, 1.001F, u0, v0, fullLight, overlay);
                vertex(consumer, matrix, x1, y1, 1.001F, u1, v0, fullLight, overlay);
                vertex(consumer, matrix, x1, y0, 1.001F, u1, v1, fullLight, overlay);
                vertex(consumer, matrix, x0, y0, 1.001F, u0, v1, fullLight, overlay);
            }
            case NORTH -> {
                vertex(consumer, matrix, x1, y1, -0.001F, u0, v0, fullLight, overlay);
                vertex(consumer, matrix, x0, y1, -0.001F, u1, v0, fullLight, overlay);
                vertex(consumer, matrix, x0, y0, -0.001F, u1, v1, fullLight, overlay);
                vertex(consumer, matrix, x1, y0, -0.001F, u0, v1, fullLight, overlay);
            }
            case EAST -> {
                vertex(consumer, matrix, 1.001F, y1, x1, u0, v0, fullLight, overlay);
                vertex(consumer, matrix, 1.001F, y1, x0, u1, v0, fullLight, overlay);
                vertex(consumer, matrix, 1.001F, y0, x0, u1, v1, fullLight, overlay);
                vertex(consumer, matrix, 1.001F, y0, x1, u0, v1, fullLight, overlay);
            }
            case WEST -> {
                vertex(consumer, matrix, -0.001F, y1, x0, u0, v0, fullLight, overlay);
                vertex(consumer, matrix, -0.001F, y1, x1, u1, v0, fullLight, overlay);
                vertex(consumer, matrix, -0.001F, y0, x1, u1, v1, fullLight, overlay);
                vertex(consumer, matrix, -0.001F, y0, x0, u0, v1, fullLight, overlay);
            }
            default -> {
            }
        }
        pose.popPose();
    }

    private static BlockPos findMonitor(Level level, BlockPos receiverPos) {
        for (Direction direction : Direction.values()) {
            BlockPos next = receiverPos.relative(direction);
            if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(next).getBlock()).equals(MONITOR)) {
                return next;
            }
        }
        return null;
    }

    private static @org.jetbrains.annotations.Nullable Direction facingOf(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("facing")
                    && property.getValueClass() == Direction.class) {
                try {
                    Object value = state.getValue((Property) property);
                    if (value instanceof Direction direction) {
                        return direction;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return Direction.NORTH;
    }

    /** Returns {width, height, column, row} of the monitor wall. */
    private static int[] measureWall(Level level, BlockPos start, BlockState startState,
                                     Direction facing) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        Direction right = facing.getClockWise();
        while (!queue.isEmpty() && seen.size() < 256) {
            BlockPos pos = queue.poll();
            int a = pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, pos.getY());
            maxB = Math.max(maxB, pos.getY());
            for (Direction direction : new Direction[]{Direction.UP, Direction.DOWN, right,
                    right.getOpposite()}) {
                BlockPos next = pos.relative(direction);
                if (seen.contains(next)) {
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                if (BuiltInRegistries.BLOCK.getKey(nextState.getBlock()).equals(MONITOR)
                        && nextState.getBlock() == startState.getBlock()) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        int a = start.getX() * right.getStepX() + start.getZ() * right.getStepZ();
        return new int[]{maxA - minA + 1, maxB - minB + 1, a - minA, maxB - start.getY()};
    }

    private static ResourceLocation upload(BlockPos key, CameraFrame frame, CameraConfig config,
                                          int screenWidth, int screenHeight) {
        long id = key.asLong();
        ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID,
                "monitor/" + Long.toHexString(id));
        DynamicTexture texture = TEXTURES.get(id);
        if (texture == null) {
            texture = new DynamicTexture(TEX, TEX, true);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            TEXTURES.put(id, texture);
            FRAMES.put(id, Long.MIN_VALUE);
        }
        long sequence = frame == null ? -1L : frame.sequence();
        Long previous = FRAMES.get(id);
        if (previous == null || previous != sequence) {
            FRAMES.put(id, sequence);
            NativeImage image = texture.getPixels();
            if (image != null) {
                fill(image, frame, config, screenWidth, screenHeight);
                texture.upload();
            }
        }
        return textureId;
    }

    private static void fill(NativeImage image, CameraFrame frame, CameraConfig config,
                             int screenWidth, int screenHeight) {
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
