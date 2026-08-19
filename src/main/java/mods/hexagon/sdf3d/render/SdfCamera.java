package mods.hexagon.sdf3d.render;

import org.joml.Vector3f;

/** A right-handed pinhole camera. {@code forward/right/up} are normalized on construction. */
public record SdfCamera(Vector3f position, Vector3f forward, Vector3f right, Vector3f up,
                        float fovYRadians, float aspect) {

    public SdfCamera {
        position = new Vector3f(position);
        forward = new Vector3f(forward).normalize();
        right = new Vector3f(right).normalize();
        up = new Vector3f(up).normalize();
    }

    /** Builds the direction of the ray for a pixel at normalized coordinates in [-1, 1]. */
    public Vector3f rayDirection(float ndcX, float ndcY) {
        float tanHalfFov = (float) Math.tan(fovYRadians * 0.5f);
        return new Vector3f(forward)
                .fma(ndcX * tanHalfFov * aspect, right)
                .fma(ndcY * tanHalfFov, up)
                .normalize();
    }
}
