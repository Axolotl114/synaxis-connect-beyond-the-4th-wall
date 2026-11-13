package com.ccvirtualcoms.client.camera.capture;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A copy of one Sable sub-level's render pose, expressed only with JOML types.
 *
 * <p>This exists so the rest of the capture package never mentions a Sable class: the Sable API can
 * therefore be unavailable (older Sable, failed load, unit tests) without breaking class loading.
 * See {@link SableCameraAdapter} for the code that fills this in.
 *
 * @param name          sub-level name for diagnostics, never {@code null}
 * @param position      world position of the pose origin
 * @param orientation   world orientation of the sub-level
 * @param rotationPoint local rotation centre (plot coordinates)
 * @param scale         pose scale, normally {@code (1, 1, 1)}
 */
public record SubLevelSnapshot(String name, Vector3d position, Quaterniond orientation,
                               Vector3d rotationPoint, Vector3d scale) {

    public SubLevelSnapshot {
        name = name == null ? "" : name;
        position = position == null ? new Vector3d() : new Vector3d(position);
        orientation = orientation == null ? new Quaterniond() : new Quaterniond(orientation);
        rotationPoint = rotationPoint == null ? new Vector3d() : new Vector3d(rotationPoint);
        scale = scale == null ? new Vector3d(1.0D, 1.0D, 1.0D) : new Vector3d(scale);
    }

    /** Copied origin, safe to mutate by the caller. */
    @Override
    public Vector3d position() {
        return new Vector3d(this.position);
    }

    /** Copied orientation, safe to mutate by the caller. */
    @Override
    public Quaterniond orientation() {
        return new Quaterniond(this.orientation);
    }

    /** Copied rotation centre, safe to mutate by the caller. */
    @Override
    public Vector3d rotationPoint() {
        return new Vector3d(this.rotationPoint);
    }

    /** Copied scale, safe to mutate by the caller. */
    @Override
    public Vector3d scale() {
        return new Vector3d(this.scale);
    }

    /** Internal accessor used by math code that wants to avoid a copy. */
    Vector3dc positionView() {
        return this.position;
    }

    /** Internal accessor used by math code that wants to avoid a copy. */
    Quaterniondc orientationView() {
        return this.orientation;
    }
}
