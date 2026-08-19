package mods.hexagon.sdf3d.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import mods.hexagon.sdf3d.block.SdfBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;

/**
 * Fullscreen "render the world as SDF" raymarcher. Each frame a voxel grid of the blocks
 * around the camera is packed into a small RGBA8 atlas texture, then a fullscreen quad
 * sphere-marches it (per pixel) with the {@code world_ray} shader.
 *
 * <p>The atlas upload goes through {@link NativeImage#upload(int, int, int, boolean)}, which
 * sets {@code GL_UNPACK_ALIGNMENT} / {@code GL_UNPACK_ROW_LENGTH} correctly before the
 * {@code glTexSubImage2D} call. This is the fix for the SIGSEGV the older hand-rolled
 * uploads hit on Mesa/gallium: with a tightly-packed buffer and a stale unpack alignment, the
 * driver could compute a padded row stride and over-read past the end of the buffer.</p>
 */
public final class WorldSdfRenderer {
    /** Voxels per axis (one voxel = one block). */
    public static final int GRID = 64;
    private static final int TILES_X = 8;
    private static final int TILES_Y = 8;
    public static final int ATLAS_W = GRID * TILES_X; // 512
    public static final int ATLAS_H = GRID * TILES_Y; // 512

    private static ShaderInstance shader;
    private static int textureId = -1;
    private static NativeImage atlas;
    private static BlockPos cachedMin;

    private WorldSdfRenderer() {}

    public static void setShader(ShaderInstance value) {
        shader = value;
        // Force re-upload after a resource reload recreates the shader.
        cachedMin = null;
    }

    /** Called from a {@link RenderLevelStageEvent} listener when the world raymarcher is enabled. */
    public static void render(RenderLevelStageEvent event) {
        if (shader == null) return;
        if (!Sdf3dApi.isWorldRendererEnabled()) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;

        var camera = event.getCamera();
        var camPos = camera.getPosition();

        // Centre the grid on the camera's block; only rebuild when that block moves.
        BlockPos camBlock = BlockPos.containing(camPos.x, camPos.y, camPos.z);
        BlockPos min = camBlock.offset(-GRID / 2, -GRID / 2, -GRID / 2);

        ensureTexture();
        if (!min.equals(cachedMin)) {
            rebuildAtlas(level, min);
            uploadAtlas();
            cachedMin = min;
        }

        Matrix4f projView = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        Matrix4f invProjView = new Matrix4f(projView).invert();

        shader.setSampler("uVoxels", textureId);
        setFloat("uCamPos", (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setUniform("uInvViewProj", invProjView);
        setUniform("uProjView", projView);
        setFloat("uGridMin", min.getX(), min.getY(), min.getZ());
        setFloat("uGridSize", GRID);
        setFloat("uAtlas", ATLAS_W, ATLAS_H);
        setFloat("uTileX", TILES_X);

        // Match the vanilla sky so the raymarched world blends with the horizon.
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        var sky = level.getSkyColor(camPos, partialTick);
        setFloat("uSky", (float) sky.x, (float) sky.y, (float) sky.z);
        setFloat("uHorizon", (float) sky.x * 0.9f, (float) sky.y * 0.9f, (float) sky.z * 0.95f);

        // Light the world from the real sky: the sun travels (cos/sin of the celestial angle),
        // and at night the moon takes over from the opposite direction, cool and dim. This is
        // the same direction used by the item/block renderers, so shadows + the sun disc drawn
        // by the shader all track where the vanilla sun actually is.
        float a = level.getTimeOfDay(partialTick) * (float) (Math.PI * 2.0);
        float sunX = Mth.cos(a);
        float sunY = Mth.sin(a);
        if (sunY > 0.0f) {
            setFloat("uSunDir", sunX, sunY, 0.0f);
            setFloat("uSunColor", 1.0f, 0.8f, 0.55f);
        } else {
            // Night: moonlight comes from the opposite celestial position, dim and cool.
            setFloat("uSunDir", -sunX, -sunY, 0.0f);
            setFloat("uSunColor", 0.28f, 0.34f, 0.5f);
        }

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        // Two triangles covering NDC [-1, 1]^2.
        builder.addVertex(-1.0f, -1.0f, 0.0f);
        builder.addVertex( 1.0f, -1.0f, 0.0f);
        builder.addVertex( 1.0f,  1.0f, 0.0f);
        builder.addVertex(-1.0f, -1.0f, 0.0f);
        builder.addVertex( 1.0f,  1.0f, 0.0f);
        builder.addVertex(-1.0f,  1.0f, 0.0f);
        MeshData mesh = builder.buildOrThrow();

        RenderSystem.setShader(() -> shader);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.setShader(() -> null);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableBlend();
    }

    private static void ensureTexture() {
        if (textureId >= 0) return;
        textureId = GlStateManager._genTexture();
        GlStateManager._bindTexture(textureId);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // Allocate storage without pixel data (the path Minecraft itself uses), then upload
        // the pixels separately below.
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, ATLAS_W, ATLAS_H, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
        atlas = new NativeImage(ATLAS_W, ATLAS_H, false);
    }

    private static void rebuildAtlas(ClientLevel level, BlockPos min) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < GRID; z++) {
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID; x++) {
                    pos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    int px = (z % TILES_X) * GRID + x;
                    int py = (z / TILES_X) * GRID + y;
                    if (isSolid(state)) {
                        MapColor color = state.getMapColor(level, pos);
                        atlas.setPixelRGBA(px, py, toAbgr(color.col));
                    } else {
                        atlas.setPixelRGBA(px, py, 0);
                    }
                }
            }
        }
    }

    private static boolean isSolid(BlockState state) {
        // The SDF example block is raymarched by its block-entity renderer, not voxelized:
        // including it in the voxel atlas would draw a second, camera-relative cube next to
        // the raymarched model (the "duplicate that jiggles with the camera").
        if (state.getBlock() instanceof SdfBlock) return false;
        // Only fully-opaque cubes belong in the voxel grid. Translucent blocks (glass, ice) and
        // non-full blocks (slabs, fences, plants, torches) would otherwise become opaque 1x1x1
        // cubes and wrongly hide whatever is behind them — the occlusion bug where a fence or a
        // pane of glass "covers" the wall behind it.
        return state.canOcclude();
    }

    /** {@code NativeImage#setPixelRGBA} stores {@code 0xAABBGGRR}; MapColor gives {@code 0xRRGGBB}. */
    private static int toAbgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (0xFF << 24) | (b << 16) | (g << 8) | r;
    }

    private static void uploadAtlas() {
        GlStateManager._bindTexture(textureId);
        // NativeImage.upload -> setUnpackPixelStoreState() sets GL_UNPACK_ALIGNMENT to the
        // pixel byte size before glTexSubImage2D, so the tightly-packed rows are read without
        // the driver inventing padding (which is what over-read the buffer and crashed Mesa).
        atlas.upload(0, 0, 0, false);
    }

    private static void setFloat(String name, float value) {
        var uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setFloat(String name, float a, float b) {
        var uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(a, b);
    }

    private static void setFloat(String name, float a, float b, float c) {
        var uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(a, b, c);
    }

    private static void setUniform(String name, Matrix4f value) {
        var uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(value);
    }
}
