package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.platform.NativeImage;
import mods.hexagon.sdf3d.api.PixelModifier;
import mods.hexagon.sdf3d.api.Sdf3dApi;

import java.util.List;

/** Applies {@link PixelModifier}s registered via {@link Sdf3dApi} to a rendered image. */
public final class SdfPixelPost {
    private SdfPixelPost() {}

    public static void apply(NativeImage image) {
        List<PixelModifier> modifiers = Sdf3dApi.pixelModifiers();
        if (modifiers.isEmpty()) return;
        int width = image.getWidth(), height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = abgrToArgb(image.getPixelRGBA(x, y));
                for (PixelModifier modifier : modifiers) {
                    argb = modifier.modify(x, y, width, height, argb);
                }
                image.setPixelRGBA(x, y, argbToAbgr(argb));
            }
        }
    }

    public static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
