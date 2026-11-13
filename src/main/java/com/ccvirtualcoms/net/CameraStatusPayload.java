package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: full camera transmitter status snapshot.
 *
 * <p>Sent on every configuration change and periodically while the trigger state is moving, so an
 * open GUI shows live values (redstone strength, stream state, device name, fallback URL) without
 * polling the server.
 */
public record CameraStatusPayload(
        BlockPos pos,
        CameraConfig config,
        CameraStreamStatus status,
        String deviceName,
        String streamUrl,
        int redstoneSignal,
        int revision,
        boolean enableInput,
        double eulerYaw,
        double eulerPitch,
        double eulerRoll,
        String detail) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CameraStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, "camera_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraStatusPayload> STREAM_CODEC =
            StreamCodec.of(CameraStatusPayload::encode, CameraStatusPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, CameraStatusPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        CameraConfigPayload.CameraCodecs.encodeConfig(buffer, payload.config());
        buffer.writeUtf(payload.status().serializedName(), 32);
        buffer.writeUtf(payload.deviceName(), 128);
        buffer.writeUtf(payload.streamUrl(), 512);
        buffer.writeVarInt(payload.redstoneSignal());
        buffer.writeVarInt(payload.revision());
        buffer.writeBoolean(payload.enableInput());
        buffer.writeDouble(payload.eulerYaw());
        buffer.writeDouble(payload.eulerPitch());
        buffer.writeDouble(payload.eulerRoll());
        buffer.writeUtf(payload.detail(), 256);
    }

    private static CameraStatusPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        CameraConfig config = CameraConfigPayload.CameraCodecs.decodeConfig(buffer);
        CameraStreamStatus status = CameraStreamStatus.byNameOrDefault(buffer.readUtf(32));
        String deviceName = buffer.readUtf(128);
        String streamUrl = buffer.readUtf(512);
        int signal = buffer.readVarInt();
        int revision = buffer.readVarInt();
        boolean enable = buffer.readBoolean();
        double yaw = buffer.readDouble();
        double pitch = buffer.readDouble();
        double roll = buffer.readDouble();
        String detail = buffer.readUtf(256);
        return new CameraStatusPayload(pos, config, status, deviceName, streamUrl, signal, revision,
                enable, yaw, pitch, roll, detail);
    }

    /** Builds a snapshot from a live block entity. */
    public static CameraStatusPayload of(CameraTransmitterBlockEntity camera) {
        return new CameraStatusPayload(
                camera.getBlockPos(),
                camera.config(),
                camera.streamingDesired() ? CameraStreamStatus.STREAMING : CameraStreamStatus.OFFLINE,
                camera.deviceName(),
                camera.streamUrl(),
                camera.redstoneSignal(),
                camera.revision(),
                camera.enableInput(),
                camera.eulerAngles()[0],
                camera.eulerAngles()[1],
                camera.eulerAngles()[2],
                camera.statusDetail());
    }

    /** Sends this camera's snapshot to one player. */
    public static void sendTo(ServerPlayer player, CameraTransmitterBlockEntity camera) {
        PacketDistributor.sendToPlayer(player, of(camera));
    }

    /** Client side: hand the snapshot to the block entity the GUI reads from. */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            Level level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            if (level.getBlockEntity(this.pos) instanceof CameraTransmitterBlockEntity camera) {
                camera.applyStatusSnapshot(this.config, this.status, this.deviceName, this.streamUrl,
                        this.redstoneSignal, this.revision, this.detail);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
