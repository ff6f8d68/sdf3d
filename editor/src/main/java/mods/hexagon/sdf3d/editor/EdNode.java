package mods.hexagon.sdf3d.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * A mutable node in the editor's model graph. Parameters are stored in the same order as the
 * {@code .s3d} text signature (not the flattened graph layout), so serializing is lossless.
 */
public final class EdNode {
    public SdfNodeSpec spec;
    public float[] params;
    public final List<EdNode> children = new ArrayList<>();
    /** When non-null this node is a {@code group("name")} reference (no params/children). */
    public String groupRef;

    public EdNode(SdfNodeSpec spec) {
        this.spec = spec;
        this.params = new float[spec.paramCount()];
        for (int i = 0; i < params.length && i < spec.defaults.length; i++) params[i] = spec.defaults[i];
    }

    public EdNode(String name) {
        this(SdfNodeSpec.byName(name));
    }

    /** A leaf that references a named group. */
    public static EdNode groupRef(String name) {
        EdNode n = new EdNode("const");
        n.groupRef = name;
        return n;
    }

    public String name() {
        return spec.name;
    }

    /** Deep copy (group references stay references). */
    public EdNode deepCopy() {
        if (groupRef != null) return groupRef(groupRef);
        EdNode copy = new EdNode(spec);
        System.arraycopy(params, 0, copy.params, 0, params.length);
        for (EdNode child : children) copy.children.add(child.deepCopy());
        return copy;
    }

    public void setParams(double... values) {
        for (int i = 0; i < params.length && i < values.length; i++) params[i] = (float) values[i];
    }

    /** Replaces this node with a fresh instance of {@code newSpec} (used by "replace with"). */
    public void replace(SdfNodeSpec newSpec) {
        this.spec = newSpec;
        this.params = new float[newSpec.paramCount()];
        for (int i = 0; i < params.length && i < newSpec.defaults.length; i++) params[i] = newSpec.defaults[i];
        this.children.clear();
    }

    /** Short label for the tree view. */
    public String label() {
        if (groupRef != null) return "group(\"" + groupRef + "\")";
        return spec.name + (params.length == 0 ? "" : " " + fmtParams());
    }

    private String fmtParams() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(params[i]));
        }
        return sb.append(")").toString();
    }

    private static String fmt(float v) {
        if (v == Math.rint(v)) return String.valueOf((int) v);
        return String.format("%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
