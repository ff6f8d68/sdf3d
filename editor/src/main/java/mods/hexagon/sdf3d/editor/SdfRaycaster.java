package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfModel;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Pure-Java sphere tracer for the editor. Marches an {@link SdfModel} along a ray and returns
 * the hit (surface point + analytic normal) or {@code null} on a miss.
 *
 * <p>Precision notes: a fixed <em>world-space</em> epsilon is the classic source of "fat rays" —
 * if it is too large, thin walls and carved shells are stepped over and read as holes or see-through
 * faces. All callers therefore pass a small epsilon scaled to the scene, and the march clamps the
 * step so a ray always makes forward progress even in degenerate fields.</p>
 */
public final class SdfRaycaster {
    private SdfRaycaster() {}

    public record Hit(Vector3f position, Vector3f normal, float distance) {}

    public static Hit march(SdfModel model, Vector3fc origin, Vector3fc direction,
                            float maxDist, int maxSteps, float epsilon) {
        float t = 0f;
        float minStep = Math.max(1e-5f, epsilon * 0.5f);
        Vector3f p = new Vector3f();
        for (int i = 0; i < maxSteps; i++) {
            p.set(direction).mul(t).add(origin);
            float d = model.distance(p);
            if (d < epsilon) {
                return new Hit(new Vector3f(p), normal(model, p, epsilon), t);
            }
            t += Math.max(d, minStep);
            if (t > maxDist || !Float.isFinite(t)) break;
        }
        return null;
    }

    /** Central-difference normal. Uses its own (small) epsilon so it stays sharp on thin features. */
    public static Vector3f normal(SdfModel model, Vector3fc point, float epsilon) {
        float e = Math.max(0.0001f, epsilon);
        float x = model.distance(new Vector3f(point).add(e, 0f, 0f)) - model.distance(new Vector3f(point).sub(e, 0f, 0f));
        float y = model.distance(new Vector3f(point).add(0f, e, 0f)) - model.distance(new Vector3f(point).sub(0f, e, 0f));
        float z = model.distance(new Vector3f(point).add(0f, 0f, e)) - model.distance(new Vector3f(point).sub(0f, 0f, e));
        return new Vector3f(x, y, z).normalize();
    }

    /**
     * Soft shadow factor in [0,1] along {@code lightDir}. Uses the classic
     * {@code clamp(k*d/t)} accumulation so edges feather instead of producing the hard "fat ray"
     * blobs a binary occlusion test gives.
     */
    public static float softShadow(SdfModel model, Vector3fc origin, Vector3fc lightDir,
                                   float maxDist, int maxSteps, float epsilon, float k) {
        float res = 1f;
        float t = Math.max(epsilon, 1e-4f);
        Vector3f p = new Vector3f();
        for (int i = 0; i < maxSteps; i++) {
            p.set(lightDir).mul(t).add(origin);
            float d = model.distance(p);
            if (d < epsilon) return 0f;
            res = Math.min(res, k * d / t);
            t += Math.max(d, epsilon * 0.5f);
            if (res < 1e-3f || t > maxDist) break;
        }
        return Math.max(0f, Math.min(1f, res));
    }
}
