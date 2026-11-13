package com.ccvirtualcoms.content.camera;

import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.net.CameraStatusPayload;
import com.ccvirtualcoms.synaxis.CameraTransmitterComponent;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.PortDef;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalType;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.CimulinkEndpoint;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.CimulinkEndpointProvider;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointAddress;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointDefinition;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointId;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointRuntimeBinding;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointUiHints;
import com.verr1.synaxis.foundation.cimulink.game.runtime.CimulinkLevelRuntime;
import com.verr1.synaxis.foundation.cimulink.game.runtime.CimulinkWorldRuntimes;
import com.verr1.synaxis.foundation.physics.SynaxisPhysics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The block entity behind a camera transmitter.
 *
 * <p>The server is the single source of truth for everything except the actual frame capture:
 *
 * <ul>
 *   <li>configuration (resolution, frame rate, colour, night vision, trigger settings, camera
 *       Euler modes and offset, shader preference) is persisted here;</li>
 *   <li>redstone reading and the Synaxis {@code enable}/{@code euler_angle} terminals are evaluated
 *       here on the server thread;</li>
 *   <li>the resulting {@code streaming} state is synchronised to watching clients, which own the
 *       actual capture, MJPEG encoding and virtual camera output.</li>
 * </ul>
 *
 * <h2>Trigger priority</h2>
 *
 * <p>Highest first:
 * <ol>
 *   <li>the Synaxis {@code enable} terminal;</li>
 *   <li>the redstone input (vanilla neighbours, or Create wireless redstone when both frequency
 *       slots are filled);</li>
 *   <li>the GUI hold state.</li>
 * </ol>
 *
 * <p>When {@link CameraConfig#redstoneControl()} is off, the GUI hold state is the sole authority.
 * When it is on:
 * <pre>
 * enable=true,  redstone=any    -&gt; ON
 * enable=false, redstone on     -&gt; ON
 * enable=false, redstone off    -&gt; OFF
 * </pre>
 * The Synaxis terminal therefore wins over redstone: when enable is true redstone cannot turn the
 * stream off, and when enable is false redstone may still turn it on.
 */
public class CameraTransmitterBlockEntity extends BlockEntity
        implements CimulinkEndpoint, CimulinkEndpointProvider {

    // ------------------------------------------------------------------ NBT keys

    private static final String CONFIG_TAG = "Camera";
    private static final String EULER_TAG = "Euler";
    private static final String EULER_VERSION_TAG = "EulerVersion";
    private static final int EULER_VERSION = 2;

    private static final String REVISION_TAG = "Revision";
    private static final String ENDPOINT_ID_TAG = "EndpointId";
    private static final String STATUS_DETAIL_TAG = "StatusDetail";
    /** Diagnostic mirrors, written for /data get and logs; never read back into state. */
    private static final String DIAG_STREAMING_TAG = "DiagStreaming";
    private static final String DIAG_REDSTONE_TAG = "DiagRedstone";
    private static final String DIAG_DEVICE_SLOT_TAG = "DiagDeviceSlot";

    /** How often the full status snapshot is pushed to watching clients, in ticks. */
    private static final int STATUS_SYNC_INTERVAL = 10;

    private static final EndpointRuntimeBinding BINDING = EndpointRuntimeBinding.allowing(new ExecutionDomain[0]);

    /** Hard limit on simultaneously registered virtual camera devices. */
    public static final int MAX_DEVICES = 4;

    // ------------------------------------------------------------------ state

    private CameraConfig config = CameraConfig.DEFAULT;

    /** Raw Euler angles (yaw, pitch, roll) as written by the Synaxis terminal, degrees. */
    private double eulerYaw;
    private double eulerPitch;
    private double eulerRoll;

    /** The Synaxis {@code enable} input of the last tick. */
    private boolean enableInput;

    /** The redstone strength read this tick, 0-15. */
    private int redstoneSignal;

    /** The latched state of the toggle-mode redstone trigger. */
    private boolean redstoneToggleState;

    /** The desired output state after applying the trigger priority. */
    private boolean streamingDesired;

    /** Free-form diagnostic line shown in the GUI and the log. */
    private String statusDetail = "Ready";

    private int revision;

    /** Virtual camera device slot assigned by the server, or -1 when unavailable. */
    private int deviceSlot = -1;

    /** Last state pushed to this client, never persisted. */
    private CameraStreamStatus clientStatus = CameraStreamStatus.OFFLINE;
    private String clientDeviceName = "";
    private String clientStreamUrl = "";

    private EndpointId endpointId = EndpointId.random();
    private boolean endpointRegistered;
    private long lastStatusSyncTick = Long.MIN_VALUE;
    private int lastSyncedRevision = -1;
    private boolean lastSyncedStreaming;
    private int lastSyncedSignal = -1;

    public CameraTransmitterBlockEntity(BlockPos pos, BlockState state) {
        super(com.ccvirtualcoms.content.CCVirtualComsRegistry.CAMERA_TRANSMITTER_BE.get(), pos, state);
        this.syncEulerFromFacing(state);
    }

    // ------------------------------------------------------------------ configuration

    public CameraConfig config() {
        return this.config;
    }

    /** Server side: validates, stores and broadcasts a client supplied configuration snapshot. */
    public String applyConfiguration(CameraConfig requested) {
        CameraConfig safe = requested == null ? CameraConfig.DEFAULT : requested.sanitized();
        if (safe.equals(this.config)) {
            this.setStatus("Configuration unchanged");
            this.broadcastStatus();
            return null;
        }
        this.config = safe;
        this.revision++;
        this.setStatus("Configuration applied");
        this.setChanged();
        Level level = this.getLevel();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
        }
        this.broadcastStatus();
        return null;
    }

    /** Monotonic counter bumped on every accepted configuration change. */
    public int revision() {
        return this.revision;
    }

    /** The desired streaming state after trigger resolution. */
    public boolean streamingDesired() {
        return this.streamingDesired;
    }

    /** The current redstone strength seen by the block, 0-15. */
    public int redstoneSignal() {
        return this.redstoneSignal;
    }

    /** The Synaxis {@code enable} value of the last tick. */
    public boolean enableInput() {
        return this.enableInput;
    }

    /** Raw Euler angles {yaw, pitch, roll} in degrees, as written by the Synaxis terminal. */
    public double[] eulerAngles() {
        return new double[] {this.eulerYaw, this.eulerPitch, this.eulerRoll};
    }

    /** Human readable diagnostic line. */
    public String statusDetail() {
        return this.statusDetail;
    }

    /** The virtual camera device slot, or -1 when no device is available. */
    public int deviceSlot() {
        return this.deviceSlot;
    }

    /** The device name shown by external applications, or an empty string when unavailable. */
    public String deviceName() {
        return this.deviceSlot < 0 ? "" : "Synaxis Camera " + (this.deviceSlot + 1);
    }

    /** The MJPEG fallback URL of this camera, or an empty string when not running. */
    public String streamUrl() {
        return com.ccvirtualcoms.camera.CameraStreamUrls.urlFor(this.getBlockPos().asLong());
    }

    /** Client side view of the last server status snapshot. */
    public CameraStreamStatus clientStatus() {
        return this.clientStatus;
    }

    /** Client side device name from the last status snapshot. */
    public String clientDeviceName() {
        return this.clientDeviceName;
    }

    /** Client side stream URL from the last status snapshot. */
    public String clientStreamUrl() {
        return this.clientStreamUrl;
    }

    /** Client side: applies a status snapshot pushed by the server. */
    public void applyStatusSnapshot(CameraConfig snapshotConfig, CameraStreamStatus status,
                                    String deviceName, String streamUrl, int signal, int newRevision,
                                    String detail) {
        if (snapshotConfig != null) {
            this.config = snapshotConfig;
        }
        this.clientStatus = status == null ? CameraStreamStatus.OFFLINE : status;
        this.clientDeviceName = deviceName == null ? "" : deviceName;
        this.clientStreamUrl = streamUrl == null ? "" : streamUrl;
        this.redstoneSignal = signal;
        this.revision = newRevision;
        if (detail != null) {
            this.statusDetail = detail;
        }
    }

    // ------------------------------------------------------------------ Synaxis access

    /**
     * The fixed terminal layout of a camera transmitter.
     *
     * <p>Synaxis requires port names to be unique across inputs and outputs, so the bidirectional
     * {@code euler_angle} terminal of the specification is represented by a matched input/output
     * pair: {@code euler_angle_in} (the circuit drives it) and {@code euler_angle_out} (the circuit
     * reads it). Both carry {@code (yaw, pitch, roll)} in degrees with the same per-axis
     * world-stable / follow-sub-level semantics.
     */
    public static ComponentSchema schema() {
        return ComponentSchema.of(
                List.of(
                        PortDef.input("enable", SignalType.BOOLEAN),
                        PortDef.input("euler_angle_in", SignalType.VEC3)),
                List.of(
                        PortDef.output("streaming", SignalType.BOOLEAN),
                        PortDef.output("euler_angle_out", SignalType.VEC3)));
    }

    /** Short labels for the Cimulink editor. */
    public Map<String, String> portLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("enable", "enable [in] boolean");
        labels.put("euler_angle_in", "euler_angle [in] vec3 (yaw, pitch, roll)");
        labels.put("streaming", "streaming [out] boolean");
        labels.put("euler_angle_out", "euler_angle [out] vec3 (yaw, pitch, roll)");
        return labels;
    }

    /** Called by the Cimulink graph through the output side. */
    public SignalValue readForSynaxis(String name) {
        return switch (name) {
            case "streaming" -> new SignalValue.Bool(this.streamingDesired);
            case "euler_angle", "euler_angle_out" ->
                    new SignalValue.Vec3(this.eulerYaw, this.eulerPitch, this.eulerRoll);
            default -> null;
        };
    }

    /** Called by the Cimulink graph through the input side. */
    public void writeFromSynaxis(String name, SignalValue value) {
        if (value == null) {
            return;
        }
        switch (name) {
            case "enable" -> {
                if (value instanceof SignalValue.Bool bool) {
                    this.enableInput = bool.value();
                }
            }
            case "euler_angle", "euler_angle_in" -> {
                if (value instanceof SignalValue.Vec3 vec) {
                    if (Double.isFinite(vec.x()) && Double.isFinite(vec.y()) && Double.isFinite(vec.z())) {
                        this.eulerYaw = normalizeYaw(vec.x());
                        this.eulerPitch = clampPitch(vec.y());
                        this.eulerRoll = normalizeRoll(vec.z());
                    }
                }
            }
            default -> {
                // Output terminals cannot be written by the graph.
            }
        }
    }

    /** The plant port object handed to Synaxis for this block entity. */
    public CameraTransmitterPlantPort createPlantPort(EndpointId id, String requestedName) {
        return new CameraTransmitterPlantPort(this, id,
                requestedName == null || requestedName.isBlank()
                        ? "Camera " + this.getBlockPos().toShortString()
                        : requestedName);
    }

    @Override
    public CimulinkEndpoint endpoint() {
        return this;
    }

    @Override
    public EndpointId id() {
        return this.endpointId;
    }

    @Override
    public EndpointAddress address() {
        Level current = this.getLevel();
        return current == null
                ? EndpointAddress.of(Level.OVERWORLD, this.getBlockPos())
                : EndpointAddress.of(current, this.getBlockPos())
                        .withBody(SynaxisPhysics.bodyAt(current, this.getBlockPos()));
    }

    @Override
    public String displayName() {
        return "Camera " + this.getBlockPos().toShortString();
    }

    @Override
    public EndpointDefinition definition() {
        ComponentSchema schema = schema();
        return new EndpointDefinition(
                CameraTransmitterComponent.ID,
                new CameraTransmitterComponent.Config(this, schema),
                schema,
                new EndpointUiHints(this.portLabels()));
    }

    @Override
    public EndpointRuntimeBinding binding() {
        return BINDING;
    }

    // ------------------------------------------------------------------ server tick

    /** Advances trigger logic and status synchronisation once per server tick. */
    public void serverTick() {
        if (this.level == null || this.level.isClientSide()) {
            return;
        }
        this.ensureEndpointRegistered();
        this.ensureDeviceSlot();

        int signal = this.readRedstoneInput();
        this.updateToggleState(signal);
        this.redstoneSignal = signal;

        boolean active = this.resolveStreaming();
        boolean changed = active != this.streamingDesired;
        this.streamingDesired = active;
        if (changed) {
            this.setStatus(active ? "Streaming requested" : "Streaming stopped");
        }

        if (this.shouldSyncStatus(changed)) {
            this.broadcastStatus();
        }
    }

    private void ensureEndpointRegistered() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        CimulinkLevelRuntime runtime = CimulinkWorldRuntimes.forLevel(serverLevel);
        if (!this.endpointRegistered) {
            runtime.registerEndpoint(this);
            this.endpointRegistered = true;
        }
    }

    private void ensureDeviceSlot() {
        if (this.deviceSlot < 0) {
            this.deviceSlot = CameraDeviceSlots.assign(this);
        }
    }

    /**
     * Resolves the final stream state from the three trigger sources.
     *
     * <p>See the class JavaDoc for the exact priority table.
     */
    private boolean resolveStreaming() {
        if (!this.config.redstoneControl()) {
            return this.config.holdOn();
        }
        if (this.enableInput) {
            return true;
        }
        if (this.config.redstoneMode() == com.ccvirtualcoms.camera.CameraRedstoneMode.TOGGLE) {
            return this.redstoneToggleState;
        }
        return this.redstoneSignal > this.config.redstoneThreshold();
    }

    /** Updates the toggle latch on rising edges across the configured threshold. */
    private void updateToggleState(int signal) {
        if (!this.config.redstoneControl()
                || this.config.redstoneMode() != com.ccvirtualcoms.camera.CameraRedstoneMode.TOGGLE) {
            return;
        }
        boolean wasAbove = this.lastAboveThreshold;
        boolean isAbove = signal > this.config.redstoneThreshold();
        if (!wasAbove && isAbove) {
            this.redstoneToggleState = !this.redstoneToggleState;
        }
        this.lastAboveThreshold = isAbove;
    }

    private boolean lastAboveThreshold;

    /**
     * Reads the redstone input.
     *
     * <p>When both Create wireless redstone frequency slots are filled, the Create redstone link
     * network is queried instead of the block's neighbours; this is wired in by
     * {@code CreateWirelessRedstone} during the trigger-integration milestone. Until then the
     * neighbour signal is used.
     */
    private int readRedstoneInput() {
        int signal = 0;
        Level current = this.getLevel();
        if (current != null) {
            for (Direction direction : Direction.values()) {
                signal = Math.max(signal, current.getSignal(this.getBlockPos().relative(direction), direction));
            }
        }
        if (this.config.hasWirelessFrequency()) {
            signal = Math.max(signal, com.ccvirtualcoms.camera.wireless.CreateWirelessRedstone
                    .queryStrength(current, this.config.frequencyA(), this.config.frequencyB()));
        }
        return Math.max(0, Math.min(15, signal));
    }

    private boolean shouldSyncStatus(boolean streamingChanged) {
        if (this.level == null) {
            return false;
        }
        long now = this.level.getGameTime();
        if (streamingChanged || this.redstoneSignal != this.lastSyncedSignal
                || this.revision != this.lastSyncedRevision) {
            this.lastSyncedRevision = this.revision;
            this.lastSyncedSignal = this.redstoneSignal;
            this.lastStatusSyncTick = now;
            return true;
        }
        if (now - this.lastStatusSyncTick >= STATUS_SYNC_INTERVAL) {
            this.lastStatusSyncTick = now;
            return true;
        }
        return false;
    }

    /** Pushes the current snapshot to every client tracking this chunk. */
    public void broadcastStatus() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        CameraStatusPayload payload = new CameraStatusPayload(
                this.getBlockPos(),
                this.config,
                this.streamingDesired ? CameraStreamStatus.STREAMING : CameraStreamStatus.OFFLINE,
                this.deviceName(),
                this.streamUrl(),
                this.redstoneSignal,
                this.revision,
                this.enableInput,
                this.eulerYaw,
                this.eulerPitch,
                this.eulerRoll,
                this.statusDetail);
        if (CameraLocator.inSubLevel(serverLevel, this.getBlockPos())) {
            // Sub-level block positions are plot coordinates; no player tracks that "chunk" of the
            // main level, so every player has to receive the snapshot (a small payload at 1 Hz).
            PacketDistributor.sendToAllPlayers(payload);
        } else {
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel,
                    new ChunkPos(this.getBlockPos()), payload);
        }
    }

    private void setStatus(String message) {
        this.statusDetail = message;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && !level.isClientSide()) {
            CameraServerTicker.register(this);
        }
    }

    @Override
    public void setRemoved() {
        CameraServerTicker.unregister(this);
        this.unregisterEndpoint();
        com.ccvirtualcoms.content.camera.CameraDeviceSlots.release(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        // Keep the camera registered while the chunk is merely unloaded: the server tick handler
        // tolerates a camera whose level/chunk is unavailable and it re-registers on reload.
        this.unregisterEndpoint();
        com.ccvirtualcoms.content.camera.CameraDeviceSlots.release(this);
        super.onChunkUnloaded();
    }

    private void unregisterEndpoint() {
        if (this.endpointRegistered && this.level instanceof ServerLevel serverLevel) {
            CimulinkWorldRuntimes.forLevel(serverLevel).unregisterEndpoint(this.endpointId);
        }
        this.endpointRegistered = false;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(CONFIG_TAG, this.config.toTag(registries));
        CompoundTag euler = new CompoundTag();
        euler.putDouble("Yaw", this.eulerYaw);
        euler.putDouble("Pitch", this.eulerPitch);
        euler.putDouble("Roll", this.eulerRoll);
        tag.put(EULER_TAG, euler);
        tag.putInt(REVISION_TAG, this.revision);
        tag.putUUID(ENDPOINT_ID_TAG, this.endpointId.value());
        tag.putString(STATUS_DETAIL_TAG, this.statusDetail);
        tag.putBoolean(DIAG_STREAMING_TAG, this.streamingDesired);
        tag.putInt(DIAG_REDSTONE_TAG, this.redstoneSignal);
        tag.putInt(DIAG_DEVICE_SLOT_TAG, this.deviceSlot);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(CONFIG_TAG, CompoundTag.TAG_COMPOUND)) {
            this.config = CameraConfig.fromTag(tag.getCompound(CONFIG_TAG), registries);
        }
        CompoundTag euler = tag.getCompound(EULER_TAG);
        if (!euler.isEmpty()) {
            this.eulerYaw = euler.getDouble("Yaw");
            this.eulerPitch = euler.getDouble("Pitch");
            this.eulerRoll = euler.getDouble("Roll");
        } else {
            this.syncEulerFromFacing(this.getBlockState());
        }
        if (!tag.contains(EULER_VERSION_TAG)) {
            // Older saves kept the default Euler angles even after the facing changed, which made
            // the view 90/180 degrees off. Re-derive once from the block state and persist v2.
            this.syncEulerFromFacing(this.getBlockState());
        }
        this.revision = tag.getInt(REVISION_TAG);
        if (tag.hasUUID(ENDPOINT_ID_TAG)) {
            this.endpointId = EndpointId.of(tag.getUUID(ENDPOINT_ID_TAG));
        }
        if (tag.contains(STATUS_DETAIL_TAG)) {
            this.statusDetail = tag.getString(STATUS_DETAIL_TAG);
        }
    }

    /** Initialises the Euler angles so the camera starts looking along its block state facing. */
    private void syncEulerFromFacing(BlockState state) {
        Direction facing = state == null
                ? Direction.NORTH
                : state.getOptionalValue(CameraTransmitterBlock.FACING).orElse(Direction.NORTH);
        this.eulerYaw = normalizeYaw(facing.toYRot());
        this.eulerPitch = facing == Direction.UP ? -90.0D : facing == Direction.DOWN ? 90.0D : 0.0D;
        this.eulerRoll = 0.0D;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The update tag also carries the status snapshot so the screen shows correct values at open. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide()) {
            this.broadcastStatus();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double normalizeYaw(double degrees) {
        double value = degrees % 360.0D;
        if (value > 180.0D) {
            value -= 360.0D;
        } else if (value < -180.0D) {
            value += 360.0D;
        }
        return value;
    }

    private static double clampPitch(double degrees) {
        return Math.max(-90.0D, Math.min(90.0D, degrees));
    }

    private static double normalizeRoll(double degrees) {
        double value = degrees % 360.0D;
        if (value > 180.0D) {
            value -= 360.0D;
        } else if (value < -180.0D) {
            value += 360.0D;
        }
        return value;
    }

    /** {@code true} when the block currently has a virtual camera device slot. */
    public boolean hasDeviceSlot() {
        return this.deviceSlot >= 0;
    }

    /** Diagnostic helper used by the GUI: the state as a short string. */
    public String describeState() {
        return "slot=" + this.deviceSlot
                + " desired=" + this.streamingDesired
                + " enable=" + this.enableInput
                + " rs=" + this.redstoneSignal
                + " rev=" + this.revision
                + " detail=" + this.statusDetail;
    }

    /** Null-safe config accessor used by GUI code. */
    public Optional<CameraConfig> configOptional() {
        return Optional.ofNullable(Objects.requireNonNullElse(this.config, CameraConfig.DEFAULT));
    }
}
