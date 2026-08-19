package mods.hexagon.sdf3d.model;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A baked model for an {@code .s3d} reference. By default it returns no quads because the model
 * is raycast, not meshed. With {@code mesh = true} (used for item icons / GUI previews) it
 * voxelizes the SDF into a coarse mesh so the item shows the real shape instead of a placeholder.
 */
public final class SdfBakedModel implements IDynamicBakedModel {
    private final ResourceLocation model;
    private final boolean mesh;
    private final TextureAtlasSprite meshSprite;
    private final boolean useAmbientOcclusion;
    private final boolean gui3d;
    private final boolean usesBlockLight;
    private final TextureAtlasSprite particle;
    private List<BakedQuad> meshQuads;

    public SdfBakedModel(ResourceLocation model, boolean mesh, TextureAtlasSprite meshSprite,
                         boolean useAmbientOcclusion, boolean gui3d, boolean usesBlockLight,
                         TextureAtlasSprite particle) {
        this.model = model;
        this.mesh = mesh;
        this.meshSprite = meshSprite;
        this.useAmbientOcclusion = useAmbientOcclusion;
        this.gui3d = gui3d;
        this.usesBlockLight = usesBlockLight;
        this.particle = particle;
    }

    public ResourceLocation model() {
        return model;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData extraData, @Nullable RenderType renderType) {
        if (!mesh || side != null) return List.of();
        if (meshQuads == null) {
            meshQuads = SdfMeshGenerator.generate(SdfModelManager.get(model), meshSprite);
        }
        return meshQuads;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return useAmbientOcclusion;
    }

    @Override
    public boolean isGui3d() {
        return gui3d;
    }

    @Override
    public boolean usesBlockLight() {
        return usesBlockLight;
    }

    @Override
    public boolean isCustomRenderer() {
        // Meshed previews render their quads normally; the raymarched world model has no quads.
        return !mesh;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particle;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
