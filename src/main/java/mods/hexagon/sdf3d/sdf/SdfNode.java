package mods.hexagon.sdf3d.sdf;

import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A signed distance function in model-local coordinates. Negative values are inside.
 * Implementations may override {@link #bounds()} to provide a tight AABB for early-out
 * culling in the raymarcher.
 */
@FunctionalInterface
public interface SdfNode {
    float distance(Vector3fc point);

    default SdfMaterial material(Vector3fc point) {
        return SdfMaterial.WHITE;
    }

    default Vector2fc uv(Vector3fc point) {
        return new Vector2f(point.x() - (float) Math.floor(point.x()), point.z() - (float) Math.floor(point.z()));
    }

    /** Conservative axis-aligned bounds in model-local space. */
    default SdfBounds bounds() {
        return SdfBounds.infinite();
    }

    default Vector3f normal(Vector3fc point, float epsilon) {
        float e = Math.max(0.0001f, epsilon);
        float x = distance(new Vector3f(point).add(e, 0, 0)) - distance(new Vector3f(point).sub(e, 0, 0));
        float y = distance(new Vector3f(point).add(0, e, 0)) - distance(new Vector3f(point).sub(0, e, 0));
        float z = distance(new Vector3f(point).add(0, 0, e)) - distance(new Vector3f(point).sub(0, 0, e));
        return new Vector3f(x, y, z).normalize();
    }
}
