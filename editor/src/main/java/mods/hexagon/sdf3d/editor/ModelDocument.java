package mods.hexagon.sdf3d.editor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The build-mode document: a root node tree, named groups, and the model-wide material/easing
 * directives. {@link #toSource()} produces a complete {@code .s3d} source string that feeds the
 * preview. Groups are emitted as {@code group("name") { ... }} definitions before the root.
 */
public final class ModelDocument {
    public static final String[] EASINGS = {"linear", "smoothstep", "easeIn", "easeOut", "easeInOut", "bezier"};

    public EdNode root = new EdNode("sphere");
    public final Map<String, EdNode> groups = new LinkedHashMap<>();

    public String texture;      // nullable (no texture directive)
    public int tint = 0xFFFFFFFF;
    public boolean emissive = false;
    public float roughness = 0.6f;
    public float metallic = 0.1f;
    public String easing = "linear";
    public final float[] bezier = {0.42f, 0f, 0.58f, 1f};

    public String toSource() {
        StringBuilder sb = new StringBuilder();
        sb.append("// SDF3D model (built in the editor)\n");
        if (texture != null && !texture.isBlank()) {
            sb.append("texture = \"").append(texture).append("\";\n");
        }
        sb.append("tint = 0x").append(Integer.toHexString(tint).toUpperCase()).append(";\n");
        sb.append("emissive = ").append(emissive).append(";\n");
        sb.append("roughness = ").append(fmt(roughness)).append(";\n");
        sb.append("metallic = ").append(fmt(metallic)).append(";\n");
        if ("bezier".equals(easing)) {
            sb.append("easing = bezier(")
                    .append(fmt(bezier[0])).append(", ").append(fmt(bezier[1])).append(", ")
                    .append(fmt(bezier[2])).append(", ").append(fmt(bezier[3])).append(");\n");
        } else if (!"linear".equals(easing)) {
            sb.append("easing = ").append(easing).append(";\n");
        }
        sb.append('\n');
        for (Map.Entry<String, EdNode> e : groups.entrySet()) {
            sb.append("group(\"").append(e.getKey()).append("\") {\n  ")
                    .append(SdfWriter.serialize(e.getValue())).append("\n}\n\n");
        }
        sb.append(SdfWriter.serialize(root)).append('\n');
        return sb.toString();
    }

    /** Creates (or overwrites) a named group with the given body. */
    public void makeGroup(String name, EdNode body) {
        groups.put(name, body);
    }

    public void renameGroup(String oldName, String newName) {
        if (oldName.equals(newName)) return;
        EdNode body = groups.remove(oldName);
        if (body == null) return;
        groups.put(newName, body);
        // Rewrite every reference to the group.
        retargetRefs(root, oldName, newName);
        for (EdNode g : groups.values()) retargetRefs(g, oldName, newName);
    }

    public void removeGroup(String name) {
        groups.remove(name);
    }

    /** Deep copy of the whole document (tree, groups, material, easing) for undo/redo. */
    public ModelDocument snapshot() {
        ModelDocument d = new ModelDocument();
        d.root = root.deepCopy();
        for (Map.Entry<String, EdNode> e : groups.entrySet()) d.groups.put(e.getKey(), e.getValue().deepCopy());
        d.texture = texture;
        d.tint = tint;
        d.emissive = emissive;
        d.roughness = roughness;
        d.metallic = metallic;
        d.easing = easing;
        System.arraycopy(bezier, 0, d.bezier, 0, bezier.length);
        return d;
    }

    /** Overwrites this document's contents with {@code s} (undo/redo restore). */
    public void restore(ModelDocument s) {
        root = s.root.deepCopy();
        groups.clear();
        for (Map.Entry<String, EdNode> e : s.groups.entrySet()) groups.put(e.getKey(), e.getValue().deepCopy());
        texture = s.texture;
        tint = s.tint;
        emissive = s.emissive;
        roughness = s.roughness;
        metallic = s.metallic;
        easing = s.easing;
        System.arraycopy(s.bezier, 0, bezier, 0, bezier.length);
    }

    /** Appends {@code subtree} to a group's body (unioned in). */
    public void addToGroup(String name, EdNode subtree) {
        EdNode body = groups.get(name);
        if (body == null) return;
        groups.put(name, unionOf(body, subtree));
    }

    /** True if {@code node} is part of the given group's body (directly or nested). */
    public boolean groupContains(String name, EdNode node) {
        EdNode body = groups.get(name);
        return body != null && contains(body, node);
    }

    /** Returns the group name whose body contains {@code node}, or null. */
    public String groupOf(EdNode node) {
        for (Map.Entry<String, EdNode> e : groups.entrySet()) {
            if (contains(e.getValue(), node)) return e.getKey();
        }
        return null;
    }

    /** Removes {@code node} from whichever group body contains it (collapsing unions). */
    public void removeFromGroup(EdNode node) {
        for (Map.Entry<String, EdNode> e : groups.entrySet()) {
            if (contains(e.getValue(), node)) {
                groups.put(e.getKey(), removeFromTree(e.getValue(), node));
                return;
            }
        }
    }

    public static EdNode unionOf(EdNode a, EdNode b) {
        if (a.name().equals("union")) {
            a.children.add(b);
            return a;
        }
        EdNode u = new EdNode("union");
        u.children.add(a);
        u.children.add(b);
        return u;
    }

    private static boolean contains(EdNode current, EdNode target) {
        if (current == target) return true;
        for (EdNode child : current.children) if (contains(child, target)) return true;
        return false;
    }

    private static void retargetRefs(EdNode node, String oldName, String newName) {
        if (oldName.equals(node.groupRef)) node.groupRef = newName;
        for (EdNode child : node.children) retargetRefs(child, oldName, newName);
    }

    /** Returns a tree with {@code target} removed; collapses a 2-child union to its survivor. */
    private static EdNode removeFromTree(EdNode current, EdNode target) {
        if (current == target) return new EdNode("sphere");
        List<EdNode> children = current.children;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == target) {
                children.remove(i);
                break;
            }
        }
        for (int i = 0; i < children.size(); i++) {
            if (contains(children.get(i), target)) {
                children.set(i, removeFromTree(children.get(i), target));
            }
        }
        if (current.spec.childCount() == 2 && current.children.size() == 1) {
            return current.children.get(0); // collapse union(a) -> a
        }
        if (current.children.isEmpty() && current.spec.childCount() > 0) {
            return new EdNode("sphere");
        }
        return current;
    }

    private static String fmt(float v) {
        return SdfWriter.fmt(v);
    }
}
