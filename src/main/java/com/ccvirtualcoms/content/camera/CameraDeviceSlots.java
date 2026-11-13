package com.ccvirtualcoms.content.camera;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side assignment of virtual camera device slots to camera block entities.
 *
 * <p>The direct-show layer registers a fixed pool of devices (see
 * {@link CameraTransmitterBlockEntity#MAX_DEVICES}). Each placed camera is bound to one free slot
 * the first time it ticks, and releases it when the block entity is removed or its chunk unloads.
 * Keeping the pool fixed means the operating system only ever sees a bounded number of device
 * registrations, and no per-block registry churn can pollute the machine.
 *
 * <p>Only touched from the server thread, so a plain synchronized map is sufficient.
 */
public final class CameraDeviceSlots {

    private static final boolean[] USED = new boolean[CameraTransmitterBlockEntity.MAX_DEVICES];
    private static final Map<CameraTransmitterBlockEntity, Integer> ASSIGNED = new WeakHashMap<>();

    private CameraDeviceSlots() {
    }

    /** Returns the slot already owned by {@code camera}, or assigns the lowest free slot. */
    public static synchronized int assign(CameraTransmitterBlockEntity camera) {
        Integer existing = ASSIGNED.get(camera);
        if (existing != null) {
            return existing;
        }
        for (int slot = 0; slot < USED.length; slot++) {
            if (!USED[slot]) {
                USED[slot] = true;
                ASSIGNED.put(camera, slot);
                return slot;
            }
        }
        return -1;
    }

    /** Releases the slot held by {@code camera}, if any. */
    public static synchronized void release(CameraTransmitterBlockEntity camera) {
        Integer slot = ASSIGNED.remove(camera);
        if (slot != null) {
            USED[slot] = false;
        }
    }

    /** Number of slots that are currently taken. */
    public static synchronized int usedSlots() {
        int used = 0;
        for (boolean value : USED) {
            if (value) {
                used++;
            }
        }
        return used;
    }

    /** Total size of the fixed device pool. */
    public static int capacity() {
        return CameraTransmitterBlockEntity.MAX_DEVICES;
    }

    /** Test/diagnostic hook: clears all assignments without touching the game world. */
    public static synchronized void reset() {
        java.util.Arrays.fill(USED, false);
        ASSIGNED.clear();
    }
}
