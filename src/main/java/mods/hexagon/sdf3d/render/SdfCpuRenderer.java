package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.platform.NativeImage;
import mods.hexagon.sdf3d.render.SdfRaycaster.RayHit;
import mods.hexagon.sdf3d.scene.SdfScene;
import mods.hexagon.sdf3d.sdf.SdfMaterial;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * CPU reference renderer. Renders an {@link SdfModel} or {@link SdfScene} into a
 * {@link NativeImage} by sphere tracing every pixel. It is intentionally correct (texture +
 * UV + smoothed lighting) rather than fast; the GPU path is the optimized equivalent.
 */
public final class SdfCpuRenderer {
    private static final Vector3f LIGHT_DIR = new Vector3f(0.5f, 1.0f, 0.3f).normalize();

    private SdfCpuRenderer() {}

    public static NativeImage renderModel(SdfModel model, SdfCamera camera, int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        renderModel(model, camera, image);
        return image;
    }

    public static void renderModel(SdfModel model, SdfCamera camera, NativeImage out) {
        int width = out.getWidth(), height = out.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float ndcX = (x + 0.5f) / width * 2.0f - 1.0f;
                float ndcY = 1.0f - (y + 0.5f) / height * 2.0f;
                Vector3f dir = camera.rayDirection(ndcX, ndcY);
                RayHit hit = SdfRaycaster.march(model, camera.position(), dir);
                out.setPixelRGBA(x, y, hit == null ? 0 : shade(hit));
            }
        }
        SdfPixelPost.apply(out);
    }

    public static void renderScene(SdfScene scene, SdfCamera camera, NativeImage out) {
        int width = out.getWidth(), height = out.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float ndcX = (x + 0.5f) / width * 2.0f - 1.0f;
                float ndcY = 1.0f - (y + 0.5f) / height * 2.0f;
                Vector3f dir = camera.rayDirection(ndcX, ndcY);
                RayHit hit = SdfRaycaster.marchScene(scene, camera.position(), dir);
                out.setPixelRGBA(x, y, hit == null ? 0 : shade(hit));
            }
        }
        SdfPixelPost.apply(out);
    }

    /** Smoothed Lambert shading over the sampled albedo. {@code light} blends ambient + diffuse from the analytic normal. */
    public static int shade(RayHit hit) {
        SdfMaterial mat = hit.material();
        int base = sampleColor(mat, hit.uv());
        if (mat.emissive()) return base;

        float diffuse = Math.max(0.0f, hit.normal().dot(LIGHT_DIR));
        float light = 0.35f + 0.65f * diffuse;
        int a = (base >>> 24) & 0xFF;
        int r = (int) ((base & 0xFF) * light) & 0xFF;
        int g = (int) (((base >>> 8) & 0xFF) * light) & 0xFF;
        int b = (int) (((base >>> 16) & 0xFF) * light) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int sampleColor(SdfMaterial mat, Vector2f uv) {
        if (mat.texture() == null) return argbToAbgr(mat.tint());
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(mat.texture());
            SpriteContents contents = sprite.contents();
            int w = contents.width(), h = contents.height();
            if (w <= 0 || h <= 0) return argbToAbgr(mat.tint());
            int x = Math.floorMod((int) Math.floor(uv.x * w), w);
            int y = Math.floorMod((int) Math.floor(uv.y * h), h);
            return sprite.getPixelRGBA(0, x, y);
        } catch (Exception e) {
            return argbToAbgr(mat.tint());
        }
    }

    /** Converts a 0xAARRGGBB tint to the 0xAABBGGRR layout used by {@link NativeImage}. */
    public static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
