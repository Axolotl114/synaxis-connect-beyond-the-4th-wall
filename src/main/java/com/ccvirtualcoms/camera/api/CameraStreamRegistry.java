package com.ccvirtualcoms.camera.api;

import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side registry connecting the three camera workstreams:
 *
 * <ol>
 *   <li>the render/capture side registers a {@link CameraFrameSource} per placed camera and keeps
 *       it updated as frames are produced;</li>
 *   <li>the stream/transport side (MJPEG HTTP server and direct-show bridge) reads the registry to
 *       encode and publish frames;</li>
 *   <li>status/device information flows back so the GUI can show what is actually happening.</li>
 * </ol>
 *
 * <p>The registry is intentionally tiny and lock-free: every camera is identified by the packed
 * block position ({@link net.minecraft.core.BlockPos#asLong()}), and each slot holds an immutable
 * snapshot that producers replace atomically.
 *
 * <p>Client only. The dedicated server never touches it.
 */
public final class CameraStreamRegistry {

    private static final ConcurrentMap<Long, Entry> ENTRIES = new ConcurrentHashMap<>();

    private CameraStreamRegistry() {
    }

    /**
     * One camera's live view.
     *
     * @param key            packed block position
     * @param config         current configuration snapshot
     * @param source         frame source polled by consumers, may be a no-op until capture starts
     * @param device         virtual camera device information (slot, name, availability)
     * @param status         effective output status
     * @param detail         human readable detail for the GUI/log
     */
    public record Entry(long key, CameraConfig config, CameraFrameSource source,
                        CameraDeviceInfo device, CameraStreamStatus status, String detail) {

        public Entry withStatus(CameraStreamStatus newStatus, String newDetail) {
            return new Entry(this.key, this.config, this.source, this.device, newStatus, newDetail);
        }
    }

    /** Registers or replaces one camera entry. */
    public static void register(Entry entry) {
        if (entry == null) {
            return;
        }
        ENTRIES.put(entry.key(), entry);
    }

    /** Updates the status of an existing entry; no-op when the camera is unknown. */
    public static void updateStatus(long key, CameraStreamStatus status, String detail) {
        ENTRIES.computeIfPresent(key, (ignored, entry) -> entry.withStatus(status, detail));
    }

    /** Removes a camera entry. */
    public static void remove(long key) {
        ENTRIES.remove(key);
    }

    /** A single entry, or {@code null}. */
    public static @Nullable Entry get(long key) {
        return ENTRIES.get(key);
    }

    /** Every registered camera, in unspecified order. */
    public static Collection<Entry> all() {
        return List.copyOf(ENTRIES.values());
    }

    /** Number of registered cameras. */
    public static int size() {
        return ENTRIES.size();
    }

    /** Clears the registry, e.g. when leaving a world. */
    public static void clear() {
        ENTRIES.clear();
    }
}
