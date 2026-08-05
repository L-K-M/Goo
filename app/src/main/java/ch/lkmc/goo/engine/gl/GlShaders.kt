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
     * One fragment = one texel of DisplacementField.applyStamp's loop:
     *
     *     b(p)  = -delta · strength · falloff(|p−c|_aspect / radius)
     *     D'(p) = b(p) + D(p + b(p))
     */
    const val STAMP_FRAG = """#version 300 es
precision highp float;
// highp explicitly: sampler2D defaults to lowp in GLSL ES, which would
// quantize the float displacement field to 8-bit steps.
uniform highp sampler2D u_field;
uniform vec2 u_center;    // stamp center, UV
uniform vec2 u_delta;     // content displacement, UV delta
uniform float u_radius;   // aspect-space radius
uniform float u_strength;
uniform float u_aspect;   // image width / height
in vec2 v_uv;
out vec4 o_field;

// BrushFalloff.weight: smoothstep(1 -> 0), C1 at both ends.
float falloff(float d) {
    if (d <= 0.0) return 1.0;
    if (d >= 1.0) return 0.0;
    float t = 1.0 - d;
    return t * t * (3.0 - 2.0 * t);
}

void main() {
    vec2 fromCenter = v_uv - u_center;
    fromCenter.x *= u_aspect;
    float d = length(fromCenter) / u_radius;
    vec2 b = -u_delta * u_strength * falloff(d);
    vec2 prev = texture(u_field, v_uv + b).xy;
    o_field = vec4(b + prev, 0.0, 1.0);
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
