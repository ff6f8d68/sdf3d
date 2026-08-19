package mods.hexagon.sdf3d.sdf;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public record SdfBounds(Vector3f min, Vector3f max) {
    public SdfBounds {
        min = new Vector3f(min);
        max = new Vector3f(max);
    }

    public static SdfBounds infinite() {
        return new SdfBounds(new Vector3f(-100000.0f), new Vector3f(100000.0f));
    }

    public static SdfBounds empty() {
        return new SdfBounds(new Vector3f(Float.POSITIVE_INFINITY), new Vector3f(Float.NEGATIVE_INFINITY));
    }

    public Vector3f center() {
        return new Vector3f(min).add(max).mul(0.5f);
    }

    public Vector3f size() {
        return new Vector3f(max).sub(min);
    }

    public float radius() {
        return size().length() * 0.5f;
    }

    public boolean isFinite() {
        return min.x > -90000.0f && max.x < 90000.0f
                && min.y > -90000.0f && max.y < 90000.0f
                && min.z > -90000.0f && max.z < 90000.0f;
    }

    public boolean isEmpty() {
        return min.x > max.x || min.y > max.y || min.z > max.z;
    }

    public boolean contains(Vector3fc point) {
        return point.x() >= min.x && point.x() <= max.x
                && point.y() >= min.y && point.y() <= max.y
                && point.z() >= min.z && point.z() <= max.z;
    }

    public SdfBounds expand(float amount) {
        Vector3f e = new Vector3f(amount, amount, amount);
        return new SdfBounds(new Vector3f(min).sub(e), new Vector3f(max).add(e));
    }

    public SdfBounds translate(Vector3fc offset) {
        return new SdfBounds(new Vector3f(min).add(offset), new Vector3f(max).add(offset));
    }

    public SdfBounds scale(Vector3fc scale) {
        Vector3f lo = new Vector3f(min).mul(scale);
        Vector3f hi = new Vector3f(max).mul(scale);
        return new SdfBounds(new Vector3f(lo).min(hi), new Vector3f(lo).max(hi));
    }

    public SdfBounds union(SdfBounds other) {
        return new SdfBounds(new Vector3f(min).min(other.min), new Vector3f(max).max(other.max));
    }

    public SdfBounds intersect(SdfBounds other) {
        SdfBounds result = new SdfBounds(new Vector3f(min).max(other.min), new Vector3f(max).min(other.max));
        return result.isEmpty() ? union(other) : result;
    }

    public boolean intersects(SdfBounds other) {
        return !(max.x < other.min.x || min.x > other.max.x
                || max.y < other.min.y || min.y > other.max.y
                || max.z < other.min.z || min.z > other.max.z);
    }

    public SdfBounds transform(Vector3fc translation, Quaternionf rotation, Vector3fc scale) {
        Vector3f low = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f high = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            Vector3f corner = new Vector3f(x == 0 ? min.x : max.x, y == 0 ? min.y : max.y, z == 0 ? min.z : max.z)
                    .mul(scale).rotate(rotation).add(translation);
            low.min(corner);
            high.max(corner);
        }
        return new SdfBounds(low, high);
    }

    /** Returns false when the ray misses; near/far are written into the returned array. */
    public float[] intersect(Vector3fc origin, Vector3fc direction) {
        float near = -Float.MAX_VALUE;
        float far = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            float o = origin.get(axis);
            float d = direction.get(axis);
            float lo = min.get(axis);
            float hi = max.get(axis);
            if (Math.abs(d) < 1.0e-7f) {
                if (o < lo || o > hi) return null;
                continue;
            }
            float a = (lo - o) / d;
            float b = (hi - o) / d;
            if (a > b) { float swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return null;
        }
        return new float[]{near, far};
    }
}
