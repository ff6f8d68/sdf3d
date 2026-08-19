package mods.hexagon.sdf3d.client;

import com.mojang.serialization.Codec;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.OptionEnum;

import java.util.List;

/**
 * Video-settings options. {@link #MODELS} toggles the {@code .s3d} overlay renderer and
 * {@link #POST_EFFECT} selects an SDF-only post effect. These are injected into
 * {@code VideoSettingsScreen} by {@code OptionsSubScreenMixin}.
 */
public final class SdfClientOptions {
    public static final OptionInstance<Boolean> MODELS = OptionInstance.createBoolean(
            "options.sdf3d.models",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.sdf3d.models.tooltip")),
            Sdf3dApi.isGpuRendererEnabled(),
            Sdf3dApi::setGpuRendererEnabled);

    public static final OptionInstance<Boolean> WORLD = OptionInstance.createBoolean(
            "options.sdf3d.world",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.sdf3d.world.tooltip")),
            Sdf3dApi.isWorldRendererEnabled(),
            Sdf3dApi::setWorldRendererEnabled);

    public static final OptionInstance<SdfPostEffect> POST_EFFECT = new OptionInstance<>(
            "options.sdf3d.postEffect",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.sdf3d.postEffect.tooltip")),
            OptionInstance.forOptionEnum(),
            new OptionInstance.Enum<>(List.of(SdfPostEffect.values()), Codec.INT.xmap(SdfPostEffect::byId, SdfPostEffect::getId)),
            SdfPostEffect.byId(Sdf3dApi.postEffect()),
            effect -> Sdf3dApi.setPostEffect(effect.getId()));

    private SdfClientOptions() {}

    public enum SdfPostEffect implements OptionEnum {
        NONE(0, "options.sdf3d.postEffect.none"),
        AMBIENT_OCCLUSION(1, "options.sdf3d.postEffect.ao"),
        GLOW(2, "options.sdf3d.postEffect.glow"),
        SOFT_SHADOW(3, "options.sdf3d.postEffect.softShadow");

        private final int id;
        private final String key;

        SdfPostEffect(int id, String key) {
            this.id = id;
            this.key = key;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public String getKey() {
            return key;
        }

        public static SdfPostEffect byId(int id) {
            for (SdfPostEffect effect : values()) {
                if (effect.id == id) return effect;
            }
            return NONE;
        }
    }
}
