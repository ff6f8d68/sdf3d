package mods.hexagon.sdf3d.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import mods.hexagon.sdf3d.api.Sdf3dApi;
import mods.hexagon.sdf3d.model.SdfModelManager;
import mods.hexagon.sdf3d.render.SdfRender;
import mods.hexagon.sdf3d.sdf.SdfBounds;
import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * Raymarches the block's {@code .s3d} model in place, using the same cube-proxy + fragment-shader
 * path as the item form ({@link SdfRender}). No scene/instance bookkeeping or fullscreen overlay
 * is needed: each placed block simply draws itself during the block-entity render pass.
 */
public class SdfBlockEntityRenderer implements BlockEntityRenderer<SdfBlockEntity> {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("sdf3d", "sdf/example_sphere");

    private static boolean logged;

    // Cached scan of nearby light-emitting blocks (torches, glowstone, ...). Re-scanned at most
    // once per second per block. Each nearby source becomes an independent point light (its own
    // position + intensity) so several lights read as distinct highlights.
    private static final int LIGHT_SCAN_RADIUS = 12;
    private static final long LIGHT_SCAN_INTERVAL = 20; // game ticks
    private static final int MAX_LIGHTS = 8;
    private static BlockPos lightScanPos;
    private static long lightScanAt = Long.MIN_VALUE;
    private static final float[] lightPosXYZ = new float[MAX_LIGHTS * 3];
    private static final float[] lightIntensity = new float[MAX_LIGHTS];
    private static int lightCount;

    public SdfBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    /**
     * Pure world-space sun direction from the celestial angle. Vanilla draws the sun on a sky
     * dome rotated by {@code timeOfDay * 360} degrees around the X axis, so the sun's world
     * direction is {@code (cos a, sin a, 0)}: +X (east) at sunrise, +Y overhead at noon, -X
     * (west) at sunset.
     */
    private static Vector3f sunDirection(Level level, float partialTick) {
        float a = level.getTimeOfDay(partialTick) * (float) (Math.PI * 2.0);
        return new Vector3f(Mth.cos(a), Mth.sin(a), 0.0f);
    }

    /** Scans nearby light-emitting blocks into the point-light arrays; returns the light count. */
    private static int scanPointLights(Level level, BlockPos center) {
        long now = level.getGameTime();
        if (center.equals(lightScanPos) && now - lightScanAt <= LIGHT_SCAN_INTERVAL) {
            return lightCount;
        }
        lightScanPos = center.immutable();
        lightScanAt = now;
        lightCount = 0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int r = LIGHT_SCAN_RADIUS;
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int dx = -r; dx <= r && lightCount < MAX_LIGHTS; dx++) {
            for (int dy = -r; dy <= r && lightCount < MAX_LIGHTS; dy++) {
                for (int dz = -r; dz <= r && lightCount < MAX_LIGHTS; dz++) {
                    pos.set(cx + dx, cy + dy, cz + dz);
                    BlockState state = level.getBlockState(pos);
                    int emission = state.getLightEmission(level, pos);
                    if (emission <= 1) continue;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < 1.0e-6) continue;
                    // world-space light centre (block centre), intensity scaled by emission.
                    lightPosXYZ[lightCount * 3] = pos.getX() + 0.5f;
                    lightPosXYZ[lightCount * 3 + 1] = pos.getY() + 0.5f;
                    lightPosXYZ[lightCount * 3 + 2] = pos.getZ() + 0.5f;
                    lightIntensity[lightCount] = (emission / 15.0f) * 2.0f;
                    lightCount++;
                }
            }
        }
        return lightCount;
    }

    @Override
    public AABB getRenderBoundingBox(SdfBlockEntity blockEntity) {
        // SDF models can extend well beyond their block; inflate by the model's actual extents
        // (with a floor) so large models are never frustum-culled at the block boundary.
        double half = 64.0;
        try {
            SdfModel model = SdfModelManager.get(MODEL);
            SdfBounds bounds = model.bounds();
            if (bounds.isFinite()) {
                org.joml.Vector3f min = bounds.min();
                org.joml.Vector3f max = bounds.max();
                half = Math.max(half, Math.max(
                        Math.max(Math.abs(min.x), Math.abs(max.x)),
                        Math.max(Math.max(Math.abs(min.y), Math.abs(max.y)),
                                Math.max(Math.abs(min.z), Math.abs(max.z)))) + 8.0);
            }
        } catch (RuntimeException ignored) {
            // Model not loaded yet: fall back to the generous default box.
        }
        return new AABB(blockEntity.getBlockPos()).inflate(half);
    }

    @Override
    public void render(SdfBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int light, int overlay) {
        if (!Sdf3dApi.isGpuRendererEnabled()) return;
        Level level = blockEntity.getLevel();
        if (level == null) return;

        SdfModel model = SdfModelManager.get(MODEL);
        if (!(model.function() instanceof SdfGraph)) {
            if (!logged) {
                LOGGER.warn("[sdf3d] SDF block model '{}' missing or not a graph", MODEL);
                logged = true;
            }
            return;
        }

        // Light the model from the real sky (sun) plus any placed light sources, each as its
        // own independent point light with its own intensity.
        SdfRender.setSunDirection(sunDirection(level, partialTick));
        int lights = scanPointLights(level, blockEntity.getBlockPos());
        SdfRender.setPointLights(lightPosXYZ, lightIntensity, lights);

        // Slow spin to demonstrate orientation support.
        double time = level.getGameTime() + partialTick;
        Quaternionf spin = new Quaternionf().rotateY((float) (time * 0.02));

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(spin);
        poseStack.scale(0.7f, 0.7f, 0.7f);
        // depthTest = true: the raymarched model tests (and writes) the real depth buffer, so
        // terrain/entities in front occlude it and it occludes what's behind it.
        SdfRender.renderModel(model, poseStack, true, light);
        poseStack.popPose();
    }
}
