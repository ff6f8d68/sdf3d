package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replicates the GLSL {@code evalSdf} two-pass evaluator in Java and verifies it agrees
 * with the recursive {@link SdfGraph#eval} used by the CPU raymarcher. If these diverge,
 * the GPU overlay will render nothing even though the CPU diagnostic ray trace hits.
 */
class SdfGpuEvaluatorTest {

    @Test
    void twoPassEvaluatorMatchesRecursiveEval() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "smooth_union(0.12, rounded_box(0, 0, 0, 0.5, 0.35, 0.5, 0.08), sphere(0.45, 0.35, 0, 0.4))");
        SdfGraph graph = (SdfGraph) model.function();
        assertTwoPassMatches(graph);

        // A model exercising transforms + arithmetic + coordinate leaves.
        SdfModel model2 = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "union(translate(1, 2, 3, sphere(0, 0, 0, 0.5)), scale(2, 1, 1, box(0, 0, 0, 0.4, 0.4, 0.4)))");
        assertTwoPassMatches((SdfGraph) model2.function());

        SdfModel model3 = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "rotate(45, 30, 15, subtract(round(0.1, sphere(0, 0, 0, 1)), box(0, 0.5, 0, 1.5, 0.3, 1.5)))");
        assertTwoPassMatches((SdfGraph) model3.function());
    }

    private static void assertTwoPassMatches(SdfGraph graph) {
        float[] data = graph.data();
        int count = graph.nodeCount();
        int root = graph.rootIndex();
        Random rng = new Random(1234);
        for (int i = 0; i < 20000; i++) {
            float x = (rng.nextFloat() - 0.5f) * 6.0f;
            float y = (rng.nextFloat() - 0.5f) * 6.0f;
            float z = (rng.nextFloat() - 0.5f) * 6.0f;
            float recursive = graph.eval(root, x, y, z);
            float twoPass = evalTwoPass(data, count, root, x, y, z);
            assertEquals(recursive, twoPass, 1e-3f,
                    "two-pass diverged at (" + x + "," + y + "," + z + ")");
        }
    }

    // --- GLSL evalSdf, translated line-for-line -----------------------------------------

    private static float nodeVal(float[] data, int base, int k) {
        return data[base + k];
    }

    private static float leafValue(int op, float[] p, float p0, float p1, float p2,
                                   float p3, float p4, float p5, float p6) {
        switch (op) {
            case SdfGraph.CONST: return p0;
            case SdfGraph.X: return p[0];
            case SdfGraph.Y: return p[1];
            case SdfGraph.Z: return p[2];
            case SdfGraph.SPHERE:
                return len3(p[0] - p0, p[1] - p1, p[2] - p2) - p3;
            case SdfGraph.BOX: {
                float qx = Math.abs(p[0] - p0) - p3;
                float qy = Math.abs(p[1] - p1) - p4;
                float qz = Math.abs(p[2] - p2) - p5;
                return len3(Math.max(qx, 0), Math.max(qy, 0), Math.max(qz, 0))
                        + Math.min(Math.max(qx, Math.max(qy, qz)), 0);
            }
            case SdfGraph.ROUNDED_BOX: {
                float qx = Math.abs(p[0] - p0) - p3;
                float qy = Math.abs(p[1] - p1) - p4;
                float qz = Math.abs(p[2] - p2) - p5;
                return len3(Math.max(qx, 0), Math.max(qy, 0), Math.max(qz, 0))
                        + Math.min(Math.max(qx, Math.max(qy, qz)), 0) - p6;
            }
            case SdfGraph.ELLIPSOID: {
                float qx = (p[0] - p0) / p3, qy = (p[1] - p1) / p4, qz = (p[2] - p2) / p5;
                return (len3(qx, qy, qz) - 1.0f) * Math.min(p3, Math.min(p4, p5));
            }
            case SdfGraph.TORUS: {
                float qx = len2(p[0] - p0, p[2] - p2) - p3;
                float qy = p[1] - p1;
                return len2(qx, qy) - p4;
            }
            case SdfGraph.CYLINDER: {
                float d = len2(p[0] - p0, p[2] - p2) - p3;
                float dy = Math.abs(p[1] - p1) - p4;
                return Math.min(Math.max(d, dy), 0) + len2(Math.max(d, 0), Math.max(dy, 0));
            }
            case SdfGraph.CAPSULE: {
                float bax = p3 - p0, bay = p4 - p1, baz = p5 - p2;
                float h = clamp(dot3(p[0] - p0, p[1] - p1, p[2] - p2, bax, bay, baz)
                        / Math.max(bax * bax + bay * bay + baz * baz, 1e-9f), 0, 1);
                return len3(p[0] - (p0 + bax * h), p[1] - (p1 + bay * h), p[2] - (p2 + baz * h)) - p6;
            }
            case SdfGraph.PLANE: return p0 * p[0] + p1 * p[1] + p2 * p[2] + p3;
            default: return 0f;
        }
    }

    private static float[] transformPoint(int op, float[] p, float p0, float p1, float p2,
                                          float p3, float p4, float p5, float p6, float p7, float p8) {
        switch (op) {
            case SdfGraph.TRANSLATE: return new float[]{p[0] - p0, p[1] - p1, p[2] - p2};
            case SdfGraph.SCALE: return new float[]{p[0] / p0, p[1] / p0, p[2] / p0};
            case SdfGraph.STRETCH: return new float[]{p[0] / p0, p[1] / p1, p[2] / p2};
            case SdfGraph.ROTATE:
                return new float[]{
                        p0 * p[0] + p1 * p[1] + p2 * p[2],
                        p3 * p[0] + p4 * p[1] + p5 * p[2],
                        p6 * p[0] + p7 * p[1] + p8 * p[2]};
            default: return p;
        }
    }

    private static float adjustTransform(int op, float v, float p0, float p1, float p2) {
        if (op == SdfGraph.SCALE) return v * p0;
        if (op == SdfGraph.STRETCH) return v * Math.min(p0, Math.min(p1, p2));
        return v;
    }

    private static float adjustUnary(int op, float v, float p0) {
        if (op == SdfGraph.NEG) return -v;
        if (op == SdfGraph.ABS) return Math.abs(v);
        if (op == SdfGraph.ROUND) return v - p0;
        if (op == SdfGraph.ONION) return Math.abs(v) - p0;
        return v;
    }

    private static float binaryValue(int op, float va, float vb, float k) {
        switch (op) {
            case SdfGraph.ADD: return va + vb;
            case SdfGraph.SUB: return va - vb;
            case SdfGraph.MUL: return va * vb;
            case SdfGraph.DIV: return va / (Math.abs(vb) < 1e-9f ? 1e-9f : vb);
            case SdfGraph.MIN:
            case SdfGraph.UNION: return Math.min(va, vb);
            case SdfGraph.MAX:
            case SdfGraph.INTERSECT: return Math.max(va, vb);
            case SdfGraph.SUBTRACT: return Math.max(va, -vb);
            case SdfGraph.SMOOTH_UNION: {
                float h = clamp(0.5f + 0.5f * (vb - va) / k, 0, 1);
                return va * h + vb * (1 - h) - k * h * (1 - h);
            }
            default: return Math.min(va, vb);
        }
    }

    private static float evalTwoPass(float[] data, int nodeCount, int root, float px, float py, float pz) {
        float[][] pt = new float[nodeCount][3];
        float[] value = new float[nodeCount];

        pt[root][0] = px;
        pt[root][1] = py;
        pt[root][2] = pz;
        for (int i = nodeCount - 1; i >= 0; i--) {
            int base = i * 12;
            int op = (int) nodeVal(data, base, 0);
            int a = (int) nodeVal(data, base, 1);
            int b = (int) nodeVal(data, base, 2);
            float p0 = nodeVal(data, base, 3), p1 = nodeVal(data, base, 4), p2 = nodeVal(data, base, 5);
            float p3 = nodeVal(data, base, 6), p4 = nodeVal(data, base, 7), p5 = nodeVal(data, base, 8);
            float p6 = nodeVal(data, base, 9), p7 = nodeVal(data, base, 10), p8 = nodeVal(data, base, 11);
            boolean transform = op == SdfGraph.TRANSLATE || op == SdfGraph.SCALE
                    || op == SdfGraph.STRETCH || op == SdfGraph.ROTATE;
            if (a >= 0) {
                float[] tp = transform ? transformPoint(op, pt[i], p0, p1, p2, p3, p4, p5, p6, p7, p8) : pt[i];
                pt[a][0] = tp[0];
                pt[a][1] = tp[1];
                pt[a][2] = tp[2];
            }
            if (b >= 0) {
                pt[b][0] = pt[i][0];
                pt[b][1] = pt[i][1];
                pt[b][2] = pt[i][2];
            }
        }

        for (int i = 0; i < nodeCount; i++) {
            int base = i * 12;
            int op = (int) nodeVal(data, base, 0);
            int a = (int) nodeVal(data, base, 1);
            int b = (int) nodeVal(data, base, 2);
            float p0 = nodeVal(data, base, 3), p1 = nodeVal(data, base, 4), p2 = nodeVal(data, base, 5);
            float p3 = nodeVal(data, base, 6), p4 = nodeVal(data, base, 7), p5 = nodeVal(data, base, 8);
            float p6 = nodeVal(data, base, 9);

            boolean transform = op == SdfGraph.TRANSLATE || op == SdfGraph.SCALE
                    || op == SdfGraph.STRETCH || op == SdfGraph.ROTATE;
            boolean unaryAdjust = op == SdfGraph.NEG || op == SdfGraph.ABS
                    || op == SdfGraph.ROUND || op == SdfGraph.ONION;

            if (a < 0 && b < 0) {
                value[i] = leafValue(op, pt[i], p0, p1, p2, p3, p4, p5, p6);
            } else if (transform) {
                value[i] = adjustTransform(op, value[a], p0, p1, p2);
            } else if (unaryAdjust) {
                value[i] = adjustUnary(op, value[a], p0);
            } else {
                value[i] = binaryValue(op, value[a], value[b], p0);
            }
        }
        return value[root];
    }

    private static float len3(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float len2(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private static float dot3(float ax, float ay, float az, float bx, float by, float bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
