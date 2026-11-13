package com.ccvirtualcoms.content.camera;

import com.ccvirtualcoms.synaxis.CameraTransmitterComponent;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentTypeId;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.game.body.GameThreadPlantPort;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointAddress;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointId;

/** Plant port that lets a Cimulink circuit reach a placed camera transmitter. */
public final class CameraTransmitterPlantPort implements GameThreadPlantPort {

    private final CameraTransmitterBlockEntity camera;
    private final EndpointId endpointId;
    private final String deviceName;

    public CameraTransmitterPlantPort(CameraTransmitterBlockEntity camera, EndpointId endpointId,
                                      String deviceName) {
        this.camera = camera;
        this.endpointId = endpointId;
        this.deviceName = deviceName;
    }

    @Override
    public EndpointId endpointId() {
        return this.endpointId;
    }

    @Override
    public EndpointAddress address() {
        return this.camera.address();
    }

    @Override
    public String deviceName() {
        return this.deviceName;
    }

    @Override
    public ComponentTypeId componentType() {
        return CameraTransmitterComponent.ID;
    }

    @Override
    public ComponentSchema schema() {
        return CameraTransmitterBlockEntity.schema();
    }

    @Override
    public boolean supportsDomain(ExecutionDomain domain) {
        return domain == ExecutionDomain.GAME_TICK;
    }

    @Override
    public void applyInput(String port, SignalValue value) {
        this.camera.writeFromSynaxis(port, value);
    }

    @Override
    public SignalValue readOutput(String port) {
        return this.camera.readForSynaxis(port);
    }
}
