package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;

/** Material data returned for a ray hit. The texture is sampled on the CPU fallback path. */
public record SdfMaterial(ResourceLocation texture, int tint, boolean emissive, float roughness, float metallic) {
    public static final SdfMaterial WHITE = new SdfMaterial(null, 0xFFFFFFFF, false, 0.7f, 0.0f);

    public SdfMaterial {
        roughness = Math.max(0.0f, Math.min(1.0f, roughness));
        metallic = Math.max(0.0f, Math.min(1.0f, metallic));
    }

    /** Convenience constructor for non-metallic materials. */
    public SdfMaterial(ResourceLocation texture, int tint, boolean emissive, float roughness) {
        this(texture, tint, emissive, roughness, 0.0f);
    }

    public int tint(float light) {
        int alpha = (tint >>> 24) & 0xFF;
        int red = (tint >>> 16) & 0xFF;
        int green = (tint >>> 8) & 0xFF;
        int blue = tint & 0xFF;
        float factor = emissive ? 1.0f : Math.max(0.0f, Math.min(1.0f, light));
        return alpha << 24
                | ((int) (red * factor) & 0xFF) << 16
                | ((int) (green * factor) & 0xFF) << 8
                | ((int) (blue * factor) & 0xFF);
    }
}
