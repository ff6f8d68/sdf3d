package mods.hexagon.sdf3d.editor;

import org.joml.Vector3f;

/**
 * Viewport transform gizmo: 3-axis translate arrows, 3-axis rotation rings, and a pivot/center
 * marker. Draws directly into the raymarched pixel buffer so the gizmo shares the same camera
 * projection as the model, and hit-tests screen-space handles so the editor can drag them.
 */
public final class Gizmo {
    private Gizmo() {}

    public enum Mode { TRANSLATE, ROTATE }

    public enum Handle { NONE, AXIS_X, AXIS_Y, AXIS_Z, RING_X, RING_Y, RING_Z }

    private static final int HIT_RADIUS = 12;      // px
    // Camera distance is ~3.2x the model radius (frameCamera), so 0.4x distance ≈ 1.3x the model
    // radius — big enough that the arrows and rings stick OUT of the model and remain grabbable.
    private static final float ARROW_FRAC = 0.40f;
    private static final float MIN_SIZE = 0.25f;

    private static final Vector3f X = new Vector3f(1, 0, 0);
    private static final Vector3f Y = new Vector3f(0, 1, 0);
    private static final Vector3f Z = new Vector3f(0, 0, 1);

    private static final int AXIS_COLORS[] = {0xFFE05555, 0xFF55D155, 0xFF5588E0};
    private static final int HOVER_COLOR = 0xFFFFE066;
    private static final int PIVOT_COLOR = 0xFFF0F0F0;

    // ------------------------------------------------------------------ rendering

    public static void render(int[] out, int w, int h, Camera cam, float aspect,
                              Vector3f pivot, Mode mode, Handle hover) {
        float len = size(cam);
        drawPivotMarker(out, w, h, cam, aspect, pivot, len);
        if (mode == Mode.TRANSLATE) {
            for (int i = 0; i < 3; i++) {
                Vector3f axis = axis(i);
                int color = hover == axisHandle(i) ? HOVER_COLOR : AXIS_COLORS[i];
                drawArrow(out, w, h, cam, aspect, pivot, axis, len, color);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                int color = hover == ringHandle(i) ? HOVER_COLOR : AXIS_COLORS[i];
                drawRing(out, w, h, cam, aspect, pivot, axis(i), len, color, hover == ringHandle(i));
            }
        }
    }

    private static void drawPivotMarker(int[] out, int w, int h, Camera cam, float aspect,
                                        Vector3f pivot, float len) {
        float[] p = new float[2];
        if (!project(pivot, cam, aspect, w, h, p)) return;
        // small dot + 4-point cross
        fillDot(out, w, h, (int) p[0], (int) p[1], 2, PIVOT_COLOR);
        int r = 6;
        line(out, w, h, (int) p[0] - r, (int) p[1], (int) p[0] + r, (int) p[1], PIVOT_COLOR);
        line(out, w, h, (int) p[0], (int) p[1] - r, (int) p[0], (int) p[1] + r, PIVOT_COLOR);
        // short 3D axis stubs through the pivot
        float stub = len * 0.14f;
        for (int i = 0; i < 3; i++) {
            Vector3f a = axis(i);
            segment(out, w, h, cam, aspect, new Vector3f(pivot).sub(a.mul(stub, new Vector3f())),
                    new Vector3f(pivot).add(a.mul(stub, new Vector3f())), AXIS_COLORS[i]);
        }
    }

    private static void drawArrow(int[] out, int w, int h, Camera cam, float aspect,
                                  Vector3f pivot, Vector3f axis, float len, int color) {
        Vector3f tip = new Vector3f(pivot).add(axis.mul(len, new Vector3f()));
        segment(out, w, h, cam, aspect, pivot, tip, color);

        // cone head: 4 spokes back from the tip in the plane perpendicular to the axis
        Vector3f u = perp(axis, true);
        Vector3f v = new Vector3f(axis).cross(u).normalize();
        float headLen = len * 0.28f;
        float headW = len * 0.10f;
        Vector3f base = new Vector3f(tip).sub(axis.mul(headLen, new Vector3f()));
        for (int i = 0; i < 4; i++) {
            float s = (i & 1) == 0 ? 1f : -1f;
            Vector3f dir = (i < 2 ? u : v);
            Vector3f corner = new Vector3f(base).add(dir.mul(headW * s, new Vector3f()));
            segment(out, w, h, cam, aspect, tip, corner, color);
        }
    }

    private static void drawRing(int[] out, int w, int h, Camera cam, float aspect,
                                 Vector3f pivot, Vector3f axis, float radius, int color, boolean thick) {
        Vector3f u = perp(axis, true);
        Vector3f v = new Vector3f(axis).cross(u).normalize();
        int N = 64;
        float[] prev = new float[2];
        float[] cur = new float[2];
        boolean havePrev = false;
        for (int i = 0; i <= N; i++) {
            float t = (float) (i / (double) N * Math.PI * 2.0);
            Vector3f p = new Vector3f(pivot)
                    .fma((float) Math.cos(t) * radius, u)
                    .fma((float) Math.sin(t) * radius, v);
            if (!project(p, cam, aspect, w, h, cur)) { havePrev = false; continue; }
            if (havePrev) {
                line(out, w, h, (int) prev[0], (int) prev[1], (int) cur[0], (int) cur[1], color);
                if (thick) {
                    line(out, w, h, (int) prev[0] + 1, (int) prev[1], (int) cur[0] + 1, (int) cur[1], color);
                    line(out, w, h, (int) prev[0], (int) prev[1] + 1, (int) cur[0], (int) cur[1] + 1, color);
                }
            }
            prev[0] = cur[0];
            prev[1] = cur[1];
            havePrev = true;
        }
    }

    // ------------------------------------------------------------------ hit testing

    public static Handle hitTest(Camera cam, float aspect, int w, int h, Vector3f pivot,
                                 Mode mode, float px, float py) {
        if (mode == Mode.TRANSLATE) {
            for (int i = 0; i < 3; i++) {
                Vector3f tip = new Vector3f(pivot).add(axis(i).mul(size(cam), new Vector3f()));
                float[] a = new float[2], b = new float[2];
                if (project(pivot, cam, aspect, w, h, a) && project(tip, cam, aspect, w, h, b)) {
                    if (distToSeg(px, py, a[0], a[1], b[0], b[1]) <= HIT_RADIUS
                            || dist(px, py, b[0], b[1]) <= HIT_RADIUS) {
                        return axisHandle(i);
                    }
                }
            }
            return Handle.NONE;
        }
        for (int i = 0; i < 3; i++) {
            if (ringDistance(cam, aspect, w, h, pivot, axis(i), px, py) <= HIT_RADIUS) {
                return ringHandle(i);
            }
        }
        return Handle.NONE;
    }

    private static float ringDistance(Camera cam, float aspect, int w, int h, Vector3f pivot,
                                      Vector3f axis, float px, float py) {
        Vector3f u = perp(axis, true);
        Vector3f v = new Vector3f(axis).cross(u).normalize();
        float radius = size(cam);
        int N = 64;
        float best = Float.MAX_VALUE;
        float[] prev = new float[2];
        float[] cur = new float[2];
        boolean havePrev = false;
        for (int i = 0; i <= N; i++) {
            float t = (float) (i / (double) N * Math.PI * 2.0);
            Vector3f p = new Vector3f(pivot)
                    .fma((float) Math.cos(t) * radius, u)
                    .fma((float) Math.sin(t) * radius, v);
            if (!project(p, cam, aspect, w, h, cur)) { havePrev = false; continue; }
            if (havePrev) best = Math.min(best, distToSeg(px, py, prev[0], prev[1], cur[0], cur[1]));
            prev[0] = cur[0];
            prev[1] = cur[1];
            havePrev = true;
        }
        return best;
    }

    // ------------------------------------------------------------------ dragging

    /**
     * World point where the mouse ray (NDC u,v) pierces the drag plane through {@code pivot}
     * containing {@code axis} and facing the camera. Anchor this plane at the start of a drag.
     */
    public static Vector3f planeHit(Camera cam, float aspect, Vector3f pivot, Vector3f axis,
                                    float u, float v) {
        Vector3f eye = cam.eye(new Vector3f());
        Vector3f fwd = cam.forward(new Vector3f());
        Vector3f f = new Vector3f(fwd).fma(-fwd.dot(axis), axis);
        if (f.lengthSquared() < 1e-4f) f.set(0, 1, 0);
        f.normalize();
        Vector3f n = new Vector3f(axis).cross(f).normalize();
        Vector3f dir = cam.ray(u, v, aspect, new Vector3f());
        float denom = n.dot(dir);
        if (Math.abs(denom) < 1e-5f) return null;
        float t = n.dot(new Vector3f(pivot).sub(eye)) / denom;
        return new Vector3f(dir).mul(t).add(eye);
    }

    /** Screen-space angle (radians) of a pixel around the projected pivot, for rotation drags. */
    public static float screenAngle(Camera cam, float aspect, int w, int h, Vector3f pivot,
                                    float px, float py) {
        float[] c = new float[2];
        if (!project(pivot, cam, aspect, w, h, c)) return 0f;
        return (float) Math.atan2(py - c[1], px - c[0]);
    }

    /** +1 or -1 so dragging right rotates clockwise/anticlockwise naturally for the viewed axis. */
    public static float rotationSign(Camera cam, Vector3f axis) {
        return axis.dot(cam.forward(new Vector3f())) >= 0f ? -1f : 1f;
    }

    // ------------------------------------------------------------------ helpers

    private static Vector3f axis(int i) {
        return switch (i) { case 0 -> X; case 1 -> Y; default -> Z; };
    }

    private static Handle axisHandle(int i) {
        return switch (i) { case 0 -> Handle.AXIS_X; case 1 -> Handle.AXIS_Y; default -> Handle.AXIS_Z; };
    }

    private static Handle ringHandle(int i) {
        return switch (i) { case 0 -> Handle.RING_X; case 1 -> Handle.RING_Y; default -> Handle.RING_Z; };
    }

    private static Vector3f perp(Vector3f axis, boolean unused) {
        Vector3f ref = Math.abs(axis.y()) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        return new Vector3f(axis).cross(ref).normalize();
    }

    private static float size(Camera cam) {
        return Math.max(MIN_SIZE, cam.distance() * ARROW_FRAC);
    }

    private static boolean project(Vector3f world, Camera cam, float aspect, int w, int h, float[] out) {
        Vector3f eye = cam.eye(new Vector3f());
        Vector3f fwd = cam.forward(new Vector3f());
        Vector3f right = new Vector3f(fwd).cross(0f, 1f, 0f).normalize();
        Vector3f up = new Vector3f(right).cross(fwd).normalize();
        float tanF = (float) Math.tan(Math.toRadians(cam.fovDegrees() * 0.5f));
        float dx = world.x() - eye.x(), dy = world.y() - eye.y(), dz = world.z() - eye.z();
        float z = dx * fwd.x() + dy * fwd.y() + dz * fwd.z();
        if (z < 0.02f) return false;
        float x = dx * right.x() + dy * right.y() + dz * right.z();
        float y = dx * up.x() + dy * up.y() + dz * up.z();
        float u = x / z / (tanF * aspect);
        float v = -y / z / tanF;
        out[0] = (u * 0.5f + 0.5f) * w;
        out[1] = (0.5f - v * 0.5f) * h;
        return true;
    }

    private static void segment(int[] out, int w, int h, Camera cam, float aspect,
                                Vector3f a, Vector3f b, int color) {
        float[] pa = new float[2], pb = new float[2];
        if (!project(a, cam, aspect, w, h, pa) || !project(b, cam, aspect, w, h, pb)) return;
        line(out, w, h, (int) pa[0], (int) pa[1], (int) pb[0], (int) pb[1], color);
    }

    private static void line(int[] out, int w, int h, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            if (x0 >= 0 && x0 < w && y0 >= 0 && y0 < h) out[y0 * w + x0] = color;
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static void fillDot(int[] out, int w, int h, int cx, int cy, int r, int color) {
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (x >= 0 && x < w && y >= 0 && y < h) out[y * w + x] = color;
            }
        }
    }

    private static float distToSeg(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / len2));
        return dist(px, py, ax + t * dx, ay + t * dy);
    }

    private static float dist(float px, float py, float x, float y) {
        return (float) Math.hypot(px - x, py - y);
    }
}
