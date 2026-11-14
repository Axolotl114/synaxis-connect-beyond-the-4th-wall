package com.ccvirtualcoms.client.camera.capture;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Collects entity screen rectangles while the real camera frame is being rendered. */
public final class EntityMaskCollector {

    private static boolean active;
    private static int mainWidth;
    private static int mainHeight;
    private static final List<float[]> RECTS = new ArrayList<>();
    private static final Vector4f POINT = new Vector4f();

    private EntityMaskCollector() {
    }

    public static void begin(int width, int height) {
        active = true;
        mainWidth = width;
        mainHeight = height;
        RECTS.clear();
    }

    public static void end() {
        active = false;
    }

    public static boolean active() {
        return active;
    }

    public static List<float[]> rects() {
        return RECTS;
    }

    /** Called at the HEAD of every entity render with the exact pose/projection of the frame. */
    public static void record(Entity entity, Matrix4f pose, Matrix4f projection, Vec3 cameraPos,
                              float partialTick) {
        if (!active) {
            return;
        }
        Vec3 offset = entity.getPosition(partialTick);
        AABB box = entity.getBoundingBox().move(-offset.x, -offset.y, -offset.z);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? box.minX : box.maxX;
            double y = (corner & 2) == 0 ? box.minY : box.maxY;
            double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
            POINT.set((float) x, (float) y, (float) z, 1.0F);
            pose.transform(POINT);
            projection.transform(POINT);
            if (POINT.w <= 0.0001F) {
                return;
            }
            float screenX = (POINT.x / POINT.w + 1.0F) * 0.5F * mainWidth;
            float screenY = (1.0F - POINT.y / POINT.w) * 0.5F * mainHeight;
            minX = Math.min(minX, screenX);
            minY = Math.min(minY, screenY);
            maxX = Math.max(maxX, screenX);
            maxY = Math.max(maxY, screenY);
        }
        AABB worldBox = entity.getBoundingBox();
        double minDistance = distanceToBox(cameraPos, worldBox, true);
        double maxDistance = distanceToBox(cameraPos, worldBox, false);
        RECTS.add(new float[]{minX, minY, maxX, maxY, (float) minDistance, (float) maxDistance});
    }

    private static double distanceToBox(Vec3 point, AABB box, boolean nearest) {
        double x = nearest ? clamp(point.x, box.minX, box.maxX) : farthest(point.x, box.minX, box.maxX);
        double y = nearest ? clamp(point.y, box.minY, box.maxY) : farthest(point.y, box.minY, box.maxY);
        double z = nearest ? clamp(point.z, box.minZ, box.maxZ) : farthest(point.z, box.minZ, box.maxZ);
        return point.distanceTo(new Vec3(x, y, z));
    }

    private static double farthest(double value, double min, double max) {
        return Math.abs(value - min) >= Math.abs(value - max) ? min : max;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
