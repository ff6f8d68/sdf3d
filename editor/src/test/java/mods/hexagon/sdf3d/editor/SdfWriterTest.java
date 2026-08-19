package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfModel;
import mods.hexagon.sdf3d.sdf.SdfParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SdfWriterTest {

    private static SdfModel parse(String source) {
        return SdfParser.parse(ResourceLocation.parse("editor:test"), source);
    }

    @Test
    void everyOpSerializesAndParses() {
        for (SdfNodeSpec spec : SdfNodeSpec.all()) {
            EdNode node = new EdNode(spec);
            while (node.children.size() < spec.childCount()) {
                node.children.add(new EdNode("sphere"));
            }
            String expr = SdfWriter.serialize(node);
            String src = "texture = \"editor:grid\";\n" + expr + "\n";
            try {
                assertNotNull(parse(src), "null model for: " + expr);
            } catch (RuntimeException e) {
                fail("op '" + spec.name + "' failed to round-trip: " + expr + " -> " + e.getMessage());
            }
        }
    }

    @Test
    void documentSourceParses() {
        ModelDocument doc = new ModelDocument();
        doc.root = new EdNode("translate");
        doc.root.children.add(new EdNode("sphere"));
        doc.root.params[1] = 0.8f;
        doc.easing = "easeInOut";
        doc.texture = "editor:grid";
        String src = doc.toSource();
        assertTrue(src.contains("easing = easeInOut;"));
        assertTrue(src.contains("translate("));
        assertNotNull(parse(src));
    }

    @Test
    void carveStampBlendMapToExpectedOps() {
        assertNotNull(parse(SdfWriter.serialize(child("carve"))));
        assertNotNull(parse(SdfWriter.serialize(child("stamp"))));
        assertNotNull(parse(SdfWriter.serialize(child("blend"))));
    }

    private static EdNode child(String name) {
        EdNode n = new EdNode(name);
        while (n.children.size() < n.spec.childCount()) n.children.add(new EdNode("sphere"));
        return n;
    }
}
