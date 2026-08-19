package mods.hexagon.sdf3d.editor;

/**
 * Sculpting brushes implemented as source rewrites. A brush is a sphere placed at a picked
 * surface point; the root expression is wrapped in the matching CSG operation so the edit stays
 * readable and re-parses through the normal pipeline.
 */
public final class SculptOps {
    private SculptOps() {}

    public enum Brush {
        CARVE,    // carve(base, brush)  = subtract (remove a blob)
        STAMP,    // stamp(base, brush)  = union (add a blob)
        BLEND,    // blend(k, base, brush) = smooth_union (smooth/relax)
        INFLATE,  // stamp with a large soft sphere (bulge a region outward)
        DEFLATE   // carve with a large soft sphere (dent a region inward)
    }

    public static String apply(String source, Brush brush, float x, float y, float z, float radius, float blendK) {
        RootExtractor.Span span = RootExtractor.root(source);
        String root = source.substring(span.start(), span.end());
        String before = source.substring(0, span.start());
        String after = source.substring(span.end());
        String brushExpr = String.format("sphere(%.4f, %.4f, %.4f, %.4f)", x, y, z, radius);
        String call = switch (brush) {
            case CARVE, DEFLATE -> "carve(" + root + ", " + brushExpr + ")";
            case STAMP, INFLATE -> "stamp(" + root + ", " + brushExpr + ")";
            case BLEND -> "blend(" + fmt(blendK) + ", " + root + ", " + brushExpr + ")";
        };
        return before + call + after;
    }

    private static String fmt(float v) {
        return String.format("%.4f", v);
    }
}
