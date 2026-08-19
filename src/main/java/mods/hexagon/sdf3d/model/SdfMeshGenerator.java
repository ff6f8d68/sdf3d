package mods.hexagon.sdf3d.model;

import mods.hexagon.sdf3d.sdf.SdfBounds;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an SDF model into a coarse voxel mesh so the item icon (and any GUI preview) shows the
 * real shape instead of a placeholder cube. This is only for non-world rendering; in the world
 * the same model is raymarched by the GPU path, never meshed.
 *
 * <p>Each surface quad is shaded with the SDF's analytic gradient at the cell centre rather than
 * its flat face normal, so the lighting reads as a smooth rounded surface even though the
 * geometry is still a voxel grid.</p>
 */
public final class SdfMeshGenerator {
    private static final int RES = 128;

    private SdfMeshGenerator() {}

    public static List<BakedQuad> generate(SdfModel model, TextureAtlasSprite sprite) {
        SdfBounds bounds = model.bounds();
        Vector3f min = new Vector3f(bounds.min());
        Vector3f max = new Vector3f(bounds.max());
        if (!bounds.isFinite()) {
            min.set(-1.0f, -1.0f, -1.0f);
            max.set(1.0f, 1.0f, 1.0f);
        }
        min.sub(0.02f, 0.02f, 0.02f);
        max.add(0.02f, 0.02f, 0.02f);

        float[] xs = axis(min.x(), max.x());
        float[] ys = axis(min.y(), max.y());
        float[] zs = axis(min.z(), max.z());

        boolean[][][] inside = new boolean[RES][RES][RES];
        Vector3f p = new Vector3f();
        for (int i = 0; i < RES; i++) {
            for (int j = 0; j < RES; j++) {
                for (int k = 0; k < RES; k++) {
                    p.set((xs[i] + xs[i + 1]) * 0.5f, (ys[j] + ys[j + 1]) * 0.5f, (zs[k] + zs[k + 1]) * 0.5f);
                    inside[i][j][k] = model.distance(p) < 0.0f;
                }
            }
        }

        int color = toABGR(model.material().tint());
        List<BakedQuad> quads = new ArrayList<>();
        for (int i = 0; i < RES; i++) {
            for (int j = 0; j < RES; j++) {
                for (int k = 0; k < RES; k++) {
                    if (!inside[i][j][k]) continue;
                    float x0 = xs[i], x1 = xs[i + 1];
                    float y0 = ys[j], y1 = ys[j + 1];
                    float z0 = zs[k], z1 = zs[k + 1];

                    // Analytic surface normal from the distance field's gradient.
                    Vector3f smooth = gradient(model, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f);

                    if (i + 1 >= RES || !inside[i + 1][j][k])
                        add(quads, sprite, color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0, smooth);
                    if (i == 0 || !inside[i - 1][j][k])
                        add(quads, sprite, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, smooth);
                    if (j + 1 >= RES || !inside[i][j + 1][k])
                        add(quads, sprite, color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, smooth);
                    if (j == 0 || !inside[i][j - 1][k])
                        add(quads, sprite, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, smooth);
                    if (k + 1 >= RES || !inside[i][j][k + 1])
                        add(quads, sprite, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, smooth);
                    if (k == 0 || !inside[i][j][k - 1])
                        add(quads, sprite, color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1, smooth);
                }
            }
        }
        return quads;
    }

    private static float[] axis(float min, float max) {
        float[] out = new float[RES + 1];
        for (int i = 0; i <= RES; i++) out[i] = min + (max - min) * ((float) i / RES);
        return out;
    }

    /** Outward-pointing surface normal from the SDF gradient, or {@code null} on a degenerate gradient. */
    private static Vector3f gradient(SdfModel model, float x, float y, float z) {
        float e = 0.03f;
        Vector3f tmp = new Vector3f();
        float dx = model.distance(tmp.set(x + e, y, z)) - model.distance(tmp.set(x - e, y, z));
        float dy = model.distance(tmp.set(x, y + e, z)) - model.distance(tmp.set(x, y - e, z));
        float dz = model.distance(tmp.set(x, y, z + e)) - model.distance(tmp.set(x, y, z - e));
        Vector3f n = new Vector3f(dx, dy, dz);
        if (n.lengthSquared() < 1e-12f) return null;
        return n.normalize();
    }

    private static void add(List<BakedQuad> quads, TextureAtlasSprite sprite, int color,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float nx, float ny, float nz, Vector3f smoothNormal) {
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float[] pos = {ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz};
        int[] data = new int[32];
        float gx = smoothNormal != null ? smoothNormal.x : nx;
        float gy = smoothNormal != null ? smoothNormal.y : ny;
        float gz = smoothNormal != null ? smoothNormal.z : nz;
        int normal = packNormal(gx, gy, gz);
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            data[o] = Float.floatToRawIntBits(pos[i * 3]);
            data[o + 1] = Float.floatToRawIntBits(pos[i * 3 + 1]);
            data[o + 2] = Float.floatToRawIntBits(pos[i * 3 + 2]);
            data[o + 3] = color;
            data[o + 4] = Float.floatToRawIntBits(u0);
            data[o + 5] = Float.floatToRawIntBits(v0);
            data[o + 6] = 0;
            data[o + 7] = normal;
        }
        quads.add(new BakedQuad(data, -1, directionFor(nx, ny, nz), sprite, false));
    }

    private static Direction directionFor(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax >= ay && ax >= az) return nx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= az) return ny > 0 ? Direction.UP : Direction.DOWN;
        return nz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int packNormal(float nx, float ny, float nz) {
        int x = ((byte) Math.round(nx * 127.0f)) & 0xFF;
        int y = ((byte) Math.round(ny * 127.0f)) & 0xFF;
        int z = ((byte) Math.round(nz * 127.0f)) & 0xFF;
        return x | (y << 8) | (z << 16);
    }

    /** ARGB -> ABGR, the format vertex colors are actually packed into. */
    private static int toABGR(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb << 16) & 0xFF0000);
    }

}
