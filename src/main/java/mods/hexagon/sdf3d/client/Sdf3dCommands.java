package mods.hexagon.sdf3d.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import mods.hexagon.sdf3d.Sdf3d;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Client-side commands for toggling the renderers during in-game testing. */
@EventBusSubscriber(modid = Sdf3d.MODID, value = Dist.CLIENT)
public final class Sdf3dCommands {
    private Sdf3dCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sdf3d");

        root.then(Commands.literal("sdf").executes(ctx -> {
            boolean now = !Sdf3dApi.isGpuRendererEnabled();
            Sdf3dApi.setGpuRendererEnabled(now);
            ctx.getSource().sendSuccess(() -> Component.literal("SDF overlay renderer: " + (now ? "ON" : "OFF")), false);
            return 1;
        }));

        root.then(Commands.literal("world").executes(ctx -> {
            boolean now = !Sdf3dApi.isWorldRendererEnabled();
            Sdf3dApi.setWorldRendererEnabled(now);
            ctx.getSource().sendSuccess(() -> Component.literal("SDF world renderer: " + (now ? "ON" : "OFF")), false);
            return 1;
        }));

        dispatcher.register(root);
    }
}
