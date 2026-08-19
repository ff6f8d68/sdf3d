package mods.hexagon.sdf3d.render;

import mods.hexagon.sdf3d.model.SdfModelManager;
import mods.hexagon.sdf3d.scene.SdfInstance;
import mods.hexagon.sdf3d.scene.SdfScene;
import mods.hexagon.sdf3d.sdf.SdfBounds;
import mods.hexagon.sdf3d.sdf.SdfMaterial;
import mods.hexagon.sdf3d.sdf.SdfModel;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Pure-CPU sphere tracing. This is the reference implementation of the "pixel hits model"
 * contract: a ray either hits (returns {@link RayHit}) or misses (returns {@code null}), and a
 * hit carries the surface point, smoothed normal, material and UV. The GPU path evaluates the
 * identical distance field in a shader.
 */
public final class SdfRaycaster {
    private static final float EPSILON = 0.001f;
    private static final int MAX_STEPS = 256;
    private static final float MAX_DISTANCE = 512.0f;

    private SdfRaycaster() {}

    public record RayHit(float distance, Vector3f point, Vector3f normal, SdfMaterial material, Vector2f uv) {}

    public static RayHit march(SdfModel model, Vector3fc origin, Vector3fc direction) {
        return march(model, origin, direction, MAX_DISTANCE);
    }

    public static RayHit march(SdfModel model, Vector3fc origin, Vector3fc direction, float maxDistance) {
        Vector3fc dir = direction;
        if (Math.abs(dir.lengthSquared() - 1.0f) > 1e-4f) dir = new Vector3f(direction).normalize();

        SdfBounds bounds = model.bounds();
        float start = 0f;
        if (bounds.isFinite()) {
            float[] span = bounds.intersect(origin, dir);
            if (span == null || span[0] > maxDistance) return null;
            start = Math.max(0f, span[0]);
        }

        float t = start;
        for (int i = 0; i < MAX_STEPS; i++) {
            Vector3f p = new Vector3f(origin).fma(t, dir);
            float d = model.distance(p);
            if (d < EPSILON) {
                Vector3f normal = model.function().normal(p, EPSILON);
                Vector2f uv = new Vector2f(model.uv(p));
                return new RayHit(t, p, normal, model.material(p), uv);
            }
            t += d;
            if (t > maxDistance) break;
        }
        return null;
    }

    /** Marches the nearest visible instance of a scene. Returns the hit with per-instance material overrides applied. */
    public static RayHit marchScene(SdfScene scene, Vector3fc origin, Vector3fc direction) {
        return marchScene(scene, origin, direction, MAX_DISTANCE);
    }

    public static RayHit marchScene(SdfScene scene, Vector3fc origin, Vector3fc direction, float maxDistance) {
        RayHit best = null;
        for (SdfInstance instance : scene.instances()) {
            if (!instance.visible()) continue;
            SdfModel model = SdfModelManager.get(instance.modelId());
            RayHit hit = marchInstance(instance, model, origin, direction, maxDistance);
            if (hit != null && (best == null || hit.distance() < best.distance())) best = hit;
        }
        return best;
    }

    /** Marches a model placed by an instance transform. Non-uniform scale is approximated via the minimum scale. */
    public static RayHit marchInstance(SdfInstance instance, SdfModel model, Vector3fc origin, Vector3fc direction, float maxDistance) {
        Quaternionf invRot = new Quaternionf(instance.rotation()).conjugate();
        Vector3fc scale = instance.scale();
        float minScale = Math.min(scale.x(), Math.min(scale.y(), scale.z()));
        if (minScale < 1e-6f) return null;

        Vector3f localOrigin = new Vector3f(origin).sub(instance.position()).rotate(invRot).div(scale);
        Vector3f localDir = new Vector3f(direction).rotate(invRot).div(scale).normalize();

        RayHit local = march(model, localOrigin, localDir, maxDistance / minScale);
        if (local == null) return null;

        Vector3f worldPoint = new Vector3f(local.point()).mul(scale).rotate(instance.rotation()).add(instance.position());
        Vector3f worldNormal = new Vector3f(local.normal()).div(scale).rotate(instance.rotation()).normalize();
        SdfMaterial material = override(local.material(), instance);
        return new RayHit(local.distance() * minScale, worldPoint, worldNormal, material, local.uv());
    }

    private static SdfMaterial override(SdfMaterial material, SdfInstance instance) {
        int tint = instance.tintOverride() != null ? instance.tintOverride() : material.tint();
        boolean emissive = instance.emissiveOverride() != null ? instance.emissiveOverride() : material.emissive();
        float roughness = instance.roughnessOverride() != null ? instance.roughnessOverride() : material.roughness();
        return new SdfMaterial(material.texture(), tint, emissive, roughness, material.metallic());
    }
}
