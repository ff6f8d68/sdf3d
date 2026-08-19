package mods.hexagon.sdf3d.sdf;

/**
 * Generates a specialized GLSL fragment shader for an {@link SdfGraph}.
 *
 * <p>The generic {@code sdf_item} shader walks a {@code uNodes[]} uniform array once per
 * march step (and once per normal/shadow sample), which costs {@code steps * nodeCount} array
 * reads with dynamic indexing. This generator instead inlines the whole graph as a
 * straight-line {@code float evalSdf(vec3 p)} function: every opcode, child index and
 * parameter is emitted as a literal, so the driver can constant-fold the entire
 * op-dispatch and keep every node in a register. Per-pixel cost becomes the exact node
 * count with no loop overhead, no uniform-array indexing, and no double pass.</p>
 *
 * <p>The generated source is self-contained and mirrors the generic shader's maths exactly,
 * so its output is bit-for-bit comparable to {@code sdf_item.fsh}.</p>
 */
public final class SdfShaderGenerator {
    private SdfShaderGenerator() {}

    /** Generates the complete fragment-shader source for {@code graph}. */
    public static String generateFragmentShader(SdfGraph graph) {
        return HEADER.replace("__MAX_DIST__", flt(maxDistFor(graph)))
                + "\n" + generateEvalSdf(graph) + "\n" + TAIL;
    }

    /**
     * The ray-march far plane must cover the model's bounding-box diagonal, or large models get
     * cut off ("culled") at their far side. Baking it per model keeps the plane tight for small
     * models while staying correct for big ones.
     */
    private static float maxDistFor(SdfGraph graph) {
        SdfBounds bounds = graph.bounds();
        float diagonal;
        if (bounds.isFinite()) {
            diagonal = bounds.size().length();
        } else {
            diagonal = (float) Math.sqrt(3.0) * (2.0f * FALLBACK_HALF_EXTENT);
        }
        return diagonal + 4.0f;
    }

    /** Matches {@code SdfRender.FALLBACK_HALF_EXTENT} (proxy cube half-extent for unbounded graphs). */
    private static final float FALLBACK_HALF_EXTENT = 8.0f;

    /** Generates the shader JSON descriptor for a specialized fragment program named by {@code key}. */
    public static String generateJson(String key) {
        return "{\n"
                + "    \"vertex\": \"sdf3d:sdf_item\",\n"
                + "    \"fragment\": \"sdf3d:sdf_item_gen_" + key + "\",\n"
                + "    \"samplers\": [\n"
                + "        { \"name\": \"uAtlas\" }\n"
                + "    ],\n"
                + "    \"uniforms\": [\n"
                + "        { \"name\": \"ModelViewMat\", \"type\": \"matrix4x4\", \"count\": 16, \"values\": [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] },\n"
                + "        { \"name\": \"ProjMat\", \"type\": \"matrix4x4\", \"count\": 16, \"values\": [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] },\n"
                + "        { \"name\": \"uInvPose\", \"type\": \"matrix4x4\", \"count\": 16, \"values\": [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] },\n"
                + "        { \"name\": \"uCamLocal\", \"type\": \"float\", \"count\": 3, \"values\": [0,0,2] },\n"
                + "        { \"name\": \"uDirLocal\", \"type\": \"float\", \"count\": 3, \"values\": [0,0,-1] },\n"
                + "        { \"name\": \"uOrtho\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uTime\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uTint\", \"type\": \"float\", \"count\": 4, \"values\": [1,1,1,1] },\n"
                + "        { \"name\": \"uEmissive\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uRoughness\", \"type\": \"float\", \"count\": 1, \"values\": [0.7] },\n"
                + "        { \"name\": \"uMetallic\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uEffect\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uHasTexture\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uSpriteRect\", \"type\": \"float\", \"count\": 4, \"values\": [0, 0, 1, 1] },\n"
                + "        { \"name\": \"uLightDir\", \"type\": \"float\", \"count\": 3, \"values\": [0.5, 1, 0.3] },\n"
                + "        { \"name\": \"uLocalClip\", \"type\": \"matrix4x4\", \"count\": 16, \"values\": [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] },\n"
                + "        { \"name\": \"uSkyLight\", \"type\": \"float\", \"count\": 1, \"values\": [1] },\n"
                + "        { \"name\": \"uBlockLight\", \"type\": \"float\", \"count\": 1, \"values\": [1] },\n"
                + "        { \"name\": \"uLightCount\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uLights\", \"type\": \"float\", \"count\": 32, \"values\": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0] },\n"
                + "        { \"name\": \"uEasing\", \"type\": \"float\", \"count\": 1, \"values\": [0] },\n"
                + "        { \"name\": \"uBezier\", \"type\": \"float\", \"count\": 4, \"values\": [0.42, 0, 0.58, 1] }\n"
                + "    ]\n"
                + "}\n";
    }

    private static String generateEvalSdf(SdfGraph graph) {
        float[] data = graph.data();
        int n = graph.nodeCount();
        int root = graph.rootIndex();

        StringBuilder sb = new StringBuilder(8192);

        // Fast scalar evaluator (used for marching, normals, shadows, AO).
        sb.append("float evalSdf(vec3 p) {\n");
        emitDeclarations(sb, n, root, false);
        emitPass1(sb, data, n);
        emitPass2(sb, data, n, false);
        sb.append("    return f").append(root).append(";\n");
        sb.append("}\n\n");

        // Local-position tracker (called once per hit so the triplanar UV sticks to a
        // deforming/morphing surface instead of swimming through model space).
        sb.append("vec3 evalLocal(vec3 p) {\n");
        emitDeclarations(sb, n, root, true);
        emitPass1(sb, data, n);
        emitPass2(sb, data, n, true);
        sb.append("    return l").append(root).append(";\n");
        sb.append("}\n");

        return sb.toString();
    }

    private static void emitDeclarations(StringBuilder sb, int n, int root, boolean trackLocal) {
        for (int i = 0; i < n; i++) sb.append("    vec3 pt").append(i).append(" = vec3(0.0);\n");
        for (int i = 0; i < n; i++) sb.append("    float f").append(i).append(" = 0.0;\n");
        if (trackLocal) {
            for (int i = 0; i < n; i++) sb.append("    vec3 l").append(i).append(" = vec3(0.0);\n");
        }
        sb.append("    pt").append(root).append(" = p;\n");
    }

    /** Pass 1: propagate points top-down (parents before children). */
    private static void emitPass1(StringBuilder sb, float[] data, int n) {
        for (int i = n - 1; i >= 0; i--) {
            int base = i * 12;
            int op = (int) data[base];
            int a = (int) data[base + 1];
            int b = (int) data[base + 2];
            if (a >= 0) {
                if (isTransform(op)) {
                    sb.append("    pt").append(a).append(" = transformPoint(")
                            .append(op).append(", pt").append(i).append(", ")
                            .append(params(data, base, 9)).append(");\n");
                } else {
                    sb.append("    pt").append(a).append(" = pt").append(i).append(";\n");
                }
            }
            if (b >= 0) {
                sb.append("    pt").append(b).append(" = pt").append(i).append(";\n");
            }
        }
    }

    /** Pass 2: evaluate values bottom-up (children before parents), plus the winning local point. */
    private static void emitPass2(StringBuilder sb, float[] data, int n, boolean trackLocal) {
        for (int i = 0; i < n; i++) {
            int base = i * 12;
            int op = (int) data[base];
            int a = (int) data[base + 1];
            int b = (int) data[base + 2];
            if (a < 0 && b < 0) {
                sb.append("    f").append(i).append(" = leafValue(").append(op)
                        .append(", pt").append(i).append(", ").append(params(data, base, 7)).append(");\n");
                if (trackLocal) sb.append("    l").append(i).append(" = pt").append(i).append(";\n");
            } else if (isTransform(op)) {
                sb.append("    f").append(i).append(" = adjustTransform(").append(op)
                        .append(", f").append(a).append(", ").append(params(data, base, 9)).append(");\n");
                if (trackLocal) sb.append("    l").append(i).append(" = l").append(a).append(";\n");
            } else if (isUnaryAdjust(op)) {
                sb.append("    f").append(i).append(" = adjustUnary(").append(op)
                        .append(", f").append(a).append(", ").append(flt(data[base + 3])).append(");\n");
                if (trackLocal) sb.append("    l").append(i).append(" = l").append(a).append(";\n");
            } else {
                sb.append("    f").append(i).append(" = binaryValue(").append(op)
                        .append(", f").append(a).append(", f").append(b)
                        .append(", ").append(flt(data[base + 3])).append(");\n");
                if (trackLocal) {
                    emitLocalSelection(sb, i, op, a, b, data[base + 3]);
                }
            }
        }
    }

    /** Chooses which child's local space defines the surface at a binary node. */
    private static void emitLocalSelection(StringBuilder sb, int i, int op, int a, int b, float k) {
        switch (op) {
            case SdfGraph.MIN:
            case SdfGraph.UNION:
            case SdfGraph.SMOOTH_UNION:
                sb.append("    l").append(i).append(" = (f").append(a).append(" < f").append(b)
                        .append(") ? l").append(a).append(" : l").append(b).append(";\n");
                break;
            case SdfGraph.MAX:
            case SdfGraph.INTERSECT:
                sb.append("    l").append(i).append(" = (f").append(a).append(" > f").append(b)
                        .append(") ? l").append(a).append(" : l").append(b).append(";\n");
                break;
            case SdfGraph.MORPH:
                sb.append("    l").append(i).append(" = mix(l").append(a).append(", l").append(b)
                        .append(", clamp(0.5 + 0.5 * sin(TAU * uTime / max(").append(flt(k))
                        .append(", 1e-4)), 0.0, 1.0));\n");
                break;
            default: // SUBTRACT, ADD, SUB, MUL, DIV: the left operand defines the surface
                sb.append("    l").append(i).append(" = l").append(a).append(";\n");
                break;
        }
    }

    /** Formats {@code count} consecutive params (p0..p{count-1}) as GLSL float literals. */
    private static String params(float[] data, int base, int count) {
        StringBuilder sb = new StringBuilder(count * 10);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(flt(data[base + 3 + i]));
        }
        return sb.toString();
    }

    private static boolean isTransform(int op) {
        return op == SdfGraph.TRANSLATE || op == SdfGraph.SCALE || op == SdfGraph.STRETCH
                || op == SdfGraph.ROTATE || op == SdfGraph.SPIN || op == SdfGraph.BOB
                || op == SdfGraph.PULSE || op == SdfGraph.TWIST || op == SdfGraph.BEND
                || op == SdfGraph.KEYFRAME_SCALE || op == SdfGraph.KEYFRAME_TRANSLATE
                || op == SdfGraph.KEYFRAME_ROTATE;
    }

    private static boolean isUnaryAdjust(int op) {
        return op == SdfGraph.NEG || op == SdfGraph.ABS || op == SdfGraph.ROUND || op == SdfGraph.ONION;
    }

    /** Formats a Java float as a valid GLSL 1.50 float literal (always carries a decimal point). */
    static String flt(float value) {
        if (Float.isNaN(value)) return "0.0";
        if (Float.isInfinite(value)) return value > 0 ? "1e30" : "-1e30";
        String s = Float.toString(value);
        if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            s += ".0";
        }
        return s;
    }

    // ------------------------------------------------------------------ shared GLSL

    private static final String HEADER = """
#version 150

uniform vec3 uCamLocal;
uniform vec3 uDirLocal;
uniform float uOrtho;
uniform float uTime;
uniform vec4 uTint;
uniform float uEmissive;
uniform float uRoughness;
uniform float uMetallic;
uniform float uEffect;
uniform sampler2D uAtlas;
uniform float uHasTexture;
uniform vec4 uSpriteRect;
uniform vec3 uLightDir;
uniform mat4 uLocalClip;
uniform float uSkyLight;
uniform float uBlockLight;
uniform float uLightCount;
uniform vec4 uLights[8];
uniform float uEasing;
uniform vec4 uBezier;

in vec3 vLocalPos;

out vec4 fragColor;

#define MAX_STEPS 32
#define MAX_DIST __MAX_DIST__
#define EPS 0.001
#define TAU 6.28318530718

// op codes (must match SdfGraph)
#define CONST 0
#define OP_X 1
#define OP_Y 2
#define OP_Z 3
#define ADD 10
#define SUB 11
#define MUL 12
#define DIV 13
#define NEG 14
#define ABS 15
#define MIN 16
#define MAX 17
#define SPHERE 20
#define BOX 21
#define ROUNDED_BOX 22
#define ELLIPSOID 23
#define TORUS 24
#define CYLINDER 25
#define CAPSULE 26
#define PLANE 27
#define CONE 36
#define HEX_PRISM 37
#define OCTAHEDRON 38
#define UNION 30
#define INTERSECT 31
#define SUBTRACT 32
#define SMOOTH_UNION 33
#define TRANSLATE 40
#define SCALE 41
#define STRETCH 42
#define ROTATE 43
#define ROUND 44
#define ONION 45
#define SPIN 51
#define BOB 52
#define PULSE 53
#define MORPH 54
#define TWIST 55
#define BEND 56
#define KEYFRAME_SCALE 57
#define KEYFRAME_TRANSLATE 58
#define KEYFRAME_ROTATE 59

// post-effect ids (must match SdfClientOptions.SdfPostEffect)
#define FX_NONE 0
#define FX_AO 1
#define FX_GLOW 2
#define FX_SHADOW 3

vec3 rotatePointInverse(vec3 p, vec3 a, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    float d = dot(a, p);
    vec3 cr = cross(a, p);
    return p * c - cr * s + a * (d * (1.0 - c));
}

float leafValue(int op, vec3 p, float p0, float p1, float p2, float p3, float p4, float p5, float p6) {
    if (op == CONST) return p0;
    if (op == OP_X) return p.x;
    if (op == OP_Y) return p.y;
    if (op == OP_Z) return p.z;
    if (op == SPHERE) return length(p - vec3(p0, p1, p2)) - p3;
    if (op == BOX) {
        vec3 q = abs(p - vec3(p0, p1, p2)) - vec3(p3, p4, p5);
        return length(max(q, vec3(0.0))) + min(max(q.x, max(q.y, q.z)), 0.0);
    }
    if (op == ROUNDED_BOX) {
        vec3 q = abs(p - vec3(p0, p1, p2)) - vec3(p3, p4, p5);
        return length(max(q, vec3(0.0))) + min(max(q.x, max(q.y, q.z)), 0.0) - p6;
    }
    if (op == ELLIPSOID) {
        vec3 q = (p - vec3(p0, p1, p2)) / vec3(p3, p4, p5);
        return (length(q) - 1.0) * min(p3, min(p4, p5));
    }
    if (op == TORUS) {
        vec2 q = vec2(length(p.xz - vec2(p0, p2)) - p3, p.y - p1);
        return length(q) - p4;
    }
    if (op == CYLINDER) {
        float d = length(p.xz - vec2(p0, p2)) - p3;
        float dy = abs(p.y - p1) - p4;
        return min(max(d, dy), 0.0) + length(vec2(max(d, 0.0), max(dy, 0.0)));
    }
    if (op == CAPSULE) {
        vec3 pa = vec3(p0, p1, p2);
        vec3 pb = vec3(p3, p4, p5);
        vec3 ba = pb - pa;
        float h = clamp(dot(p - pa, ba) / max(dot(ba, ba), 1e-9), 0.0, 1.0);
        return length(p - (pa + ba * h)) - p6;
    }
    if (op == PLANE) return p0 * p.x + p1 * p.y + p2 * p.z + p3;
    if (op == CONE) {
        vec3 q = p - vec3(p0, p1, p2);
        float r1 = abs(p3);
        float h = p4;
        float r = length(q.xz);
        float b = r1 / max(h, 1e-4);
        float a = sqrt(max(1.0 - b * b, 0.0));
        float k = -b * r + a * q.y;
        if (k < 0.0) return length(q) - r1;
        if (k > a * h) return length(q - vec3(0.0, h, 0.0));
        return a * r + b * q.y - r1;
    }
    if (op == HEX_PRISM) {
        vec3 q = p - vec3(p0, p1, p2);
        float rad = p3;
        float h = p4;
        vec2 axz = abs(q.xz);
        vec2 kvec = vec2(-0.866025404, 0.5);
        float kz = 0.577350269;
        float dotv = dot(kvec, axz);
        axz -= 2.0 * min(dotv, 0.0) * kvec;
        axz -= vec2(clamp(axz.x, -kz * rad, kz * rad), rad);
        float d = length(axz) * sign(axz.y);
        float dy = abs(q.y) - h;
        return min(max(d, dy), 0.0) + length(vec2(max(d, 0.0), max(dy, 0.0)));
    }
    if (op == OCTAHEDRON) {
        vec3 q = abs(p - vec3(p0, p1, p2));
        float s = p3;
        float m = q.x + q.y + q.z - s;
        vec3 r;
        if (3.0 * q.x < m) r = q;
        else if (3.0 * q.y < m) r = q.yzx;
        else if (3.0 * q.z < m) r = q.zxy;
        else return m * 0.57735027;
        float k = clamp(0.5 * (r.z - r.y + s), 0.0, s);
        return length(vec3(r.x, r.y - s + k, r.z - k));
    }
    return 0.0;
}

float easeBezier(float f) {
    vec2 p1 = uBezier.xy;
    vec2 p2 = uBezier.zw;
    float t = clamp(f, 0.0, 1.0);
    for (int i = 0; i < 4; i++) {
        float omt = 1.0 - t;
        float x = 3.0 * omt * omt * t * p1.x + 3.0 * omt * t * t * p2.x + t * t * t;
        float dx = 3.0 * omt * omt * p1.x + 6.0 * omt * t * (p2.x - p1.x) + 3.0 * t * t * (1.0 - p2.x);
        if (abs(dx) < 1e-6) break;
        t = clamp(t - (x - f) / dx, 0.0, 1.0);
    }
    float omt = 1.0 - t;
    return 3.0 * omt * omt * t * p1.y + 3.0 * omt * t * t * p2.y + t * t * t;
}

float applyEasing(float f) {
    int e = int(uEasing + 0.5);
    f = clamp(f, 0.0, 1.0);
    if (e == 1) return f * f * (3.0 - 2.0 * f);
    if (e == 2) return f * f;
    if (e == 3) return 1.0 - (1.0 - f) * (1.0 - f);
    if (e == 4) return f * f * (3.0 - 2.0 * f);
    if (e == 5) return easeBezier(f);
    return f;
}

float keyframeValue(float time, float period, float t0, float v0, float t1, float v1,
                    float t2, float v2, float t3, float v3) {
    if (period <= 0.0) period = 1.0;
    float t = mod(time, period);
    if (t < 0.0) t += period;
    if (t <= t0) return v0;
    if (t >= t3) return v3;
    if (t < t1) return mix(v0, v1, applyEasing((t - t0) / max(t1 - t0, 1e-6)));
    if (t < t2) return mix(v1, v2, applyEasing((t - t1) / max(t2 - t1, 1e-6)));
    return mix(v2, v3, applyEasing((t - t2) / max(t3 - t2, 1e-6)));
}

float keyframeFraction(float time, float period, float t0, float t1) {
    if (period <= 0.0) period = 1.0;
    float t = mod(time, period);
    if (t < 0.0) t += period;
    if (t <= t0) return 0.0;
    if (t < t1) return applyEasing((t - t0) / max(t1 - t0, 1e-6));
    return 1.0 - applyEasing((t - t1) / max(period - t1, 1e-6));
}

vec3 transformPoint(int op, vec3 p, float p0, float p1, float p2, float p3, float p4, float p5, float p6, float p7, float p8) {
    if (op == TRANSLATE) return p - vec3(p0, p1, p2);
    if (op == SCALE) return p / p0;
    if (op == STRETCH) return p / vec3(p0, p1, p2);
    if (op == ROTATE) {
        vec3 r0 = vec3(p0, p1, p2);
        vec3 r1 = vec3(p3, p4, p5);
        vec3 r2 = vec3(p6, p7, p8);
        return vec3(dot(r0, p), dot(r1, p), dot(r2, p));
    }
    if (op == SPIN) return rotatePointInverse(p, vec3(p0, p1, p2), radians(p3 * uTime));
    if (op == BOB) return p - vec3(p0, p1, p2) * (p3 * sin(TAU * p4 * uTime));
    if (op == PULSE) {
        float s = mix(p0, p1, 0.5 + 0.5 * sin(TAU * p2 * uTime));
        return p / s;
    }
    if (op == TWIST) {
        float a = p0 * p.y;
        float c = cos(a);
        float s = sin(a);
        return vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
    }
    if (op == BEND) {
        float a = p0 * p.x;
        float c = cos(a);
        float s = sin(a);
        return vec3(c * p.x - s * p.y, s * p.x + c * p.y, p.z);
    }
    if (op == KEYFRAME_SCALE) {
        float s = keyframeValue(uTime, p0, p1, p2, p3, p4, p5, p6, p7, p8);
        return p / s;
    }
    if (op == KEYFRAME_TRANSLATE) {
        float f = keyframeFraction(uTime, p0, p1, p5);
        return p - vec3(mix(p2, p6, f), mix(p3, p7, f), mix(p4, p8, f));
    }
    if (op == KEYFRAME_ROTATE) {
        float f = keyframeFraction(uTime, p3, p4, p6);
        return rotatePointInverse(p, vec3(p0, p1, p2), radians(mix(p5, p7, f)));
    }
    return p;
}

float adjustTransform(int op, float v, float p0, float p1, float p2, float p3, float p4, float p5, float p6, float p7, float p8) {
    if (op == SCALE) return v * p0;
    if (op == STRETCH) return v * min(p0, min(p1, p2));
    if (op == PULSE) {
        float s = mix(p0, p1, 0.5 + 0.5 * sin(TAU * p2 * uTime));
        return v * s;
    }
    if (op == KEYFRAME_SCALE) {
        float s = keyframeValue(uTime, p0, p1, p2, p3, p4, p5, p6, p7, p8);
        return v * s;
    }
    return v;
}

float adjustUnary(int op, float v, float p0) {
    if (op == NEG) return -v;
    if (op == ABS) return abs(v);
    if (op == ROUND) return v - p0;
    if (op == ONION) return abs(v) - p0;
    return v;
}

float binaryValue(int op, float va, float vb, float k) {
    if (op == ADD) return va + vb;
    if (op == SUB) return va - vb;
    if (op == MUL) return va * vb;
    if (op == DIV) return va / (abs(vb) < 1e-9 ? 1e-9 : vb);
    if (op == MIN || op == UNION) return min(va, vb);
    if (op == MAX || op == INTERSECT) return max(va, vb);
    if (op == SUBTRACT) return max(va, -vb);
    if (op == SMOOTH_UNION) {
        float h = clamp(0.5 + 0.5 * (vb - va) / k, 0.0, 1.0);
        return mix(vb, va, h) - k * h * (1.0 - h);
    }
    if (op == MORPH) {
        float blend = clamp(0.5 + 0.5 * sin(TAU * uTime / max(k, 1e-4)), 0.0, 1.0);
        return mix(va, vb, blend);
    }
    return min(va, vb);
}
""";

    private static final String TAIL = """
vec3 calcNormal(vec3 p) {
    // Tetrahedral gradient: 4 samples instead of 6.
    vec2 k = vec2(1.0, -1.0);
    return normalize(
        k.xyy * evalSdf(p + k.xyy * EPS) +
        k.yyx * evalSdf(p + k.yyx * EPS) +
        k.yxy * evalSdf(p + k.yxy * EPS) +
        k.xxx * evalSdf(p + k.xxx * EPS));
}

// Triplanar texture sampling in model-local space, remapped into the sprite's atlas rect.
vec3 sampleAlbedo(vec3 p, vec3 n) {
    vec3 absN = abs(n);
    float s = absN.x + absN.y + absN.z;
    vec3 w = absN / max(s, 1e-6);
    vec2 uv = p.zy * w.x + p.xz * w.y + p.xy * w.z;
    uv = fract(uv);
    vec2 rect = uSpriteRect.zw - uSpriteRect.xy;
    vec2 lo = uSpriteRect.xy + rect * 0.001;
    vec2 hi = uSpriteRect.zw - rect * 0.001;
    return texture(uAtlas, mix(lo, hi, uv)).rgb;
}

void main() {
    vec3 rayOrigin;
    vec3 rayDir;
    if (uOrtho > 0.5) {
        rayOrigin = vLocalPos;
        rayDir = normalize(uDirLocal);
    } else {
        rayOrigin = uCamLocal;
        rayDir = normalize(vLocalPos - uCamLocal);
    }

    float t = 0.0;
    bool hit = false;
    vec3 hitPos = vec3(0.0);
    for (int i = 0; i < MAX_STEPS; i++) {
        vec3 p = rayOrigin + rayDir * t;
        float d = evalSdf(p);
        if (d < EPS * max(1.0, t)) {
            hit = true;
            hitPos = p;
            break;
        }
        t += max(d * 0.9, 0.0005);
        if (t > MAX_DIST) break;
    }
    if (!hit) discard;

    // Write real depth at the hit point (model-local -> clip via uLocalClip) so the model
    // both occludes and is occluded correctly against the world instead of drawing on top.
    vec4 clipPos = uLocalClip * vec4(hitPos, 1.0);
    gl_FragDepth = (clipPos.z / clipPos.w) * 0.5 + 0.5;

    vec3 normal = calcNormal(hitPos);
    vec3 hitLocal = evalLocal(hitPos);
    vec3 albedo = uTint.rgb;
    if (uHasTexture > 0.5) albedo *= sampleAlbedo(hitLocal, normal);
    vec3 lightDir = normalize(uLightDir);
    vec3 viewDir = normalize(-rayDir);

    float NdotL = max(dot(normal, lightDir), 0.0);
    float NdotV = max(dot(normal, viewDir), 1e-4);
    vec3 halfVec = normalize(lightDir + viewDir);
    float NdotH = max(dot(normal, halfVec), 0.0);

    float roughness = clamp(uRoughness, 0.04, 1.0);
    float metallic = clamp(uMetallic, 0.0, 1.0);

    // Cook-Torrance microfacet BRDF: GGX distribution, Schlick-GGX geometry, Schlick Fresnel.
    float a = roughness * roughness;
    float a2 = a * a;
    float ddenom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    float D = a2 / max(3.14159265 * ddenom * ddenom, 1e-4);
    float k = a * 0.5;
    float G = (NdotL / max(NdotL * (1.0 - k) + k, 1e-4)) * (NdotV / max(NdotV * (1.0 - k) + k, 1e-4));
    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    float HdotV = max(dot(halfVec, viewDir), 0.0);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - HdotV, 5.0);

    vec3 specular = (D * G * F) / max(4.0 * NdotL * NdotV, 1e-4);
    vec3 kd = (1.0 - F) * (1.0 - metallic);
    vec3 direct = kd * albedo * NdotL + specular;

    // Self-shadows are always on: soft-march toward the light so the model casts shadows that
    // track where the sun (or a placed light) actually is. Surfaces facing away from the light
    // skip the march (they are dark either way).
    int effect = int(uEffect + 0.5);
    float shadow = 1.0;
    if (NdotL > 0.03) {
        float sh = 1.0;
        float st = 0.02;
        for (int i = 0; i < 8; i++) {
            float d = evalSdf(hitPos + lightDir * st);
            sh = min(sh, 8.0 * d / st);
            st += max(d, 0.01);
            if (sh < 0.02) break;
        }
        shadow = clamp(sh, 0.0, 1.0);
    }

    // In-game light: sky light drives the ambient term, block light (torches etc.) drives
    // the directional key light, instead of a hard-coded camera-relative constant.
    float ambientLight = 0.03 + 0.5 * uSkyLight;
    float keyLight = 0.1 + 0.9 * uBlockLight;
    vec3 lit = (uEmissive > 0.5) ? albedo : (albedo * ambientLight + direct * keyLight * shadow);

    // Point lights (placed torches / glowstone): each is an independent source with its own
    // position and intensity, so several lights read as distinct highlights instead of one
    // averaged direction. Positions are model-local (transformed on the CPU).
    if (uEmissive <= 0.5) {
        int lightCount = int(uLightCount + 0.5);
        for (int li = 0; li < lightCount; li++) {
            vec3 toLight = uLights[li].xyz - hitPos;
            float dist = length(toLight);
            vec3 L = toLight / max(dist, 1e-4);
            float atten = uLights[li].w / (1.0 + dist * dist * 0.8);
            float plNdotL = max(dot(normal, L), 0.0);
            vec3 plHalf = normalize(L + viewDir);
            float plSpec = pow(max(dot(normal, plHalf), 0.0), mix(64.0, 4.0, roughness));
            lit += (albedo * plNdotL + mix(vec3(0.04), albedo, metallic) * plSpec) * atten;
        }
    }

    if (effect == FX_AO) {
        float d = evalSdf(hitPos + normal * 0.06);
        float ao = clamp(d / 0.06, 0.0, 1.0);
        lit *= (0.5 + 0.5 * ao);
    } else if (effect == FX_GLOW) {
        float d = evalSdf(hitPos + normal * 0.05);
        float rim = clamp(1.0 - abs(d) / 0.1, 0.0, 1.0);
        lit = mix(lit, albedo, 0.5 * rim);
    }

    fragColor = vec4(lit, uTint.a);
}
""";
}
