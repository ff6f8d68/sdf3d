package mods.hexagon.sdf3d.editor;

/**
 * Serializes an editable node tree back into {@code .s3d} source text. This is the inverse of
 * the parser's expression layer: every function name + argument layout matches {@code SdfParser}.
 */
public final class SdfWriter {
    private SdfWriter() {}

    public static String serialize(EdNode root) {
        return expr(root);
    }

    private static String expr(EdNode node) {
        if (node.groupRef != null) return "group(\"" + node.groupRef + "\")";
        String name = node.name();
        if (name.equals("const")) return fmt(node.params[0]);
        if (name.equals("x") || name.equals("y") || name.equals("z")) return name;
        if (name.equals("neg") || name.equals("abs")) {
            return name + "(" + expr(node.children.get(0)) + ")";
        }

        StringBuilder sb = new StringBuilder(name).append('(');
        int p = 0;
        int c = 0;
        boolean first = true;
        for (int i = 0; i < node.spec.layout.length(); i++) {
            char slot = node.spec.layout.charAt(i);
            if (!first) sb.append(", ");
            first = false;
            if (slot == 'p') sb.append(fmt(node.params[p++]));
            else sb.append(expr(node.children.get(c++)));
        }
        return sb.append(')').toString();
    }

    public static String fmt(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7f) return String.valueOf((int) v);
        String s = String.format("%.4f", v);
        // trim trailing zeros (keep at least one decimal)
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
