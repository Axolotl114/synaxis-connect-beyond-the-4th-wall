package com.ccvirtualcoms.client.camera.capture;

import com.ccvirtualcoms.CCVirtualComs;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

/**
 * Renders one camera view into a private render target, with no shader pipeline involved.
 *
 * <p>This is the fast path used when Iris is not installed: the whole level (including Sable
 * sub-levels, because they are drawn inside the vanilla chunk pass) is re-rendered from the captured
 * pose into an off-screen target, then read back asynchronously by {@link PboFrameReader}.
 *
 * <p>It is deliberately <em>not</em> used when Iris is present: Iris binds and composites into the
 * main render target, so a private target would stay black. See {@link CameraCapturePipeline} for
 * the path selection.
 *
 * <p>Everything is wrapped in the same state save/restore NeoForge and other off-screen renderers
 * use ({@link GlStateBackup}, projection backup, model view stack, light texture layer), so the
 * frame the player sees is not disturbed.
 *
 * <p>Render thread only.
 */
public final class OffscreenViewRenderer implements AutoCloseable {

    private static final float NEAR_PLANE = 0.05F;

    private final Camera camera = new Camera();

    private TextureTarget target;
    private int width;
    private int height;
    private boolean closed;

    /** Prepares (or resizes) the private target. */
    public void ensureSize(int width, int height) {
        RenderSystem.assertOnRenderThread();
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        if (this.target != null && this.width == safeWidth && this.height == safeHeight) {
            return;
        }
        if (this.target != null) {
            this.target.destroyBuffers();
        }
        this.width = safeWidth;
        this.height = safeHeight;
        this.target = new TextureTarget(safeWidth, safeHeight, true, Minecraft.ON_OSX);
        this.target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    }

    /** The frame buffer the readback should drain, or {@code -1} when not prepared. */
    public int frameBufferId() {
        return this.target == null ? -1 : this.target.frameBufferId;
    }

    /** Width of the private target. */
    public int width() {
        return this.width;
    }

    /** Height of the private target. */
    public int height() {
        return this.height;
    }

    /**
     * Renders the level from the given pose into the private target.
     *
     * @param level       client level to render
     * @param rotation    camera-to-world rotation (including roll)
     * @param x           eye position, world X
     * @param y           eye position, world Y
     * @param z           eye position, world Z
     * @param fovDegrees  vertical field of view in degrees
     * @param partialTick frame partial tick
     * @return {@code true} when the frame was rendered and can be read back
     */
    public boolean render(ClientLevel level, Quaterniond rotation, double x, double y, double z,
                          float fovDegrees, float partialTick) {
        RenderSystem.assertOnRenderThread();
        if (this.closed || this.target == null || level == null || rotation == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.levelRenderer == null
                || minecraft.gameRenderer == null) {
            return false;
        }
        if (!(this.camera instanceof CaptureCameraAccess access)) {
            // The Camera mixin is not loaded; nothing can position the camera.
            return false;
        }
        float[] euler = CameraTransformMath.eulerFromRotation(rotation, new float[3]);
        // Initialise the camera (eye height, fluid, partial tick) from the player, then override the
        // transform; LevelRenderer reads both position and rotation from this object.
        this.camera.setup(level, minecraft.player, false, false, partialTick);
        access.ccvc$setPosition(new Vec3(x, y, z));
        access.ccvc$setRotation(euler[0], euler[1]);

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(Math.max(1.0F, Math.min(179.0F, fovDegrees))),
                (float) this.width / (float) this.height,
                NEAR_PLANE,
                Math.max(64.0F, minecraft.gameRenderer.getDepthFar()));
        Matrix4f view = new Matrix4f().rotation(new Quaternionf(new Quaterniond(rotation).conjugate()));

        GlStateBackup glState = new GlStateBackup();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        LightTexture lightTexture = minecraft.gameRenderer.lightTexture();
        boolean pushedModelView = false;
        boolean lightLayerOn = false;
        boolean rendered = false;
        RenderSystem.backupGlState(glState);
        RenderSystem.backupProjectionMatrix();
        try {
            this.target.clear(Minecraft.ON_OSX);
            this.target.bindWrite(true);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            lightTexture.updateLightTexture(partialTick);
            Lighting.setupLevel();
            lightTexture.turnOnLightLayer();
            lightLayerOn = true;
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            modelView.pushMatrix();
            pushedModelView = true;
            modelView.identity().mul(view);
            RenderSystem.applyModelViewMatrix();
            minecraft.levelRenderer.renderLevel(minecraft.getTimer(), false, this.camera,
                    minecraft.gameRenderer, lightTexture, view, projection);
            rendered = true;
        } catch (RuntimeException failure) {
            CCVirtualComs.LOGGER.warn("[camera] Off-screen render failed: {}", failure.toString());
        } finally {
            if (lightLayerOn) {
                lightTexture.turnOffLightLayer();
            }
            if (pushedModelView) {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.restoreGlState(glState);
            this.target.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
        return rendered;
    }

    /** Releases the private target; must run on the render thread. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.target != null && RenderSystem.isOnRenderThread()) {
            this.target.destroyBuffers();
        }
        this.target = null;
    }

    @Override
    public String toString() {
        return "OffscreenViewRenderer[" + this.width + "x" + this.height + "]";
    }
}
