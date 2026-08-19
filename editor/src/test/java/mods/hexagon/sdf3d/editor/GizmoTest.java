package mods.hexagon.sdf3d.editor;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GizmoTest {

    @Test
    void planeHitMovesAlongAxis() {
        Camera cam = new Camera();
        Vector3f pivot = new Vector3f(0, 0, 0);
        Vector3f axis = new Vector3f(1, 0, 0);
        Vector3f a = Gizmo.planeHit(cam, 1.333f, pivot, axis, 0.1f, 0f);
        Vector3f b = Gizmo.planeHit(cam, 1.333f, pivot, axis, 0.3f, 0f);
        assertNotNull(a);
        assertNotNull(b);
        float da = new Vector3f(a).sub(pivot).dot(axis);
        float db = new Vector3f(b).sub(pivot).dot(axis);
        assertTrue(Math.abs(db - da) > 1e-3f, "dragging should change the axis projection: " + da + " vs " + db);
    }
}
