#version 150

uniform sampler2D uVoxels;
uniform vec3 uCamPos;
uniform mat4 uInvViewProj;
uniform mat4 uProjView;
uniform vec3 uGridMin;
uniform float uGridSize;
uniform vec2 uAtlas;
uniform float uTileX;
uniform vec3 uSky;
uniform vec3 uHorizon;
uniform vec3 uSunDir;
uniform vec3 uSunColor;

in vec2 vNdc;

out vec4 fragColor;

// The voxel grid is packed into a 2D atlas: slice `z` lives in tile (z % uTileX, z / uTileX).
vec2 voxelUv(vec3 v) {
    float tile = v.z;
    float tx = mod(tile, uTileX);
    float ty = floor(tile / uTileX);
    float u = (tx * uGridSize + v.x + 0.5) / uAtlas.x;
    float w = (ty * uGridSize + v.y + 0.5) / uAtlas.y;
    return vec2(u, w);
}

bool inGrid(vec3 v) {
    return v.x >= 0.0 && v.y >= 0.0 && v.z >= 0.0
        && v.x < uGridSize && v.y < uGridSize && v.z < uGridSize;
}

bool solidAt(vec3 v) {
    return inGrid(v) && texture(uVoxels, voxelUv(v)).a > 0.5;
}

void main() {
    // Unproject this pixel's NDC coordinate to a far-plane world point, then ray in.
    vec4 worldW = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 far = worldW.xyz / worldW.w;
    vec3 rd = normalize(far - uCamPos);

    // Sky gradient: above the horizon use the sky colour, below use the horizon colour.
    vec3 sky = mix(uHorizon, uSky, clamp(rd.y * 4.0 + 0.5, 0.0, 1.0));
    // Warm glow around the sun direction (also tints the horizon fog at sunrise/sunset).
    float sunDot = dot(rd, uSunDir);
    sky += uSunColor * pow(max(sunDot, 0.0), 32.0) * 0.35;

    // Voxel-space ray origin and direction (voxels are 1 world unit = 1 block).
    vec3 origin = uCamPos - uGridMin;
    vec3 v = floor(origin);
    vec3 step = vec3(rd.x >= 0.0 ? 1.0 : -1.0,
                     rd.y >= 0.0 ? 1.0 : -1.0,
                     rd.z >= 0.0 ? 1.0 : -1.0);
    // Amanatides & Woo DDA: distance along the ray to cross each voxel boundary.
    vec3 inv = 1.0 / max(abs(rd), vec3(1e-8));
    vec3 tDelta = inv;
    vec3 tMax = (step * (v - origin + 0.5) + 0.5) * inv;

    float hitT = -1.0;
    vec3 hitV = vec3(0.0);

    for (int i = 0; i < 256; i++) {
        if (!inGrid(v)) break;
        if (solidAt(v)) {
            hitV = v;
            hitT = max(tMax.x, max(tMax.y, tMax.z)); // approximate entry distance
            break;
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) { v.x += step.x; tMax.x += tDelta.x; }
        else if (tMax.y < tMax.z) { v.y += step.y; tMax.y += tDelta.y; }
        else { v.z += step.z; tMax.z += tDelta.z; }
    }

    if (hitT < 0.0) {
        // No solid voxel on this ray: paint over the vanilla terrain with sky so the
        // raymarched world fully replaces it (instead of leaking through on misses).
        // Draw the sun disc where the real sun sits (the vanilla sun is covered by this pass).
        float sunAng = acos(clamp(sunDot, -1.0, 1.0));
        sky += uSunColor * (1.0 - smoothstep(0.03, 0.12, sunAng));
        fragColor = vec4(sky, 1.0);
        gl_FragDepth = 1.0;
        return;
    }

    vec3 vox = texture(uVoxels, voxelUv(hitV)).rgb;

    // Face normal from exposed neighbours.
    vec3 n = vec3(0.0);
    if (!solidAt(hitV + vec3( 1.0, 0.0, 0.0))) n += vec3( 1.0, 0.0, 0.0);
    if (!solidAt(hitV + vec3(-1.0, 0.0, 0.0))) n += vec3(-1.0, 0.0, 0.0);
    if (!solidAt(hitV + vec3(0.0,  1.0, 0.0))) n += vec3(0.0,  1.0, 0.0);
    if (!solidAt(hitV + vec3(0.0, -1.0, 0.0))) n += vec3(0.0, -1.0, 0.0);
    if (!solidAt(hitV + vec3(0.0, 0.0,  1.0))) n += vec3(0.0, 0.0,  1.0);
    if (!solidAt(hitV + vec3(0.0, 0.0, -1.0))) n += vec3(0.0, 0.0, -1.0);
    if (dot(n, n) < 1.0e-5) n = vec3(0.0, 1.0, 0.0);
    n = normalize(n);

    // Sun-driven directional shading (matches where the sun disc is drawn above).
    vec3 L = normalize(uSunDir);
    float diffuse = max(dot(n, L), 0.0);
    vec3 colour = vox * (0.35 + 0.65 * diffuse);

    // Voxel shadows: march toward the sun; if a solid voxel blocks the light, darken the face.
    // (Fixed-step so it stays cheap; 24 steps covers the grid depth at the sun's angle.)
    float shadow = 1.0;
    vec3 sp = hitV;
    for (int i = 0; i < 24; i++) {
        sp += L * 0.75;
        if (!inGrid(sp)) break;
        if (solidAt(sp)) { shadow = 0.25; break; }
    }
    colour *= mix(0.55, 1.0, shadow);

    // Distance fog so the grid edge fades into the sky instead of a hard void cutoff.
    float fog = clamp(hitT / max(uGridSize * 2.0, 1.0), 0.0, 1.0);
    colour = mix(colour, sky, fog * 0.85);

    fragColor = vec4(colour, 1.0);

    // Write depth at the true ray-entry point, not the voxel centre: the centre is up to half
    // a block too deep, which made entities and placed models clip into block faces. Slab-test
    // the ray against the hit voxel's AABB to get the exact entry distance.
    vec3 boxMin = uGridMin + hitV;
    vec3 boxMax = boxMin + vec3(1.0);
    vec3 t0 = (boxMin - uCamPos) / rd;
    vec3 t1 = (boxMax - uCamPos) / rd;
    float tNear = max(max(min(t0.x, t1.x), min(t0.y, t1.y)), min(t0.z, t1.z));
    if (isnan(tNear) || tNear < 0.0) tNear = 0.0;
    vec3 hitWorld = uCamPos + rd * tNear;
    vec4 clip = uProjView * vec4(hitWorld, 1.0);
    gl_FragDepth = (clip.z / clip.w) * 0.5 + 0.5;
}
