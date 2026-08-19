package mods.hexagon.sdf3d.model;

import mods.hexagon.sdf3d.Sdf3d;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid = Sdf3d.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModelEvents {
    private ClientModelEvents() {}

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(SdfGeometryLoader.ID, SdfGeometryLoader.INSTANCE);
    }
}
