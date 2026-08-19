package mods.hexagon.sdf3d.editor;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Picks the node under a surface point in the editor's {@link EdNode} tree. It mirrors the
 * parser's SDF math for primitives and transforms (translating/rotating/scaling the query point
 * down into each leaf's local space) and returns the leaf whose surface is closest to the point.
 * Combinators and animation nodes simply recurse, which gives the correct "closest surface leaf"
 * for the unions/stamps/blends the editor produces when sculpting.
 */
public final class EdPicker {
    private EdPicker() {}

    public static EdNode pick(EdNode root, Map<String, EdNode> groups, Vector3f p, float time) {
        float[] best = {Float.POSITIVE_INFINITY};
        EdNode[] found = {null};
        pickRec(root, groups, p.x(), p.y(), p.z(), time, best, found);
        return found[0];
    }

    private static void pickRec(EdNode n, Map<String, EdNode> groups, float x, float y, float z,
                                float time, float[] best, EdNode[] found) {
        if (n == null) return;
        if (n.groupRef != null) {
            pickRec(groups.get(n.groupRef), groups, x, y, z, time, best, found);
            return;
        }
        Float d = primitiveDistance(n, x, y, z);
        if (d != null) {
            float ad = Math.abs(d);
            if (ad < best[0]) {
                best[0] = ad;
                found[0] = n;
            }
            return;
        }
        switch (n.name()) {
            case "translate" -> pickRec(child(n), groups, x - n.params[0], y - n.params[1], z - n.params[2], time, best, found);
            case "scale" -> {
                float s = n.params[0];
                pickRec(child(n), groups, x / s, y / s, z / s, time, best, found);
            }
            case "stretch" -> pickRec(child(n), groups, x / n.params[0], y / n.params[1], z / n.params[2], time, best, found);
            case "rotate" -> {
                Quaternionf q = new Quaternionf().rotateXYZ(rad(n.params[0]), rad(n.params[1]), rad(n.params[2])).conjugate();
                Vector3f rp = new Vector3f(x, y, z).rotate(q);
                pickRec(child(n), groups, rp.x, rp.y, rp.z, time, best, found);
            }
            default -> {
                for (EdNode c : n.children) pickRec(c, groups, x, y, z, time, best, found);
            }
        }
    }

    private static EdNode child(EdNode n) {
        return n.children.isEmpty() ? null : n.children.get(0);
    }

    private static float rad(float deg) {
        return (float) Math.toRadians(deg);
    }

    /** Signed distance for primitive leaves; {@code null} for non-primitive nodes. */
    private static Float primitiveDistance(EdNode n, float x, float y, float z) {
        float[] p = n.params;
        return switch (n.name()) {
            case "sphere" -> {
                float dx = x - p[0], dy = y - p[1], dz = z - p[2];
                yield (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - p[3];
            }
            case "box" -> boxDistance(x - p[0], y - p[1], z - p[2], p[3], p[4], p[5], 0f);
            case "rounded_box" -> boxDistance(x - p[0], y - p[1], z - p[2], p[3], p[4], p[5], p[6]);
            case "ellipsoid" -> {
                float dx = (x - p[0]) / p[3], dy = (y - p[1]) / p[4], dz = (z - p[2]) / p[5];
                yield (len3(dx, dy, dz) - 1.0f) * Math.min(p[3], Math.min(p[4], p[5]));
            }
            case "torus" -> {
                float qx = x - p[0], qy = y - p[1], qz = z - p[2];
                float radial = (float) Math.sqrt(qx * qx + qz * qz) - p[3];
                yield (float) Math.sqrt(radial * radial + qy * qy) - p[4];
            }
            case "capsule" -> {
                float ax = p[0], ay = p[1], az = p[2], bx = p[3], by = p[4], bz = p[5], r = p[6];
                float bax = bx - ax, bay = by - ay, baz = bz - az;
                float ll = bax * bax + bay * bay + baz * baz;
                float px = x - ax, py = y - ay, pz = z - az;
                float t = ll > 1e-9f ? clamp((px * bax + py * bay + pz * baz) / ll, 0, 1) : 0;
                float cx = ax + bax * t, cy = ay + bay * t, cz = az + baz * t;
                float dx = x - cx, dy = y - cy, dz = z - cz;
                yield (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            }
            case "plane" -> {
                Vector3f nn = new Vector3f(p[0], p[1], p[2]).normalize();
                yield nn.x() * x + nn.y() * y + nn.z() * z + p[3];
            }
            case "cylinder" -> {
                float qx = x - p[0], qy = y - p[1], qz = z - p[2];
                float dx = (float) Math.sqrt(qx * qx + qz * qz) - p[3];
                float dy = Math.abs(qy) - p[4] * 0.5f;
                yield Math.min(Math.max(dx, dy), 0) + len2(Math.max(dx, 0), Math.max(dy, 0));
            }
            case "cone" -> {
                float qx = x - p[0], qy = y - p[1], qz = z - p[2];
                float r1 = Math.abs(p[3]), h = p[4] * 0.5f;
                float r = (float) Math.sqrt(qx * qx + qz * qz);
                float slope = r1 / Math.max(h, 1e-4f);
                float cosa = (float) Math.sqrt(Math.max(1.0f - slope * slope, 0.0f));
                float k = -slope * r + cosa * qy;
                if (k < 0.0f) yield (float) Math.sqrt(r * r + qy * qy) - r1;
                if (k > cosa * h) yield (float) Math.sqrt(r * r + (qy - h) * (qy - h));
                yield cosa * r + slope * qy - r1;
            }
            case "hex_prism" -> {
                float px = x - p[0], py = y - p[1], pz = z - p[2];
                float rad = p[3], h = p[4] * 0.5f;
                float ax = Math.abs(px), az = Math.abs(pz);
                float kx = -0.866025404f, ky = 0.5f, kz = 0.577350269f;
                float dotv = kx * ax + ky * az;
                float t = 2.0f * Math.min(dotv, 0.0f);
                ax -= t * kx; az -= t * ky;
                ax -= clamp(ax, -kz * rad, kz * rad); az -= rad;
                float d = (float) Math.sqrt(ax * ax + az * az) * Math.signum(az);
                float dy = Math.abs(py) - h;
                yield Math.min(Math.max(d, dy), 0.0f) + len2(Math.max(d, 0.0f), Math.max(dy, 0.0f));
            }
            case "octahedron" -> {
                float px = Math.abs(x - p[0]), py = Math.abs(y - p[1]), pz = Math.abs(z - p[2]);
                float s = p[3];
                float m = px + py + pz - s;
                float qx, qy, qz;
                if (3.0f * px < m) { qx = px; qy = py; qz = pz; }
                else if (3.0f * py < m) { qx = py; qy = pz; qz = px; }
                else if (3.0f * pz < m) { qx = pz; qy = px; qz = py; }
                else yield m * 0.57735027f;
                float k = clamp(0.5f * (qz - qy + s), 0.0f, s);
                yield len3(qx, qy - s + k, qz - k);
            }
            default -> null;
        };
    }

    private static float boxDistance(float qx, float qy, float qz, float hx, float hy, float hz, float r) {
        float ax = Math.abs(qx) - hx;
        float ay = Math.abs(qy) - hy;
        float az = Math.abs(qz) - hz;
        float outside = len3(Math.max(ax, 0), Math.max(ay, 0), Math.max(az, 0));
        float inside = Math.min(Math.max(ax, Math.max(ay, az)), 0);
        return outside + inside - r;
    }

    private static float len3(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float len2(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
