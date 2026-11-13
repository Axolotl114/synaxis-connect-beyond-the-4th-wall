package com.ccvirtualcoms.client.camera.stream;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraStreamStatus;
import com.ccvirtualcoms.camera.CameraStreamUrls;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.ccvirtualcoms.camera.api.CameraFrameSource;
import com.ccvirtualcoms.camera.api.CameraStreamRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Client side owner of the MJPEG fallback pipeline.
 *
 * <p>Lifecycle (all on the client, nothing runs on a dedicated server):
 * <ol>
 *   <li>{@link #tick()} is called from a client tick event; the first call lazily starts the
 *       loopback HTTP server and the supervisor thread. A bind failure (port taken) is retried
 *       every few seconds rather than taking the game down;</li>
 *   <li>the supervisor thread reconciles the encoder workers with
 *       {@link CameraStreamRegistry}: one daemon worker per registered camera;</li>
 *   <li>each worker polls its {@link CameraFrameSource}, encodes at the configured frame rate into
 *       JPEG and publishes the frame to {@link MjpegHttpServer}, which fans it out to all MJPEG
 *       clients;</li>
 *   <li>the worker also computes the effective {@link CameraStreamStatus} ("streaming", "offline"
 *       or "driver_unavailable") and writes it back into the registry so the GUI, the log and the
 *       Python client all see the same state.</li>
 * </ol>
 *
 * <p>Encoding is always done on the worker's own daemon thread, never on the render thread, and the
 * pipeline is latest-wins at every hop: render -&gt; source snapshot -&gt; encoder -&gt; per-client
 * bounded queue. A slow client can therefore never stall the camera.
 */
public final class CameraStreamService {

    private static final CameraStreamService INSTANCE = new CameraStreamService();

    /** How often the supervisor reconciles the worker set with the registry. */
    private static final long SUPERVISOR_INTERVAL_MILLIS = 250L;

    /** Retry interval after a failed bind (the port may be freed later). */
    private static final long START_RETRY_NANOS = 5_000_000_000L;

    /** A frame is considered fresh for this long after it was seen by the worker. */
    private static final long FRAME_FRESH_NANOS = 2_000_000_000L;

    /** Worker idle poll interval bounds. */
    private static final long MIN_POLL_NANOS = 1_000_000L;
    private static final long MAX_POLL_NANOS = 5_000_000L;

    private final Object lifecycleLock = new Object();
    private final Map<Long, Worker> workers = new HashMap<>();

    private volatile MjpegHttpServer server;
    private volatile Thread supervisor;
    private volatile boolean running;
    private volatile String startFailure = "";
    private long nextStartAttemptNanos;

    private CameraStreamService() {
    }

    public static CameraStreamService get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called every client tick; starts the service lazily and retries failed binds. */
    public void tick() {
        if (this.running) {
            return;
        }
        if (System.nanoTime() < this.nextStartAttemptNanos) {
            return;
        }
        start();
    }

    /** Starts the HTTP server and supervisor if they are not running yet. */
    public void start() {
        synchronized (this.lifecycleLock) {
            if (this.running) {
                return;
            }
            MjpegHttpServer candidate = new MjpegHttpServer(CameraStreamUrls.port());
            if (!candidate.start()) {
                this.startFailure = candidate.lastError();
                this.nextStartAttemptNanos = System.nanoTime() + START_RETRY_NANOS;
                CCVirtualComs.LOGGER.warn("[camera] MJPEG fallback not available yet ({}); retrying in 5s",
                        candidate.lastError());
                return;
            }
            this.server = candidate;
            this.running = true;
            this.startFailure = "";
            Thread thread = new Thread(this::supervise, "ccvc-camera-stream");
            thread.setDaemon(true);
            this.supervisor = thread;
            thread.start();
            CCVirtualComs.LOGGER.info("[camera] fallback stream ready: {} (status: {})",
                    CameraStreamUrls.urlFor(0L).replace("/camera/0/", "/camera/<pos>/"),
                    CameraStreamUrls.statusUrl());
        }
    }

    /** Stops the HTTP server and every encoder worker. */
    public void stop() {
        Thread thread;
        MjpegHttpServer current;
        synchronized (this.lifecycleLock) {
            if (!this.running && this.server == null) {
                return;
            }
            this.running = false;
            thread = this.supervisor;
            this.supervisor = null;
            current = this.server;
            this.server = null;
            for (Worker worker : new ArrayList<>(this.workers.values())) {
                worker.stopWorker();
            }
            this.workers.clear();
        }
        if (thread != null) {
            thread.interrupt();
        }
        if (current != null) {
            current.stop();
        }
        CCVirtualComs.LOGGER.info("[camera] fallback stream stopped");
    }

    /** {@code true} while the fallback HTTP server is listening. */
    public boolean isRunning() {
        return this.running;
    }

    /** Why the last start attempt failed, or an empty string. */
    public String startFailure() {
        return this.startFailure;
    }

    /** One line describing the pipeline, used by {@code /ccvc_cameras status} and the log. */
    public String describe() {
        MjpegHttpServer current = this.server;
        if (current == null) {
            return "mjpeg=stopped" + (this.startFailure.isBlank() ? "" : " (" + this.startFailure + ")");
        }
        return current.describe() + " workers=" + this.workers.size()
                + " registry=" + CameraStreamRegistry.size();
    }

    // ------------------------------------------------------------------ supervisor

    private void supervise() {
        while (this.running) {
            try {
                reconcileWorkers();
            } catch (RuntimeException failure) {
                CCVirtualComs.LOGGER.warn("[camera] stream supervisor failed: {}", failure.toString());
            }
            try {
                Thread.sleep(SUPERVISOR_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void reconcileWorkers() {
        Set<Long> alive = new HashSet<>();
        for (CameraStreamRegistry.Entry entry : CameraStreamRegistry.all()) {
            long key = entry.key();
            alive.add(key);
            Worker existing = this.workers.get(key);
            if (existing == null || !existing.isAlive()) {
                if (existing != null) {
                    this.workers.remove(key);
                }
                Worker worker = new Worker(key);
                this.workers.put(key, worker);
                worker.start();
                CCVirtualComs.LOGGER.debug("[camera] encoder worker started for {}", Long.toHexString(key));
            }
        }
        List<Long> stale = new ArrayList<>();
        for (Long key : this.workers.keySet()) {
            if (!alive.contains(key)) {
                stale.add(key);
            }
        }
        for (Long key : stale) {
            Worker worker = this.workers.remove(key);
            if (worker != null) {
                worker.stopWorker();
            }
            MjpegHttpServer current = this.server;
            if (current != null) {
                current.remove(key);
            }
            CCVirtualComs.LOGGER.debug("[camera] encoder worker stopped for {}", Long.toHexString(key));
        }
    }

    // ------------------------------------------------------------------ worker

    private final class Worker extends Thread {

        private final long key;
        private final MjpegEncoder encoder;
        private volatile boolean workerRunning = true;

        private long lastSequence = Long.MIN_VALUE;
        private long lastEncodeNanos;
        private long lastFrameNanos;
        private long lastFailureLogNanos;

        private CameraStreamStatus lastStatus;
        private String lastDetail = "";
        private long encodedFrames;
        private long failedFrames;

        private Worker(long key) {
            super("ccvc-camera-" + Long.toHexString(key));
            setDaemon(true);
            this.key = key;
            this.encoder = new MjpegEncoder();
        }

        private void stopWorker() {
            this.workerRunning = false;
            interrupt();
        }

        @Override
        public void run() {
            try {
                loop();
            } finally {
                this.encoder.close();
                MjpegHttpServer current = CameraStreamService.this.server;
                if (current != null) {
                    current.remove(this.key);
                }
            }
        }

        private void loop() {
            long pollNanos = MIN_POLL_NANOS;
            while (this.workerRunning && CameraStreamService.this.running) {
                CameraStreamRegistry.Entry entry = CameraStreamRegistry.get(this.key);
                if (entry == null) {
                    return;
                }
                CameraConfig config = entry.config() == null ? CameraConfig.DEFAULT : entry.config();
                CameraFrameSource source = entry.source();
                CameraFrame frame = null;
                try {
                    frame = source == null ? null : source.latestFrame();
                } catch (RuntimeException failure) {
                    logFailure("frame source failed", failure);
                }

                long now = System.nanoTime();
                int fps = Math.max(1, config.fps());
                long frameInterval = Math.max(1L, 1_000_000_000L / fps);

                if (frame != null && frame.sequence() != this.lastSequence) {
                    this.lastFrameNanos = now;
                    if (now - this.lastEncodeNanos >= frameInterval) {
                        encodeAndPublish(entry, config, frame);
                    }
                }

                updateRegistration(entry, config, source, now);

                pollNanos = Math.max(MIN_POLL_NANOS,
                        Math.min(MAX_POLL_NANOS, frameInterval / 4));
                try {
                    // Thread.sleep(long, int) requires the nanosecond argument to be below
                    // 1,000,000; passing the raw poll time in nanoseconds throws
                    // IllegalArgumentException and silently kills the worker thread.
                    Thread.sleep(pollNanos / 1_000_000L, (int) (pollNanos % 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void encodeAndPublish(CameraStreamRegistry.Entry entry, CameraConfig config, CameraFrame frame) {
            try {
                byte[] jpeg = this.encoder.encode(frame, config);
                if (jpeg.length == 0) {
                    this.failedFrames++;
                    return;
                }
                MjpegHttpServer current = CameraStreamService.this.server;
                if (current != null) {
                    current.publish(this.key, jpeg, frame.width(), frame.height(), frame.sequence());
                }
                this.lastSequence = frame.sequence();
                this.lastEncodeNanos = System.nanoTime();
                this.encodedFrames++;
                if (this.encodedFrames == 1L) {
                    CCVirtualComs.LOGGER.info("[camera] first frame encoded for {} ({}x{}, {} fps, {})",
                            Long.toHexString(this.key), config.resolution().width(),
                            config.resolution().height(), config.fps(), MjpegEncoder.lookDescription(config));
                }
            } catch (Exception failure) {
                this.failedFrames++;
                logFailure("JPEG encoding failed", failure);
            }
        }

        private void updateRegistration(CameraStreamRegistry.Entry entry, CameraConfig config,
                                        @Nullable CameraFrameSource source, long now) {
            CameraDriverStatus.Snapshot driver = CameraDriverStatus.get();
            boolean fresh = source != null && now - this.lastFrameNanos < FRAME_FRESH_NANOS;
            String streamUrl = CameraStreamUrls.urlFor(this.key);

            CameraStreamStatus status;
            String detail;
            if (!driver.available()) {
                status = CameraStreamStatus.DRIVER_UNAVAILABLE;
                detail = "driver unavailable (" + driver.state().serializedName()
                        + (driver.detail().isBlank() ? "" : ": " + driver.detail())
                        + ") - MJPEG fallback " + streamUrl;
            } else if (fresh) {
                status = CameraStreamStatus.STREAMING;
                detail = "streaming " + config.resolution().label() + " @" + config.fps()
                        + "fps " + MjpegEncoder.lookDescription(config)
                        + " (encoded " + this.encodedFrames + ", dropped " + this.failedFrames + ")";
            } else {
                status = CameraStreamStatus.OFFLINE;
                String reason = source == null ? "no frame source" : source.unavailableReason();
                detail = "offline" + (reason == null || reason.isBlank() ? "" : " (" + reason + ")")
                        + " - MJPEG fallback " + streamUrl;
            }

            if (entry.status() == status && Objects.equals(entry.detail(), detail)) {
                return;
            }
            CameraStreamRegistry.updateStatus(this.key, status, detail);
            if (status != this.lastStatus) {
                this.lastStatus = status;
                this.lastDetail = detail;
                switch (status) {
                    case STREAMING -> CCVirtualComs.LOGGER.info("[camera] {} streaming: {}", Long.toHexString(this.key), detail);
                    case DRIVER_UNAVAILABLE -> CCVirtualComs.LOGGER.warn("[camera] {} driver unavailable: {}",
                            Long.toHexString(this.key), detail);
                    case OFFLINE -> CCVirtualComs.LOGGER.info("[camera] {} offline: {}", Long.toHexString(this.key), detail);
                }
            }
        }

        private void logFailure(String what, Throwable failure) {
            long now = System.nanoTime();
            if (now - this.lastFailureLogNanos < 5_000_000_000L) {
                return;
            }
            this.lastFailureLogNanos = now;
            CCVirtualComs.LOGGER.warn("[camera] {} for {}: {}", what, Long.toHexString(this.key),
                    failure.toString());
        }
    }
}
