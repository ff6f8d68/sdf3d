package mods.hexagon.sdf3d.model;

import mods.hexagon.sdf3d.sdf.SdfModel;
import mods.hexagon.sdf3d.sdf.SdfParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side loader/cache for {@code .s3d} models. A model id of {@code sdf3d:foo/bar}
 * resolves to the resource {@code assets/sdf3d/sdf/foo/bar.s3d} — the same convention the
 * obj loader uses for its {@code model} field (a resource path with the extension omitted).
 */
public final class SdfModelManager {
    private static final Map<ResourceLocation, SdfModel> CACHE = new HashMap<>();

    private SdfModelManager() {}

    public static SdfModel get(ResourceLocation id) {
        return CACHE.computeIfAbsent(id, SdfModelManager::load);
    }

    /** The resource location a model id resolves to (with the {@code .s3d} extension). */
    public static ResourceLocation resourceLocationFor(ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath() + ".s3d");
    }

    /** Parses a model from raw {@code .s3d} source. Exposed for tooling and tests. */
    public static SdfModel load(ResourceLocation id, InputStream in) throws IOException {
        String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return SdfParser.parse(id, source);
    }

    private static SdfModel load(ResourceLocation id) {
        ResourceLocation resource = resourceLocationFor(id);
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        try (InputStream in = manager.getResourceOrThrow(resource).open()) {
            return load(id, in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load SDF model '" + id + "' from " + resource, e);
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
