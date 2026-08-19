package mods.hexagon.sdf3d.sdf;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Programmatic builder for {@link SdfNode} trees. All functions operate in model-local
 * coordinates and return conservative {@link SdfBounds} where cheaply available, so the
 * raymarcher can cull rays early.
 */
public final class SdfNodes {
    private SdfNodes() {}

    /** A distance function plus optional tight bounds. */
    @FunctionalInterface
    private interface Fn {
        float eval(Vector3fc p);
    }

    private static SdfNode node(Fn fn, SdfBounds bounds) {
        return new SdfNode() {
            @Override public float distance(Vector3fc point) { return fn.eval(point); }
            @Override public SdfBounds bounds() { return bounds; }
        };
    }

    private static SdfNode node(Fn fn) {
        return node(fn, SdfBounds.infinite());
    }

    private static Vector3f c(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }

    // ------------------------------------------------------------------ primitives

    public static SdfNode sphere(float x, float y, float z, float radius) {
        Vector3f center = c(x, y, z);
        float r = Math.abs(radius);
        return node(p -> new Vector3f(p).sub(center).length() - r,
                new SdfBounds(c(x - r, y - r, z - r), c(x + r, y + r, z + r)));
    }

    public static SdfNode box(float x, float y, float z, float hx, float hy, float hz) {
        Vector3f center = c(x, y, z);
        Vector3f half = new Vector3f(Math.abs(hx), Math.abs(hy), Math.abs(hz));
        return node(p -> {
            float qx = Math.abs(p.x() - center.x) - half.x;
            float qy = Math.abs(p.y() - center.y) - half.y;
            float qz = Math.abs(p.z() - center.z) - half.z;
            float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f), oz = Math.max(qz, 0f);
            float outside = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            float inside = Math.min(Math.max(qx, Math.max(qy, qz)), 0f);
            return outside + inside;
        }, new SdfBounds(new Vector3f(center).sub(half), new Vector3f(center).add(half)));
    }

    public static SdfNode roundedBox(float x, float y, float z, float hx, float hy, float hz, float radius) {
        Vector3f center = c(x, y, z);
        Vector3f half = new Vector3f(Math.abs(hx), Math.abs(hy), Math.abs(hz));
        float r = Math.abs(radius);
        return node(p -> {
            float qx = Math.abs(p.x() - center.x) - half.x;
            float qy = Math.abs(p.y() - center.y) - half.y;
            float qz = Math.abs(p.z() - center.z) - half.z;
            float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f), oz = Math.max(qz, 0f);
            float outside = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            float inside = Math.min(Math.max(qx, Math.max(qy, qz)), 0f);
            return outside + inside - r;
        }, new SdfBounds(new Vector3f(center).sub(half).sub(r, r, r), new Vector3f(center).add(half).add(r, r, r)));
    }

    public static SdfNode ellipsoid(float x, float y, float z, float rx, float ry, float rz) {
        Vector3f center = c(x, y, z);
        Vector3f radii = new Vector3f(Math.max(Math.abs(rx), 1e-4f), Math.max(Math.abs(ry), 1e-4f), Math.max(Math.abs(rz), 1e-4f));
        float minRadius = Math.min(radii.x, Math.min(radii.y, radii.z));
        return node(p -> {
            Vector3f q = new Vector3f(p).sub(center).div(radii);
            return (q.length() - 1.0f) * minRadius;
        }, new SdfBounds(new Vector3f(center).sub(radii), new Vector3f(center).add(radii)));
    }

    public static SdfNode torus(float x, float y, float z, float major, float minor) {
        Vector3f center = c(x, y, z);
        float ra = Math.abs(major);
        float rb = Math.abs(minor);
        return node(p -> {
            Vector3f q = new Vector3f(p).sub(center);
            float radial = (float) Math.sqrt(q.x * q.x + q.z * q.z) - ra;
            return (float) Math.sqrt(radial * radial + q.y * q.y) - rb;
        }, new SdfBounds(c(x - ra - rb, y - rb, z - ra - rb), c(x + ra + rb, y + rb, z + ra + rb)));
    }

    public static SdfNode cylinder(float x, float y, float z, float radius, float height) {
        Vector3f center = c(x, y, z);
        float r = Math.abs(radius);
        float h = Math.abs(height) * 0.5f;
        return node(p -> {
            Vector3f q = new Vector3f(p).sub(center);
            float dx = (float) Math.sqrt(q.x * q.x + q.z * q.z) - r;
            float dy = Math.abs(q.y) - h;
            return Math.min(Math.max(dx, dy), 0.0f) + new Vector2f(Math.max(dx, 0.0f), Math.max(dy, 0.0f)).length();
        }, new SdfBounds(c(x - r, y - h, z - r), c(x + r, y + h, z + r)));
    }

    public static SdfNode capsule(float ax, float ay, float az, float bx, float by, float bz, float radius) {
        Vector3f a = c(ax, ay, az);
        Vector3f b = c(bx, by, bz);
        float r = Math.abs(radius);
        Vector3f ba = new Vector3f(b).sub(a);
        float len2 = ba.lengthSquared();
        return node(p -> {
            float t = len2 > 1e-9f ? Math.max(0.0f, Math.min(1.0f, new Vector3f(p).sub(a).dot(ba) / len2)) : 0.0f;
            Vector3f closest = new Vector3f(a).fma(t, ba);
            return new Vector3f(p).sub(closest).length() - r;
        }, new SdfBounds(new Vector3f(a).min(b).sub(r, r, r), new Vector3f(a).max(b).add(r, r, r)));
    }

    public static SdfNode plane(float nx, float ny, float nz, float offset) {
        Vector3f normal = new Vector3f(nx, ny, nz).normalize();
        return node(p -> normal.dot(p) + offset, SdfBounds.infinite());
    }

    // ------------------------------------------------------------------ combinators

    public static SdfNode union(SdfNode... nodes) {
        if (nodes.length == 0) return node(p -> Float.POSITIVE_INFINITY);
        if (nodes.length == 1) return nodes[0];
        SdfBounds bounds = SdfBounds.empty();
        for (SdfNode n : nodes) bounds = bounds.union(n.bounds());
        return node(p -> {
            float value = Float.POSITIVE_INFINITY;
            for (SdfNode n : nodes) value = Math.min(value, n.distance(p));
            return value;
        }, bounds);
    }

    public static SdfNode intersect(SdfNode... nodes) {
        if (nodes.length == 0) return node(p -> Float.NEGATIVE_INFINITY);
        if (nodes.length == 1) return nodes[0];
        SdfBounds bounds = nodes[0].bounds();
        for (int i = 1; i < nodes.length; i++) bounds = bounds.intersect(nodes[i].bounds());
        return node(p -> {
            float value = Float.NEGATIVE_INFINITY;
            for (SdfNode n : nodes) value = Math.max(value, n.distance(p));
            return value;
        }, bounds);
    }

    public static SdfNode subtract(SdfNode a, SdfNode b) {
        return node(p -> Math.max(a.distance(p), -b.distance(p)), a.bounds());
    }

    public static SdfNode smoothUnion(float radius, SdfNode a, SdfNode b) {
        float k = Math.max(0.0001f, radius);
        SdfBounds bounds = a.bounds().union(b.bounds()).expand(k);
        return node(p -> {
            float da = a.distance(p);
            float db = b.distance(p);
            float h = Math.max(k - Math.abs(da - db), 0.0f) / k;
            return Math.min(da, db) - h * h * h * k / 6.0f;
        }, bounds);
    }

    public static SdfNode smoothIntersect(float radius, SdfNode a, SdfNode b) {
        float k = Math.max(0.0001f, radius);
        return node(p -> {
            float da = a.distance(p);
            float db = b.distance(p);
            float h = Math.max(k - Math.abs(da - db), 0.0f) / k;
            return Math.max(da, db) + h * h * h * k / 6.0f;
        }, a.bounds().intersect(b.bounds()));
    }

    public static SdfNode smoothSubtract(float radius, SdfNode a, SdfNode b) {
        float k = Math.max(0.0001f, radius);
        return node(p -> {
            float da = a.distance(p);
            float db = b.distance(p);
            float h = Math.max(k - Math.abs(da + db), 0.0f) / k;
            return Math.max(da, -db) + h * h * h * k / 6.0f;
        }, a.bounds());
    }

    public static SdfNode round(SdfNode child, float radius) {
        float r = Math.abs(radius);
        return node(p -> child.distance(p) - r, child.bounds().expand(r));
    }

    public static SdfNode onion(SdfNode child, float thickness) {
        float t = Math.max(0.0001f, Math.abs(thickness));
        return node(p -> Math.abs(child.distance(p)) - t, child.bounds().expand(t));
    }

    // ------------------------------------------------------------------ transforms

    public static SdfNode translate(float x, float y, float z, SdfNode child) {
        Vector3f offset = c(x, y, z);
        return node(p -> child.distance(new Vector3f(p).sub(offset)), child.bounds().translate(offset));
    }

    public static SdfNode scale(float x, float y, float z, SdfNode child) {
        Vector3f scale = new Vector3f(Math.max(Math.abs(x), 0.0001f), Math.max(Math.abs(y), 0.0001f), Math.max(Math.abs(z), 0.0001f));
        float distanceScale = Math.min(scale.x, Math.min(scale.y, scale.z));
        return node(p -> child.distance(new Vector3f(p).div(scale)) * distanceScale, child.bounds().scale(scale));
    }

    public static SdfNode stretch(float x, float y, float z, SdfNode child) {
        return scale(x, y, z, child);
    }

    public static SdfNode rotate(float degreesX, float degreesY, float degreesZ, SdfNode child) {
        Quaternionf rotation = new Quaternionf().rotateXYZ((float) Math.toRadians(degreesX), (float) Math.toRadians(degreesY), (float) Math.toRadians(degreesZ));
        Quaternionf inverse = new Quaternionf(rotation).conjugate();
        SdfBounds bounds = child.bounds().transform(c(0, 0, 0), rotation, new Vector3f(1, 1, 1));
        return node(p -> child.distance(new Vector3f(p).rotate(inverse)), bounds);
    }

    public static SdfNode mirror(float axisX, float axisY, float axisZ, SdfNode child) {
        int ax = axisX != 0 ? 1 : 0;
        int ay = axisY != 0 ? 1 : 0;
        int az = axisZ != 0 ? 1 : 0;
        return node(p -> child.distance(new Vector3f(ax != 0 ? -p.x() : p.x(), ay != 0 ? -p.y() : p.y(), az != 0 ? -p.z() : p.z())),
                child.bounds());
    }

    // ------------------------------------------------------------------ material / uv

    public static SdfNode withMaterial(SdfNode child, SdfMaterial material) {
        return new SdfNode() {
            @Override public float distance(Vector3fc point) { return child.distance(point); }
            @Override public SdfMaterial material(Vector3fc point) { return material; }
            @Override public Vector2fc uv(Vector3fc point) { return child.uv(point); }
            @Override public SdfBounds bounds() { return child.bounds(); }
        };
    }

    public static SdfNode withUv(SdfNode child, float scaleU, float scaleV) {
        return new SdfNode() {
            @Override public float distance(Vector3fc point) { return child.distance(point); }
            @Override public Vector2fc uv(Vector3fc point) {
                Vector2fc uv = child.uv(point);
                return new Vector2f(uv.x() * scaleU, uv.y() * scaleV);
            }
            @Override public SdfBounds bounds() { return child.bounds(); }
        };
    }
}
