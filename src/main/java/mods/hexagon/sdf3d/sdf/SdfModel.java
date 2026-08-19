package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2fc;
import org.joml.Vector3fc;

public record SdfModel(ResourceLocation id, SdfNode function, SdfBounds bounds, SdfMaterial material) {
    public SdfModel {
        if (material == null) material = SdfMaterial.WHITE;
    }

    public float distance(Vector3fc point) {
        return function.distance(point);
    }

    public SdfMaterial material(Vector3fc point) {
        SdfMaterial nodeMaterial = function.material(point);
        return nodeMaterial == SdfMaterial.WHITE ? material : nodeMaterial;
    }

    public Vector2fc uv(Vector3fc point) {
        return function.uv(point);
    }
}
