package mods.hexagon.sdf3d.scene;

import mods.hexagon.sdf3d.model.SdfModelManager;
import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfMaterial;
import mods.hexagon.sdf3d.sdf.SdfModel;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered collection of {@link SdfInstance} placements. The CPU raymarcher renders each
 * instance individually (full material/UV fidelity), while {@link #buildGraph()} lowers the
 * scene into a single flattened graph for the GPU raymarcher.
 */
public final class SdfScene {
    private final List<SdfInstance> instances = new ArrayList<>();
    private SdfMaterial material = SdfMaterial.WHITE;

    public SdfInstance add(ResourceLocation modelId, Vector3fc position, Quaternionf rotation, Vector3fc scale) {
        SdfInstance instance = new SdfInstance(modelId, position, rotation, scale);
        instances.add(instance);
        return instance;
    }

    public void remove(SdfInstance instance) {
        instances.remove(instance);
    }

    public void clear() {
        instances.clear();
    }

    public List<SdfInstance> instances() {
        return Collections.unmodifiableList(instances);
    }

    public SdfMaterial material() {
        return material;
    }

    public void setMaterial(SdfMaterial material) {
        this.material = material == null ? SdfMaterial.WHITE : material;
    }

    /**
     * Combines all visible instances into one flattened graph (post-order) for the GPU path.
     * Instances are skipped once the node budget is exhausted. Returns {@code null} if nothing fits.
     */
    public SdfGraph buildGraph() {
        SdfGraph.Builder b = new SdfGraph.Builder();
        List<Integer> roots = new ArrayList<>();
        int budget = SdfGraph.maxNodes();

        for (SdfInstance instance : instances) {
            if (!instance.visible()) continue;
            SdfModel model = SdfModelManager.get(instance.modelId());
            if (!(model.function() instanceof SdfGraph graph)) continue;

            // The GPU path shades the whole scene with a single material, so track the first
            // visible model's material here.
            if (roots.isEmpty()) this.material = model.material();

            int needed = graph.nodeCount() + 4;
            if (b.nodeCount() + needed > budget) continue; // skip; scene exceeds GPU node budget

            int root = b.append(graph);
            root = wrapStretch(b, root, instance.scale());
            root = wrapRotate(b, root, instance.rotation());
            root = wrapTranslate(b, root, instance.position());
            roots.add(root);
        }

        if (roots.isEmpty()) return null;
        int root = roots.get(0);
        for (int i = 1; i < roots.size(); i++) {
            root = b.addNode(SdfGraph.UNION, root, roots.get(i));
        }
        return b.build(root);
    }

    private static int wrapTranslate(SdfGraph.Builder b, int child, Vector3fc t) {
        if (Math.abs(t.x()) < 1e-6f && Math.abs(t.y()) < 1e-6f && Math.abs(t.z()) < 1e-6f) return child;
        return b.addNode(SdfGraph.TRANSLATE, child, -1, t.x(), t.y(), t.z());
    }

    private static int wrapStretch(SdfGraph.Builder b, int child, Vector3fc s) {
        if (Math.abs(s.x() - 1) < 1e-6f && Math.abs(s.y() - 1) < 1e-6f && Math.abs(s.z() - 1) < 1e-6f) return child;
        if (Math.abs(s.x() - s.y()) < 1e-6f && Math.abs(s.y() - s.z()) < 1e-6f) {
            return b.addNode(SdfGraph.SCALE, child, -1, s.x());
        }
        return b.addNode(SdfGraph.STRETCH, child, -1, s.x(), s.y(), s.z());
    }

    private static int wrapRotate(SdfGraph.Builder b, int child, Quaternionf rotation) {
        if (Math.abs(rotation.x) < 1e-6f && Math.abs(rotation.y) < 1e-6f
                && Math.abs(rotation.z) < 1e-6f && Math.abs(rotation.w - 1) < 1e-6f) {
            return child;
        }
        Matrix3f m = new Matrix3f().set(new Quaternionf(rotation).conjugate());
        return b.addNode(SdfGraph.ROTATE, child, -1,
                m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22());
    }
}
