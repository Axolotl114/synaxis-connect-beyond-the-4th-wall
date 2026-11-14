package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.content.monitor.CameraMonitorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: set the camera and enable state of a monitor wall. */
public record MonitorConfigPayload(BlockPos pos, boolean hasCamera, long cameraKey, boolean enabled)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MonitorConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, "monitor_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MonitorConfigPayload> STREAM_CODEC =
            StreamCodec.of(MonitorConfigPayload::encode, MonitorConfigPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, MonitorConfigPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeBoolean(payload.hasCamera());
        buffer.writeLong(payload.cameraKey());
        buffer.writeBoolean(payload.enabled());
    }

    private static MonitorConfigPayload decode(RegistryFriendlyByteBuf buffer) {
        return new MonitorConfigPayload(BlockPos.STREAM_CODEC.decode(buffer), buffer.readBoolean(),
                buffer.readLong(), buffer.readBoolean());
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D,
                    this.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                return;
            }
            BlockEntity blockEntity = level.getBlockEntity(this.pos);
            if (!(blockEntity instanceof CameraMonitorBlockEntity monitor)) {
                return;
            }
            CameraMonitorBlockEntity master = monitor.master();
            CameraMonitorBlockEntity target = master == null ? monitor : master;
            target.apply(this.hasCamera ? BlockPos.of(this.cameraKey) : null, this.enabled);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
