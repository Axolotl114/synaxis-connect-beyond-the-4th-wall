package com.ccvirtualcoms.camera.api;

/**
 * A virtual camera device exposed to the operating system, or the reason none could be exposed.
 *
 * @param slot            device pool slot, 0-based
 * @param deviceName      name external applications see, e.g. {@code Synaxis Camera 1}
 * @param driverAvailable {@code false} when the virtual camera driver is not installed
 * @param streamUrl       MJPEG fallback URL, always available while the encoder runs
 * @param reason          human readable reason when {@link #driverAvailable} is false
 */
public record CameraDeviceInfo(int slot, String deviceName, boolean driverAvailable, String streamUrl,
                               String reason) {

    public CameraDeviceInfo {
        deviceName = deviceName == null ? "" : deviceName;
        streamUrl = streamUrl == null ? "" : streamUrl;
        reason = reason == null ? "" : reason;
    }
}
