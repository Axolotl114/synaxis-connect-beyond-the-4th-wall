package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.content.camera.CameraMonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client to server: change the whole-screen configuration of a camera monitor.
 *
 * <p>The GUI is declarative: it sends the complete desired state (the selected camera as a packed
 * block position plus the on/off flag). The server validates that the player is close enough to the
 * screen's master, writes the values into the master block entity (which persists them in its NBT)
 * and syncs the block entity update tag to every client tracking the screen.
 *
 * <p>Monitors merge into screens, so the payload is always addressed to the master (min corner)
 * position. If a client sends a slave position anyway, the server resolves the component master
 * before applying the change.
 */
public record CameraMonitorPayload(BlockPos pos, long cameraKey, boolean enabled)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("CCVirtualComs/CameraMonitor");

    /** Maximum distance a player may be from the monitor while editing it. */
    private static final double MAX_EDIT_DISTANCE_SQ = 64.0D * 64.0D;

    public static final CustomPacketPayload.Type<CameraMonitorPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, "camera_monitor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraMonitorPayload> STREAM_CODEC =
            StreamCodec.of(CameraMonitorPayload::encode, CameraMonitorPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, CameraMonitorPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeLong(payload.cameraKey());
        buffer.writeBoolean(payload.enabled());
    }

    private static CameraMonitorPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        long cameraKey = buffer.readLong();
        boolean enabled = buffer.readBoolean();
        return new CameraMonitorPayload(pos, cameraKey, enabled);
    }

    /** Server side application of a screen configuration request. */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockEntity blockEntity = level.getBlockEntity(this.pos);
            if (!(blockEntity instanceof CameraMonitorBlockEntity monitor)) {
                return;
            }
            CameraMonitorBlockEntity master = monitor.master();
            if (master == null) {
                if (!monitor.isMaster()) {
                    return; // master is not available, so there is nothing authoritative to write
                }
                master = monitor;
            }
            Vec3 worldPos = Vec3.atCenterOf(master.getBlockPos());
            if (player.distanceToSqr(worldPos) > MAX_EDIT_DISTANCE_SQ) {
                LOGGER.debug("Ignoring camera monitor configuration from {} - out of range",
                        player.getGameProfile().getName());
                return;
            }
            if (master.applyMonitorConfig(this.cameraKey, this.enabled)) {
                master.commit();
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Registers the payload on the same mod-bus event the other payloads use.
     *
     * <p>Kept in this new class instead of editing {@code CCVirtualComs#registerPayloads} so the
     * feature stays inside its own files; the registration itself is identical.
     */
    @EventBusSubscriber(modid = CCVirtualComs.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {

        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToServer(TYPE, STREAM_CODEC,
                    (payload, context) -> payload.handle(context));
        }
    }
}
