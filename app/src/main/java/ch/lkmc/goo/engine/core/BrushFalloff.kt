package ch.lkmc.goo.engine.core

/**
 * The radial falloff every brush kernel shares (PLAN.md §4.1).
 *
 * Maps a normalized distance from the brush center (0 = center, 1 = rim) to
 * a weight in [0, 1]. The curve is `smoothstep(1 → 0)`: C1-continuous at
 * both ends, so stamped displacement fields stay crease-free — a hard-edged
 * falloff would print visible rings into the warp.
 *
 * Pure JVM on purpose: this exact function is mirrored by the GLSL brush
 * shader, and these semantics are pinned by unit tests the shader is kept
 * trivially close to.
 */
object BrushFalloff {

    /** Weight at normalized distance [d] from the brush center. */
    fun weight(d: Float): Float {
        if (d <= 0f) return 1f
        if (d >= 1f) return 0f
        val t = 1f - d
        // smoothstep(0, 1, t) = t² · (3 − 2t)
        return t * t * (3f - 2f * t)
    }

    /**
     * Weight under a specific [FalloffProfile]. All profiles are 1 at the
     * center, 0 at (and beyond) the rim, and C1 throughout — including at
     * the plateau boundary, where smoothstep's zero end-slope meets the
     * flat top.
     */
    fun weight(d: Float, profile: FalloffProfile): Float = when (profile) {
        FalloffProfile.SMOOTHSTEP -> weight(d)
        FalloffProfile.FEATHER -> weight(d).let { it * it }
        FalloffProfile.PLATEAU ->
            if (d <= PLATEAU_EDGE) 1f
            else weight((d - PLATEAU_EDGE) / (1f - PLATEAU_EDGE))
    }

    /**
     * Radial-mode center ramp: 0 at the exact stamp center (where the
     * outward direction is undefined) rising smoothstep to 1 by
     * [BrushDynamics.CENTER_RAMP_END]. Multiplies INFLATE/DEFLATE weights.
     */
    fun centerRamp(d: Float): Float {
        val end = BrushDynamics.CENTER_RAMP_END
        if (d <= 0f) return 0f
        if (d >= end) return 1f
        val t = d / end
        return t * t * (3f - 2f * t)
    }

    /** Normalized distance where the PLATEAU profile starts falling. */
    const val PLATEAU_EDGE = 0.7f
}
