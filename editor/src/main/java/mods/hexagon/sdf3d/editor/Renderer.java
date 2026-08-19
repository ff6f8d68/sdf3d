package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfBounds;
import mods.hexagon.sdf3d.sdf.SdfModel;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.stream.IntStream;

/**
 * Software renderer: sphere-traces the model into an ARGB pixel buffer with triplanar UV,
 * texture sampling, a directional key light + soft shadow, and a UV-grid debug mode. Rows are
 * traced in parallel so a large graph stays interactive on multi-core machines.
 */
public final class Renderer {
    public static final int BACKGROUND = 0xFF17181C;
    private static final Vector3f LIGHT_DIR = new Vector3f(0.45f, 0.8f, 0.35f).normalize();

    private Renderer() {}

    public static int[] render(SdfModel model, TextureStore texture, Camera camera,
                               int width, int height, float uvScale, boolean showUvGrid) {
        int[] out = new int[width * height];
        float aspect = (float) width / Math.max(1, height);
        float maxDist = marchRadius(model);
        float epsilon = Math.max(0.0002f, maxDist * 0.00005f);
        Vector3f eye = camera.eye(new Vector3f());

        fillBackground(out, width, height, camera, aspect);

        IntStream.range(0, height).parallel().forEach(y -> {
            float v = 1f - (y + 0.5f) / height * 2f; // +1 top
            Vector3f dir = new Vector3f();
            Vector2f uv = new Vector2f();
            Vector3f hitPos = new Vector3f();
            Vector3f normal = new Vector3f();
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width * 2f - 1f;
                camera.ray(u, v, aspect, dir);
                SdfRaycaster.Hit hit = SdfRaycaster.march(model, eye, dir, maxDist, 512, epsilon);
                if (hit == null) continue; // keep grid/sky background
                hitPos.set(hit.position());
                normal.set(hit.normal());
                triplanarUv(normal, hitPos, uv, uvScale);
                int albedo = showUvGrid ? uvGridColor(uv) : sample(texture, model, uv);
                out[y * width + x] = shade(albedo, normal, dir, hitPos, model, maxDist, epsilon);
            }
        });
        return out;
    }

    /** Small marching epsilon scaled to the scene (shared with picking so hits stay consistent). */
    public static float epsilon(SdfModel model) {
        return Math.max(0.0002f, marchRadius(model) * 0.00005f);
    }

    /** World radius used as the raymarch far plane (bounds diagonal + margin). */
    public static float marchRadius(SdfModel model) {
        SdfBounds bounds = model.bounds();
        Vector3f min = bounds.min();
        Vector3f max = bounds.max();
        if (!Float.isFinite(min.x()) || !Float.isFinite(max.x())) return 16f;
        Vector3f half = new Vector3f(max).sub(min).mul(0.5f);
        return Math.max(1f, half.length() + 0.5f) * 2f;
    }

    public static void triplanarUv(Vector3f normal, Vector3f position, Vector2f out, float uvScale) {
        float ax = Math.abs(normal.x());
        float ay = Math.abs(normal.y());
        float az = Math.abs(normal.z());
        if (ax >= ay && ax >= az) out.set(position.z() * uvScale, position.y() * uvScale);
        else if (ay >= az) out.set(position.x() * uvScale, position.z() * uvScale);
        else out.set(position.x() * uvScale, position.y() * uvScale);
    }

    private static int sample(TextureStore texture, SdfModel model, Vector2f uv) {
        if (texture != null) return texture.sample(uv.x(), uv.y());
        return model.material(null).tint();
    }

    private static int shade(int albedo, Vector3f normal, Vector3f rayDir,
                             Vector3f hitPos, SdfModel model, float maxDist, float epsilon) {
        float diff = Math.max(0f, normal.dot(LIGHT_DIR));
        Vector3f shadowOrigin = new Vector3f(hitPos).fma(epsilon * 8f, normal);
        float shadow = SdfRaycaster.softShadow(model, shadowOrigin, LIGHT_DIR, maxDist, 128, epsilon, 12f);

        Vector3f half = new Vector3f(LIGHT_DIR).sub(rayDir).normalize();
        float spec = (float) Math.pow(Math.max(0f, normal.dot(half)), 40f) * 0.4f;

        float light = 0.38f + 0.62f * diff * shadow;
        int ir = (int) Math.min(255f, ((albedo >>> 16) & 0xFF) * light + 255f * spec);
        int ig = (int) Math.min(255f, ((albedo >>> 8) & 0xFF) * light + 255f * spec);
        int ib = (int) Math.min(255f, (albedo & 0xFF) * light + 255f * spec);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    /** Procedural UV grid so the user can see how auto-UVs wrap the surface. */
    private static int uvGridColor(Vector2f uv) {
        int tx = Math.floorMod((int) Math.floor(uv.x() * 8f), 8);
        int ty = Math.floorMod((int) Math.floor(uv.y() * 8f), 8);
        boolean line = tx == 0 || ty == 0;
        return line ? 0xFF222222 : ((tx + ty) & 1) == 0 ? 0xFFFFFFFF : 0xFFC8C8C8;
    }

    // ------------------------------------------------------------------ background grid + axes

    private static void fillBackground(int[] out, int width, int height, Camera camera, float aspect) {
        int skyTop = 0xFF0F1013;
        int skyBottom = 0xFF26282F;
        for (int y = 0; y < height; y++) {
            float t = (float) y / Math.max(1, height - 1);
            int c = lerpColor(skyBottom, skyTop, t);
            for (int x = 0; x < width; x++) out[y * width + x] = c;
        }

        Projector pr = new Projector(camera, aspect, width, height);
        float extent = Math.max(4f, camera.distance() * 1.3f);
        float step = gridStep(camera.distance());
        for (float k = -extent; k <= extent + 1e-3f; k += step) {
            if (Math.abs(k) < step * 0.5f) continue; // origin lines drawn as axes below
            boolean major = Math.abs(k - Math.round(k)) < 1e-3f;
            int color = major ? 0xFF3A3D44 : 0xFF24262C;
            pr.segment(new Vector3f(-extent, 0, k), new Vector3f(extent, 0, k), color, out, width, height);
            pr.segment(new Vector3f(k, 0, -extent), new Vector3f(k, 0, extent), color, out, width, height);
        }

        // world axes: X red, Y green, Z blue
        pr.segment(new Vector3f(0, 0, 0), new Vector3f(extent, 0, 0), 0xFFC04040, out, width, height);
        pr.segment(new Vector3f(0, 0, 0), new Vector3f(0, extent, 0), 0xFF40C040, out, width, height);
        pr.segment(new Vector3f(0, 0, 0), new Vector3f(0, 0, extent), 0xFF4060C0, out, width, height);
    }

    private static float gridStep(float distance) {
        if (distance > 24f) return 2f;
        if (distance > 10f) return 1f;
        return 0.5f;
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Camera basis + perspective projection so background lines match the raymarched pixels. */
    private static final class Projector {
        private final Vector3f eye = new Vector3f();
        private final Vector3f forward = new Vector3f();
        private final Vector3f right = new Vector3f();
        private final Vector3f up = new Vector3f();
        private final float tanF;
        private final float aspect;
        private final int width;
        private final int height;

        Projector(Camera camera, float aspect, int width, int height) {
            camera.eye(eye);
            camera.forward(forward);
            right.set(forward).cross(0f, 1f, 0f).normalize();
            up.set(right).cross(forward).normalize();
            this.tanF = (float) Math.tan(Math.toRadians(camera.fovDegrees() * 0.5f));
            this.aspect = aspect;
            this.width = width;
            this.height = height;
        }

        boolean project(Vector3f world, float[] out) {
            float dx = world.x() - eye.x(), dy = world.y() - eye.y(), dz = world.z() - eye.z();
            float z = dx * forward.x() + dy * forward.y() + dz * forward.z();
            if (z < 0.02f) return false;
            float x = dx * right.x() + dy * right.y() + dz * right.z();
            float y = dx * up.x() + dy * up.y() + dz * up.z();
            float u = x / z / (tanF * aspect);
            float v = -y / z / tanF;
            out[0] = (u * 0.5f + 0.5f) * width;
            out[1] = (0.5f - v * 0.5f) * height;
            return true;
        }

        void segment(Vector3f a, Vector3f b, int color, int[] out, int width, int height) {
            float[] pa = new float[2], pb = new float[2];
            if (!project(a, pa) || !project(b, pb)) return;
            drawLine(out, width, height, pa[0], pa[1], pb[0], pb[1], color);
        }
    }

    private static void drawLine(int[] out, int w, int h, float x0, float y0, float x1, float y1, int color) {
        int ix0 = Math.round(x0), iy0 = Math.round(y0);
        int ix1 = Math.round(x1), iy1 = Math.round(y1);
        int dx = Math.abs(ix1 - ix0), sx = ix0 < ix1 ? 1 : -1;
        int dy = -Math.abs(iy1 - iy0), sy = iy0 < iy1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            if (ix0 >= 0 && ix0 < w && iy0 >= 0 && iy0 < h) out[iy0 * w + ix0] = color;
            if (ix0 == ix1 && iy0 == iy1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; ix0 += sx; }
            if (e2 <= dx) { err += dx; iy0 += sy; }
        }
    }
}
