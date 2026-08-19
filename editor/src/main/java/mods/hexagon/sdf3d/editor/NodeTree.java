package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds a human-readable tree from {@link SdfGraph}'s flattened post-order node array
 * (each node stores {@code [op, childA, childB, p0..p8]} with a stride of 12).
 */
public final class NodeTree {
    private NodeTree() {}

    public record Node(String label, int index, List<Node> children) {
        public static Node leaf(String label, int index) {
            return new Node(label, index, List.of());
        }
    }

    public static Node build(SdfGraph graph) {
        return build(graph, graph.rootIndex());
    }

    private static Node build(SdfGraph graph, int index) {
        float[] d = graph.data();
        int base = index * 12;
        int op = (int) d[base];
        int a = (int) d[base + 1];
        int b = (int) d[base + 2];
        float[] p = new float[9];
        System.arraycopy(d, base + 3, p, 0, 9);

        List<Node> children = new ArrayList<>(2);
        if (a >= 0) children.add(build(graph, a));
        if (b >= 0) children.add(build(graph, b));
        if (children.isEmpty()) return Node.leaf(NodeInfo.opName(op) + params(p), index);
        return new Node(NodeInfo.opName(op) + params(p), index, children);
    }

    private static String params(float[] p) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (float v : p) {
            if (v == 0f) break; // params are trailing-zero padded
            if (shown++ == 0) sb.append('(');
            else sb.append(", ");
            sb.append(trim(v));
        }
        if (shown > 0) sb.append(')');
        return sb.toString();
    }

    private static String trim(float v) {
        if (v == Math.rint(v)) return String.valueOf((int) v);
        return String.format("%.3f", v);
    }
}
