package ch.lkmc.goo.engine.gl

/**
 * The engine's shaders. Each one is a transliteration of a method on
 * [ch.lkmc.goo.engine.core.DisplacementField] / BrushFalloff — the unit
 * tests pin the semantics there; keep the pairs trivially close:
 *
 * - `falloff()`            ↔ BrushFalloff.weight
 * - STAMP_FRAG main        ↔ DisplacementField.applyStamp
 * - WARP_FRAG main         ↔ DisplacementField.warpedSource
 */
object GlShaders {

    /**
     * Fullscreen triangle-strip quad; a_pos in [-1,1]² NDC, v_uv in [0,1]²
     * — deliberately NOT flipped: the fragment writing field texel row 0
     * gets v_uv.y = 0, and that texel is later sampled at v = 0, so field
     * texel v ≡ image UV v (top-left origin) stays self-consistent across
     * the stamp write and the warp read. Only WARP_VERT, which maps to the
     * y-up window, flips.
     */
    const val QUAD_VERT = """#version 300 es
layout(location = 0) in vec2 a_pos;
out vec2 v_uv;
void main() {
    v_uv = a_pos * 0.5 + 0.5;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

    /**
     * Stamp pass: reads the previous field (u_field), writes the next.
     * One fragment = one texel of DisplacementField.applyStamp's loop,
     * switched on u_mode (StampMode.shaderId) and u_profile
     * (FalloffProfile.shaderId).
     *
     * Warp modes:  D'(p) = b(p) + D(p + b(p))
     * Field modes: RELAX blends toward a 4-tap blur of D; ERASE fades D.
     *
     * The literals mirror core constants — keep in sync:
     *   0.004 = BrushDynamics.RADIAL_STEP_UV
     *   0.22  = BrushDynamics.BLEND_STEP
     *   0.08  = BrushDynamics.CENTER_RAMP_END
     *   0.7   = BrushFalloff.PLATEAU_EDGE
     */
    const val STAMP_FRAG = """#version 300 es
precision highp float;
// highp explicitly: sampler2D defaults to lowp in GLSL ES, which would
// quantize the float displacement field to 8-bit steps.
uniform highp sampler2D u_field;
uniform vec2 u_center;      // stamp center, UV
uniform vec2 u_delta;       // content displacement, UV delta
uniform float u_radius;     // aspect-space radius
uniform float u_strength;
uniform float u_aspect;     // image width / height
uniform int u_mode;         // StampMode.shaderId
uniform int u_profile;      // FalloffProfile.shaderId
uniform vec2 u_fieldTexel;  // 1 / field dimensions
in vec2 v_uv;
out vec4 o_field;

// BrushFalloff.weight: smoothstep(1 -> 0), C1 at both ends.
float base(float d) {
    if (d <= 0.0) return 1.0;
    if (d >= 1.0) return 0.0;
    float t = 1.0 - d;
    return t * t * (3.0 - 2.0 * t);
}

// BrushFalloff.weight(d, profile).
float falloff(float d) {
    if (u_profile == 1) { float w = base(d); return w * w; }
    if (u_profile == 2) return d <= 0.7 ? 1.0 : base((d - 0.7) / 0.3);
    return base(d);
}

// BrushFalloff.centerRamp.
float centerRamp(float d) {
    float t = clamp(d / 0.08, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// smoothOdd (DisplacementField): odd, C1, zero at 0, ±1 by |x| = 1.
float smoothOdd(float x) {
    float a = min(abs(x), 1.0);
    float shaped = a * a * (3.0 - 2.0 * a);
    return x < 0.0 ? -shaped : shaped;
}

// sign() that is +1 at zero, matching the Kotlin helper.
float signPos(float x) { return x < 0.0 ? -1.0 : 1.0; }

void main() {
    vec2 fromCenter = v_uv - u_center;
    fromCenter.x *= u_aspect;
    // FalloffProfile.DRIP (3) compresses the distance BELOW the center
    // so the lobe reaches downward; every other profile is radial.
    // 0.45 = BrushDynamics.DRIP_LOBE.
    vec2 metric = fromCenter;
    if (u_profile == 3 && metric.y > 0.0) metric.y *= 0.45;
    float distA = length(metric);
    float d = distA / u_radius;
    float w = falloff(d) * u_strength;
    // The radial direction is measured on the TRUE offset, not the
    // anisotropic metric — only the weight is reshaped.
    float distR = length(fromCenter);
    // Field texel: xy = displacement, z = Fusion mask (see
    // DisplacementField — CHANNELS is 3; the texture's w is unused).
    vec3 cur = texture(u_field, v_uv).xyz;
    vec3 next;
    if (u_mode == 5) {              // FUSE: mask flow, displacement as-is
        // 0.3  = BrushDynamics.FUSE_STEP (0.22 below = BLEND_STEP) —
        // documented-duplication convention, keep in sync.
        next = vec3(cur.xy, clamp(cur.z + w * 0.3, 0.0, 1.0));
    } else if (u_mode == 3) {       // RELAX
        vec3 blur = 0.25 * (
            texture(u_field, v_uv + vec2(u_fieldTexel.x, 0.0)).xyz +
            texture(u_field, v_uv - vec2(u_fieldTexel.x, 0.0)).xyz +
            texture(u_field, v_uv + vec2(0.0, u_fieldTexel.y)).xyz +
            texture(u_field, v_uv - vec2(0.0, u_fieldTexel.y)).xyz);
        next = mix(cur, blur, w * 0.22);
    } else if (u_mode == 4) {       // ERASE (un-fuses too)
        next = cur * (1.0 - w * 0.22);
    } else {                        // warp modes: b(p) then warp-of-warp
        vec2 b;
        if (u_mode == 0) {          // DIRECTIONAL
            b = -u_delta * w;
        } else if (u_mode == 7) {   // COMB: teeth cut across the drag
            // 3.0 = BrushDynamics.COMB_TEETH, 6.2831855 = TAU.
            vec2 a = vec2(u_delta.x * u_aspect, u_delta.y);
            float len = length(a);
            if (len < 1e-9) {
                b = vec2(0.0);
            } else {
                vec2 across = vec2(-a.y / len, a.x / len);
                float s = dot(fromCenter, across) / u_radius;
                float teeth = 0.5 + 0.5 * cos(6.2831855 * s * 3.0);
                b = -u_delta * w * teeth;
            }
        } else if (u_mode == 9) {   // FAULT: opposed shear across the seam
            // 0.01 = BrushDynamics.FAULT_STEP_UV.
            vec2 a = vec2(u_delta.x * u_aspect, u_delta.y);
            float len = length(a);
            if (len < 1e-9) {
                b = vec2(0.0);
            } else {
                vec2 t = a / len;
                float side = smoothOdd(dot(fromCenter, vec2(-t.y, t.x)) / u_radius);
                float m = side * w * 0.01;
                b = vec2((t.x / u_aspect) * m, t.y * m);
            }
        } else {                    // radial family: 1, 2, 6, 8
            // 0.004 = RADIAL_STEP_UV, 0.0035 = SWIRL_STEP_UV,
            // 0.01 = RIPPLE_STEP_UV, 3.0 = RIPPLE_BANDS.
            float ramp = w * centerRamp(d);
            if (distR < 1e-6) {
                b = vec2(0.0);
            } else if (u_mode == 6) {           // VORTEX
                vec2 t = vec2(-fromCenter.y / distR, fromCenter.x / distR);
                float m = ramp * 0.0035 * signPos(u_delta.x);
                b = vec2((t.x / u_aspect) * m, t.y * m);
            } else {
                vec2 outward =
                    vec2((fromCenter.x / distR) / u_aspect, fromCenter.y / distR);
                if (u_mode == 8) {              // RIPPLE
                    b = outward * (ramp * 0.01 * sin(6.2831855 * 3.0 * d));
                } else {                        // INFLATE (1) / DEFLATE (2)
                    b = (u_mode == 1 ? -1.0 : 1.0) * outward * (ramp * 0.004);
                }
            }
        }
        // Mask rides the same lookup — painted fusion moves with the goo.
        vec3 prev = texture(u_field, v_uv + b).xyz;
        next = vec3(b + prev.xy, prev.z);
    }
    o_field = vec4(next, 0.0);
}
"""

    /**
     * Warp pass vertex stage: the quad covers the letterboxed image rect
     * (u_rectPx maps it), so v_uv is image UV directly.
     *
     * Since the view transform (v1.1): positions run through PIXEL space,
     * because a similarity applied in NDC would shear under non-square
     * viewports (NDC x and y scale differ by the aspect). u_view is the
     * ViewTransform similarity (a = s·cosθ, b = s·sinθ, tx, ty) —
     * identity (1,0,0,0) for export and movie passes, which render the
     * document, never the view. u_rectPx.y is the TOP edge; a negative
     * height flips (the export/readback trick, unchanged in spirit).
     */
    const val WARP_VERT = """#version 300 es
layout(location = 0) in vec2 a_pos;   // shared quad VBO: NDC corners in [-1,1]²
uniform vec4 u_rectPx;                // image quad in pixels: x, y = top-left, z, w = size
uniform vec2 u_viewport;              // render target size in pixels
uniform vec4 u_view;                  // pixel-space similarity: a, b, tx, ty
out vec2 v_uv;
void main() {
    vec2 unit = a_pos * 0.5 + 0.5;               // [0,1]², origin bottom-left
    v_uv = vec2(unit.x, 1.0 - unit.y);           // image UV, origin top-left
    // Pixel position, y-down to match v_uv's row order.
    vec2 px = vec2(u_rectPx.x + unit.x * u_rectPx.z,
                   u_rectPx.y + (1.0 - unit.y) * u_rectPx.w);
    vec2 tp = vec2(u_view.x * px.x - u_view.y * px.y + u_view.z,
                   u_view.y * px.x + u_view.x * px.y + u_view.w);
    gl_Position = vec4(tp.x / u_viewport.x * 2.0 - 1.0,
                       1.0 - tp.y / u_viewport.y * 2.0, 0.0, 1.0);
}
"""

    /**
     * The global-effect levers ride the warp pass as analytic uniforms —
     * GlobalField.displacement transliterated. u_g order is
     * GlobalParams.toArray(): bulge, twirl, squeeze, stretch, spike,
     * static. Literals mirror GlobalField constants — keep in sync:
     *   2.5  = TWIRL_MAX_RAD    0.35 = BULGE_SCALE   0.3  = AXIS_SCALE
     *   0.08 = SPIKE_SCALE      8    = SPIKE_COUNT
     *   0.05 = STATIC_SCALE     24   = STATIC_CELLS
     *   0.5  = LENS_SCALE       2.0  = LENS_TWIRL_RAD  0.35 = LENS_CORE
     * and the lens `type ==` literals are LensType.shaderId values.
     * The integer hash is bit-identical to GlobalField.hash — value
     * noise stays CPU/GPU consistent (no sin-hash driver drift).
     */
    const val WARP_FRAG = """#version 300 es
precision highp float;
// highp int explicitly: fragment shaders predeclare mediump int (GLSL ES
// §4.5.4), which the spec only guarantees 16 bits — the integer hash
// below would collapse (h >> 16 ≡ 0, products wrap at 2^16) on 16-bit-ALU
// GPUs. highp int is exactly 32-bit, matching Kotlin UInt bit for bit.
precision highp int;
uniform sampler2D u_image;
// highp: see STAMP_FRAG — the field must not be read at lowp.
uniform highp sampler2D u_field;
// GOOvie tween pair (PLAN.md §4.1: tweening two fields is mix(D1,D2,t)).
// Outside a scrub u_tween is 0 and u_fieldB is bound to the live field,
// so mix() degenerates to the plain read.
uniform highp sampler2D u_fieldB;
uniform float u_tween;
// Fusion (PLAN.md §3): photo B, cover-cropped to A's UV space at upload,
// revealed by the field's z-channel mask. u_hasB gates stale masks when
// no B is loaded.
uniform sampler2D u_imageB;
uniform float u_hasB;
uniform float u_gAspect;   // image width / height
uniform float u_g[6];
// Placed warps (proposal 0006). Fixed-size pack, so the pass cost never
// depends on the document: xy = center UV, z = radius (aspect space),
// w = strength. u_lensType carries LensType.shaderId.
uniform vec4 u_lens[4];
uniform int u_lensType[4];
uniform int u_lensCount;
in vec2 v_uv;
out vec4 o_color;

float hash(uint x, uint y, uint seed) {
    uint h = x * 1664525u + y * 1013904223u + seed * 2654435761u;
    h = h ^ (h >> 16);
    h *= 2246822519u;
    h = h ^ (h >> 13);
    return float(h & 0x00FFFFFFu) / 16777216.0;
}

float valueNoise(float x, float y, uint seed) {
    float xf = floor(x);
    float yf = floor(y);
    int x0 = int(xf);
    int y0 = int(yf);
    float fx = x - xf;
    float fy = y - yf;
    float sx = fx * fx * (3.0 - 2.0 * fx);
    float sy = fy * fy * (3.0 - 2.0 * fy);
    float a = hash(uint(x0), uint(y0), seed);
    float b = hash(uint(x0 + 1), uint(y0), seed);
    float c = hash(uint(x0), uint(y0 + 1), seed);
    float d = hash(uint(x0 + 1), uint(y0 + 1), seed);
    float top = a + (b - a) * sx;
    float bottom = c + (d - c) * sx;
    return top + (bottom - top) * sy;
}

float smoothShape(float t) { return t * t * (3.0 - 2.0 * t); }

// GlobalField.displacement, line for line.
vec2 globalDisp(vec2 uv) {
    float ax = (uv.x - 0.5) * u_gAspect;
    float ay = uv.y - 0.5;
    float r = sqrt(ax * ax + ay * ay);
    float rMax = sqrt(u_gAspect * u_gAspect + 1.0) * 0.5;
    float shape = smoothShape(1.0 - clamp(r / rMax, 0.0, 1.0));
    float dx = 0.0;
    float dy = 0.0;
    if (u_g[0] != 0.0 && r > 1e-6) {
        float m = -u_g[0] * 0.35 * shape * r / rMax;
        dx += (ax / r) * m;
        dy += (ay / r) * m;
    }
    if (u_g[1] != 0.0) {
        float theta = u_g[1] * 2.5 * shape;
        float c = cos(theta);
        float s = sin(theta);
        dx += ax * c - ay * s - ax;
        dy += ax * s + ay * c - ay;
    }
    if (u_g[2] != 0.0) {
        dx += ax * u_g[2] * 0.3;
        dy += -ay * u_g[2] * 0.3 * 0.5;
    }
    if (u_g[3] != 0.0) {
        dy += -ay * u_g[3] * 0.3;
    }
    if (u_g[4] != 0.0 && r > 1e-6) {
        float phi = atan(ay, ax);
        float m = u_g[4] * 0.08 * shape * sin(8.0 * phi);
        dx += (ax / r) * m;
        dy += (ay / r) * m;
    }
    if (u_g[5] != 0.0) {
        float nx = valueNoise(uv.x * 24.0, uv.y * 24.0, 1u);
        float ny = valueNoise(uv.x * 24.0, uv.y * 24.0, 2u);
        dx += (nx * 2.0 - 1.0) * u_g[5] * 0.05;
        dy += (ny * 2.0 - 1.0) * u_g[5] * 0.05;
    }
    // Placed warps, on top of the frame-centered ones — same aspect
    // space, so they sum in before the one conversion back to UV.
    for (int i = 0; i < 4; i++) {
        if (i >= u_lensCount) break;
        vec4 lens = u_lens[i];
        float lx = (uv.x - lens.x) * u_gAspect;
        float ly = uv.y - lens.y;
        float d = sqrt(lx * lx + ly * ly);
        if (d >= lens.z) continue;
        float window = smoothShape(1.0 - d / lens.z);
        int type = u_lensType[i];
        if (type == 3) {
            float theta = lens.w * 2.0 * window;
            float c = cos(theta);
            float s = sin(theta);
            dx += lx * c - ly * s - lx;
            dy += lx * s + ly * c - ly;
        } else if (d > 1e-6) {
            float ramp = type == 2
                ? min(d / (lens.z * 0.35), 1.0)
                : d / lens.z;
            float dir = type == 1 ? 1.0 : -1.0;
            float m = dir * lens.w * 0.5 * lens.z * window * ramp;
            dx += (lx / d) * m;
            dy += (ly / d) * m;
        }
    }

    return vec2(dx / u_gAspect, dy);
}

void main() {
    // xy = displacement, z = fusion mask; the vec3 mix tweens both, so
    // GOOvies animate fusion reveals with no extra machinery.
    vec3 fa = texture(u_field, v_uv).xyz;
    vec3 fb = texture(u_fieldB, v_uv).xyz;
    vec3 f = mix(fa, fb, u_tween);
    vec2 disp = f.xy + globalDisp(v_uv);
    vec2 src = v_uv + disp;
    vec4 colorA = texture(u_image, src);
    vec4 colorB = texture(u_imageB, src);
    o_color = mix(colorA, colorB, clamp(f.z, 0.0, 1.0) * u_hasB);
}
"""
}
