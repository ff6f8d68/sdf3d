package mods.hexagon.sdf3d.api;

/**
 * A screen-space, per-pixel post processor. Modifiers registered through
 * {@link Sdf3dApi#addPixelModifier} run after the SDF image is shaded, in registration
 * order, so devs can tweak individual pixels in post.
 */
@FunctionalInterface
public interface PixelModifier {
    /** @return the replacement color for the pixel, in 0xAARRGGBB layout. */
    int modify(int x, int y, int width, int height, int colorArgb);
}
