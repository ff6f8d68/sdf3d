package mods.hexagon.sdf3d.render;

import mods.hexagon.sdf3d.Sdf3d;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Fires the fullscreen "render the world as SDF" pass after the solid terrain layer, so the
 * raymarched voxels overwrite the terrain (colour + depth) before entities are drawn on top.
 * Gated by the {@code SDF World} video option, which is off by default.
 */
@EventBusSubscriber(modid = Sdf3d.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class WorldRenderEvents {
    private WorldRenderEvents() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        if (!Sdf3dApi.isWorldRendererEnabled()) return;
        WorldSdfRenderer.render(event);
    }
}
