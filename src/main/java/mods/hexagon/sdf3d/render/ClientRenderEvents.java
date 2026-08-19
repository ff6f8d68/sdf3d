package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import mods.hexagon.sdf3d.Sdf3d;
import mods.hexagon.sdf3d.block.SdfBlockEntityRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

@EventBusSubscriber(modid = Sdf3d.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientRenderEvents {
    private ClientRenderEvents() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Sdf3d.SDF_BLOCK_ENTITY.get(), SdfBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            // Drop any specialized programs left over from a previous resource reload.
            SdfShaderCompiler.invalidate();

            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Sdf3d.MODID, "sdf_item"),
                            DefaultVertexFormat.POSITION),
                    SdfRender::setShader);
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Sdf3d.MODID, "world_ray"),
                            DefaultVertexFormat.POSITION),
                    WorldSdfRenderer::setShader);

            // Bake a specialized shader for every .s3d model up front, so the first time a
            // model is viewed there is no compile hitch and no lazy-first-frame stall.
            SdfShaderCompiler.precompileAll(event.getResourceProvider());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create shader", e);
        }
    }
}
