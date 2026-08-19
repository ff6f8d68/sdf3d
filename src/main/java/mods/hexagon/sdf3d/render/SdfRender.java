package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import mods.hexagon.sdf3d.sdf.SdfBounds;
import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.util.Arrays;

/**
 * Draws an {@code .s3d} model by raymarching it directly (no mesh): a bounding-box proxy cube
 * is drawn with a fragment shader that sphere-traces the SDF. The camera is computed in
 * model-local space so the same path works for the orthographic GUI view, the perspective
 * first-person view, and the in-world block.
 *
 * <p>Per model, a specialized shader is compiled ({@link SdfShaderCompiler}) that inlines the
 * graph, so the per-pixel evaluation is a straight-line sequence rather than a dynamic
 * uniform-array walk. The generic {@code sdf_item} shader is the fallback.</p>
 */
public final class SdfRender {
    private static ShaderInstance shader;

    /** Half-extent of the proxy cube when a model has no finite bounds (unbounded primitives). */
    private static final float FALLBACK_HALF_EXTENT = 8.0f;

    // The node graph is immutable per model, so its serialized data (and the uniform upload) is
    // cached and only re-uploaded when the model changes.
    private static ResourceLocation cachedModel;
    private static float[] cachedBuffer = new float[SdfGraph.maxNodes() * 12];
    private static int cachedCount;
    private static int cachedRoot;
    private static SdfBounds cachedBounds = SdfBounds.infinite();

    // Texture sampling state (resolved once per model; the block atlas is stitched at load).
    private static boolean cachedHasTexture;
    private static float spriteU0, spriteV0, spriteU1, spriteV1;
    private static int atlasTextureId = -1;

    // World-space direction toward the sun. Set per frame by the item/block renderers from the
    // level's celestial angle (see SdfRender.setSunDirection); transformed into model-local space
    // in renderModel so the model's shading + self-shadows track where the sun actually is.
    private static final Vector3f FALLBACK_SUN = new Vector3f(0.42f, 0.84f, 0.25f);
    private static Vector3f sunDirWorld = new Vector3f(FALLBACK_SUN).normalize();

    // Placed point lights (torches, glowstone, ...) in world space, with per-light intensity.
    // Each is uploaded as an independent source so several lights read as distinct highlights.
    private static final int MAX_POINT_LIGHTS = 8;
    private static final Vector3f[] pointLightPos = new Vector3f[MAX_POINT_LIGHTS];
    private static final float[] pointLightIntensity = new float[MAX_POINT_LIGHTS];
    private static int pointLightCount;
    static {
        for (int i = 0; i < MAX_POINT_LIGHTS; i++) pointLightPos[i] = new Vector3f();
    }

    private SdfRender() {}

    public static void setShader(ShaderInstance value) {
        shader = value;
        atlasTextureId = -1; // the atlas may be re-created on resource reload
        cachedModel = null; // force node/texture re-upload into the fresh program on reload
    }

    /**
     * Sets the world-space unit direction toward the sun (or a combined sun + placed-light
     * direction). Used to light the model and cast its self-shadows from the real sky.
     */
    public static void setSunDirection(Vector3fc dir) {
        if (dir == null) {
            sunDirWorld = new Vector3f(FALLBACK_SUN).normalize();
            return;
        }
        sunDirWorld = new Vector3f(dir);
        float len = sunDirWorld.length();
        if (len < 1.0e-6f) {
            sunDirWorld.set(FALLBACK_SUN).normalize();
        } else {
            sunDirWorld.div(len);
        }
    }

    /**
     * Sets the placed point lights to shade the model with. {@code positions} are world-space
     * block centres; {@code intensities} are per-light brightness (0..1). Pass {@code count} == 0
     * to clear. Transformed to model-local space in {@link #renderModel}.
     */
    public static void setPointLights(float[] positionsXYZ, float[] intensities, int count) {
        pointLightCount = Math.min(Math.max(count, 0), MAX_POINT_LIGHTS);
        for (int i = 0; i < pointLightCount; i++) {
            pointLightPos[i].set(positionsXYZ[i * 3], positionsXYZ[i * 3 + 1], positionsXYZ[i * 3 + 2]);
            pointLightIntensity[i] = intensities[i];
        }
    }

    public static void clearPointLights() {
        pointLightCount = 0;
    }

    public static boolean active() {
        return shader != null;
    }

    /** The shader to use for {@code model}: a specialized one if available, else the generic one. */
    private static ShaderInstance shaderFor(SdfModel model) {
        ShaderInstance specialized = SdfShaderCompiler.get(model);
        return specialized != null ? specialized : shader;
    }

    /**
     * Raymarches {@code model}. {@code depthTest} should be {@code false} for GUI/hand items
     * (which float above everything) and {@code true} for in-world blocks (so geometry in front
     * occludes them).
     */
    public static void renderModel(SdfModel model, PoseStack poseStack, boolean depthTest) {
        renderModel(model, poseStack, depthTest, LightTexture.pack(15, 15));
    }

    public static void renderModel(SdfModel model, PoseStack poseStack, boolean depthTest, int light) {
        if (shader == null) return;
        if (!(model.function() instanceof SdfGraph graph)) return;

        ShaderInstance active = shaderFor(model);

        // Cache the flattened node data (and texture) — it only changes when the model changes.
        if (!model.id().equals(cachedModel)) {
            cachedModel = model.id();
            cachedBounds = model.bounds();
            resolveTexture(model.material().texture());
            if (active == shader) {
                // Generic uniform-array path only: upload the node data once per model.
                float[] data = graph.data();
                if (data.length > cachedBuffer.length) {
                    cachedBuffer = new float[data.length];
                }
                System.arraycopy(data, 0, cachedBuffer, 0, data.length);
                if (data.length < cachedBuffer.length) {
                    Arrays.fill(cachedBuffer, data.length, cachedBuffer.length, 0.0f);
                }
                cachedCount = graph.nodeCount();
                cachedRoot = graph.rootIndex();
                setUniform(active, "uNodes", cachedBuffer);
                setFloat(active, "uNodeCount", cachedCount);
                setFloat(active, "uRoot", cachedRoot);
            }
        }

        int tint = model.material().tint();
        setFloat(active, "uTint", ((tint >>> 16) & 0xFF) / 255.0f, ((tint >>> 8) & 0xFF) / 255.0f,
                (tint & 0xFF) / 255.0f, ((tint >>> 24) & 0xFF) / 255.0f);
        setFloat(active, "uEmissive", model.material().emissive() ? 1.0f : 0.0f);
        setFloat(active, "uRoughness", model.material().roughness());
        setFloat(active, "uMetallic", model.material().metallic());
        setFloat(active, "uEffect", Sdf3dApi.postEffect());
        // In-game light: decode the packed light passed by the renderer (sky + block levels).
        setFloat(active, "uSkyLight", LightTexture.sky(light) / 15.0f);
        setFloat(active, "uBlockLight", LightTexture.block(light) / 15.0f);
        // keyframe easing curve (presets or cubic-bezier control points)
        setFloat(active, "uEasing", (float) graph.easing());
        float[] bezier = graph.bezier();
        setFloat(active, "uBezier", bezier[0], bezier[1], bezier[2], bezier[3]);
        setFloat(active, "uHasTexture", cachedHasTexture ? 1.0f : 0.0f);
        setFloat(active, "uSpriteRect", spriteU0, spriteV0, spriteU1, spriteV1);
        if (cachedHasTexture) {
            int id = atlasTextureId();
            if (id >= 0) active.setSampler("uAtlas", id);
        }
        setFloat(active, "uTime", (float) (System.nanoTime() / 1_000_000_000.0));

        // Bounds for the proxy cube (model-local space).
        Vector3f cubeMin = new Vector3f(cachedBounds.min());
        Vector3f cubeMax = new Vector3f(cachedBounds.max());
        if (!cachedBounds.isFinite()) {
            // The graph uses an unbounded primitive (a plane, or a raw x/y/z algebraic
            // expression) so its symbolic bounds are infinite. Use a generous proxy cube so
            // the whole model still renders, instead of clipping to a 1-block box.
            cubeMin.set(-FALLBACK_HALF_EXTENT, -FALLBACK_HALF_EXTENT, -FALLBACK_HALF_EXTENT);
            cubeMax.set(FALLBACK_HALF_EXTENT, FALLBACK_HALF_EXTENT, FALLBACK_HALF_EXTENT);
        }
        cubeMin.sub(0.05f, 0.05f, 0.05f);
        cubeMax.add(0.05f, 0.05f, 0.05f);

        // Camera in model-local space. The render-system matrix is outer -> view; the pose stack
        // maps local -> outer, so local -> view is modelView * pose.
        Matrix4f modelView = RenderSystem.getModelViewMatrix();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        boolean ortho = Math.abs(projection.m33() - 1.0f) < 1e-4f;

        Matrix4f pose = poseStack.last().pose();
        Matrix4f invPose = new Matrix4f(pose).invert();
        Matrix4f invFull = new Matrix4f(modelView).mul(pose).invert();

        Vector4f camLocal = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f).mul(invFull);
        Vector4f dirLocal = new Vector4f(0.0f, 0.0f, -1.0f, 0.0f).mul(invFull);
        Vector3f dir3 = new Vector3f(dirLocal.x, dirLocal.y, dirLocal.z);
        if (dir3.lengthSquared() > 1e-8f) dir3.normalize();

        setUniform(active, "uInvPose", invPose);
        // local -> clip (projection * modelView * pose) so the shader can write the real
        // fragment depth at the raymarch hit (correct occlusion against the world).
        setUniform(active, "uLocalClip", new Matrix4f(projection).mul(modelView).mul(pose));
        setFloat(active, "uCamLocal", camLocal.x, camLocal.y, camLocal.z);
        setFloat(active, "uDirLocal", dir3.x, dir3.y, dir3.z);
        setFloat(active, "uOrtho", ortho ? 1.0f : 0.0f);

        // Real sun / placed-light direction in model-local space (the pose maps local -> world,
        // so invPose maps the world-space sun direction back into the model's frame).
        Vector4f sunWorld = new Vector4f(sunDirWorld.x, sunDirWorld.y, sunDirWorld.z, 0.0f).mul(invPose);
        Vector3f sunLocal = new Vector3f(sunWorld.x, sunWorld.y, sunWorld.z);
        if (sunLocal.lengthSquared() > 1.0e-8f) sunLocal.normalize();
        setFloat(active, "uLightDir", sunLocal.x, sunLocal.y, sunLocal.z);

        // Placed point lights, transformed into model-local space and uploaded as independent
        // sources (xyz = position, w = intensity) so each reads as its own highlight.
        float[] lights = new float[MAX_POINT_LIGHTS * 4];
        for (int i = 0; i < pointLightCount; i++) {
            Vector4f lp = new Vector4f(pointLightPos[i].x, pointLightPos[i].y, pointLightPos[i].z, 1.0f).mul(invPose);
            lights[i * 4] = lp.x;
            lights[i * 4 + 1] = lp.y;
            lights[i * 4 + 2] = lp.z;
            lights[i * 4 + 3] = pointLightIntensity[i];
        }
        setUniform(active, "uLights", lights);
        setFloat(active, "uLightCount", pointLightCount);

        // Bounding-box proxy cube, transformed into outer space by the pose stack.
        Vector3f[] c = new Vector3f[]{
                transform(cubeMin.x, cubeMin.y, cubeMin.z, pose),
                transform(cubeMax.x, cubeMin.y, cubeMin.z, pose),
                transform(cubeMax.x, cubeMax.y, cubeMin.z, pose),
                transform(cubeMin.x, cubeMax.y, cubeMin.z, pose),
                transform(cubeMin.x, cubeMin.y, cubeMax.z, pose),
                transform(cubeMax.x, cubeMin.y, cubeMax.z, pose),
                transform(cubeMax.x, cubeMax.y, cubeMax.z, pose),
                transform(cubeMin.x, cubeMax.y, cubeMax.z, pose)
        };
        int[][] tris = {
                {0, 2, 1}, {0, 3, 2}, // -Z
                {4, 5, 6}, {4, 6, 7}, // +Z
                {0, 4, 7}, {0, 7, 3}, // -X
                {1, 2, 6}, {1, 6, 5}, // +X
                {0, 1, 5}, {0, 5, 4}, // -Y
                {3, 7, 6}, {3, 6, 2}  // +Y
        };

        if (depthTest) RenderSystem.enableDepthTest();
        else RenderSystem.disableDepthTest();
        // Write depth only for world-space rendering (the shader writes gl_FragDepth at the
        // true hit distance); GUI/overlay items keep the old no-write behavior.
        RenderSystem.depthMask(depthTest);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        for (int[] tri : tris) {
            for (int idx : tri) {
                builder.addVertex(c[idx].x, c[idx].y, c[idx].z);
            }
        }
        MeshData mesh = builder.buildOrThrow();

        RenderSystem.setShader(() -> active);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.setShader(() -> null);

        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    /** Resolves the model's albedo texture to its atlas UV rect (falls back to tint if missing). */
    private static void resolveTexture(ResourceLocation texture) {
        cachedHasTexture = false;
        if (texture == null) return;
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(texture);
            if (sprite == null) return;
            spriteU0 = sprite.getU0();
            spriteV0 = sprite.getV0();
            spriteU1 = sprite.getU1();
            spriteV1 = sprite.getV1();
            cachedHasTexture = true;
        } catch (RuntimeException ignored) {
            // Atlas not stitched yet (or unknown texture): keep the tint-only fallback.
        }
    }

    private static int atlasTextureId() {
        if (atlasTextureId < 0) {
            try {
                var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
                if (atlas != null) atlasTextureId = atlas.getId();
            } catch (RuntimeException ignored) {
            }
        }
        return atlasTextureId;
    }

    private static Vector3f transform(float x, float y, float z, Matrix4f m) {
        Vector4f v = new Vector4f(x, y, z, 1.0f).mul(m);
        return new Vector3f(v.x, v.y, v.z);
    }

    private static void setFloat(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setFloat(ShaderInstance shader, String name, float a, float b, float c) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(a, b, c);
    }

    private static void setFloat(ShaderInstance shader, String name, float a, float b, float c, float d) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(a, b, c, d);
    }

    private static void setUniform(ShaderInstance shader, String name, float[] values) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(values);
    }

    private static void setUniform(ShaderInstance shader, String name, Matrix4f value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(value);
    }
}
