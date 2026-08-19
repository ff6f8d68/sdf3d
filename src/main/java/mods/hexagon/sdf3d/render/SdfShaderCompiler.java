package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import mods.hexagon.sdf3d.Sdf3d;
import mods.hexagon.sdf3d.model.SdfModelManager;
import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfModel;
import mods.hexagon.sdf3d.sdf.SdfShaderGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles a per-model specialized fragment shader (see {@link SdfShaderGenerator}) at runtime
 * and caches it. The generated shader inlines the model's SDF graph so the GPU evaluates a
 * straight-line sequence instead of walking the {@code uNodes[]} uniform array — this is what
 * removes the per-pixel loop overhead that made complex models lag.
 *
 * <p>Compilation is lazy (first render of a model, on the render thread) and always falls back
 * to the generic {@code sdf_item} shader if it fails, so a bad generated shader can never break
 * rendering.</p>
 */
public final class SdfShaderCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, ShaderInstance> CACHE = new HashMap<>();
    private static final Set<ResourceLocation> FAILED = new HashSet<>();

    private SdfShaderCompiler() {}

    /**
     * Returns a specialized {@link ShaderInstance} for {@code model}, or {@code null} if the
     * model isn't a graph, compilation failed, or we're not on the render thread.
     */
    public static ShaderInstance get(SdfModel model) {
        ShaderInstance cached = CACHE.get(model.id());
        if (cached != null) return cached;
        if (FAILED.contains(model.id())) return null;
        return compile(model, Minecraft.getInstance().getResourceManager());
    }

    /**
     * Compiles (and caches) a specialized shader for {@code model} using {@code delegate} to
     * resolve the shared vertex shader and resource-pack source. Used to pre-bake models during
     * {@code RegisterShadersEvent}, before the player ever sees them.
     */
    public static void precompile(SdfModel model, ResourceProvider delegate) {
        if (CACHE.containsKey(model.id()) || FAILED.contains(model.id())) return;
        compile(model, delegate);
    }

    /**
     * Pre-bakes a specialized shader for every {@code .s3d} model in the mod namespace.
     * Called from {@code RegisterShadersEvent}, which fires during the initial resource reload
     * at game startup (before the title screen), so no compile work is deferred to world load.
     */
    public static void precompileAll(ResourceProvider delegate) {
        int baked = 0;
        try {
            ResourceManager manager = Minecraft.getInstance().getResourceManager();
            for (ResourceLocation location : manager
                    .listResources(Sdf3d.MODID, l -> l.getPath().startsWith("sdf/") && l.getPath().endsWith(".s3d"))
                    .keySet()) {
                String path = location.getPath();
                ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(
                        location.getNamespace(), path.substring(0, path.length() - ".s3d".length()));
                try {
                    precompile(SdfModelManager.get(modelId), delegate);
                    if (CACHE.containsKey(modelId)) baked++;
                } catch (Exception e) {
                    LOGGER.error("[sdf3d] Failed to pre-bake shader for '{}': {}", modelId, e.toString());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("[sdf3d] Failed to enumerate SDF models for pre-baking: {}", e.toString());
        }
        LOGGER.info("[sdf3d] Pre-baked {} specialized SDF shader(s) at startup", baked);
    }

    private static ShaderInstance compile(SdfModel model, ResourceProvider delegate) {
        if (!(model.function() instanceof SdfGraph graph)) return null;
        ResourceLocation id = model.id();
        if (FAILED.contains(id)) return null;
        try {
            String key = keyFor(id);
            String fragment = SdfShaderGenerator.generateFragmentShader(graph);
            String json = SdfShaderGenerator.generateJson(key);
            ResourceProvider provider = new OverlayProvider(delegate, key, fragment, json);
            ShaderInstance shader = new ShaderInstance(provider,
                    ResourceLocation.fromNamespaceAndPath(Sdf3d.MODID, "item_gen_" + key),
                    DefaultVertexFormat.POSITION);
            CACHE.put(id, shader);
            LOGGER.info("[sdf3d] Compiled specialized SDF shader for '{}' ({} nodes)", id, graph.nodeCount());
            return shader;
        } catch (Exception e) {
            LOGGER.error("[sdf3d] Failed to compile specialized shader for '{}'; using generic fallback: {}",
                    id, e.toString());
            FAILED.add(id);
            return null;
        }
    }

    /** Drops all cached programs (call on resource reload so stale GL programs aren't reused). */
    public static void invalidate() {
        for (ShaderInstance shader : CACHE.values()) {
            try {
                // Close the unique fragment program so an edited model recompiles on reload;
                // the shared vertex program (sdf3d:sdf_item) must be left alone.
                shader.getFragmentProgram().close();
            } catch (RuntimeException ignored) {
            }
            try {
                shader.close();
            } catch (RuntimeException ignored) {
            }
        }
        CACHE.clear();
        FAILED.clear();
    }

    private static String keyFor(ResourceLocation id) {
        return Integer.toUnsignedString(id.toString().hashCode(), 36);
    }

    /** Serves the generated JSON + fragment source and delegates everything else to the real manager. */
    private static final class OverlayProvider implements ResourceProvider {
        private final ResourceProvider delegate;
        private final Map<ResourceLocation, Resource> overlay;

        OverlayProvider(ResourceProvider delegate, String key, String fragment, String json) {
            this.delegate = delegate;
            PackResources pack = pickPack(delegate);
            this.overlay = new HashMap<>();
            overlay.put(ResourceLocation.fromNamespaceAndPath(Sdf3d.MODID,
                    "shaders/core/item_gen_" + key + ".json"), new Resource(pack, source(json)));
            overlay.put(ResourceLocation.fromNamespaceAndPath(Sdf3d.MODID,
                    "shaders/core/sdf_item_gen_" + key + ".fsh"), new Resource(pack, source(fragment)));
        }

        private static IoSupplier<InputStream> source(String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return () -> new ByteArrayInputStream(bytes);
        }

        private static PackResources pickPack(ResourceProvider delegate) {
            if (delegate instanceof ResourceManager rm) {
                return rm.listPacks().findFirst()
                        .orElseThrow(() -> new IllegalStateException("No resource pack available to host generated shader"));
            }
            return Minecraft.getInstance().getResourceManager().listPacks().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No resource pack available to host generated shader"));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            Resource generated = overlay.get(location);
            if (generated != null) return Optional.of(generated);
            return delegate.getResource(location);
        }
    }
}
