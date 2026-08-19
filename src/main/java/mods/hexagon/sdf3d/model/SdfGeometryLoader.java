package mods.hexagon.sdf3d.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonDeserializationContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;

/**
 * Geometry loader registered under {@code sdf3d:sdf}. A model JSON can reference an
 * {@code .s3d} file the same way the obj loader references an {@code .obj}:
 * <pre>
 *   { "loader": "sdf3d:sdf", "model": "sdf3d:sdf/example_sphere" }
 * </pre>
 * The resulting baked model emits no mesh quads (SDF models are raycast, not meshed).
 */
public final class SdfGeometryLoader implements IGeometryLoader<SdfGeometry> {
    public static final SdfGeometryLoader INSTANCE = new SdfGeometryLoader();
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("sdf3d", "sdf");

    private SdfGeometryLoader() {}

    @Override
    public SdfGeometry read(JsonObject jsonObject, JsonDeserializationContext context) {
        ResourceLocation model = ResourceLocation.parse(GsonHelper.getAsString(jsonObject, "model"));
        boolean mesh = GsonHelper.getAsBoolean(jsonObject, "mesh", false);
        return new SdfGeometry(model, mesh);
    }
}
