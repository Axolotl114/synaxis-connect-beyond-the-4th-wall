package com.ccvirtualcoms.client.camera.render;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.content.CCVirtualComsRegistry;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlock;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Three-axis gimbal camera renderer.
 *
 * <p>Model contract (see {@code blockbench_edits/camera_gimbal}): front is north (-Z), pivots are in
 * model units (÷16 for blocks), hierarchy is base → yaw(Y) → pitch(X) → roll(Z); the status LED sits
 * under pitch (never rolls) and blinks while the camera is enabled.
 */
public final class CameraTransmitterRenderer implements BlockEntityRenderer<CameraTransmitterBlockEntity> {

    private static final String[] PARTS = {"base", "yaw", "pitch", "roll", "status_led", "status_led_on"};
    private static final Map<String, BakedModel> MODELS = new HashMap<>();

    private static final double YAW_PIVOT = 3.0D;
    private static final double CAMERA_PIVOT = 9.5D;
    private static final int FULL_BRIGHT = 15728880;

    public CameraTransmitterRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraTransmitterBlockEntity camera, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        int light = net.minecraft.client.renderer.LightTexture.pack(
                minecraft.level == null ? 0 : minecraft.level.getBrightness(
                        net.minecraft.world.level.LightLayer.BLOCK, camera.getBlockPos()),
                minecraft.level == null ? 15 : Math.max(
                        minecraft.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,
                                camera.getBlockPos()),
                        minecraft.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,
                                camera.getBlockPos().above())));
        if (minecraft.level == null) {
            return;
        }
        if (com.ccvirtualcoms.client.camera.capture.CameraCapturePipeline.get()
                .activeCaptureKey() == camera.getBlockPos().asLong()) {
            return; // never render the camera's own body into its own view
        }
        resolveModels(minecraft);
        BlockState state = camera.getBlockState();
        Direction facing = state.getOptionalValue(CameraTransmitterBlock.FACING).orElse(Direction.NORTH);

        pose.pushPose();
        applyFacing(pose, facing);
        draw("base", pose, buffers, state, light, packedOverlay);

        double rawYaw = camera.eulerAngles()[0];
        double rawPitch = camera.eulerAngles()[1];
        double rawRoll = camera.eulerAngles()[2];
        double yaw = rawYaw;
        double pitch = rawPitch;
        double roll = rawRoll;
        org.joml.Quaterniond headTarget = null;
        if (camera.config() != null) {
            dev.ryanhcode.sable.companion.SubLevelAccess subLevel =
                    com.ccvirtualcoms.content.camera.CameraLocator.containingSubLevel(
                            minecraft.level, camera.getBlockPos());
            if (subLevel instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSubLevel) {
                org.joml.Quaterniond subRotation = new org.joml.Quaterniond(
                        clientSubLevel.renderPose(partialTick).orientation());
                // Model animation runs in sub-level local space:
                //   follow axes -> raw local Euler component;
                //   world-stable axes -> world angle converted into local space by inverse(sub-level).
                org.joml.Quaterniond local = com.ccvirtualcoms.client.camera.capture.CameraTransformMath
                        .cameraRotation((float) yaw, (float) pitch, (float) roll);
                org.joml.Quaterniond worldToLocal = new org.joml.Quaterniond(subRotation)
                        .invert().mul(local);
                float[] worldLocal = com.ccvirtualcoms.client.camera.capture.CameraTransformMath
                        .eulerFromRotation(worldToLocal, new float[3]);
                float useYaw = camera.config().yawMode() == com.ccvirtualcoms.camera.CameraAxisMode.WORLD_STABLE
                        ? worldLocal[0] : (float) yaw;
                // The model hierarchy applies Rx(-pitch) and Rz(+roll); the world->local Euler
                // decomposition has the opposite signs for these two components.
                float usePitch = camera.config().pitchMode() == com.ccvirtualcoms.camera.CameraAxisMode.WORLD_STABLE
                        ? -worldLocal[1] : (float) pitch;
                float useRoll = camera.config().rollMode() == com.ccvirtualcoms.camera.CameraAxisMode.WORLD_STABLE
                        ? -worldLocal[2] : (float) roll;
                org.joml.Quaterniond worldRotation = com.ccvirtualcoms.client.camera.capture.CameraTransformMath
                        .cameraRotation(useYaw, usePitch, useRoll);
                org.joml.Quaterniond modelRotation = new org.joml.Quaterniond(subRotation)
                        .invert().mul(worldRotation);
                float[] display = com.ccvirtualcoms.client.camera.capture.CameraTransformMath
                        .eulerFromRotation(modelRotation, new float[3]);
                // Frames use the per-axis converted angles (world-stable axes counter-rotate the
                // sub-level, follow axes stay local). Only the head is driven by the quaternion to
                // avoid gimbal-lock spinning.
                // Use the per-axis values directly: follow = local Euler, world = local decomposition
                // of the world rotation (the full-pose decomposition would double the compensation).
                yaw = useYaw;
                pitch = usePitch;
                roll = useRoll;
                if (Math.abs(pitch) > 89.0D) {
                    headTarget = new org.joml.Quaterniond(modelRotation);
                }
            }
        }

        // Local yaw relative to the facing already applied above (model front = north/-Z).
        pose.pushPose();
        pivotRotate(pose, Axis.YP, (float) (facing.toYRot() - yaw + 180.0D), 8.0D, YAW_PIVOT, 8.0D);
        draw("yaw", pose, buffers, state, light, packedOverlay);

        // Pitch: model +X looks up, Minecraft pitch positive looks down.
        pose.pushPose();
        pivotRotate(pose, Axis.XP, (float) -pitch, 8.0D, CAMERA_PIVOT, 8.0D);
        draw("pitch", pose, buffers, state, light, packedOverlay);
        draw("status_led", pose, buffers, state, light, packedOverlay);
        if (camera.clientStatus() == CameraStreamStatus.STREAMING
                && Math.floorMod(minecraft.level.getGameTime(), 20L) < 10L) {
            draw("status_led_on", pose, buffers, state, light, packedOverlay);
        }

        pose.pushPose();
        if (headTarget != null) {
            org.joml.Quaterniond facingQuat = facingQuaternion(facing);
            float yawAngle = (float) (facing.toYRot() - yaw + 180.0D);
            org.joml.Quaterniond frame = new org.joml.Quaterniond(facingQuat)
                    .mul(new org.joml.Quaterniond().rotationY((float) Math.toRadians(yawAngle)))
                    .mul(new org.joml.Quaterniond().rotationX((float) Math.toRadians(-pitch)));
            org.joml.Quaterniond target = new org.joml.Quaterniond(facingQuat).invert()
                    .mul(headTarget)
                    .mul(new org.joml.Quaterniond().rotationY((float) Math.PI));
            org.joml.Quaterniond delta = frame.invert().mul(target);
            pose.translate(8.0D / 16.0D, CAMERA_PIVOT / 16.0D, 8.0D / 16.0D);
            pose.mulPose(new org.joml.Quaternionf(delta));
            pose.translate(-8.0D / 16.0D, -CAMERA_PIVOT / 16.0D, -8.0D / 16.0D);
        } else {
            pivotRotate(pose, Axis.ZP, (float) roll, 8.0D, CAMERA_PIVOT, 8.0D);
        }
        draw("roll", pose, buffers, state, light, packedOverlay);
        pose.popPose();

        pose.popPose();
        pose.popPose();
        pose.popPose();
    }

    private static org.joml.Quaterniond facingQuaternion(Direction facing) {
        org.joml.Quaterniond quat = new org.joml.Quaterniond();
        switch (facing) {
            case SOUTH -> quat.rotationY((float) Math.PI);
            case EAST -> quat.rotationY((float) (Math.PI / 2.0D));
            case WEST -> quat.rotationY((float) (Math.PI * 1.5D));
            case UP -> quat.rotationX((float) (Math.PI * 1.5D));
            case DOWN -> quat.rotationX((float) (Math.PI / 2.0D));
            default -> {
            }
        }
        return quat;
    }

    private static void applyFacing(PoseStack pose, Direction facing) {
        pose.translate(0.5D, 0.0D, 0.5D);
        switch (facing) {
            case SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            case EAST -> pose.mulPose(Axis.YP.rotationDegrees(90.0F));
            case WEST -> pose.mulPose(Axis.YP.rotationDegrees(270.0F));
            case UP -> pose.mulPose(Axis.XP.rotationDegrees(270.0F));
            case DOWN -> pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            default -> {
            }
        }
        pose.translate(-0.5D, 0.0D, -0.5D);
    }

    private static void pivotRotate(PoseStack pose, com.mojang.math.Axis axis, float degrees,
                                    double x, double y, double z) {
        pose.translate(x / 16.0D, y / 16.0D, z / 16.0D);
        pose.mulPose(axis.rotationDegrees(degrees));
        pose.translate(-x / 16.0D, -y / 16.0D, -z / 16.0D);
    }

    private static void draw(String part, PoseStack pose, MultiBufferSource buffers, BlockState state,
                             int packedLight, int packedOverlay) {
        BakedModel model = MODELS.get(part);
        if (model == null) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutout());
        RandomSource random = RandomSource.create(42L);
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            consumer.putBulkData(pose.last(), quad, 1.0F, 1.0F, 1.0F, 1.0F, packedLight, packedOverlay);
        }
        for (Direction direction : Direction.values()) {
            for (BakedQuad quad : model.getQuads(state, direction, random)) {
                consumer.putBulkData(pose.last(), quad, 1.0F, 1.0F, 1.0F, 1.0F, packedLight, packedOverlay);
            }
        }
    }

    private static void resolveModels(Minecraft minecraft) {
        if (MODELS.size() == PARTS.length) {
            return;
        }
        MODELS.clear();
        for (String part : PARTS) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID,
                    "block/camera/" + part);
            MODELS.put(part, minecraft.getModelManager()
                    .getModel(ModelResourceLocation.standalone(id)));
        }
    }

    /** Registers the renderer and the standalone part models. */
    @EventBusSubscriber(modid = CCVirtualComs.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class ClientEvents {

        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
            for (String part : PARTS) {
                event.register(ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID,
                                "block/camera/" + part)));
            }
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(CCVirtualComsRegistry.CAMERA_TRANSMITTER_BE.get(),
                    CameraTransmitterRenderer::new);
        }
    }
}
