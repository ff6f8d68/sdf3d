# SDF3D — `.s3d` model format

A `.s3d` file describes a 3D shape as a single **signed distance function** (SDF),
written as nested function calls. The mod raymarches this function directly on the
GPU — there is no mesh, so shapes are always perfectly smooth.

```text
smooth_union(0.12, rounded_box(0, 0, 0, 0.5, 0.35, 0.5, 0.08), sphere(0.45, 0.35, 0, 0.4))
```

- **Negative** distance means *inside* the shape, **positive** means *outside*.
- `+Y` is up. Most primitives are axis-aligned along Y where it matters.
- Distances are in "blocks" (1.0 = one block), matching Minecraft's world scale.
- `//` starts a line comment. Whitespace and newlines are insignificant.
- A file has optional **material directives** followed by exactly **one** root
  expression (the SDF).

---

## Where files live

A model id of `sdf3d:sdf/example_sphere` resolves to the resource
`assets/sdf3d/sdf/example_sphere.s3d` (the extension is appended automatically).

You can reference a model from a block/item model JSON using the geometry loader:

```json
{ "loader": "sdf3d:sdf", "model": "sdf3d:sdf/example_sphere" }
```

Two example models ship with the mod:

- **`example_sphere.s3d`** — the showcase: a *sculpted humanoid* built out of a
  plain sphere via `carve`/`stamp`/`blend`. It uses every feature of the format:
  groups (limbs instanced with a keyframed walk cycle), morphing (the head
  oscillates between the sculpted face and a bare sphere), keyframe tracks with
  `easing` (arms, legs, head sway, beating heart), sculpting (eye sockets,
  mouth, waist groove, a chest window showing a beating octahedron heart),
  triplanar UV mapping, and spin/bob/pulse/twist/bend on top. This is the model
  used by the `example_sdf_block` item and block.
- **`example_gallery.s3d`** — a 7×4 grid, one demo per cell (every primitive,
  CSG op, transform and animation in isolation).

---

## Material directives

Placed at the top of the file as `key = value;`:

| Directive    | Type    | Example              | Meaning                                   |
|--------------|---------|----------------------|-------------------------------------------|
| `texture`    | string  | `"sdf3d:textures/foo"` | Albedo texture from the block atlas, sampled with triplanar UV (optional). |
| `tint`       | int     | `0xFFFF8800`         | Base color, `0xAARRGGBB` (also decimal).  |
| `emissive`   | bool    | `false`              | `true` = unlit (ignores light).           |
| `roughness`  | float   | `0.6`                | `0..1`, surface roughness hint.           |
| `metallic`   | float   | `0.0`                | `0..1`, metallicness (PBR).               |
| `easing`     | ident / bezier | `easeInOut` or `bezier(0.42,0,0.58,1)` | keyframe interpolation curve (default `linear`). |

`roughness` and `metallic` feed a Cook-Torrance microfacet BRDF (GGX distribution,
Schlick-GGX geometry, Schlick Fresnel) in the GPU raymarcher, so metals render with a
coloured specular highlight and dielectric surfaces get a soft Blinn-like sheen.

### Easing curves

`easing` controls how every keyframed animation (`keyframe_scale`,
`keyframe_translate`, `keyframe_rotate`) interpolates between its keyframes:

| Value | Behaviour |
|-------|-----------|
| `linear` (default) | constant-speed lerp |
| `smoothstep` | smooth S-curve (slow-fast-slow) |
| `easeIn` | quadratic ease-in |
| `easeOut` | quadratic ease-out |
| `easeInOut` | ease-in then ease-out |
| `bezier(cx1, cy1, cx2, cy2)` | CSS-style cubic-bezier with the given control points |

```text
easing = easeInOut;
// or a custom curve:
easing = bezier(0.3, 0.0, 0.7, 1.0);
```

```text
tint = 0xFFFF8800;
emissive = false;
roughness = 0.6;
metallic = 0.0;

sphere(0, 0, 0, 0.5)
```

---

## Coordinates & arithmetic

- `x`, `y`, `z` — the current point's coordinate (usable to build raw functions).
- Numeric literals: decimal (`1.5`) or hex (`0xFF`).

| Function | Meaning |
|----------|---------|
| `add(a, b, …)` | sum |
| `sub(a, b)`    | `a - b` |
| `mul(a, b, …)` | product |
| `div(a, b)`    | `a / b` |
| `neg(a)`       | `-a` |
| `abs(a)`       | absolute value |
| `min(a, b, …)` | minimum |
| `max(a, b, …)` | maximum |

Example (algebraic sphere): `sub(add(mul(x,x), add(mul(y,y), mul(z,z))), 1)`.

---

## Primitives

All take a centre/position first, then size parameters.

| Function | Parameters | Description |
|----------|-----------|-------------|
| `sphere(x, y, z, r)` | centre, radius | sphere |
| `box(x, y, z, hx, hy, hz)` | centre, half-extents | axis-aligned box |
| `rounded_box(x, y, z, hx, hy, hz, r)` | centre, half-extents, rounding | box with rounded edges |
| `ellipsoid(x, y, z, rx, ry, rz)` | centre, radii | ellipsoid |
| `torus(x, y, z, R, r)` | centre, major, minor | torus in the XZ plane |
| `cylinder(x, y, z, r, h)` | centre, radius, height | cylinder along Y |
| `capsule(ax, ay, az, bx, by, bz, r)` | two endpoints, radius | capsule (segment) |
| `plane(nx, ny, nz, d)` | normal (auto-normalized), offset | infinite plane |
| `cone(x, y, z, r, h)` | centre, base radius, height | round cone, tip up (+Y) |
| `hex_prism(x, y, z, r, h)` | centre, circumradius, height | hexagonal prism along Y |
| `octahedron(x, y, z, s)` | centre, size | octahedron (diamond) |

---

## Combinators (CSG)

| Function | Parameters | Description |
|----------|-----------|-------------|
| `union(a, b, …)` | shapes | boolean union (min) |
| `intersect(a, b, …)` | shapes | boolean intersection (max) |
| `subtract(a, b)` | `a`, `b` | `a` minus `b` |
| `smooth_union(k, a, b, …)` | smoothing, shapes | blended union |
| `smooth_intersect(k, a, b)` | smoothing, shapes | blended intersection |
| `smooth_subtract(k, a, b)` | smoothing, shapes | blended subtraction |

`k` is the blend radius (in blocks). Larger values round the seam more.

### Sculpting brushes

These are aliases that read like the brush tools of a sculpting package:

| Function | Parameters | Description |
|----------|-----------|-------------|
| `carve(base, brush)` | shapes | carve the brush shape out of the base (`subtract`) |
| `stamp(base, brush)` | shapes | stamp/add the brush onto the base (`union`) |
| `blend(k, a, b)` | smoothing, shapes | smoothly blend two shapes (`smooth_union`) |

```text
// Carve a sphere-shaped hollow out of a box.
carve(box(0, 0, 0, 0.6, 0.6, 0.6), sphere(0.2, 0.2, 0.2, 0.4))
```

---

## Transforms

Transform functions take their numeric parameters first and the **child** shape last.

| Function | Parameters | Description |
|----------|-----------|-------------|
| `translate(x, y, z, child)` | offset, child | move |
| `scale(x, y, z, child)` | scale, child | scale (uniform or not, auto-detected) |
| `stretch(x, y, z, child)` | scale, child | explicit non-uniform scale |
| `rotate(dx, dy, dz, child)` | degrees, child | rotate (XYZ order) |
| `round(r, child)` | radius, child | inflate / round the surface |
| `onion(t, child)` | thickness, child | hollow shell of thickness `t` |

---

## Animation

Animation transforms are driven by a global clock (seconds). They evaluate on both
the CPU and GPU paths, so they work in the item, in the hand, and in the world.

| Function | Parameters | Description |
|----------|-----------|-------------|
| `spin(ax, ay, az, degPerSec, child)` | axis, speed, child | rotate around the axis |
| `bob(ax, ay, az, amp, cps, child)` | axis, amplitude, cycles/sec, child | translate along the axis |
| `pulse(minS, maxS, cps, child)` | min scale, max scale, cycles/sec, child | pulse the scale |
| `morph(a, b, periodSec)` | shapes, period | cyclical crossfade between shapes `a` and `b` |
| `twist(radPerUnitY, child)` | twist amount, child | twist around +Y (deformation) |
| `bend(radPerUnitX, child)` | bend amount, child | bend around +Z (deformation) |
| `keyframe_scale(period, t0,v0,t1,v1,t2,v2,t3,v3, child)` | period, 4 time/value pairs, child | keyframed scale over time |
| `keyframe_translate(period, t0,x0,y0,z0, t1,x1,y1,z1, child)` | period, 2 keyframed positions, child | keyframed translation |
| `keyframe_rotate(ax, ay, az, period, t0, angle0, t1, angle1, child)` | axis, period, 2 keyframed angles, child | keyframed rotation |

Examples:

```text
// A sphere orbiting the origin once per second.
spin(0, 1, 0, 360, sphere(0.75, 0, 0, 0.25))

// A sphere bobbing up and down.
bob(0, 1, 0, 0.3, 1, sphere(0, 0, 0, 0.2))

// A sphere breathing between 0.5x and 1.5x scale.
pulse(0.5, 1.5, 1, sphere(0, 0, 0, 0.3))

// Morph a box into a sphere over 2 seconds.
morph(box(0, 0, 0, 0.4, 0.4, 0.4), sphere(0, 0, 0, 0.4), 2)

// Twist a tall box around +Y (2 radians per block of height).
twist(2, box(0, 0, 0, 0.3, 0.8, 0.3))

// Keyframed scale: 0.5x at t=0, 1.5x at t=1, back to 0.5x at t=2 (loops every 2s).
keyframe_scale(2, 0, 0.5, 1, 1.5, 2, 0.5, 2, 0.5, sphere(0, 0, 0, 0.3))

// Keyframed translation: moves from (0,0,0) to (0,1,0) and back, looping every 4s.
keyframe_translate(4, 0, 0,0,0, 2, 0,1,0, sphere(0, 0, 0, 0.3))

// Keyframed rotation: rocks a box around +Z from 0° to 45° and back, every 2s.
keyframe_rotate(0, 0, 1, 2, 0, 0, 1, 45, box(0, 0, 0, 0.4, 0.4, 0.4))
```

`morph` blends the two distance fields linearly, so the two shapes should be
roughly the same size/position for a clean transition. `keyframe_scale` linearly
interpolates between its four `(time, value)` pairs and wraps at `period` seconds.
`keyframe_translate` lerps a position between two `(time, x, y, z)` keyframes, and
`keyframe_rotate` lerps an angle (in degrees) between two `(time, angle)` keyframes.
All three keyframe functions apply the global `easing` curve to their interpolation
(see *Material directives → Easing curves*).
`twist`/`bend` are space deformations, so their bounds are estimated conservatively
(a bounding sphere) — keep the twist/bend amount small to avoid surprises.

---

## Groups

Reusable named sub-shapes. Define a group with a braced block, then reference it by
name as an expression; the reference inlines (duplicates) the group's subtree, so a
group can be instantiated multiple times with different transforms.

```text
// Definition: a named union of two primitives.
group("wing") {
    box(0, 0, 0, 0.4, 0.1, 0.3),
    translate(0, 0, 0.3, sphere(0, 0, 0, 0.15))
}

// References (each is an independent copy of the wing subtree).
union(
    translate(-0.5, 0, 0, group("wing")),
    translate( 0.5, 0, 0, spin(0, 1, 0, 90, group("wing")))
)
```

- Groups must be defined before they are referenced.
- A group's body is one or more comma-separated expressions, implicitly unioned.
- `group("name")` is usable anywhere a shape expression is expected.

---

## Full example

A compact example of the basics:

```text
// A rounded box fused to a sphere, with a spinning torus on top.
tint = 0xFFFF8800;
emissive = false;
roughness = 0.6;

union(
    smooth_union(0.12, rounded_box(0, 0, 0, 0.5, 0.35, 0.5, 0.08), sphere(0.45, 0.35, 0, 0.4)),
    translate(0, 0.6, 0, spin(0, 1, 0, 90, torus(0, 0, 0, 0.25, 0.08)))
)
```

And the full sculpted-humanoid showcase (what `example_sphere.s3d` contains),
showing groups, sculpting brushes, keyframes and morphing working together:

```text
texture = "minecraft:block/iron_block";
roughness = 0.25;
metallic = 0.9;
easing = easeInOut;

group("head") {
    blend(0.06, sphere(0, 0, 0, 0.24), ellipsoid(0, -0.06, 0.02, 0.22, 0.21, 0.235))
}

group("face") {
    stamp(                                  // hair
        stamp(                              // nose
            stamp(                          // right ear
                stamp(                      // left ear
                    stamp(                  // right eye
                        stamp(              // left eye
                            carve(          // mouth
                                carve(      // right socket
                                    carve(  // left socket
                                        group("head"),
                                        translate(-0.085, 0.045, -0.21, sphere(0, 0, 0, 0.042))
                                    ),
                                    translate(0.085, 0.045, -0.21, sphere(0, 0, 0, 0.042))
                                ),
                                translate(0, -0.105, -0.20, box(0, 0, 0, 0.075, 0.022, 0.035))
                            ),
                            translate(-0.085, 0.045, -0.23, sphere(0, 0, 0, 0.03))
                        ),
                        translate(0.085, 0.045, -0.23, sphere(0, 0, 0, 0.03))
                    ),
                    translate(-0.245, 0, 0, sphere(0, 0, 0, 0.055))
                ),
                translate(0.245, 0, 0, sphere(0, 0, 0, 0.055))
            ),
            translate(0, -0.025, -0.235, ellipsoid(0, 0, 0, 0.035, 0.055, 0.025))
        ),
        group("hair")
    )
}

group("arm") {
    smooth_union(0.06,
        sphere(0, 0, 0, 0.11),
        smooth_union(0.06,
            capsule(0, 0, 0, 0, -0.35, 0, 0.08),
            smooth_union(0.06,
                capsule(0, -0.35, 0, 0, -0.68, 0, 0.07),
                sphere(0, -0.74, 0, 0.08)
            )
        )
    )
}

spin(0, 1, 0, 12,                           // turntable
    smooth_union(0.09,
        translate(0, 1.62, 0,
            keyframe_translate(4, 0, -0.03, 0, 0, 2, 0.03, 0, 0,
                morph(group("face"), sphere(0, 0, 0, 0.24), 6.0)
            )
        ),
        translate(-0.33, 1.30, 0, keyframe_rotate(1, 0, 0, 2.0, 0, 15, 1, -15, group("arm"))),
        translate( 0.33, 1.30, 0, keyframe_rotate(1, 0, 0, 2.0, 0, -15, 1, 15, group("arm")))
    )
)
```

The file on disk adds the legs, torso (with the carved heart window and beating
heart), belt buckle, breathing `pulse`, `twist`, `bob` and the hair `bend`
group — see `src/main/resources/assets/sdf3d/sdf/example_sphere.s3d` for the
complete source.
