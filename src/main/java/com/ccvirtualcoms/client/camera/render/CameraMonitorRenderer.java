package com.ccvirtualcoms.client.camera.render;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.client.camera.capture.CameraCaptureRuntime;
import com.ccvirtualcoms.client.camera.capture.CapturedCamera;
import com.ccvirtualcoms.content.CCVirtualComsRegistry;
import com.ccvirtualcoms.content.camera.CameraMonitorBlock;
import com.ccvirtualcoms.content.camera.CameraMonitorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws the live camera picture on camera monitors.
 *
 * <p>The static block model supplies the thin metal frame; this renderer only draws the screen
 * quad, which is textured with the merged screen's {@link CameraMonitorScreenTextures.ScreenTexture}
 * and is slightly in front of the frame to avoid z-fighting.
 *
 * <p>Every block of a merged wall draws its own tile, but the texture coordinates are computed from
 * the tile's column/row and the screen's width/height, so the wall samples one continuous image.
 * The tiles on the outer edge inset the quad a little; the CC:Tweaked monitor bezel of the block
 * model then shows through as a frame around the whole wall. Interior tiles are not inset, so a
 * merged wall has no seams.
 *
 * <p>Disabled screens, missing cameras and frames that are not yet available are shown as opaque
 * black.
 */
public final class CameraMonitorRenderer implements BlockEntityRenderer<CameraMonitorBlockEntity> {

    /** How far in front of the frame plane the screen quad sits. */
    private static final float SCREEN_OFFSET = 0.002F;

    /** Bezel width revealed on the outer edge of a merged screen, in blocks. */
    private static final float BORDER = 2.0F / 16.0F;

    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public CameraMonitorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraMonitorBlockEntity monitor, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = monitor.getLevel();
        if (level == null) {
            return;
        }

        CameraMonitorBlockEntity master = monitor.master();
        long cameraKey = master == null ? CameraMonitorBlockEntity.NO_CAMERA : master.cameraKey();
        boolean enabled = master != null && master.enabled()
                && cameraKey != CameraMonitorBlockEntity.NO_CAMERA;

        int screenWidth = Math.max(1, monitor.screenWidth());
        int screenHeight = Math.max(1, monitor.screenHeight());
        CameraMonitorScreenTextures.ScreenTexture screen =
                CameraMonitorScreenTextures.get(monitor.screenOrigin().asLong());

        if (enabled) {
            CapturedCamera captured = CameraCaptureRuntime.get().camera(cameraKey);
            CameraFrame frame = captured == null ? null : captured.latestFrame();
            if (frame != null) {
                screen.update(frame, screenWidth, screenHeight);
            } else {
                screen.clear();
            }
        } else {
            screen.clear();
        }

        this.drawScreen(monitor, screen.id(), pose, buffers);
    }

    private void drawScreen(CameraMonitorBlockEntity monitor, ResourceLocation texture,
                            PoseStack pose, MultiBufferSource buffers) {
        Level level = monitor.getLevel();
        if (level == null) {
            return;
        }
        Direction facing = monitor.getBlockState()
                .getOptionalValue(CameraMonitorBlock.FACING)
                .orElse(Direction.NORTH);
        Direction right = facing.getCounterClockWise();
        BlockPos pos = monitor.getBlockPos();
        BlockPos origin = monitor.screenOrigin();

        float insetLeft = sameScreen(level, pos.relative(right.getOpposite()), origin) ? 0.0F : BORDER;
        float insetRight = sameScreen(level, pos.relative(right), origin) ? 0.0F : BORDER;
        float insetBottom = sameScreen(level, pos.below(), origin) ? 0.0F : BORDER;
        float insetTop = sameScreen(level, pos.above(), origin) ? 0.0F : BORDER;

        int width = Math.max(1, monitor.screenWidth());
        int height = Math.max(1, monitor.screenHeight());
        int column = Mth.clamp(monitor.column(), 0, width - 1);
        int row = Mth.clamp(monitor.row(), 0, height - 1);

        float uLeft = (column + insetLeft) / width;
        float uRight = (column + 1.0F - insetRight) / width;
        float vBottom = 1.0F - (row + insetBottom) / height;
        float vTop = 1.0F - (row + 1.0F - insetTop) / height;

        float normalX = facing.getStepX();
        float normalZ = facing.getStepZ();
        float rightX = right.getStepX();
        float rightZ = right.getStepZ();

        float centerX = 0.5F + normalX * (0.5F + SCREEN_OFFSET);
        float centerZ = 0.5F + normalZ * (0.5F + SCREEN_OFFSET);
        float leftHalf = 0.5F - insetLeft;
        float rightHalf = 0.5F - insetRight;
        float leftX = centerX - rightX * leftHalf;
        float leftZ = centerZ - rightZ * leftHalf;
        float rightXPos = centerX + rightX * rightHalf;
        float rightZPos = centerZ + rightZ * rightHalf;
        float bottomY = insetBottom;
        float topY = 1.0F - insetTop;

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        vertex(consumer, pose, leftX, bottomY, leftZ, uLeft, vBottom, normalX, normalZ);
        vertex(consumer, pose, rightXPos, bottomY, rightZPos, uRight, vBottom, normalX, normalZ);
        vertex(consumer, pose, rightXPos, topY, rightZPos, uRight, vTop, normalX, normalZ);
        vertex(consumer, pose, leftX, topY, leftZ, uLeft, vTop, normalX, normalZ);
    }

    private static void vertex(VertexConsumer consumer, PoseStack pose, float x, float y, float z,
                               float u, float v, float normalX, float normalZ) {
        consumer.addVertex(pose.last(), x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose.last(), normalX, 0.0F, normalZ);
    }

    /** {@code true} when the neighbour is a tile of the same merged screen. */
    private static boolean sameScreen(Level level, BlockPos pos, BlockPos origin) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof CameraMonitorBlockEntity neighbour
                && !neighbour.isRemoved()
                && origin.equals(neighbour.screenOrigin());
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    /** Registers the renderer. */
    @EventBusSubscriber(modid = CCVirtualComs.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class ClientEvents {

        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(CCVirtualComsRegistry.CAMERA_MONITOR_BE.get(),
                    CameraMonitorRenderer::new);
        }
    }
}
