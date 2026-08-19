package mods.hexagon.sdf3d.model;

import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;

import java.util.function.Function;

public final class SdfGeometry implements IUnbakedGeometry<SdfGeometry> {
    private final ResourceLocation model;
    private final boolean mesh;

    public SdfGeometry(ResourceLocation model, boolean mesh) {
        this.model = model;
        this.mesh = mesh;
    }

    public ResourceLocation model() {
        return model;
    }

    public boolean mesh() {
        return mesh;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                           Function<Material, TextureAtlasSprite> spriteGetter,
                           ModelState modelState, ItemOverrides overrides) {
        TextureAtlasSprite particle = spriteGetter.apply(
                new Material(TextureAtlas.LOCATION_BLOCKS, MissingTextureAtlasSprite.getLocation()));
        TextureAtlasSprite meshSprite = spriteGetter.apply(
                new Material(TextureAtlas.LOCATION_BLOCKS, ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete")));
        return new SdfBakedModel(model, mesh, meshSprite, context.useAmbientOcclusion(), context.isGui3d(), context.useBlockLight(), particle);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> modelGetter, IGeometryBakingContext context) {
    }
}
