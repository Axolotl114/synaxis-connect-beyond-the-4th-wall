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
     * Minimum spacing between any two camera captures. Ten captures per second keeps the extra
     * world render from causing visible frame-time spikes while still looking smooth.
     */
    public static final long CAPTURE_INTERVAL_NANOS = 100_000_000L;

    /** Capture interval while somebody watches a stream (30 fps). */
    public static final long STREAMING_CAPTURE_INTERVAL_NANOS = 33_333_333L;

    /** Capture interval with no viewers: keep a slow live preview without wasting GPU (5 fps). */
    public static final long IDLE_CAPTURE_INTERVAL_NANOS = 200_000_000L;

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
    private final FrameBackup frameBackup = new FrameBackup();
    private com.mojang.blaze3d.pipeline.TextureTarget maskTarget;
    private int maskWidth;
    private int maskHeight;

    @Nullable
    private CameraOverride override;
    @Nullable
    private PendingCapture pending;
    private boolean capturing;
    private boolean savedRenderHand;
    private int captureFov;
    private int loggedCaptureFov = -1;
    private boolean savedBobView;
    private int savedHurtTime;
    private int savedHurtDuration;
    private float savedSpin;
    private float savedOldSpin;

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
    private org.joml.Matrix4f captureProjection;
    private volatile org.joml.Quaternionf captureCameraRotation;
    private net.minecraft.world.phys.Vec3 captureCameraPosition;
    private float captureYaw;
    private float capturePitch;
    private float captureRoll;
    private boolean maskPass;
    private boolean maskRendering;
    private boolean mainTargetFlickerLogged;
    private boolean mainFrameLogged;

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
    public record CameraOverride(Vec3 position, float yaw, float pitch, float roll) {
    }

    /** One capture picked for the current frame. */
    private record CaptureRequest(CapturedCamera camera, Session session, CapturePath path,
                                  long requestedNanos) {
    }

    /** A whole-frame camera render in progress. */
    private record PendingCapture(CapturedCamera camera, Session session, int width, int height,
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
    /**
     * Decides whether this whole frame is rendered from the camera and, if so, sets the camera
     * override before {@code Camera#setup} runs. The player's frame is not modified here.
     */
    public void beginFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.maskRendering || this.capturing || this.pending != null || minecraft.level == null
                || minecraft.screen != null) {
            return;
        }
        // The previous full player frame is what gets held on the screen during a capture frame.
        if (!this.frameBackup.hasFrame()) {
            return;
        }
        long now = System.nanoTime();
        // Adaptive capture rate: stream at 30 fps only while somebody watches (MJPEG/DirectShow),
        // otherwise idle at 5 fps so an unwatched camera does not keep re-rendering the world.
        boolean watched = com.ccvirtualcoms.client.camera.stream.CameraStreamService.get()
                .viewerCount() > 0L;
        long captureInterval = watched
                ? STREAMING_CAPTURE_INTERVAL_NANOS
                : IDLE_CAPTURE_INTERVAL_NANOS;
        if (now - this.lastCaptureNanos < captureInterval) {
            return;
        }
        CapturedCamera best = null;
        Session bestSession = null;
        Set<Long> alive = new HashSet<>();
        for (CapturedCamera camera : CameraCaptureRuntime.get().cameras()) {
            alive.add(camera.key());
            Session session = this.sessions.computeIfAbsent(camera.key(), Session::new);
            CameraConfig config = camera.config();
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
                        "[camera] Readback budget exhausted ({} px/s); dropping captures.",
                        pixelBudget());
            }
            return;
        }
        CameraPoseResolver.ResolvedPose resolved = best.resolvedPose();
        if (resolved == null) {
            return;
        }
        this.override = new CameraOverride(
                new Vec3(resolved.pose().x(), resolved.pose().y(), resolved.pose().z()),
                resolved.pose().yaw(), resolved.pose().pitch(), resolved.pose().roll());
        this.captureYaw = resolved.pose().yaw();
        this.capturePitch = resolved.pose().pitch();
        this.captureRoll = resolved.pose().roll();
        this.pending = new PendingCapture(best, bestSession, resolution.width(),
                resolution.height(), now);
        this.savedRenderHand = false;
        if (minecraft.gameRenderer instanceof com.ccvirtualcoms.mixin.client.GameRendererAccess access) {
            this.savedRenderHand = access.ccvc$isRenderHand();
            access.ccvc$setRenderHand(false);
        }
        this.captureProjection = new org.joml.Matrix4f(
                com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
        boolean thermalLook = !config.color() && config.nightVision();
        if (thermalLook) {
            EntityMaskCollector.begin(minecraft.getMainRenderTarget().width,
                    minecraft.getMainRenderTarget().height);
        }
        this.captureFov = config.fov();
        if (this.captureFov != this.loggedCaptureFov) {
            this.loggedCaptureFov = this.captureFov;
            CCVirtualComs.LOGGER.info("[camera] capture frame forced FOV = {} (player FOV untouched)",
                    this.captureFov);
        }
        this.isolatePlayerCameraFeedback(minecraft);
    }

    /**
     * Reads the depth buffer of the camera frame while the main target still holds it and turns it
     * into a 1/0 brightness mask: 1 for geometry close to the camera (creatures, sub-level
     * surfaces), 0 for the distant background. The mask is attached to the captured frame so the
     * monochrome + night-vision palette can lift only those pixels.
     */
    /**
     * Second render of the exact same camera frame, with terrain/sky/weather suppressed, so only
     * entities (and their block entities) are drawn. The main target is read back as the mask.
     */
    private float[] renderEntityMask(Minecraft minecraft, int width, int height) {
        if (this.maskRendering || minecraft.level == null) {
            return null;
        }
        com.mojang.blaze3d.pipeline.RenderTarget main = minecraft.getMainRenderTarget();
        int mainWidth = main.width;
        int mainHeight = main.height;
        this.maskRendering = true;
        this.maskPass = true;
        try {
            main.clear(Minecraft.ON_OSX);
            main.bindWrite(true);
            com.mojang.blaze3d.systems.RenderSystem.viewport(0, 0, mainWidth, mainHeight);
            minecraft.gameRenderer.renderLevel(minecraft.getTimer());
            java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(mainWidth * mainHeight * 4);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, mainWidth, mainHeight,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, 0);
            double destinationAspect = (double) width / (double) height;
            double sourceAspect = (double) mainWidth / (double) mainHeight;
            int cropX = 0;
            int cropY = 0;
            int cropWidth = mainWidth;
            int cropHeight = mainHeight;
            if (sourceAspect > destinationAspect) {
                cropWidth = (int) Math.round(mainHeight * destinationAspect);
                cropX = (mainWidth - cropWidth) / 2;
            } else if (sourceAspect < destinationAspect) {
                cropHeight = (int) Math.round(mainWidth / destinationAspect);
                cropY = (mainHeight - cropHeight) / 2;
            }
            float[] mask = new float[width * height];
            for (int y = 0; y < height; y++) {
                int sourceY = cropY + (int) ((long) (height - 1 - y) * cropHeight / height);
                for (int x = 0; x < width; x++) {
                    int sourceX = cropX + (int) ((long) x * cropWidth / width);
                    int index = (sourceY * mainWidth + sourceX) * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
                    mask[y * width + x] = (r + g + b) > 24 ? 1.0F : 0.0F;
                }
            }
            return mask;
        } catch (RuntimeException failure) {
            CCVirtualComs.LOGGER.warn("[camera] entity mask pass failed: {}", failure.toString());
            return null;
        } finally {
            this.maskPass = false;
            this.maskRendering = false;
            minecraft.getMainRenderTarget().bindWrite(false);
            com.mojang.blaze3d.systems.RenderSystem.viewport(0, 0,
                    minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        }
    }

    private com.mojang.blaze3d.pipeline.TextureTarget ensureMaskTarget(int width, int height) {
        if (this.maskTarget != null && this.maskWidth == width && this.maskHeight == height) {
            return this.maskTarget;
        }
        if (this.maskTarget != null) {
            this.maskTarget.destroyBuffers();
        }
        this.maskTarget = new com.mojang.blaze3d.pipeline.TextureTarget(width, height, false,
                Minecraft.ON_OSX);
        this.maskTarget.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        this.maskWidth = width;
        this.maskHeight = height;
        return this.maskTarget;
    }

    private float[] readProximityMask(Minecraft minecraft, int width, int height) {
        com.mojang.blaze3d.pipeline.RenderTarget source = minecraft.getMainRenderTarget();
        int sourceWidth = source.width;
        int sourceHeight = source.height;
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }
        double destinationAspect = (double) width / (double) height;
        double sourceAspect = (double) sourceWidth / (double) sourceHeight;
        int cropX = 0;
        int cropY = 0;
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        if (sourceAspect > destinationAspect) {
            cropWidth = (int) Math.round(sourceHeight * destinationAspect);
            cropX = (sourceWidth - cropWidth) / 2;
        } else if (sourceAspect < destinationAspect) {
            cropHeight = (int) Math.round(sourceWidth / destinationAspect);
            cropY = (sourceHeight - cropHeight) / 2;
        }
        java.nio.FloatBuffer depth = org.lwjgl.BufferUtils.createFloatBuffer(sourceWidth * sourceHeight);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        org.lwjgl.opengl.GL11.glReadPixels(0, 0, sourceWidth, sourceHeight,
                org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, depth);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, 0);
        float near = 0.05F;
        float far = (float) minecraft.gameRenderer.getDepthFar();
        float[] mask = new float[width * height];
        for (int y = 0; y < height; y++) {
            int sourceY = cropY + (int) ((long) (height - 1 - y) * cropHeight / height);
            for (int x = 0; x < width; x++) {
                int sourceX = cropX + (int) ((long) x * cropWidth / width);
                float depthValue = depth.get(sourceY * sourceWidth + sourceX);
                float distance = far * near / Math.max(0.000001F, far - depthValue * (far - near));
                mask[y * width + x] = distance;
            }
        }
        return mask;
    }

    /**
     * Runs after the player's world is rendered and before the hand/HUD. Copies the whole camera
     * frame out, puts the previous player frame back and restores the player camera for the
     * hand/HUD passes. The player therefore never sees the camera frame.
     */
    public void afterWorldRender() {
        PendingCapture capture = this.pending;
        if (capture == null || this.maskRendering) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // The projection that produced this camera frame (used by the entity mask pass).
        this.captureProjection = new org.joml.Matrix4f(
                com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
        boolean issued = false;
        try {
            ReadbackTarget target = capture.session.readbackTarget(capture.width, capture.height);
            if (target.blitFrom(minecraft.getMainRenderTarget())) {
                PboFrameReader reader = capture.session.reader(capture.width, capture.height);
                capture.session.path = CapturePath.MAIN_TARGET;
                capture.session.detail = "whole-frame camera capture; previous player frame held";
                boolean thermalLook = !capture.camera.config().color()
                        && capture.camera.config().nightVision();
                float[] mask = null;
                if (thermalLook) {
                    // Strict mask pass: render only entities into the mask target, and use the
                    // depth proximity mask for sub-level geometry when the camera is inside one.
                    mask = this.renderEntityMask(minecraft, capture.width, capture.height);
                }
                issued = reader.requestFramebufferRead(target.frameBufferId(), mask);
            } else {
                capture.session.detail = "skipped: could not copy the main frame";
            }
        } catch (RuntimeException failure) {
            capture.session.failedFrames++;
            this.failures++;
            capture.session.detail = "capture failed: " + failure;
            CCVirtualComs.LOGGER.warn("[camera] capture failed: {}", failure.toString());
        } finally {
            this.override = null;
            this.pending = null;
            this.captureFov = 0;
            if (minecraft.gameRenderer instanceof com.ccvirtualcoms.mixin.client.GameRendererAccess access) {
                access.ccvc$setRenderHand(this.savedRenderHand);
            }
            this.restorePlayerCameraFeedback(minecraft);
            // Hold the previous complete player frame on screen (frame hold, not a flash).
            this.frameBackup.restore(minecraft);
            // The capture frame moved the real camera; put it back for hand/HUD rendering.
            var entity = minecraft.getCameraEntity() == null ? minecraft.player : minecraft.getCameraEntity();
            if (entity != null && minecraft.level != null) {
                minecraft.gameRenderer.getMainCamera().setup(minecraft.level, entity,
                        !minecraft.options.getCameraType().isFirstPerson(),
                        minecraft.options.getCameraType().isMirrored(),
                        CameraCaptureRuntime.get().partialTick());
            }
        }
        if (issued) {
            capture.session.lastCaptureNanos = capture.requestedNanos();
            capture.session.pendingCaptureNanos = capture.requestedNanos();
            this.lastCaptureNanos = capture.requestedNanos();
        } else {
            capture.session.droppedFrames++;
            this.drops++;
        }
    }

    /** Vertical FOV to force for the current capture frame, or {@code 0} when none is active. */
    public int captureFov() {
        return this.captureFov;
    }

    /** Camera override for the current capture frame, polled by {@code CameraCaptureMixin}. */
    public @Nullable CameraOverride cameraOverride() {
        return this.override;
    }

    /** {@code true} while the pipeline is rendering its own camera frame. */
    /** Called from the camera mixin right after the capture pose is applied. */
    public void captureFrameCamera(net.minecraft.client.Camera camera) {
        this.captureCameraRotation = new org.joml.Quaternionf(camera.rotation());
        this.captureCameraPosition = camera.getPosition();
    }

    public boolean isMaskPass() {
        return this.maskPass;
    }

    /** Key of the camera whose view is being rendered right now, or {@code -1}. */
    public long activeCaptureKey() {
        PendingCapture capture = this.pending;
        return capture == null ? -1L : capture.camera.key();
    }

    public boolean isCapturing() {
        return this.capturing;
    }

    public void onFrameEnd() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            this.frameBackup.save(minecraft);
        }
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

    /** Releases every GPU resource; call when leaving a level. */
    public void reset() {
        for (Session session : this.sessions.values()) {
            session.close();
        }
        this.sessions.clear();
        this.offscreen.close();
        this.frameBackup.reset();
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

    /**
     * A capture frame must not inherit the player's view bob, hurt tilt or nausea (impact/spin)
     * effects: those are player feedback, not camera properties. They are zeroed for the one frame
     * and restored right after the camera frame has been rendered.
     */
    private void isolatePlayerCameraFeedback(Minecraft minecraft) {
        this.savedBobView = minecraft.options.bobView().get();
        minecraft.options.bobView().set(false);
        if (minecraft.player == null) {
            return;
        }
        this.savedHurtTime = minecraft.player.hurtTime;
        this.savedHurtDuration = minecraft.player.hurtDuration;
        this.savedSpin = minecraft.player.spinningEffectIntensity;
        this.savedOldSpin = minecraft.player.oSpinningEffectIntensity;
        minecraft.player.hurtTime = 0;
        minecraft.player.hurtDuration = 0;
        minecraft.player.spinningEffectIntensity = 0.0F;
        minecraft.player.oSpinningEffectIntensity = 0.0F;
    }

    private void restorePlayerCameraFeedback(Minecraft minecraft) {
        minecraft.options.bobView().set(this.savedBobView);
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.hurtTime = this.savedHurtTime;
        minecraft.player.hurtDuration = this.savedHurtDuration;
        minecraft.player.spinningEffectIntensity = this.savedSpin;
        minecraft.player.oSpinningEffectIntensity = this.savedOldSpin;
    }

    /** One-line global diagnostics. */
    public String describe() {
        return "captures=" + this.captures + " drops=" + this.drops + " failures=" + this.failures
                + " readback=" + (this.lastReadbackNanos / 1_000L) + "us"
                + " cameras=" + this.sessions.size()
                + " iris=" + IrisCompat.describe();
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
        if (!this.offscreenSupported()) {
            path = CapturePath.PLACEHOLDER;
            degradation = "off-screen capture unavailable: camera transform mixin not loaded";
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
