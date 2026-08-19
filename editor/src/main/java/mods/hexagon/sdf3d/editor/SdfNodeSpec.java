package mods.hexagon.sdf3d.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes one {@code .s3d} function for the node-graph builder: its name, a category, the
 * order of its scalar parameters vs child-SDF arguments (the {@code layout} string of
 * {@code 'p'}/{@code 'c'} characters), human labels for the parameters, and sensible defaults.
 */
public final class SdfNodeSpec {
    public final String name;
    public final String category;
    public final String layout;
    public final String[] params;
    public final float[] defaults;

    private SdfNodeSpec(String name, String category, String layout, String[] params, float[] defaults) {
        this.name = name;
        this.category = category;
        this.layout = layout;
        this.params = params;
        this.defaults = defaults;
    }

    public int paramCount() {
        return count(layout, 'p');
    }

    public int childCount() {
        return count(layout, 'c');
    }

    public boolean isLeaf() {
        return childCount() == 0;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static final Map<String, SdfNodeSpec> BY_NAME = new LinkedHashMap<>();

    private static SdfNodeSpec reg(String name, String category, String layout, float[] defaults, String... params) {
        SdfNodeSpec spec = new SdfNodeSpec(name, category, layout, params, defaults == null ? new float[count(layout, 'p')] : defaults);
        BY_NAME.put(name, spec);
        return spec;
    }

    static {
        // Primitives
        reg("sphere",       "Primitive", "pppp",     f(0, 0, 0, 0.5f), "x", "y", "z", "radius");
        reg("box",          "Primitive", "pppppp",   f(0, 0, 0, 0.5f, 0.5f, 0.5f), "x", "y", "z", "halfX", "halfY", "halfZ");
        reg("rounded_box",  "Primitive", "ppppppp",  f(0, 0, 0, 0.4f, 0.4f, 0.4f, 0.1f), "x", "y", "z", "halfX", "halfY", "halfZ", "radius");
        reg("ellipsoid",    "Primitive", "pppppp",   f(0, 0, 0, 0.5f, 0.7f, 0.5f), "x", "y", "z", "rx", "ry", "rz");
        reg("torus",        "Primitive", "ppppp",    f(0, 0, 0, 0.5f, 0.15f), "x", "y", "z", "majorRadius", "minorRadius");
        reg("capsule",      "Primitive", "ppppppp",  f(0, 0.5f, 0, 0, -0.5f, 0, 0.15f), "ax", "ay", "az", "bx", "by", "bz", "radius");
        reg("plane",        "Primitive", "pppp",     f(0, 1, 0, 0), "nx", "ny", "nz", "offset");
        reg("cylinder",     "Primitive", "ppppp",    f(0, 0, 0, 0.4f, 1.0f), "x", "y", "z", "radius", "height");
        reg("cone",         "Primitive", "ppppp",    f(0, 0, 0, 0.4f, 1.0f), "x", "y", "z", "radius", "height");
        reg("hex_prism",    "Primitive", "ppppp",    f(0, 0, 0, 0.4f, 1.0f), "x", "y", "z", "radius", "height");
        reg("octahedron",   "Primitive", "pppp",     f(0, 0, 0, 0.5f), "x", "y", "z", "size");

        // Combine (CSG)
        reg("union",            "Combine", "cc",     null);
        reg("intersect",        "Combine", "cc",     null);
        reg("subtract",         "Combine", "cc",     null);
        reg("carve",            "Combine", "cc",     null);
        reg("stamp",            "Combine", "cc",     null);
        reg("blend",            "Combine", "pcc",    f(0.1f), "k");
        reg("smooth_union",     "Combine", "pcc",    f(0.1f), "k");
        reg("smooth_intersect", "Combine", "pcc",    f(0.1f), "k");
        reg("smooth_subtract",  "Combine", "pcc",    f(0.1f), "k");

        // Transforms
        reg("translate", "Transform", "pppc", f(0, 0, 0), "x", "y", "z");
        reg("rotate",    "Transform", "pppc", f(0, 0, 0), "degX", "degY", "degZ");
        reg("scale",     "Transform", "pppc", f(1, 1, 1), "sx", "sy", "sz");
        reg("stretch",   "Transform", "pppc", f(1, 1, 1), "sx", "sy", "sz");
        reg("round",     "Transform", "pc",   f(0.05f), "radius");
        reg("onion",     "Transform", "pc",   f(0.05f), "thickness");

        // Animations
        reg("spin",  "Animate", "ppppc",  f(0, 1, 0, 30), "axisX", "axisY", "axisZ", "degPerSec");
        reg("bob",   "Animate", "pppppc", f(0, 1, 0, 0.2f, 1), "axisX", "axisY", "axisZ", "amplitude", "cyclesPerSec");
        reg("pulse", "Animate", "pppc",   f(0.9f, 1.1f, 1), "minScale", "maxScale", "cyclesPerSec");
        reg("morph", "Animate", "ccp",    f(1.0f), "periodSec");
        reg("twist", "Animate", "pc",     f(1.0f), "radPerUnitY");
        reg("bend",  "Animate", "pc",     f(1.0f), "radPerUnitX");

        // Keyframes
        reg("keyframe_scale",     "Keyframe", "pppppppppc", f(2, 0, 1, 1, 1.2f, 2, 1, 3, 0.9f),
                "period", "t0", "v0", "t1", "v1", "t2", "v2", "t3", "v3");
        reg("keyframe_translate", "Keyframe", "pppppppppc", f(2, 0, 0, 0, 0, 1, 0, 0.3f, 0),
                "period", "t0", "x0", "y0", "z0", "t1", "x1", "y1", "z1");
        reg("keyframe_rotate",    "Keyframe", "ppppppppc",  f(0, 1, 0, 2, 0, -20, 1, 20),
                "axisX", "axisY", "axisZ", "period", "t0", "angle0", "t1", "angle1");

        // Advanced (algebraic SDFs)
        reg("x",     "Advanced", "",  null);
        reg("y",     "Advanced", "",  null);
        reg("z",     "Advanced", "",  null);
        reg("const", "Advanced", "p", f(1.0f), "value");
        reg("add",   "Advanced", "cc", null);
        reg("sub",   "Advanced", "cc", null);
        reg("mul",   "Advanced", "cc", null);
        reg("div",   "Advanced", "cc", null);
        reg("min",   "Advanced", "cc", null);
        reg("max",   "Advanced", "cc", null);
        reg("neg",   "Advanced", "c",  null);
        reg("abs",   "Advanced", "c",  null);
    }

    private static float[] f(float... v) {
        return v;
    }

    public static SdfNodeSpec byName(String name) {
        SdfNodeSpec spec = BY_NAME.get(name);
        if (spec == null) throw new IllegalArgumentException("Unknown node '" + name + "'");
        return spec;
    }

    public static List<String> categories() {
        List<String> out = new ArrayList<>();
        for (SdfNodeSpec spec : BY_NAME.values()) if (!out.contains(spec.category)) out.add(spec.category);
        return out;
    }

    public static List<SdfNodeSpec> byCategory(String category) {
        List<SdfNodeSpec> out = new ArrayList<>();
        for (SdfNodeSpec spec : BY_NAME.values()) if (spec.category.equals(category)) out.add(spec);
        return out;
    }

    public static List<SdfNodeSpec> all() {
        return new ArrayList<>(BY_NAME.values());
    }
}
