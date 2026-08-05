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

void main() {
    vec2 fromCenter = v_uv - u_center;
    fromCenter.x *= u_aspect;
    float distA = length(fromCenter);
    float d = distA / u_radius;
    float w = falloff(d) * u_strength;
    vec2 cur = texture(u_field, v_uv).xy;
    vec2 next;
    if (u_mode == 3) {              // RELAX
        vec2 blur = 0.25 * (
            texture(u_field, v_uv + vec2(u_fieldTexel.x, 0.0)).xy +
            texture(u_field, v_uv - vec2(u_fieldTexel.x, 0.0)).xy +
            texture(u_field, v_uv + vec2(0.0, u_fieldTexel.y)).xy +
            texture(u_field, v_uv - vec2(0.0, u_fieldTexel.y)).xy);
        next = mix(cur, blur, w * 0.22);
    } else if (u_mode == 4) {       // ERASE
        next = cur * (1.0 - w * 0.22);
    } else {                        // warp modes: b(p) then warp-of-warp
        vec2 b;
        if (u_mode == 0) {          // DIRECTIONAL
            b = -u_delta * w;
        } else {                    // INFLATE (1) / DEFLATE (2)
            float m = w * centerRamp(d) * 0.004;
            vec2 outward = distA < 1e-6
                ? vec2(0.0)
                : vec2((fromCenter.x / distA) / u_aspect, fromCenter.y / distA);
            b = (u_mode == 1 ? -1.0 : 1.0) * outward * m;
        }
        next = b + texture(u_field, v_uv + b).xy;
    }
    o_field = vec4(next, 0.0, 1.0);
}
"""

    /**
     * Warp pass: paints the image quad sampling the source at p + D(p).
     * The quad covers the letterboxed image rect (u_rect maps it), so v_uv
     * here is image UV directly.
     */
    const val WARP_VERT = """#version 300 es
layout(location = 0) in vec2 a_pos;   // shared quad VBO: NDC corners in [-1,1]²
uniform vec4 u_rect;                  // image quad in NDC: x, y = bottom-left, z, w = size
out vec2 v_uv;
void main() {
    vec2 unit = a_pos * 0.5 + 0.5;               // [0,1]², origin bottom-left
    v_uv = vec2(unit.x, 1.0 - unit.y);           // image UV, origin top-left
    vec2 ndc = u_rect.xy + unit * u_rect.zw;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""

    /**
     * The global-effect levers ride the warp pass as analytic uniforms —
     * GlobalField.displacement transliterated. u_g order is
     * GlobalParams.toArray(): bulge, twirl, squeeze, stretch, spike,
     * static. Literals mirror GlobalField constants — keep in sync:
     *   2.5  = TWIRL_MAX_RAD    0.35 = BULGE_SCALE   0.30 = AXIS_SCALE
     *   0.08 = SPIKE_SCALE      8    = SPIKE_COUNT
     *   0.05 = STATIC_SCALE     24   = STATIC_CELLS
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
uniform float u_gAspect;   // image width / height
uniform float u_g[6];
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
        dx += ax * u_g[2] * 0.30;
        dy += -ay * u_g[2] * 0.30 * 0.5;
    }
    if (u_g[3] != 0.0) {
        dy += -ay * u_g[3] * 0.30;
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
    return vec2(dx / u_gAspect, dy);
}

void main() {
    vec2 dispA = texture(u_field, v_uv).xy;
    vec2 dispB = texture(u_fieldB, v_uv).xy;
    vec2 disp = mix(dispA, dispB, u_tween) + globalDisp(v_uv);
    o_color = texture(u_image, v_uv + disp);
}
"""
}
