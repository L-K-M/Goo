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

    const val WARP_FRAG = """#version 300 es
precision highp float;
uniform sampler2D u_image;
// highp: see STAMP_FRAG — the field must not be read at lowp.
uniform highp sampler2D u_field;
in vec2 v_uv;
out vec4 o_color;
void main() {
    vec2 disp = texture(u_field, v_uv).xy;
    o_color = texture(u_image, v_uv + disp);
}
"""
}
