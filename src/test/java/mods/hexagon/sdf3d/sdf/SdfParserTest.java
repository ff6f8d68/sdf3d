package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdfParserTest {

    @Test
    void parsesAndEvaluatesSphere() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"), "sphere(1, 2, 3, 0.5)");
        assertEquals(-0.5f, model.distance(new Vector3f(1, 2, 3)), 1e-4f);
        assertEquals(0.0f, model.distance(new Vector3f(1.5f, 2, 3)), 1e-3f);
        assertEquals(0.5f, model.distance(new Vector3f(2, 2, 3)), 1e-4f);
    }

    @Test
    void parsesArithmeticSdf() {
        // x*x + y*y + z*z - 1 (algebraic sphere equation; tests raw arithmetic + x/y/z leaves)
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "sub(add(mul(x, x), add(mul(y, y), mul(z, z))), 1)");
        assertEquals(-1.0f, model.distance(new Vector3f(0, 0, 0)), 1e-4f);
        assertEquals(0.0f, model.distance(new Vector3f(1, 0, 0)), 1e-3f);
    }

    @Test
    void parsesTransformsAndCombinators() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "union(sphere(0, 0, 0, 0.5), translate(1, 0, 0, sphere(0, 0, 0, 0.25)))");
        assertTrue(model.distance(new Vector3f(0, 0, 0)) < 0);
        assertTrue(model.distance(new Vector3f(1, 0, 0)) < 0);
        assertTrue(model.distance(new Vector3f(0.625f, 0, 0)) > 0);
    }

    @Test
    void parsesMaterialDirectives() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "texture = \"sdf3d:textures/foo\";\n emissive = true;\n sphere(0, 0, 0, 1)");
        assertEquals(ResourceLocation.parse("sdf3d:textures/foo"), model.material().texture());
        assertTrue(model.material().emissive());
    }

    @Test
    void parsesPbrMaterialDirectives() {
        SdfModel model = SdfParser.parse(ResourceLocation.parse("sdf3d:test"),
                "roughness = 0.25;\n metallic = 0.9;\n sphere(0, 0, 0, 1)");
        assertEquals(0.25f, model.material().roughness(), 1e-4f);
        assertEquals(0.9f, model.material().metallic(), 1e-4f);
    }
}
