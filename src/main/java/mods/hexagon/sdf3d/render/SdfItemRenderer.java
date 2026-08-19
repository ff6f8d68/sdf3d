package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import mods.hexagon.sdf3d.model.SdfModelManager;
import mods.hexagon.sdf3d.render.SdfRender;
import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/**
 * Renders an {@code .s3d} item by raymarching it directly (no mesh) via {@link SdfRender}.
 * Works for both the orthographic GUI view and the perspective first-person/third-person views.
 */
public class SdfItemRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("sdf3d", "sdf/example_sphere");

    public SdfItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource buffer, int light, int overlay) {
        if (!Sdf3dApi.isGpuRendererEnabled()) return;
        // Light the model from the real world sun so reflections/self-shadows follow the sky.
        // No placed point lights apply to an item in a GUI/hand slot.
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            float a = level.getTimeOfDay(partial) * (float) (Math.PI * 2.0);
            SdfRender.setSunDirection(new Vector3f(Mth.cos(a), Mth.sin(a), 0.0f));
        }
        SdfRender.clearPointLights();
        SdfModel model = SdfModelManager.get(MODEL);
        if (!(model.function() instanceof SdfGraph)) return;
        // Depth test against the world everywhere except the GUI (inventory/hotbar), where the
        // item floats in screen space; ground/held items must be occluded by terrain and
        // entities and occlude things behind them.
        boolean depthTest = context != ItemDisplayContext.GUI;
        SdfRender.renderModel(model, poseStack, depthTest, light);
    }
}
