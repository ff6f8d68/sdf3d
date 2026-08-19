package mods.hexagon.sdf3d.api;

import mods.hexagon.sdf3d.scene.SdfInstance;
import mods.hexagon.sdf3d.scene.SdfScene;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public entry point for placing SDF models anywhere in the world and hooking per-pixel
 * post-processing. Call {@link #scene()} to reach the active placement collection, or use the
 * {@code place} helpers below.
 */
public final class Sdf3dApi {
    private static final CopyOnWriteArrayList<PixelModifier> MODIFIERS = new CopyOnWriteArrayList<>();
    private static volatile SdfScene scene = new SdfScene();
    private static volatile boolean gpuRendererEnabled = true;
    private static volatile boolean worldRendererEnabled = false;
    private static volatile int postEffect = 0;

    private Sdf3dApi() {}

    public static SdfScene scene() {
        return scene;
    }

    public static void setScene(SdfScene value) {
        scene = value == null ? new SdfScene() : value;
    }

    /** Places a model (unrotated, unit scale) at a world position. */
    public static SdfInstance place(ResourceLocation modelId, Vector3fc position) {
        return scene.add(modelId, position, new Quaternionf(), new Vector3f(1, 1, 1));
    }

    /** Places a model at a world position with rotation and non-uniform scale ("stretch"). */
    public static SdfInstance place(ResourceLocation modelId, Vector3fc position, Quaternionf rotation, Vector3fc scale) {
        return scene.add(modelId, position, rotation, scale);
    }

    public static void remove(SdfInstance instance) {
        scene.remove(instance);
    }

    public static void clearScene() {
        scene.clear();
    }

    public static boolean isGpuRendererEnabled() {
        return gpuRendererEnabled;
    }

    public static void setGpuRendererEnabled(boolean enabled) {
        gpuRendererEnabled = enabled;
    }

    /**
     * Whether the fullscreen world raymarcher (render-the-whole-terrain-as-SDF) is enabled.
     * Defaults to {@code false} because it uploads a voxel atlas and raymarches every pixel,
     * which is experimental and can be slow.
     */
    public static boolean isWorldRendererEnabled() {
        return worldRendererEnabled;
    }

    public static void setWorldRendererEnabled(boolean enabled) {
        worldRendererEnabled = enabled;
    }

    /** The active SDF-only post effect id (see {@code SdfClientOptions.SdfPostEffect}). */
    public static int postEffect() {
        return postEffect;
    }

    public static void setPostEffect(int effect) {
        postEffect = effect;
    }

    public static void addPixelModifier(PixelModifier modifier) {
        MODIFIERS.add(modifier);
    }

    public static void removePixelModifier(PixelModifier modifier) {
        MODIFIERS.remove(modifier);
    }

    public static List<PixelModifier> pixelModifiers() {
        return List.copyOf(MODIFIERS);
    }
}
