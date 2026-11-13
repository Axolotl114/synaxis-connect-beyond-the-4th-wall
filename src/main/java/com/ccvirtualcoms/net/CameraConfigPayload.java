package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraAxisMode;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraRedstoneMode;
import com.ccvirtualcoms.camera.CameraRenderMode;
import com.ccvirtualcoms.camera.CameraResolution;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.content.camera.CameraLocator;
import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client to server: replace a camera transmitter's whole configuration.
 *
 * <p>Like the virtual serial port board, the GUI is declarative: it always sends the complete
 * desired state. The server validates it through {@link CameraConfig#sanitized()} and is the only
 * writer of the persisted configuration.
 */
public record CameraConfigPayload(BlockPos pos, CameraConfig config) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("CCVirtualComs/CameraConfig");

    /** Maximum distance a player may be from the camera while editing it. */
    private static final double MAX_EDIT_DISTANCE_SQ = 64.0D * 64.0D;

    public static final CustomPacketPayload.Type<CameraConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, "camera_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraConfigPayload> STREAM_CODEC =
            StreamCodec.of(CameraConfigPayload::encode, CameraConfigPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, CameraConfigPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        CameraCodecs.encodeConfig(buffer, payload.config() == null ? CameraConfig.DEFAULT : payload.config());
    }

    private static CameraConfigPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        return new CameraConfigPayload(pos, CameraCodecs.decodeConfig(buffer));
    }

    /** Server side application of a configuration request. */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockEntity blockEntity = CameraLocator.find(level, this.pos);
            if (!(blockEntity instanceof CameraTransmitterBlockEntity camera)) {
                return;
            }
            Vec3 worldPos = CameraLocator.worldPosition(level, this.pos);
            if (player.distanceToSqr(worldPos) > MAX_EDIT_DISTANCE_SQ) {
                LOGGER.debug("Ignoring camera configuration from {} - out of range",
                        player.getGameProfile().getName());
                return;
            }
            String error = camera.applyConfiguration(this.config);
            if (error != null) {
                LOGGER.debug("Rejected camera configuration from {}: {}",
                        player.getGameProfile().getName(), error);
            }
            CameraStatusPayload.sendTo(player, camera);
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Shared wire format for the camera configuration, also used by the status payload. */
    public static final class CameraCodecs {

        private CameraCodecs() {
        }

        static void encodeConfig(RegistryFriendlyByteBuf buffer, CameraConfig config) {
            CameraResolution resolution = config.resolution();
            buffer.writeVarInt(resolution.width());
            buffer.writeVarInt(resolution.height());
            buffer.writeVarInt(config.fps());
            buffer.writeBoolean(config.color());
            buffer.writeBoolean(config.nightVision());
            buffer.writeBoolean(config.redstoneControl());
            buffer.writeBoolean(config.holdOn());
            buffer.writeVarInt(config.redstoneMode().ordinal());
            buffer.writeVarInt(config.redstoneThreshold());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, config.frequencyA());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, config.frequencyB());
            buffer.writeVarInt(config.yawMode().ordinal());
            buffer.writeVarInt(config.pitchMode().ordinal());
            buffer.writeVarInt(config.rollMode().ordinal());
            buffer.writeDouble(config.offsetX());
            buffer.writeDouble(config.offsetY());
            buffer.writeDouble(config.offsetZ());
            buffer.writeVarInt(config.renderMode().ordinal());
            buffer.writeVarInt(config.fov());
        }

        static CameraConfig decodeConfig(RegistryFriendlyByteBuf buffer) {
            int width = buffer.readVarInt();
            int height = buffer.readVarInt();
            int fps = buffer.readVarInt();
            boolean color = buffer.readBoolean();
            boolean nightVision = buffer.readBoolean();
            boolean redstoneControl = buffer.readBoolean();
            boolean holdOn = buffer.readBoolean();
            CameraRedstoneMode redstoneMode = ordinal(CameraRedstoneMode.values(), buffer.readVarInt(),
                    CameraRedstoneMode.HOLD);
            int threshold = buffer.readVarInt();
            ItemStack frequencyA = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            ItemStack frequencyB = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            CameraAxisMode yawMode = ordinal(CameraAxisMode.values(), buffer.readVarInt(), CameraAxisMode.WORLD_STABLE);
            CameraAxisMode pitchMode = ordinal(CameraAxisMode.values(), buffer.readVarInt(), CameraAxisMode.WORLD_STABLE);
            CameraAxisMode rollMode = ordinal(CameraAxisMode.values(), buffer.readVarInt(), CameraAxisMode.WORLD_STABLE);
            double offsetX = buffer.readDouble();
            double offsetY = buffer.readDouble();
            double offsetZ = buffer.readDouble();
            CameraRenderMode renderMode = ordinal(CameraRenderMode.values(), buffer.readVarInt(),
                    CameraRenderMode.FOLLOW_PLAYER);
            int fov = buffer.readVarInt();
            return new CameraConfig(
                    new CameraResolution(width, height),
                    fps,
                    color,
                    nightVision,
                    redstoneControl,
                    holdOn,
                    redstoneMode,
                    threshold,
                    frequencyA,
                    frequencyB,
                    yawMode,
                    pitchMode,
                    rollMode,
                    offsetX,
                    offsetY,
                    offsetZ,
                    renderMode,
                    fov);
        }

        static CameraStreamStatus statusByName(String name) {
            return CameraStreamStatus.byNameOrDefault(name);
        }

        static <E> E ordinal(E[] values, int index, E fallback) {
            return index >= 0 && index < values.length ? values[index] : fallback;
        }
    }
}
