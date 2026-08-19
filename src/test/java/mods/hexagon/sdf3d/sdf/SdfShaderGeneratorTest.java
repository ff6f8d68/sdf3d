package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural checks for {@link SdfShaderGenerator}: the generated GLSL must inline the graph. */
class SdfShaderGeneratorTest {

    @Test
    void floatLiteralsAreValidGlsl() {
        assertEquals("1.0", SdfShaderGenerator.flt(1.0f));
        assertEquals("0.5", SdfShaderGenerator.flt(0.5f));
        assertEquals("-2.0", SdfShaderGenerator.flt(-2.0f));
        assertEquals("0.0", SdfShaderGenerator.flt(0.0f));
        assertEquals("1000000.0", SdfShaderGenerator.flt(1000000f));
        assertEquals("0.33333334", SdfShaderGenerator.flt(1.0f / 3.0f));
        assertEquals("0.0", SdfShaderGenerator.flt(Float.NaN));
        assertTrue(SdfShaderGenerator.flt(Float.POSITIVE_INFINITY).contains("1e30"));
    }

    @Test
    void generatedShaderInlinesGraphWithoutUniformArray() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "smooth_union(0.12, rounded_box(0, 0, 0, 0.5, 0.35, 0.5, 0.08), sphere(0.45, 0.35, 0, 0.4))");
        String src = SdfShaderGenerator.generateFragmentShader((SdfGraph) model.function());

        assertTrue(src.contains("float evalSdf(vec3 p)"));
        assertTrue(src.contains("return f" + ((SdfGraph) model.function()).rootIndex()));
        // No generic dynamic walk remains.
        assertFalse(src.contains("uNodes"));
        assertFalse(src.contains("uNodeCount"));
        assertFalse(src.contains("uRoot"));
        assertFalse(src.contains("for (int i = nodeCount"));
        assertFalse(src.contains("MAX_NODES"));
        assertFalse(src.contains("nodeVal("));
        // The graph opcodes are emitted as literal dispatch calls.
        assertTrue(src.contains("leafValue("));
        assertTrue(src.contains("binaryValue("));
    }

    @Test
    void generatedShaderDeclaresExactlyOnePtAndValuePerNode() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "union(translate(1, 2, 3, sphere(0, 0, 0, 0.5)), scale(2, 1, 1, box(0, 0, 0, 0.4, 0.4, 0.4)))");
        SdfGraph graph = (SdfGraph) model.function();
        String src = SdfShaderGenerator.generateFragmentShader(graph);

        // Two evaluators (evalSdf + evalLocal) each declare one pt/f per node.
        assertEquals(2 * graph.nodeCount(), countMatches(src, "vec3 pt[0-9]+"));
        assertEquals(2 * graph.nodeCount(), countMatches(src, "float f[0-9]+"));
        assertEquals(1, countMatches(src, "return f[0-9]+"));
        assertEquals(1, countMatches(src, "return l[0-9]+"));
        // The local-position tracker and in-game light uniforms are present.
        assertTrue(src.contains("vec3 evalLocal(vec3 p)"));
        assertTrue(src.contains("uniform vec3 uLightDir"));
        assertTrue(src.contains("uniform float uSkyLight"));
        assertTrue(src.contains("uniform float uBlockLight"));
    }

    @Test
    void generatedShaderHandlesEveryOpcodeClass() {
        String source =
                "union(" +
                "  keyframe_scale(2, 0, 0.5, 1, 1.5, 2, 0.5, 2, 0.5, sphere(0, 0, 0, 0.3))," +
                "  neg(round(0.05, box(0, 0, 0, 0.4, 0.4, 0.4)))" +
                ")";
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"), source);
        String src = SdfShaderGenerator.generateFragmentShader((SdfGraph) model.function());

        // leafValue for sphere/box, adjustUnary for neg/round, binaryValue for union,
        // transformPoint for the keyframe scale (a point transform).
        assertTrue(src.contains("leafValue("));
        assertTrue(src.contains("adjustUnary("));
        assertTrue(src.contains("binaryValue("));
        assertTrue(src.contains("transformPoint("));
        // Braces must be balanced.
        assertEquals(0, braceBalance(src));
    }

    @Test
    void maxDistScalesWithModelBounds() {
        float small = extractMaxDist(SdfShaderGenerator.generateFragmentShader(
                (SdfGraph) SdfParser.parse(ResourceLocation.parse("sdf3d:test"), "sphere(0, 0, 0, 0.5)").function()));
        float big = extractMaxDist(SdfShaderGenerator.generateFragmentShader(
                (SdfGraph) SdfParser.parse(ResourceLocation.parse("sdf3d:test"), "sphere(0, 0, 0, 50)").function()));
        // A radius-50 sphere has a ~100-unit diagonal; the far plane must grow with it or the
        // model is culled at its far side.
        assertTrue(big > small);
        assertTrue(big > 50.0f);
    }

    @Test
    void generatedShaderBracesBalancedForLargeModel() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "smooth_union(0.1," +
                "  union(torus(0, 0, 0, 0.8, 0.2), sphere(0, 0, 0, 0.5))," +
                "  intersect(cylinder(0, 0, 0, 0.6, 1.0), octahedron(0, 0, 0, 0.7))," +
                "  cone(0, 0.2, 0, 0.4, 0.8)," +
                "  hex_prism(0, 0, 0, 0.5, 0.6)," +
                "  capsule(-0.5, -0.5, 0, 0.5, 0.5, 0, 0.2))");
        SdfGraph graph = (SdfGraph) model.function();
        String src = SdfShaderGenerator.generateFragmentShader(graph);
        assertEquals(0, braceBalance(src));
        // Sanity: the graph's root return statement is emitted after the evalSdf signature.
        assertTrue(src.indexOf("return f" + graph.rootIndex() + ";") > src.indexOf("float evalSdf(vec3 p)"));
    }

    private static float extractMaxDist(String src) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#define MAX_DIST ([0-9.eE+-]+)").matcher(src);
        assertTrue(m.find(), "MAX_DIST define not found");
        return Float.parseFloat(m.group(1));
    }

    private static int countMatches(String haystack, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(haystack);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static int braceBalance(String src) {
        int depth = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }
}
