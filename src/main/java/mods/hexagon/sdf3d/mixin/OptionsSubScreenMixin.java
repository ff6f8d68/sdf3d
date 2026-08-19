package mods.hexagon.sdf3d.mixin;

import mods.hexagon.sdf3d.client.SdfClientOptions;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Adds the SDF renderer toggle and post-effect selector to the video settings list. We mixin
 * {@link OptionsSubScreen} (where the {@code list} field is declared) and guard on the actual
 * screen type so the options only appear on {@link VideoSettingsScreen}.
 */
@Mixin(OptionsSubScreen.class)
public abstract class OptionsSubScreenMixin {
    @Shadow
    @Nullable
    protected OptionsList list;

    @Inject(method = "addContents", at = @At("TAIL"))
    private void sdf3d$addSdfOptions(CallbackInfo ci) {
        if (!((Object) this instanceof VideoSettingsScreen)) return;
        if (this.list != null) {
            this.list.addBig(SdfClientOptions.MODELS);
            this.list.addBig(SdfClientOptions.WORLD);
            this.list.addBig(SdfClientOptions.POST_EFFECT);
        }
    }
}
