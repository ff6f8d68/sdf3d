package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SdfExtendedShapesTest {

    private static SdfModel parse(String source) {
        return SdfParser.parse(ResourceLocation.parse("sdf3d:test"), source);
    }

    @Test
    void coneEvaluates() {
        SdfModel model = parse("cone(0, 0, 0, 0.5, 1.0)");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "cone centre should be inside");
        assertTrue(model.distance(new Vector3f(2, 0, 0)) > 0, "far point should be outside");
        assertTrue(model.distance(new Vector3f(0, -0.45f, 0)) < 0, "base should be inside");
        assertTrue(model.distance(new Vector3f(0, 0.6f, 0)) > 0, "above the tip should be outside");
    }

    @Test
    void hexPrismEvaluates() {
        SdfModel model = parse("hex_prism(0, 0, 0, 0.5, 1.0)");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "centre should be inside");
        assertTrue(model.distance(new Vector3f(1, 0, 0)) > 0, "outside the circumradius");
        assertTrue(model.distance(new Vector3f(0, 0.6f, 0)) > 0, "above the prism");
    }

    @Test
    void octahedronEvaluates() {
        SdfModel model = parse("octahedron(0, 0, 0, 0.5)");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "centre should be inside");
        assertTrue(model.distance(new Vector3f(1, 0, 0)) > 0, "outside should be outside");
    }

    @Test
    void spinAnimatesAroundAxis() {
        SdfModel model = parse("spin(0, 1, 0, 360, sphere(1, 0, 0, 0.2))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(0.0f);
        assertTrue(model.distance(new Vector3f(1, 0, 0)) < 0, "sphere should be at origin at t=0");

        graph.setTime(0.25f); // 90 degrees around +Y: (1,0,0) -> (0,0,-1)
        assertTrue(model.distance(new Vector3f(0, 0, -1)) < 0, "sphere should have rotated 90 deg");
        assertTrue(model.distance(new Vector3f(1, 0, 0)) > 0, "original position should now be empty");
    }

    @Test
    void bobTranslatesAlongAxis() {
        SdfModel model = parse("bob(0, 1, 0, 0.3, 1, sphere(0, 0, 0, 0.1))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(0.0f);
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "sphere should be at origin at t=0");

        graph.setTime(0.25f); // sin(pi/2) = 1 -> offset +0.3 along Y
        assertTrue(model.distance(new Vector3f(0, 0.3f, 0)) < 0, "sphere should have bobbed up");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) > 0, "origin should now be empty");
    }

    @Test
    void pulseScalesOverTime() {
        SdfModel model = parse("pulse(0.5, 1.5, 1, sphere(0, 0, 0, 0.3))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(0.0f); // scale = 1.0
        assertTrue(model.distance(new Vector3f(0.35f, 0, 0)) > 0, "radius 0.3 at t=0");

        graph.setTime(0.25f); // scale = 1.5 -> radius 0.45
        assertTrue(model.distance(new Vector3f(0.35f, 0, 0)) < 0, "radius 0.45 at t=0.25");
    }

    @Test
    void morphBlendsBetweenShapes() {
        // box at origin, sphere at (1,0,0); over a 2s period the blend oscillates 0..1.
        SdfModel model = parse("morph(box(0, 0, 0, 0.4, 0.4, 0.4), sphere(1, 0, 0, 0.4), 2)");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(1.5f); // blend = 0 -> pure box
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "origin should be inside the box");
        assertTrue(model.distance(new Vector3f(1, 0, 0)) > 0, "sphere centre should be outside the box");

        graph.setTime(0.5f); // blend = 1 -> pure sphere
        assertTrue(model.distance(new Vector3f(1, 0, 0)) < 0, "sphere centre should be inside the sphere");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) > 0, "origin should be outside the sphere");

        assertTrue(model.distance(new Vector3f(3, 3, 3)) > 0, "far point should be outside");
    }

    @Test
    void twistDeformsAroundY() {
        // A narrow box along Y; twisting rotates the top relative to the bottom.
        SdfModel model = parse("twist(2, box(0, 0, 0, 0.3, 0.8, 0.3))");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "centre should be inside");
        assertTrue(model.distance(new Vector3f(2, 0, 0)) > 0, "far point should be outside");
    }

    @Test
    void bendDeformsAroundZ() {
        SdfModel model = parse("bend(1, box(0, 0, 0, 0.3, 0.8, 0.3))");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "centre should be inside");
        assertTrue(model.distance(new Vector3f(2, 0, 0)) > 0, "far point should be outside");
    }

    @Test
    void keyframeScaleInterpolates() {
        // period 2s: 0.5x at t=0 -> 1.5x at t=1 -> back to 0.5x at t=2.
        SdfModel model = parse("keyframe_scale(2, 0, 0.5, 1, 1.5, 2, 0.5, 2, 0.5, sphere(0, 0, 0, 0.3))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(0.0f); // scale 0.5 -> radius 0.15
        assertTrue(model.distance(new Vector3f(0.2f, 0, 0)) > 0, "radius 0.15 at t=0");

        graph.setTime(1.0f); // scale 1.5 -> radius 0.45
        assertTrue(model.distance(new Vector3f(0.35f, 0, 0)) < 0, "radius 0.45 at t=1");
    }

    @Test
    void keyframeTranslateInterpolates() {
        // period 4s: position (0,0,0) at t=0 -> (0,1,0) at t=2 -> back to (0,0,0) at t=4.
        SdfModel model = parse("keyframe_translate(4, 0, 0,0,0, 2, 0,1,0, sphere(0, 0, 0, 0.3))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(0.0f);
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "sphere should be at origin at t=0");

        graph.setTime(2.0f);
        assertTrue(model.distance(new Vector3f(0, 1, 0)) < 0, "sphere should have moved up at t=2");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) > 0, "origin should now be empty");
    }

    @Test
    void groupDefinitionAndReference() {
        SdfModel model = parse(
                "group(\"wing\") { box(0, 0, 0, 0.4, 0.1, 0.3), translate(0, 0, 0.3, sphere(0, 0, 0, 0.15)) }\n" +
                "union(translate(-0.5, 0, 0, group(\"wing\")), translate(0.5, 0, 0, group(\"wing\")))");
        assertTrue(model.distance(new Vector3f(-0.5f, 0, 0)) < 0, "left wing should be present");
        assertTrue(model.distance(new Vector3f(0.5f, 0, 0)) < 0, "right wing should be present");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) > 0, "gap between wings should be empty");
    }

    @Test
    void carveSubtractsBrush() {
        // carve = subtract: a sphere carved out of a box leaves the origin empty.
        SdfModel model = parse("carve(box(0, 0, 0, 0.6, 0.6, 0.6), sphere(0, 0, 0, 0.4))");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) > 0, "carved centre should be empty");
        assertTrue(model.distance(new Vector3f(0.5f, 0, 0)) < 0, "box corner should remain");
    }

    @Test
    void stampAddsBrush() {
        // stamp = union: a sphere added onto a box.
        SdfModel model = parse("stamp(box(0, 0, 0, 0.4, 0.4, 0.4), sphere(1, 0, 0, 0.3))");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "box should be present");
        assertTrue(model.distance(new Vector3f(1, 0, 0)) < 0, "stamped sphere should be present");
    }

    @Test
    void blendSmoothlyUnites() {
        // blend = smooth_union.
        SdfModel model = parse("blend(0.2, sphere(0, 0, 0, 0.4), sphere(0.7, 0, 0, 0.4))");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0, "first sphere");
        assertTrue(model.distance(new Vector3f(0.7f, 0, 0)) < 0, "second sphere");
        assertTrue(model.distance(new Vector3f(3, 0, 0)) > 0, "far point outside");
    }

    @Test
    void easingAffectsKeyframeInterpolation() {
        // keyframe_translate: (0,0,0) at t=0 -> (0,2,0) at t=2, over a 4s loop.
        SdfModel linear = parse("keyframe_translate(4, 0, 0,0,0, 2, 0,2,0, sphere(0, 0, 0, 0.1))");
        SdfModel easeIn = parse("easing = easeIn;\nkeyframe_translate(4, 0, 0,0,0, 2, 0,2,0, sphere(0, 0, 0, 0.1))");
        SdfGraph linearG = (SdfGraph) linear.function();
        SdfGraph easeInG = (SdfGraph) easeIn.function();

        linearG.setTime(1.0f);
        easeInG.setTime(1.0f);

        // linear at t=1: fraction 0.5 -> centre y=1. easeIn (quadratic): fraction 0.25 -> centre y=0.5.
        assertTrue(linear.distance(new Vector3f(0, 1, 0)) < 0, "linear midpoint at y=1");
        assertTrue(easeIn.distance(new Vector3f(0, 0.5f, 0)) < 0, "easeIn midpoint at y=0.5");
        assertTrue(easeIn.distance(new Vector3f(0, 1, 0)) > 0, "easeIn not yet at y=1");
    }

    @Test
    void keyframeRotateLoopsContinuously() {
        // period 2s: 0 deg at t=0 -> 90 deg at t=1 -> back to 0 deg at t=2, looping smoothly
        // (a triangle wave) rather than snapping back at the wrap.
        SdfModel model = parse("keyframe_rotate(0, 0, 1, 2, 0, 0, 1, 90, sphere(1, 0, 0, 0.2))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(1.0f); // 90 deg -> sphere at +Y
        assertTrue(model.distance(new Vector3f(0, 1, 0)) < 0, "sphere should be at +Y at t=1");

        graph.setTime(1.5f); // halfway back -> 45 deg -> (cos45, sin45, 0)
        assertTrue(model.distance(new Vector3f(0.707f, 0.707f, 0)) < 0, "sphere should be at 45 deg at t=1.5");
        assertTrue(model.distance(new Vector3f(0, 1, 0)) > 0, "sphere should have left +Y at t=1.5");

        graph.setTime(2.0f); // wraps to t=0 -> 0 deg -> back at +X (continuity at the loop)
        assertTrue(model.distance(new Vector3f(1, 0, 0)) < 0, "sphere should be back at +X at t=2");
    }

    @Test
    void keyframeRotateInterpolates() {
        // period 2s: 0 deg at t=0 -> 90 deg at t=1 around +Z (box at +X swings up to +Y).
        SdfModel model = parse("keyframe_rotate(0, 0, 1, 2, 0, 0, 1, 90, sphere(1, 0, 0, 0.2))");
        SdfGraph graph = (SdfGraph) model.function();

        graph.setTime(0.0f);
        assertTrue(model.distance(new Vector3f(1, 0, 0)) < 0, "sphere should be at +X at t=0");

        graph.setTime(1.0f); // 90 deg around +Z: (1,0,0) -> (0,1,0)
        assertTrue(model.distance(new Vector3f(0, 1, 0)) < 0, "sphere should have rotated to +Y at t=1");
        assertTrue(model.distance(new Vector3f(1, 0, 0)) > 0, "+X should now be empty");
    }
}
