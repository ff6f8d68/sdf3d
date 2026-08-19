package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfModel;
import mods.hexagon.sdf3d.sdf.SdfParser;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCoreTest {

    private static SdfModel parse(String source) {
        return SdfParser.parse(ResourceLocation.parse("editor:test"), source);
    }

    @Test
    void raycastsSphere() {
        SdfModel m = parse("sphere(0, 0, 0, 0.5)");
        SdfRaycaster.Hit hit = SdfRaycaster.march(m, new Vector3f(0, 0, 5), new Vector3f(0, 0, -1), 10, 256, 0.001f);
        assertNotNull(hit);
        assertEquals(4.5f, hit.distance(), 0.01f);
        assertTrue(hit.normal().z() > 0.9f, "front normal should point +Z");
    }

    @Test
    void raycastMissIsNull() {
        SdfModel m = parse("sphere(0, 0, 0, 0.5)");
        SdfRaycaster.Hit hit = SdfRaycaster.march(m, new Vector3f(10, 0, 0), new Vector3f(0, 0, -1), 10, 256, 0.001f);
        assertNull(hit);
    }

    @Test
    void thinCarveShellIsNotSeeThrough() {
        // A 0.05-thick shell carved out of a unit sphere. A ray aimed at the centre must land on
        // the outer face (z=1) rather than step over the thin wall and leak out the far side.
        SdfModel m = parse("subtract(sphere(0, 0, 0, 1), sphere(0, 0, 0, 0.95))");
        SdfRaycaster.Hit hit = SdfRaycaster.march(m, new Vector3f(0, 0, 5), new Vector3f(0, 0, -1), 10, 512, 0.0005f);
        assertNotNull(hit);
        assertEquals(4.0f, hit.distance(), 0.01f);
        assertTrue(hit.normal().z() > 0.9f, "outer face normal should point +Z");
    }

    @Test
    void picksClosestLeafInUnion() {
        ModelDocument doc = new ModelDocument();
        doc.root = new EdNode("union");
        EdNode left = new EdNode("sphere");
        left.setParams(-1, 0, 0, 0.4);
        EdNode right = new EdNode("sphere");
        right.setParams(1, 0, 0, 0.4);
        doc.root.children.add(left);
        doc.root.children.add(right);
        EdNode picked = EdPicker.pick(doc.root, doc.groups, new Vector3f(1.4f, 0, 0), 0);
        assertSame(right, picked);
    }

    @Test
    void picksLeafThroughTranslate() {
        ModelDocument doc = new ModelDocument();
        EdNode t = new EdNode("translate");
        t.setParams(0, 2, 0);
        EdNode s = new EdNode("sphere");
        s.setParams(0, 0, 0, 0.5);
        t.children.add(s);
        doc.root = t;
        EdNode picked = EdPicker.pick(doc.root, doc.groups, new Vector3f(0, 2.5f, 0), 0);
        assertSame(s, picked);
    }

    @Test
    void softShadowBlocksAndClears() {
        SdfModel m = parse("sphere(0, 0, 0, 0.5)");
        // Light towards +Z: the far side (-Z) is occluded, a point beyond the near side is lit.
        float blocked = SdfRaycaster.softShadow(m, new Vector3f(0, 0, -0.5f), new Vector3f(0, 0, 1), 10, 128, 0.001f, 12f);
        float clear = SdfRaycaster.softShadow(m, new Vector3f(0, 0, 0.6f), new Vector3f(0, 0, 1), 10, 128, 0.001f, 12f);
        assertEquals(0f, blocked, 1e-3f);
        assertEquals(1f, clear, 1e-3f);
    }

    @Test
    void findsRootSpanAcrossDirectives() {
        String src = "texture = \"editor:grid\";\nroughness = 0.6;\n\nspin(0, 1, 0, 20, sphere(0, 0, 0, 1))\n";
        RootExtractor.Span s = RootExtractor.root(src);
        assertEquals("spin(0, 1, 0, 20, sphere(0, 0, 0, 1))", src.substring(s.start(), s.end()));
    }

    @Test
    void carveWrapsRoot() {
        String src = "sphere(0, 0, 0, 1);\n";
        String out = SculptOps.apply(src, SculptOps.Brush.CARVE, 0.5f, 0.5f, 0.5f, 0.2f, 0f);
        assertTrue(out.startsWith("carve(sphere(0, 0, 0, 1), sphere(0.5000, 0.5000, 0.5000, 0.2000))"));
        // Result must still parse.
        assertNotNull(parse(out));
    }

    @Test
    void blendAndStampParse() {
        assertNotNull(parse(SculptOps.apply("sphere(0,0,0,1);", SculptOps.Brush.BLEND, 0, 0, 0, 0.2f, 0.15f)));
        assertNotNull(parse(SculptOps.apply("sphere(0,0,0,1);", SculptOps.Brush.STAMP, 0, 0, 0, 0.2f, 0f)));
    }

    @Test
    void textureSamplesWrapAndStamps() {
        TextureStore t = new TextureStore(8, 8);
        t.clear(0xFF000000);
        t.stamp(4, 4, 2, 0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, t.getPixel(4, 4));
        // UV wraps: u=1.5 -> x=4, v=1.0 -> y=0.
        assertEquals(t.getPixel(4, 0), t.sample(1.5f, 1.0f));
    }

    @Test
    void buildsNodeTreeWithChildren() {
        SdfModel m = parse("union(sphere(0,0,0,0.5), box(0,0,0,0.4,0.4,0.4))");
        NodeTree.Node root = NodeTree.build((mods.hexagon.sdf3d.sdf.SdfGraph) m.function());
        assertEquals(2, root.children().size());
        assertTrue(root.label().startsWith("union"));
    }

    @Test
    void documentSnapshotIsDeepAndRestorable() {
        ModelDocument doc = new ModelDocument();
        doc.root = new EdNode("union");
        doc.root.children.add(new EdNode("sphere"));
        doc.root.children.add(new EdNode("box"));
        doc.makeGroup("g", new EdNode("sphere"));
        doc.tint = 0xFF00FF00;
        doc.roughness = 0.9f;
        String before = doc.toSource();

        ModelDocument snap = doc.snapshot();

        // Mutating the original must not affect the snapshot (deep copy).
        doc.root.children.get(0).params[3] = 2.0f;
        doc.tint = 0xFF0000FF;
        assertNotEquals(before, doc.toSource());

        doc.restore(snap);
        assertEquals(before, doc.toSource());
        assertEquals(0xFF00FF00, doc.tint);
        assertEquals(0.9f, doc.roughness, 0.001f);
        assertTrue(doc.groups.containsKey("g"));
    }
}
