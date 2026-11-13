package com.ccvirtualcoms.synaxis;

import com.ccvirtualcoms.content.camera.CameraTransmitterBlockEntity;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentConfig;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentMemory;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSemantics;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentType;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentTypeId;
import com.verr1.synaxis.foundation.cimulink.core.component.EmptyComponentMemory;
import com.verr1.synaxis.foundation.cimulink.core.component.EvalContext;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.InputPort;
import com.verr1.synaxis.foundation.cimulink.core.signal.OutputPort;
import com.verr1.synaxis.foundation.cimulink.core.signal.PortDef;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalReader;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Cimulink component type contributed by the camera transmitter.
 *
 * <p>It exposes exactly three terminals:
 *
 * <ul>
 *   <li>{@code enable} - {@code boolean}, input. The circuit drives it; {@code true} requests the
 *       stream. Highest priority trigger.</li>
 *   <li>{@code streaming} - {@code boolean}, output. The circuit reads it; {@code true} while the
 *       camera is actually streaming (enable requested and the stream started).</li>
 *   <li>{@code euler_angle_in} / {@code euler_angle_out} - {@code vec3}, input and output.
 *       Component order is {@code (yaw, pitch, roll)} in degrees. Synaxis requires port names to be
 *       unique, so the specification's bidirectional {@code euler_angle} terminal is a matched
 *       input/output pair. Each component's meaning is chosen per axis in the GUI: world stable
 *       components are absolute world angles, follow components are local angles relative to the
 *       Sable sub-level. Writing applies the same per-axis interpretation.</li>
 * </ul>
 */
public final class CameraTransmitterComponent implements ComponentType {

    /** Stable component identifier; never rename (saved circuits reference it). */
    public static final ComponentTypeId ID = ComponentTypeId.of("cc_virtual_coms:camera_transmitter");

    public static final CameraTransmitterComponent INSTANCE = new CameraTransmitterComponent();

    private static final ComponentSchema EMPTY_SCHEMA = ComponentSchema.of(List.of(), List.of());

    private CameraTransmitterComponent() {
    }

    @Override
    public ComponentTypeId id() {
        return ID;
    }

    @Override
    public ComponentSchema schema(ComponentConfig config) {
        return config instanceof Config cameraConfig ? cameraConfig.schema() : EMPTY_SCHEMA;
    }

    @Override
    public ComponentConfig defaultConfig() {
        return Config.empty();
    }

    @Override
    public ComponentMemory createMemory(ComponentConfig config) {
        return EmptyComponentMemory.INSTANCE;
    }

    @Override
    public ComponentSemantics semantics(ComponentConfig config) {
        ComponentSchema schema = schema(config);
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        schema.outputs().forEach(output -> dependencies.put(output.name(), Set.of()));
        return new ComponentSemantics(true, true, true, Set.of(ExecutionDomain.GAME_TICK), dependencies);
    }

    @Override
    public void evaluate(EvalContext ctx, ComponentConfig config, ComponentMemory memory,
                         SignalReader in, SignalWriter out) {
        if (!(config instanceof Config cameraConfig) || cameraConfig.camera() == null) {
            ComponentSchema fallback = config instanceof Config cameraConfig2
                    ? cameraConfig2.schema()
                    : EMPTY_SCHEMA;
            writeDefaults(fallback, out);
            return;
        }
        for (PortDef def : cameraConfig.schema().outputs()) {
            SignalValue value = cameraConfig.camera().readForSynaxis(def.name());
            out.write(new OutputPort(def.name()), value == null ? def.defaultValue() : value);
        }
    }

    @Override
    public void step(EvalContext ctx, ComponentConfig config, ComponentMemory memory,
                     SignalReader in, SignalWriter out) {
        if (!(config instanceof Config cameraConfig) || cameraConfig.camera() == null) {
            return;
        }
        for (PortDef def : cameraConfig.schema().inputs()) {
            SignalValue value = in.read(new InputPort(def.name()));
            if (value != null) {
                cameraConfig.camera().writeFromSynaxis(def.name(), value);
            }
        }
    }

    private static void writeDefaults(ComponentSchema schema, SignalWriter out) {
        schema.outputs().forEach(output -> out.write(new OutputPort(output.name()), output.defaultValue()));
    }

    /**
     * Per-instance component configuration.
     *
     * @param camera the owning block entity, or {@code null} for the detached default config
     * @param schema the fixed terminal layout
     */
    public record Config(CameraTransmitterBlockEntity camera, ComponentSchema schema)
            implements ComponentConfig {

        public static Config empty() {
            return new Config(null, EMPTY_SCHEMA);
        }
    }
}
