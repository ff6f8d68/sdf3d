package mods.hexagon.sdf3d.sdf;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A flattened, immutable SDF tree. Each node occupies 12 floats:
 * <pre>
 *   [op, childA, childB, p0..p8]
 * </pre>
 * Nodes are laid out in post-order (children always have a lower index than their parent),
 * which makes evaluation a simple forward pass and, importantly, lets the GPU evaluate the
 * same graph without recursion (GLSL forbids recursive functions).
 *
 * <p>This class also implements {@link SdfNode}, so a parsed model can be consumed by the
 * CPU path (see {@link #distance}) as well as serialized for the GPU path.</p>
 */
public final class SdfGraph implements SdfNode {
    // leaf / arithmetic ops
    public static final int CONST = 0;
    public static final int X = 1, Y = 2, Z = 3;
    public static final int ADD = 10, SUB = 11, MUL = 12, DIV = 13, NEG = 14, ABS = 15, MIN = 16, MAX = 17;
    // primitives
    public static final int SPHERE = 20, BOX = 21, ROUNDED_BOX = 22, ELLIPSOID = 23, TORUS = 24, CYLINDER = 25, CAPSULE = 26, PLANE = 27;
    // extended primitives
    public static final int CONE = 36, HEX_PRISM = 37, OCTAHEDRON = 38;
    // combinators
    public static final int UNION = 30, INTERSECT = 31, SUBTRACT = 32, SMOOTH_UNION = 33, SMOOTH_INTERSECT = 34, SMOOTH_SUBTRACT = 35;
    // transforms
    public static final int TRANSLATE = 40, SCALE = 41, STRETCH = 42, ROTATE = 43, ROUND = 44, ONION = 45;
    // animation transforms (time-driven)
    public static final int SPIN = 51, BOB = 52, PULSE = 53;
    // morph + advanced deformations / keyframed animation
    public static final int MORPH = 54, TWIST = 55, BEND = 56, KEYFRAME_SCALE = 57;
    public static final int KEYFRAME_TRANSLATE = 58, KEYFRAME_ROTATE = 59;

    private static final int STRIDE = 12;
    // Budget for the GPU uniform-array path: 192 nodes * 12 floats = 2304 components.
    // (The GL floor is 1024, but desktop drivers — including Intel/Mesa — expose far more;
    // 192 is enough for the full showcase model without reworking the node encoding.)
    private static final int MAX_NODES = 192;

    private final float[] data;
    private final int nodeCount;
    private final int rootIndex;
    private final SdfBounds bounds;
    private volatile float time = 0.0f;
    // keyframe interpolation curve: 0=linear 1=smoothstep 2=easeIn 3=easeOut 4=easeInOut 5=bezier
    private int easing = 0;
    private float[] bezier = {0.42f, 0.0f, 0.58f, 1.0f};

    private SdfGraph(float[] data, int nodeCount, int rootIndex, SdfBounds bounds) {
        this.data = data;
        this.nodeCount = nodeCount;
        this.rootIndex = rootIndex;
        this.bounds = bounds;
    }

    /** Seconds since some fixed epoch; drives the {@code spin}/{@code bob}/{@code pulse} nodes on the CPU path. */
    public void setTime(float time) {
        this.time = time;
    }

    public float time() {
        return time;
    }

    /** Sets the keyframe interpolation curve (see {@link #easing}). */
    public void setEasing(int code, float cx1, float cy1, float cx2, float cy2) {
        this.easing = code;
        if (code == 5) this.bezier = new float[]{cx1, cy1, cx2, cy2};
    }

    public int easing() { return easing; }
    public float[] bezier() { return bezier; }

    public int nodeCount() { return nodeCount; }
    public int rootIndex() { return rootIndex; }

    /** Raw serialized node data; exactly {@code nodeCount() * 12} floats. */
    public float[] data() { return Arrays.copyOf(data, nodeCount * STRIDE); }

    @Override
    public float distance(Vector3fc point) {
        return eval(rootIndex, point.x(), point.y(), point.z());
    }

    @Override
    public SdfBounds bounds() {
        return bounds;
    }

    public float eval(int index, float x, float y, float z) {
        int base = index * STRIDE;
        int op = (int) data[base];
        int a = (int) data[base + 1];
        int b = (int) data[base + 2];
        float p0 = data[base + 3], p1 = data[base + 4], p2 = data[base + 5];
        float p3 = data[base + 6], p4 = data[base + 7], p5 = data[base + 8];
        float p6 = data[base + 9], p7 = data[base + 10], p8 = data[base + 11];
        switch (op) {
            case CONST: return p0;
            case X: return x;
            case Y: return y;
            case Z: return z;
            case ADD: return eval(a, x, y, z) + eval(b, x, y, z);
            case SUB: return eval(a, x, y, z) - eval(b, x, y, z);
            case MUL: return eval(a, x, y, z) * eval(b, x, y, z);
            case DIV: { float db = eval(b, x, y, z); return eval(a, x, y, z) / (Math.abs(db) < 1e-9f ? 1e-9f : db); }
            case NEG: return -eval(a, x, y, z);
            case ABS: return Math.abs(eval(a, x, y, z));
            case MIN: return Math.min(eval(a, x, y, z), eval(b, x, y, z));
            case MAX: return Math.max(eval(a, x, y, z), eval(b, x, y, z));
            case SPHERE: {
                float dx = x - p0, dy = y - p1, dz = z - p2;
                return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - p3;
            }
            case BOX: {
                float qx = Math.abs(x - p0) - p3;
                float qy = Math.abs(y - p1) - p4;
                float qz = Math.abs(z - p2) - p5;
                float outside = len3(Math.max(qx, 0), Math.max(qy, 0), Math.max(qz, 0));
                float inside = Math.min(Math.max(qx, Math.max(qy, qz)), 0);
                return outside + inside;
            }
            case ROUNDED_BOX: {
                float qx = Math.abs(x - p0) - p3;
                float qy = Math.abs(y - p1) - p4;
                float qz = Math.abs(z - p2) - p5;
                float outside = len3(Math.max(qx, 0), Math.max(qy, 0), Math.max(qz, 0));
                float inside = Math.min(Math.max(qx, Math.max(qy, qz)), 0);
                return outside + inside - p6;
            }
            case ELLIPSOID: {
                float dx = (x - p0) / p3, dy = (y - p1) / p4, dz = (z - p2) / p5;
                float minRadius = Math.min(p3, Math.min(p4, p5));
                return (len3(dx, dy, dz) - 1.0f) * minRadius;
            }
            case TORUS: {
                float qx = x - p0, qy = y - p1, qz = z - p2;
                float radial = (float) Math.sqrt(qx * qx + qz * qz) - p3;
                return (float) Math.sqrt(radial * radial + qy * qy) - p4;
            }
            case CYLINDER: {
                float qx = x - p0, qy = y - p1, qz = z - p2;
                float dx = (float) Math.sqrt(qx * qx + qz * qz) - p3;
                float dy = Math.abs(qy) - p4;
                return Math.min(Math.max(dx, dy), 0) + len2(Math.max(dx, 0), Math.max(dy, 0));
            }
            case CAPSULE: {
                float ax = p0, ay = p1, az = p2, bx = p3, by = p4, bz = p5, r = p6;
                float bax = bx - ax, bay = by - ay, baz = bz - az;
                float len2 = bax * bax + bay * bay + baz * baz;
                float px = x - ax, py = y - ay, pz = z - az;
                float t = len2 > 1e-9f ? clamp((px * bax + py * bay + pz * baz) / len2, 0, 1) : 0;
                float cx = ax + bax * t, cy = ay + bay * t, cz = az + baz * t;
                float dx = x - cx, dy = y - cy, dz = z - cz;
                return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            }
            case PLANE: return p0 * x + p1 * y + p2 * z + p3;
            case CONE: {
                // round cone: centre (p0,p1,p2), bottom radius p3, half-height p4, pointed tip at +y
                float qx = x - p0, qy = y - p1, qz = z - p2;
                float r1 = Math.abs(p3), h = p4;
                float r = (float) Math.sqrt(qx * qx + qz * qz);
                float slope = r1 / Math.max(h, 1e-4f);
                float cosa = (float) Math.sqrt(Math.max(1.0f - slope * slope, 0.0f));
                float k = -slope * r + cosa * qy;
                if (k < 0.0f) return (float) Math.sqrt(r * r + qy * qy) - r1;
                if (k > cosa * h) return (float) Math.sqrt(r * r + (qy - h) * (qy - h));
                return cosa * r + slope * qy - r1;
            }
            case HEX_PRISM: {
                // hexagonal prism: centre (p0,p1,p2), circumradius p3, half-height p4, hexagon in XZ
                float px = x - p0, py = y - p1, pz = z - p2;
                float rad = p3, h = p4;
                float ax = Math.abs(px), az = Math.abs(pz);
                float kx = -0.866025404f, ky = 0.5f, kz = 0.577350269f;
                float dotv = kx * ax + ky * az;
                float t = 2.0f * Math.min(dotv, 0.0f);
                ax -= t * kx; az -= t * ky;
                ax -= clamp(ax, -kz * rad, kz * rad); az -= rad;
                float d = (float) Math.sqrt(ax * ax + az * az) * Math.signum(az);
                float dy = Math.abs(py) - h;
                return Math.min(Math.max(d, dy), 0.0f) + len2(Math.max(d, 0.0f), Math.max(dy, 0.0f));
            }
            case OCTAHEDRON: {
                // octahedron: centre (p0,p1,p2), size p3
                float px = Math.abs(x - p0), py = Math.abs(y - p1), pz = Math.abs(z - p2);
                float s = p3;
                float m = px + py + pz - s;
                float qx, qy, qz;
                if (3.0f * px < m) { qx = px; qy = py; qz = pz; }
                else if (3.0f * py < m) { qx = py; qy = pz; qz = px; }
                else if (3.0f * pz < m) { qx = pz; qy = px; qz = py; }
                else return m * 0.57735027f;
                float k = clamp(0.5f * (qz - qy + s), 0.0f, s);
                return len3(qx, qy - s + k, qz - k);
            }
            case UNION: return Math.min(eval(a, x, y, z), eval(b, x, y, z));
            case INTERSECT: return Math.max(eval(a, x, y, z), eval(b, x, y, z));
            case SUBTRACT: return Math.max(eval(a, x, y, z), -eval(b, x, y, z));
            case SMOOTH_UNION: {
                float da = eval(a, x, y, z), db = eval(b, x, y, z);
                float h = clamp(0.5f + 0.5f * (db - da) / p0, 0, 1);
                return mix(db, da, h) - p0 * h * (1.0f - h);
            }
            case SMOOTH_INTERSECT: {
                float da = eval(a, x, y, z), db = eval(b, x, y, z);
                float h = clamp(0.5f - 0.5f * (db - da) / p0, 0, 1);
                return mix(db, da, h) + p0 * h * (1.0f - h);
            }
            case SMOOTH_SUBTRACT: {
                float da = eval(a, x, y, z), db = eval(b, x, y, z);
                float h = clamp(0.5f - 0.5f * (db + da) / p0, 0, 1);
                return mix(da, -db, h) + p0 * h * (1.0f - h);
            }
            case TRANSLATE: return eval(a, x - p0, y - p1, z - p2);
            case SCALE: return eval(a, x / p0, y / p0, z / p0) * p0;
            case STRETCH: {
                float sx = p0, sy = p1, sz = p2;
                float minScale = Math.min(sx, Math.min(sy, sz));
                return eval(a, x / sx, y / sy, z / sz) * minScale;
            }
            case ROTATE: {
                // stored matrix is the inverse rotation, row-major
                float rx = p0 * x + p1 * y + p2 * z;
                float ry = p3 * x + p4 * y + p5 * z;
                float rz = p6 * x + p7 * y + p8 * z;
                return eval(a, rx, ry, rz);
            }
            case ROUND: return eval(a, x, y, z) - p0;
            case ONION: return Math.abs(eval(a, x, y, z)) - p0;
            case SPIN: {
                // rotate the child around axis (p0,p1,p2) by p3 degrees/second * time
                float angle = (float) Math.toRadians(p3 * time);
                float[] rp = rotatePointInverse(x, y, z, p0, p1, p2, angle);
                return eval(a, rp[0], rp[1], rp[2]);
            }
            case BOB: {
                // translate the child along axis (p0,p1,p2) by p3 * sin(2pi * p4 * time)
                float off = p3 * (float) Math.sin(2.0 * Math.PI * p4 * time);
                return eval(a, x - p0 * off, y - p1 * off, z - p2 * off);
            }
            case PULSE: {
                // uniform scale between p0 and p1 at p2 cycles/second
                float s = mix(p0, p1, 0.5f + 0.5f * (float) Math.sin(2.0 * Math.PI * p2 * time));
                float inv = 1.0f / Math.max(Math.abs(s), 1e-6f);
                return eval(a, x * inv, y * inv, z * inv) * s;
            }
            case MORPH: {
                // cyclical crossfade between two distance fields over `p0` seconds
                float blend = 0.5f + 0.5f * (float) Math.sin(2.0 * Math.PI * time / Math.max(p0, 1e-4f));
                return mix(eval(a, x, y, z), eval(b, x, y, z), clamp(blend, 0, 1));
            }
            case TWIST: {
                // twist the child around +Y by `p0` radians per unit of Y
                float ang = p0 * y;
                float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
                return eval(a, c * x - s * z, y, s * x + c * z);
            }
            case BEND: {
                // bend the child around +Z by `p0` radians per unit of X
                float ang = p0 * x;
                float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
                return eval(a, c * x - s * y, s * x + c * y, z);
            }
            case KEYFRAME_SCALE: {
                // scale the child by a piecewise-linear keyframe track evaluated at `time`:
                // p0 = loop period, p1..p8 = 4 (time, value) pairs.
                float s = keyframeValue(time, p0, p1, p2, p3, p4, p5, p6, p7, p8);
                float inv = 1.0f / Math.max(Math.abs(s), 1e-6f);
                return eval(a, x * inv, y * inv, z * inv) * s;
            }
            case KEYFRAME_TRANSLATE: {
                // 2-keyframe position track: p0=period, (p1..p4)=(t0,x0,y0,z0), (p5..p8)=(t1,x1,y1,z1).
                float f = keyframeFraction(time, p0, p1, p5);
                float tx = mix(p2, p6, f), ty = mix(p3, p7, f), tz = mix(p4, p8, f);
                return eval(a, x - tx, y - ty, z - tz);
            }
            case KEYFRAME_ROTATE: {
                // rotate around (p0,p1,p2) by a keyframed angle: p3=period, (p4,p5)=(t0,angle0), (p6,p7)=(t1,angle1).
                float f = keyframeFraction(time, p3, p4, p6);
                float ang = (float) Math.toRadians(mix(p5, p7, f));
                float[] r = rotatePointInverse(x, y, z, p0, p1, p2, ang);
                return eval(a, r[0], r[1], r[2]);
            }
            default: return 0f;
        }
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

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Piecewise-linear keyframe track: {@code period} is the loop length, then 4 (time, value) pairs. */
    private float keyframeValue(float time, float period,
                                 float t0, float v0, float t1, float v1,
                                 float t2, float v2, float t3, float v3) {
        if (period <= 0f) period = 1f;
        float t = ((time % period) + period) % period;
        if (t <= t0) return v0;
        if (t >= t3) return v3;
        if (t < t1) return mix(v0, v1, applyEasing((t - t0) / Math.max(t1 - t0, 1e-6f)));
        if (t < t2) return mix(v1, v2, applyEasing((t - t1) / Math.max(t2 - t1, 1e-6f)));
        return mix(v2, v3, applyEasing((t - t2) / Math.max(t3 - t2, 1e-6f)));
    }

    /**
     * 2-keyframe fraction over a looping period, with the configured easing curve applied.
     * The track ramps 0→1 from t0 to t1, then 1→0 back to t0 over the rest of the period, so
     * two-keyframe tracks (translate/rotate) loop smoothly instead of snapping at the wrap.
     */
    private float keyframeFraction(float time, float period, float t0, float t1) {
        if (period <= 0f) period = 1f;
        float t = ((time % period) + period) % period;
        if (t <= t0) return 0f;
        if (t < t1) {
            return applyEasing((t - t0) / Math.max(t1 - t0, 1e-6f));
        }
        return 1.0f - applyEasing((t - t1) / Math.max(period - t1, 1e-6f));
    }

    private float applyEasing(float f) {
        f = clamp(f, 0, 1);
        switch (easing) {
            case 1: return f * f * (3.0f - 2.0f * f);       // smoothstep
            case 2: return f * f;                            // easeIn (quadratic)
            case 3: return 1.0f - (1.0f - f) * (1.0f - f);   // easeOut
            case 4: return f * f * (3.0f - 2.0f * f);        // easeInOut
            case 5: return easeBezier(f);                    // cubic bezier
            default: return f;                               // linear
        }
    }

    /** CSS-style cubic-bezier easing: maps the time fraction to a value via control points. */
    private float easeBezier(float f) {
        float p1x = bezier[0], p1y = bezier[1], p2x = bezier[2], p2y = bezier[3];
        float t = f;
        for (int i = 0; i < 4; i++) {
            float omt = 1.0f - t;
            float x = 3.0f * omt * omt * t * p1x + 3.0f * omt * t * t * p2x + t * t * t;
            float dx = 3.0f * omt * omt * p1x + 6.0f * omt * t * (p2x - p1x) + 3.0f * t * t * (1.0f - p2x);
            if (Math.abs(dx) < 1e-6f) break;
            t = clamp(t - (x - f) / dx, 0, 1);
        }
        float omt = 1.0f - t;
        return 3.0f * omt * omt * t * p1y + 3.0f * omt * t * t * p2y + t * t * t;
    }

    /** Rotates a point by {@code -angle} around the (unit) axis — the inverse of a forward rotation. */
    private static float[] rotatePointInverse(float x, float y, float z, float ax, float ay, float az, float angle) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float dot = ax * x + ay * y + az * z;
        float cx = ay * z - az * y;
        float cy = az * x - ax * z;
        float cz = ax * y - ay * x;
        float omc = 1.0f - c;
        return new float[]{
                x * c - cx * s + ax * dot * omc,
                y * c - cy * s + ay * dot * omc,
                z * c - cz * s + az * dot * omc
        };
    }

    // ------------------------------------------------------------------ building

    public static final class Builder {
        private float[] data = new float[64];
        private int size = 0;
        private final List<Integer> nodes = new ArrayList<>();

        public int nodeCount() { return nodes.size(); }

        /** Adds a node in post-order (children must already exist). Returns the new node index. */
        public int addNode(int op, int childA, int childB, float... params) {
            int index = nodes.size();
            nodes.add(index);
            int base = index * STRIDE;
            ensure(base + STRIDE);
            data[base] = op;
            data[base + 1] = childA;
            data[base + 2] = childB;
            for (int i = 0; i < 9; i++) data[base + 3 + i] = i < params.length ? params[i] : 0f;
            return index;
        }

        /** Copies an existing graph into this builder (offsetting indices). Returns the new root index. */
        public int append(SdfGraph graph) {
            int offset = nodes.size();
            int copied = graph.nodeCount * STRIDE;
            ensure(offset * STRIDE + copied);
            System.arraycopy(graph.data, 0, data, offset * STRIDE, copied);
            for (int i = 0; i < graph.nodeCount; i++) {
                int base = (offset + i) * STRIDE;
                int childA = (int) data[base + 1];
                int childB = (int) data[base + 2];
                if (childA >= 0) data[base + 1] = childA + offset;
                if (childB >= 0) data[base + 2] = childB + offset;
                nodes.add(offset + i);
            }
            return graph.rootIndex + offset;
        }

        public int op(int index) {
            return (int) data[index * STRIDE];
        }

        public float param(int index, int i) {
            return data[index * STRIDE + 3 + i];
        }

        public int childA(int index) {
            return (int) data[index * STRIDE + 1];
        }

        public int childB(int index) {
            return (int) data[index * STRIDE + 2];
        }

        /** Removes the last {@code count} nodes (used to drop constant-folded argument subtrees). */
        public void pop(int count) {
            if (count <= 0) return;
            for (int i = 0; i < count; i++) nodes.remove(nodes.size() - 1);
            size = nodes.size() * STRIDE;
        }

        private void ensure(int needed) {
            if (needed > data.length) {
                data = Arrays.copyOf(data, Math.max(needed, data.length * 2));
            }
            size = Math.max(size, needed);
        }

        public SdfGraph build(int rootIndex) {
            float[] copy = Arrays.copyOf(data, nodes.size() * STRIDE);
            SdfBounds bounds = computeBounds(copy, nodes.size(), rootIndex);
            return new SdfGraph(copy, nodes.size(), rootIndex, bounds);
        }
    }

    private static SdfBounds computeBounds(float[] data, int count, int index) {
        int base = index * STRIDE;
        int op = (int) data[base];
        int a = (int) data[base + 1];
        int b = (int) data[base + 2];
        float p0 = data[base + 3], p1 = data[base + 4], p2 = data[base + 5];
        float p3 = data[base + 6], p4 = data[base + 7], p5 = data[base + 8];
        float p6 = data[base + 9], p7 = data[base + 10], p8 = data[base + 11];
        switch (op) {
            case SPHERE:
                return boxBounds(p0 - p3, p1 - p3, p2 - p3, p0 + p3, p1 + p3, p2 + p3);
            case BOX:
                return boxBounds(p0 - p3, p1 - p4, p2 - p5, p0 + p3, p1 + p4, p2 + p5);
            case ROUNDED_BOX:
                return boxBounds(p0 - p3 - p6, p1 - p4 - p6, p2 - p5 - p6, p0 + p3 + p6, p1 + p4 + p6, p2 + p5 + p6);
            case ELLIPSOID:
                return boxBounds(p0 - p3, p1 - p4, p2 - p5, p0 + p3, p1 + p4, p2 + p5);
            case TORUS:
                return boxBounds(p0 - p3 - p4, p1 - p4, p2 - p3 - p4, p0 + p3 + p4, p1 + p4, p2 + p3 + p4);
            case CYLINDER:
                return boxBounds(p0 - p3, p1 - p4, p2 - p3, p0 + p3, p1 + p4, p2 + p3);
            case CAPSULE: {
                float loX = Math.min(p0, p3) - p6, loY = Math.min(p1, p4) - p6, loZ = Math.min(p2, p5) - p6;
                float hiX = Math.max(p0, p3) + p6, hiY = Math.max(p1, p4) + p6, hiZ = Math.max(p2, p5) + p6;
                return boxBounds(loX, loY, loZ, hiX, hiY, hiZ);
            }
            case UNION:
                return childBounds(data, count, a).union(childBounds(data, count, b));
            case INTERSECT:
                return childBounds(data, count, a).intersect(childBounds(data, count, b));
            case SUBTRACT:
            case SMOOTH_SUBTRACT:
                return childBounds(data, count, a);
            case SMOOTH_UNION:
                return childBounds(data, count, a).union(childBounds(data, count, b)).expand(p0);
            case SMOOTH_INTERSECT:
                return childBounds(data, count, a).intersect(childBounds(data, count, b)).expand(p0);
            case TRANSLATE:
                return childBounds(data, count, a).translate(new Vector3f(p0, p1, p2));
            case SCALE:
                return childBounds(data, count, a).scale(new Vector3f(p0, p0, p0));
            case STRETCH:
                return childBounds(data, count, a).scale(new Vector3f(p0, p1, p2));
            case ROTATE:
                return rotateBounds(childBounds(data, count, a), p0, p1, p2, p3, p4, p5, p6, p7, p8);
            case CONE:
                return boxBounds(p0 - p3, p1 - p4, p2 - p3, p0 + p3, p1 + p4, p2 + p3);
            case HEX_PRISM:
                return boxBounds(p0 - p3, p1 - p4, p2 - p3, p0 + p3, p1 + p4, p2 + p3);
            case OCTAHEDRON:
                return boxBounds(p0 - p3, p1 - p3, p2 - p3, p0 + p3, p1 + p3, p2 + p3);
            case ROUND:
                return childBounds(data, count, a).expand(p0);
            case ONION:
                return childBounds(data, count, a).expand(p0);
            case SPIN: {
                SdfBounds child = childBounds(data, count, a);
                Vector3f min = child.min(), max = child.max();
                float r = maxAbs(min.x, max.x, min.y, max.y, min.z, max.z);
                return boxBounds(-r, -r, -r, r, r, r);
            }
            case BOB: {
                SdfBounds child = childBounds(data, count, a);
                Vector3f axis = new Vector3f(p0, p1, p2).normalize();
                Vector3f off = new Vector3f(axis).mul(p3);
                return child.translate(off).union(child.translate(new Vector3f(off).negate()));
            }
            case PULSE: {
                SdfBounds child = childBounds(data, count, a);
                float lo = Math.max(0.0001f, Math.min(p0, p1));
                float hi = Math.max(Math.abs(p0), Math.abs(p1));
                return child.scale(new Vector3f(lo, lo, lo)).union(child.scale(new Vector3f(hi, hi, hi)));
            }
            case MORPH:
                return childBounds(data, count, a).union(childBounds(data, count, b));
            case TWIST:
            case BEND: {
                SdfBounds child = childBounds(data, count, a);
                Vector3f min = child.min(), max = child.max();
                float r = maxAbs(min.x, max.x, min.y, max.y, min.z, max.z);
                return boxBounds(-r, -r, -r, r, r, r);
            }
            case KEYFRAME_SCALE: {
                SdfBounds child = childBounds(data, count, a);
                float vmin = Math.min(Math.min(p1, p3), Math.min(p5, p7));
                float vmax = Math.max(Math.max(Math.abs(p1), Math.abs(p3)), Math.max(Math.abs(p5), Math.abs(p7)));
                float lo = Math.max(0.0001f, Math.min(vmin, vmax));
                float hi = Math.max(vmax, lo);
                return child.scale(new Vector3f(lo, lo, lo)).union(child.scale(new Vector3f(hi, hi, hi)));
            }
            case KEYFRAME_TRANSLATE: {
                SdfBounds child = childBounds(data, count, a);
                return child.translate(new Vector3f(p2, p3, p4)).union(child.translate(new Vector3f(p6, p7, p8)));
            }
            case KEYFRAME_ROTATE: {
                SdfBounds child = childBounds(data, count, a);
                Vector3f min = child.min(), max = child.max();
                float r = maxAbs(min.x, max.x, min.y, max.y, min.z, max.z);
                return boxBounds(-r, -r, -r, r, r, r);
            }
            default:
                return SdfBounds.infinite();
        }
    }

    private static SdfBounds childBounds(float[] data, int count, int child) {
        return child >= 0 ? computeBounds(data, count, child) : SdfBounds.infinite();
    }

    private static SdfBounds boxBounds(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new SdfBounds(new Vector3f(x0, y0, z0), new Vector3f(x1, y1, z1));
    }

    private static float maxAbs(float... values) {
        float m = 0.0f;
        for (float v : values) m = Math.max(m, Math.abs(v));
        return m;
    }

    private static SdfBounds rotateBounds(SdfBounds bounds, float m0, float m1, float m2, float m3, float m4, float m5, float m6, float m7, float m8) {
        // stored matrix is the inverse rotation; the forward transform is its transpose.
        Vector3f low = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f high = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f min = bounds.min(), max = bounds.max();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            float cx = x == 0 ? min.x : max.x;
            float cy = y == 0 ? min.y : max.y;
            float cz = z == 0 ? min.z : max.z;
            float rx = m0 * cx + m3 * cy + m6 * cz; // transpose: row i <-> col i
            float ry = m1 * cx + m4 * cy + m7 * cz;
            float rz = m2 * cx + m5 * cy + m8 * cz;
            low.min(new Vector3f(rx, ry, rz));
            high.max(new Vector3f(rx, ry, rz));
        }
        return new SdfBounds(low, high);
    }

    /** The maximum number of nodes a single serialized graph may contain (uniform budget). */
    public static int maxNodes() { return MAX_NODES; }
}
