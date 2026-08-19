package mods.hexagon.sdf3d.scene;

import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A single placement of an {@code .s3d} model in world space. Supports arbitrary position,
 * full orientation and non-uniform scale ("stretching"), plus optional per-instance material
 * overrides (used by the CPU path). The transform is mutable so renderers can animate it.
 */
public final class SdfInstance {
    private final ResourceLocation modelId;
    private final Vector3f position;
    private final Quaternionf rotation;
    private final Vector3f scale;
    private final Integer tintOverride;
    private final Boolean emissiveOverride;
    private final Float roughnessOverride;
    private boolean visible;

    public SdfInstance(ResourceLocation modelId, Vector3fc position, Quaternionf rotation, Vector3fc scale) {
        this(modelId, position, rotation, scale, null, null, null, true);
    }

    public SdfInstance(ResourceLocation modelId, Vector3fc position, Quaternionf rotation, Vector3fc scale,
                       Integer tintOverride, Boolean emissiveOverride, Float roughnessOverride, boolean visible) {
        this.modelId = modelId;
        this.position = new Vector3f(position);
        this.rotation = new Quaternionf(rotation);
        this.scale = new Vector3f(scale);
        this.tintOverride = tintOverride;
        this.emissiveOverride = emissiveOverride;
        this.roughnessOverride = roughnessOverride;
        this.visible = visible;
    }

    public ResourceLocation modelId() { return modelId; }
    public Vector3f position() { return position; }
    public Quaternionf rotation() { return rotation; }
    public Vector3f scale() { return scale; }
    public Integer tintOverride() { return tintOverride; }
    public Boolean emissiveOverride() { return emissiveOverride; }
    public Float roughnessOverride() { return roughnessOverride; }
    public boolean visible() { return visible; }

    public void setPosition(Vector3fc value) { position.set(value); }
    public void setRotation(Quaternionf value) { rotation.set(value); }
    public void setScale(Vector3fc value) { scale.set(value); }
    public void setVisible(boolean value) { visible = value; }
}
