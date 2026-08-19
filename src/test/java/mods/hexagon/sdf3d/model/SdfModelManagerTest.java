package mods.hexagon.sdf3d.model;

import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdfModelManagerTest {

    @Test
    void resolvesResourceLocation() {
        assertEquals(ResourceLocation.parse("sdf3d:sdf/example_sphere.s3d"),
                SdfModelManager.resourceLocationFor(ResourceLocation.parse("sdf3d:sdf/example_sphere")));
    }

    @Test
    void loadsBundledExampleModel() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/sdf3d/sdf/example_sphere.s3d")) {
            assertNotNull(in, "bundled example_sphere.s3d should be on the classpath");
            SdfModel model = SdfModelManager.load(ResourceLocation.parse("sdf3d:sdf/example_sphere"), in);
            // The sculpted humanoid: inside the head (morphs but always solid at its centre)
            // and inside the chest, but outside everywhere far away.
            assertTrue(model.distance(new Vector3f(0, 1.62f, 0)) < 0, "head centre should be inside");
            assertTrue(model.distance(new Vector3f(0, 1.14f, 0)) < 0, "chest centre should be inside");
            assertTrue(model.distance(new Vector3f(10, 10, 10)) > 0, "far away should be outside");
        }
    }

    @Test
    void bundledExampleIncorporatesGroupsMorphAndKeyframes() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/sdf3d/sdf/example_sphere.s3d")) {
            SdfModel model = SdfModelManager.load(ResourceLocation.parse("sdf3d:sdf/example_sphere"), in);
            SdfGraph graph = (SdfGraph) model.function();

            // The showcase model must actually use the headline features: sculpting brushes,
            // group references (flattened subtree duplication), morphing and keyframes.
            float[] ops = graph.data();
            int morphs = 0, keyframes = 0, carves = 0, stamps = 0, smooths = 0;
            for (int i = 0; i < graph.nodeCount(); i++) {
                int op = (int) ops[i * 12];
                if (op == SdfGraph.MORPH) morphs++;
                if (op == SdfGraph.KEYFRAME_SCALE || op == SdfGraph.KEYFRAME_TRANSLATE
                        || op == SdfGraph.KEYFRAME_ROTATE) keyframes++;
                if (op == SdfGraph.SUBTRACT) carves++;
                if (op == SdfGraph.UNION) stamps++;
                if (op == SdfGraph.SMOOTH_UNION) smooths++;
            }
            assertTrue(morphs > 0, "model should use morph");
            assertTrue(keyframes >= 5, "model should use several keyframe tracks (arms, legs, heart, head)");
            assertTrue(carves >= 4, "model should sculpt with carve (sockets, mouth, waist, window)");
            assertTrue(stamps >= 6, "model should sculpt with stamp (eyes, ears, nose, hair, buckle)");
            assertTrue(smooths > 0, "model should blend joints");
            // The generic uniform-array fallback path caps the graph at MAX_NODES; the
            // specialized per-model shader has no limit, but staying under keeps both paths safe.
            assertTrue(graph.nodeCount() <= SdfGraph.maxNodes(),
                    "model has " + graph.nodeCount() + " nodes (cap " + SdfGraph.maxNodes() + ")");
        }
    }
}
