package mods.hexagon.sdf3d.editor;

import org.joml.Vector3f;

/**
 * Orbit camera for the editor viewport. Keeps a look-at target plus yaw/pitch/distance, and
 * can produce eye-space rays for a pixel in NDC space. All math is pure JOML, no UI types.
 */
public final class Camera {
    private final Vector3f target = new Vector3f(0f, 0.6f, 0f);
    private float distance = 6f;
    private float yaw = 0.6f;
    private float pitch = 0.35f;
    private float fovDegrees = 45f;

    public Vector3f target() {
        return target;
    }

    public float distance() {
        return distance;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float fovDegrees() {
        return fovDegrees;
    }

    public void orbit(float dYaw, float dPitch) {
        yaw += dYaw;
        pitch = clamp(pitch + dPitch, -1.55f, 1.55f);
    }

    public void dolly(float factor) {
        distance = clamp(distance * factor, 0.2f, 200f);
    }

    public void pan(float dx, float dy) {
        Vector3f forward = forward(new Vector3f());
        Vector3f right = new Vector3f(forward).cross(0f, 1f, 0f).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        float scale = distance * 0.0016f;
        target.fma(-dx * scale, right).fma(dy * scale, up);
    }

    /** Camera position in world space. */
    public Vector3f eye(Vector3f dest) {
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float sy = (float) Math.sin(yaw);
        float cy = (float) Math.cos(yaw);
        return dest.set(sy * cp, sp, cy * cp).mul(distance).add(target);
    }

    public Vector3f forward(Vector3f dest) {
        return new Vector3f(target).sub(eye(new Vector3f())).normalize();
    }

    /**
     * Ray direction for a pixel. {@code u}/{@code v} are in [-1, 1] NDC (v = +1 is the top of
     * the image). {@code aspect} is width/height so pixels stay square.
     */
    public Vector3f ray(float u, float v, float aspect, Vector3f dest) {
        Vector3f forward = forward(new Vector3f());
        Vector3f right = new Vector3f(forward).cross(0f, 1f, 0f).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        float tanF = (float) Math.tan(Math.toRadians(fovDegrees * 0.5f));
        return dest.set(forward)
                .fma(u * tanF * aspect, right)
                .fma(-v * tanF, up)
                .normalize();
    }

    public void frameRadius(float radius) {
        distance = Math.max(0.5f, radius * 3.2f);
    }

    public void lookFront() { yaw = 0f; pitch = 0f; }
    public void lookSide() { yaw = (float) (Math.PI / 2); pitch = 0f; }
    public void lookTop() { yaw = 0f; pitch = 1.45f; }
    public void lookPerspective() { yaw = 0.6f; pitch = 0.35f; }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
