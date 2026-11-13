package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.content.camera.CameraLocator;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Direct C2S edit of the camera's yaw/pitch/roll Euler angles (camera page inputs). */
public record CameraEulerPayload(BlockPos pos, double yaw, double pitch, double roll)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CameraEulerPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    CCVirtualComs.MOD_ID, "camera_euler"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraEulerPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeBlockPos(payload.pos);
                buffer.writeDouble(payload.yaw);
                buffer.writeDouble(payload.pitch);
                buffer.writeDouble(payload.roll);
            }, buffer -> new CameraEulerPayload(buffer.readBlockPos(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble()));

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            CameraTransmitterBlockEntity camera = CameraLocator.find(level, this.pos);
            if (camera == null) {
                return;
            }
            Vec3 world = CameraLocator.worldPosition(level, this.pos);
            if (player.distanceToSqr(world) > 64.0D * 64.0D) {
                return;
            }
            camera.applyEulerAngles(this.yaw, this.pitch, this.roll);
            CameraStatusPayload.sendTo(player, camera);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
