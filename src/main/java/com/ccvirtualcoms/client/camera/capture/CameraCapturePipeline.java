package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.camera.CameraConfig;
import com.ccvirtualcoms.camera.CameraRenderMode;
import com.ccvirtualcoms.camera.CameraResolution;
import com.ccvirtualcoms.camera.api.CameraFrame;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Render-thread capture scheduler: decides which camera is captured on which frame, renders it,
 * starts an asynchronous readback and publishes the finished frame.
 *
 * <h2>Path selection</h2>
 * <ul>
 *   <li><b>OFFSCREEN</b> — Iris absent: re-render the level into a private target (fast, no effect on
 *       the player frame).</li>
 *   <li><b>MAIN_TARGET</b> — Iris present: override the real camera for one frame and grab the main
 *       render target after the level is drawn (Iris only ever composites there).</li>
 *   <li><b>PLACEHOLDER</b> — neither path is available: the synthetic frame source keeps the
 *       transport chain alive and the reason is reported to the GUI/status detail.</li>
 * </ul>
 *
 * <h2>Budget and back-pressure</h2>
 * <p>At most {@link #MAX_CAPTURES_PER_FRAME} camera is captured per frame, each camera is throttled
 * to its configured frame rate, and a token bucket limits total readback throughput to
 * {@link #READBACK_PIXEL_BUDGET_PER_SECOND} pixels per second (overridable with
 * {@code -Dcc_virtual_coms.camera.pixelBudget}). Captures that do not fit are dropped and counted,
 * never queued, so a slow consumer can never stall the render thread.
 *
 * <h2>Hooks</h2>
 * <p>{@link #onFrameBegin()} / {@link #onAfterLevelRender()} come from {@code GameRendererCaptureMixin};
 * {@link #onFrameEnd()} is driven by {@code RenderFrameEvent.Post}. Without the mixin the pipeline
 * simply never captures and the placeholder path is reported as degraded.
 */
public final class CameraCapturePipeline {

    /** How many cameras may be captured in a single frame. */
    public static final int MAX_CAPTURES_PER_FRAME = 1;

    /** Default readback throughput budget: pixels per second (640x480 at 30 fps). */
    public static final long DEFAULT_READBACK_PIXEL_BUDGET_PER_SECOND = 640L * 480L * 30L;

    /** System property overriding the readback pixel budget. */
    public static final String PIXEL_BUDGET_PROPERTY = "cc_virtual_coms.camera.pixelBudget";

    /**
     * Minimum spacing between main-target captures. Each one forces the player's view to be rendered
     * twice, so this is deliberately far below the display frame rate (10 captures per second).
     */
    public static final long MAIN_TARGET_MIN_INTERVAL_NANOS = 100_000_000L;

    /** Fallback vertical field of view until the GUI exposes an FOV setting. */
    public static final float DEFAULT_FOV_DEGREES = 70.0F;

    private static final CameraCapturePipeline INSTANCE = new CameraCapturePipeline();

    private final Map<Long, Session> sessions = new LinkedHashMap<>();
    private final OffscreenViewRenderer offscreen = new OffscreenViewRenderer();
    private final PresentGuard presentGuard = new PresentGuard();
    private boolean mainTargetCapturedThisFrame;

    @Nullable
    private CaptureRequest pending;
    @Nullable
    private CameraOverride override;

    private long budgetWindowStartNanos;
    private long budgetPixelsUsed;
    private long lastBudgetLogNanos;

    /** Minimum interval between budget-exhausted warnings. */
    private static final long BUDGET_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private long captures;
    private long drops;
    private long failures;
    private long lastReadbackNanos;
    private long lastCaptureNanos;
    private boolean mainTargetFlickerLogged;

    private CameraCapturePipeline() {
    }

    /** The singleton. */
    public static CameraCapturePipeline get() {
        return INSTANCE;
    }

    /** Which capture strategy a camera is currently using. */
    public enum CapturePath {
        OFFSCREEN,
        MAIN_TARGET,
        PLACEHOLDER
    }

    /** A camera transform applied to the real camera for exactly one frame. */
    public record CameraOverride(Vec3 position, float yaw, float pitch) {
    }

    /** One capture picked for the current frame. */
    private record CaptureRequest(CapturedCamera camera, Session session, CapturePath path,
                                  long requestedNanos) {
    }

    /** Per-camera capture state (render thread only). */
    private static final class Session {
        final long key;
        PboFrameReader reader;
        ReadbackTarget readbackTarget;
        int readerWidth = -1;
        int readerHeight = -1;
        long lastCaptureNanos;
        long pendingCaptureNanos;
        long lastMainTargetCaptureNanos;
        int sequence;
        long capturedFrames;
        long droppedFrames;
        long failedFrames;
        long lastReadbackNanos;
        CapturePath path = CapturePath.PLACEHOLDER;
        String detail = "capture pipeline idle";

        Session(long key) {
            this.key = key;
        }

        boolean canCapture(long now, CameraConfig config) {
            long interval = 1_000_000_000L / Math.max(1, config.fps());
            return now - this.lastCaptureNanos >= interval;
        }

        PboFrameReader reader(int width, int height) {
            if (this.reader != null && this.readerWidth == width && this.readerHeight == height) {
                return this.reader;
            }
            if (this.reader != null) {
                this.reader.close();
            }
            this.reader = new PboFrameReader(width, height);
            this.readerWidth = width;
            this.readerHeight = height;
            return this.reader;
        }

        @Nullable
        ReadbackTarget readbackTarget(int width, int height) {
            if (this.readbackTarget != null && this.readbackTarget.width() == width
                    && this.readbackTarget.height() == height) {
                return this.readbackTarget;
            }
            if (this.readbackTarget != null) {
                this.readbackTarget.close();
            }
            this.readbackTarget = new ReadbackTarget(width, height);
            return this.readbackTarget;
        }

        void close() {
            if (this.reader != null) {
                this.reader.close();
                this.reader = null;
            }
            if (this.readbackTarget != null) {
                this.readbackTarget.close();
                this.readbackTarget = null;
            }
        }
    }

    // ------------------------------------------------------------------ hooks

    /**
     * Picks the capture for this frame. Must run before the camera is set up, so a main-target
     * capture can override the camera for the whole frame.
     */
    public void onFrameBegin() {
        this.pending = null;
        this.override = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long now = System.nanoTime();
        CapturedCamera best = null;
        Session bestSession = null;
        Set<Long> alive = new HashSet<>();
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            alive.add(camera.key());
            Session session = this.sessions.computeIfAbsent(camera.key(), Session::new);
            CameraConfig config = camera.config();
            // Resolve the path for every camera (not just the one captured this frame) so the
            // degradation reason is visible in the GUI/status detail immediately.
            CapturePath path = this.resolvePath(camera, session, config);
            if (path == CapturePath.PLACEHOLDER) {
                continue;
            }
            if (!camera.streaming() || camera.pose() == null || !session.canCapture(now, config)) {
                continue;
            }
            if (best == null || session.lastCaptureNanos < bestSession.lastCaptureNanos) {
                best = camera;
                bestSession = session;
            }
        }
        this.pruneSessions(alive);

        if (best == null || bestSession == null) {
            return;
        }
        CameraConfig config = best.config();
        CameraResolution resolution = config.resolution();
        if (!this.budgetAllows(now, resolution)) {
            bestSession.droppedFrames++;
            this.drops++;
            bestSession.detail = "frame budget exhausted; capture dropped";
            if (now - this.lastBudgetLogNanos > BUDGET_LOG_INTERVAL_NANOS) {
                this.lastBudgetLogNanos = now;
                CCVirtualComs.LOGGER.warn(
                        "[camera] Readback budget exhausted ({} px/s); dropping captures. "
                                + "Lower the camera fps/resolution or raise -D{}.",
                        pixelBudget(), PIXEL_BUDGET_PROPERTY);
            }
            return;
        }
        this.pending = new CaptureRequest(best, bestSession, bestSession.path, now);
        if (bestSession.path == CapturePath.MAIN_TARGET) {
            CameraPoseResolver.ResolvedPose pose = best.resolvedPose();
            if (pose != null) {
                this.override = new CameraOverride(
                        new Vec3(pose.pose().x(), pose.pose().y(), pose.pose().z()),
                        pose.pose().yaw(), pose.pose().pitch());
            }
        }
    }

    /**
     * Performs the selected capture: renders off-screen (no active shader pack) or blits the main
     * target (shader pack active), then starts the asynchronous readback. Runs after the level has
     * been rendered and before the hand/GUI.
     *
     * @return {@code true} when the main render target was captured and the caller must re-render
     *         the player's view before the frame is presented (otherwise the player would briefly
     *         see the camera view - the "flicker" this path is known for)
     */
    public boolean onAfterLevelRender() {
        CaptureRequest request = this.pending;
        this.pending = null;
        this.override = null;
        if (request == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Session session = request.session();
        CapturedCamera camera = request.camera();
        CameraConfig config = camera.config();
        int width = config.resolution().width();
        int height = config.resolution().height();
        boolean mainTargetCaptured = false;
        try {
            boolean isMainTarget = request.path() == CapturePath.MAIN_TARGET;
            if (isMainTarget) {
                long now = System.nanoTime();
                // Main-target capture re-renders the player view afterwards, so keep it well below
                // the display rate: at most ten captures per second.
                if (now - session.lastMainTargetCaptureNanos < MAIN_TARGET_MIN_INTERVAL_NANOS) {
                    session.droppedFrames++;
                    this.drops++;
                    session.detail = "main-target capture throttled to keep the player view stable";
                    return false;
                }
                session.lastMainTargetCaptureNanos = now;
            }
            boolean issued;
            if (isMainTarget) {
                issued = this.captureMainTarget(minecraft, session, width, height);
            } else {
                issued = this.captureOffscreen(minecraft, camera, session, width, height);
            }
            if (issued) {
                session.lastCaptureNanos = request.requestedNanos();
                session.pendingCaptureNanos = request.requestedNanos();
                this.lastCaptureNanos = request.requestedNanos();
                mainTargetCaptured = isMainTarget;
                if (mainTargetCaptured) {
                    this.mainTargetCapturedThisFrame = true;
                }
            } else {
                session.droppedFrames++;
                this.drops++;
            }
        } catch (RuntimeException failure) {
            session.failedFrames++;
            this.failures++;
            session.detail = "capture failed: " + failure;
            CCVirtualComs.LOGGER.warn("[camera] Capture failed for {}: {}", camera.pos(),
                    failure.toString());
        } finally {
            // The main target must be the draw target again for the hand and GUI passes.
            minecraft.getMainRenderTarget().bindWrite(false);
        }
        return mainTargetCaptured;
    }

    /** Polls finished readbacks and publishes them to the cameras. */
    public void onFrameEnd() {
        // Runs after the GUI, immediately before the buffer swap. If this frame captured the main
        // target (camera view), put the previous player frame back so the screen never flashes.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            this.presentGuard.afterFrame(minecraft, this.mainTargetCapturedThisFrame);
        }
        this.mainTargetCapturedThisFrame = false;
        for (Session session : this.sessions.values()) {
            if (session.reader == null) {
                continue;
            }
            CameraFrame frame = session.reader.poll(++session.sequence, session.pendingCaptureNanos);
            if (frame == null) {
                continue;
            }
            session.capturedFrames++;
            session.lastReadbackNanos = Math.max(0L, System.nanoTime() - frame.captureNanos());
            this.lastReadbackNanos = session.lastReadbackNanos;
            this.captures++;
            CapturedCamera camera = CameraCaptureRuntime.get().camera(session.key);
            if (camera != null) {
                camera.acceptCapturedFrame(frame);
            }
            if (session.capturedFrames == 1L) {
                CCVirtualComs.LOGGER.info(
                        "[camera] First captured frame from {} via {} ({}x{}, readback {} us)",
                        camera == null ? Long.toHexString(session.key) : camera.pos(),
                        session.path, frame.width(), frame.height(),
                        session.lastReadbackNanos / 1_000L);
            }
        }
    }

    /** Camera override for the current frame, or {@code null}; polled by the Camera mixin. */
    public @Nullable CameraOverride cameraOverride() {
        return this.override;
    }

    /** Releases every GPU resource; call when leaving a level. */
    public void reset() {
        this.pending = null;
        this.override = null;
        for (Session session : this.sessions.values()) {
            session.close();
        }
        this.sessions.clear();
        this.offscreen.close();
        this.presentGuard.reset();
        this.mainTargetCapturedThisFrame = false;
        this.budgetPixelsUsed = 0L;
        this.budgetWindowStartNanos = 0L;
    }

    /** Releases one camera's GPU resources (called when the block disappears). */
    public void forget(long key) {
        Session session = this.sessions.remove(key);
        if (session != null) {
            session.close();
        }
    }

    /** Capture path currently used by a camera. */
    public CapturePath pathOf(long key) {
        Session session = this.sessions.get(key);
        return session == null ? CapturePath.PLACEHOLDER : session.path;
    }

    /** Capture diagnostics of one camera. */
    public String detailOf(long key) {
        Session session = this.sessions.get(key);
        return session == null ? "capture pipeline idle" : session.detail;
    }

    /** Capture diagnostics of one camera. */
    public String statsOf(long key) {
        Session session = this.sessions.get(key);
        if (session == null) {
            return "captured=0 dropped=0 failed=0";
        }
        return "captured=" + session.capturedFrames + " dropped=" + session.droppedFrames
                + " failed=" + session.failedFrames
                + " readback=" + (session.lastReadbackNanos / 1_000L) + "us"
                + " path=" + session.path;
    }

    /** One-line global diagnostics. */
    public String describe() {
        return "captures=" + this.captures + " drops=" + this.drops + " failures=" + this.failures
                + " readback=" + (this.lastReadbackNanos / 1_000L) + "us"
                + " cameras=" + this.sessions.size()
                + " iris=" + IrisCompat.describe();
    }

    // ------------------------------------------------------------------ internals

    private boolean captureMainTarget(Minecraft minecraft, Session session, int width, int height) {
        if (IrisCompat.isRenderingShadowPass()) {
            session.detail = "skipped: Iris shadow pass is active";
            return false;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null) {
            session.detail = "skipped: no main render target";
            return false;
        }
        ReadbackTarget target = session.readbackTarget(width, height);
        if (target == null || !target.blitFrom(main)) {
            session.detail = "skipped: blit to readback target failed";
            return false;
        }
        PboFrameReader reader = session.reader(width, height);
        session.path = CapturePath.MAIN_TARGET;
        session.detail = "main-target capture (Iris compatible)";
        return reader.requestFramebufferRead(target.frameBufferId());
    }

    private boolean captureOffscreen(Minecraft minecraft, CapturedCamera camera, Session session,
                                     int width, int height) {
        CameraPoseResolver.ResolvedPose resolved = camera.resolvedPose();
        if (resolved == null) {
            session.detail = "skipped: no camera pose";
            return false;
        }
        this.offscreen.ensureSize(width, height);
        if (!this.offscreen.render(minecraft.level, resolved.rotation(),
                resolved.pose().x(), resolved.pose().y(), resolved.pose().z(),
                DEFAULT_FOV_DEGREES, CameraCaptureRuntime.get().partialTick())) {
            session.detail = "skipped: off-screen render unavailable";
            return false;
        }
        PboFrameReader reader = session.reader(width, height);
        session.path = CapturePath.OFFSCREEN;
        session.detail = "off-screen capture (no shader pipeline)";
        return reader.requestFramebufferRead(this.offscreen.frameBufferId());
    }

    private CapturePath resolvePath(CapturedCamera camera, Session session, CameraConfig config) {
        boolean iris = IrisCompat.irisInstalled();
        boolean shadersActive = iris && IrisCompat.shaderPackInUse();
        CameraRenderMode mode = config.renderMode();
        // Shader compatibility is intentionally not required: a shader-composed frame can only be
        // captured by hijacking the player's main render target, which is what caused the visible
        // flicker. The camera therefore always renders into a private off-screen target. With a
        // shader pack active the image contains vanilla lighting instead of shader effects, but the
        // player's view is never touched.
        CapturePath path = CapturePath.OFFSCREEN;
        String degradation = null;
        if (shadersActive) {
            degradation = "shader pack active: camera image uses vanilla lighting (shader effects "
                    + "are not captured); off-screen capture keeps the player view stable";
            if (!this.mainTargetFlickerLogged) {
                this.mainTargetFlickerLogged = true;
                CCVirtualComs.LOGGER.info("[camera] Shader pack is active, but capture stays "
                        + "off-screen on purpose: no shader effects in the camera image and no "
                        + "flicker in the player view.");
            }
        } else if (iris) {
            degradation = "Iris present but no shader pack is active; off-screen capture keeps "
                    + "the player view untouched";
        } else if (mode == CameraRenderMode.ON) {
            degradation = "Iris is not installed, so shaders cannot be used; off-screen capture";
        }
        if (path == CapturePath.MAIN_TARGET && !this.mainTargetSupported()) {
            path = CapturePath.PLACEHOLDER;
            degradation = "main-target capture unavailable: camera mixin not loaded";
        } else if (path == CapturePath.OFFSCREEN && !this.offscreenSupported()) {
            path = CapturePath.PLACEHOLDER;
            degradation = "off-screen capture unavailable: camera mixin not loaded";
        } else if (path == CapturePath.MAIN_TARGET && camera.resolvedPose() != null
                && camera.resolvedPose().hasRoll()) {
            // Minecraft's Camera cannot express roll; the main-target path cannot keep the roll.
            degradation = "roll is not representable on the vanilla camera; main-target capture "
                    + "drops it (use the off-screen path for a rolled view)";
        }
        session.path = path;
        String detail = degradation == null ? path.name().toLowerCase(java.util.Locale.ROOT)
                : degradation;
        session.detail = detail;
        if (camera.source() instanceof LiveFrameSource live) {
            live.setDegradation(path == CapturePath.PLACEHOLDER || degradation != null
                    ? degradation
                    : "");
        }
        return path;
    }

    private boolean mainTargetSupported() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gameRenderer != null
                && minecraft.gameRenderer.getMainCamera() instanceof CaptureCameraAccess;
    }

    private boolean offscreenSupported() {
        return RenderSystem.isOnRenderThread() && this.mainTargetSupported();
    }

    private void pruneSessions(Set<Long> alive) {
        Iterator<Map.Entry<Long, Session>> iterator = this.sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Session> entry = iterator.next();
            if (!alive.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private boolean budgetAllows(long now, CameraResolution resolution) {
        long window = 1_000_000_000L;
        if (this.budgetWindowStartNanos == 0L || now - this.budgetWindowStartNanos >= window) {
            this.budgetWindowStartNanos = now;
            this.budgetPixelsUsed = 0L;
        }
        long cost = (long) resolution.width() * resolution.height();
        if (this.budgetPixelsUsed + cost > pixelBudget()) {
            return false;
        }
        this.budgetPixelsUsed += cost;
        return true;
    }

    private long pixelBudget() {
        String raw = System.getProperty(PIXEL_BUDGET_PROPERTY);
        if (raw != null) {
            try {
                long value = Long.parseLong(raw.trim());
                if (value > 0L) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the default.
            }
        }
        // The default is one 640x480@30 stream. Multiple tracked cameras each need their own
        // share, otherwise a single busy camera starves the others and their streams look offline.
        int cameras = Math.max(1, this.sessions.size());
        return DEFAULT_READBACK_PIXEL_BUDGET_PER_SECOND * cameras;
    }
}
