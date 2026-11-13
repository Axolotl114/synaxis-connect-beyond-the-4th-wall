package com.ccvirtualcoms.content.camera;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Advances every loaded camera transmitter once per server tick.
 *
 * <p>Driven from {@code ServerTickEvent.Post} rather than a block entity ticker: a ticker is only
 * attached while the containing chunk is entity-ticking (players nearby), which would make a camera
 * that a player walked away from silently stop updating its redstone/terminal state. The rest of
 * this mod already runs its devices from the server tick event for the same reason.
 */
public final class CameraServerTicker {

    private static final Set<CameraTransmitterBlockEntity> CAMERAS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private CameraServerTicker() {
    }

    /** Registers a server-side camera; idempotent. */
    public static synchronized void register(CameraTransmitterBlockEntity camera) {
        if (camera != null) {
            CAMERAS.add(camera);
        }
    }

    /** Unregisters a camera. */
    public static synchronized void unregister(CameraTransmitterBlockEntity camera) {
        CAMERAS.remove(camera);
    }

    /** Advances every registered camera. Called once per server tick. */
    public static void tickAll() {
        CameraTransmitterBlockEntity[] snapshot;
        synchronized (CameraServerTicker.class) {
            snapshot = CAMERAS.toArray(new CameraTransmitterBlockEntity[0]);
        }
        for (CameraTransmitterBlockEntity camera : snapshot) {
            if (camera.isRemoved()) {
                unregister(camera);
                continue;
            }
            try {
                camera.serverTick();
            } catch (RuntimeException failure) {
                com.ccvirtualcoms.CCVirtualComs.LOGGER.error("Camera tick failed at {}",
                        camera.getBlockPos(), failure);
            }
        }
    }

    /** Number of registered cameras (diagnostics and tests). */
    public static synchronized int size() {
        return CAMERAS.size();
    }

    /** A snapshot of every registered camera, safe to iterate off the server thread. */
    public static synchronized java.util.List<CameraTransmitterBlockEntity> snapshot() {
        return java.util.List.copyOf(CAMERAS);
    }
}
