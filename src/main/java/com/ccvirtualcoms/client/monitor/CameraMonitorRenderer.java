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
        drawOverlay(receiver, pose, buffers, overlay);
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
        int[] wall = wallBounds(level, monitorPos, state, facing);
        int width = wall[0];
        int height = wall[1];
        BlockPos origin = new BlockPos(wall[2], wall[3], wall[4]);
        int[] right = {wall[5], 0, wall[6]};
        CapturedCamera camera = CameraCaptureRuntime.get().camera(receiver.cameraPos().asLong());
        CameraFrame frame = camera == null ? null : camera.latestFrame();
        CameraConfig config = camera == null ? CameraConfig.DEFAULT : camera.config();
        ResourceLocation texture = upload(receiver.getBlockPos(), frame, config, width, height);
        pose.pushPose();
        pose.translate(origin.getX() - receiver.getBlockPos().getX(),
                origin.getY() - receiver.getBlockPos().getY(),
                origin.getZ() - receiver.getBlockPos().getZ());
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(texture));
        int fullLight = LightTexture.FULL_BRIGHT;
        // One big quad cover the whole wall, 0.01 block outside the monitor front surface.
        float faceX = facing.getStepX() > 0 ? 1.0F : 0.0F;
        float faceY = facing.getStepY() > 0 ? 1.0F : 0.0F;
        float faceZ = facing.getStepZ() > 0 ? 1.0F : 0.0F;
        float ox = faceX + facing.getStepX() * 0.01F;
        float oy = faceY + facing.getStepY() * 0.01F;
        float oz = faceZ + facing.getStepZ() * 0.01F;
        float rx = right[0];
        float rz = right[2];
        float w = width;
        float h = height;
        // bottom-left, bottom-right, top-right, top-left (viewer looking at the screen)
        vertex(consumer, matrix, ox, oy, oz, 0.0F, 1.0F, fullLight, overlay);
        vertex(consumer, matrix, ox + rx * w, oy, oz + rz * w, 1.0F, 1.0F, fullLight, overlay);
        vertex(consumer, matrix, ox + rx * w, oy + h, oz + rz * w, 1.0F, 0.0F, fullLight, overlay);
        vertex(consumer, matrix, ox, oy + h, oz, 0.0F, 0.0F, fullLight, overlay);
        pose.popPose();
    }

    private static int[] wallBounds(Level level, BlockPos start, BlockState startState,
                                    Direction facing) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        Direction right = facing.getCounterClockWise();
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
        BlockPos origin = start;
        for (BlockPos pos : seen) {
            int a = pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
            if (a == minA && pos.getY() == minB) {
                origin = pos;
                break;
            }
        }
        return new int[]{maxA - minA + 1, maxB - minB + 1, origin.getX(), origin.getY(),
                origin.getZ(), right.getStepX(), right.getStepZ()};
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
