package com.ccvirtualcoms.camera.api;

import org.jetbrains.annotations.Nullable;

/**
 * A poll-only source of the newest camera frame.
 *
 * <p>Consumers (MJPEG encoder, virtual camera transport) poll and get either the newest frame or
 * {@code null} when the camera has no frame yet. Polling with a bounded, latest-wins queue keeps a
 * slow consumer from ever back-pressuring the render thread.
 */
public interface CameraFrameSource {

    /** The most recently captured frame, or {@code null} when none is available. */
    @Nullable
    CameraFrame latestFrame();

    /** {@code true} while the source is producing frames. */
    boolean isProducing();

    /** A human readable reason the source has no frames, or an empty string. */
    default String unavailableReason() {
        return "";
    }
}
