package ch.lkmc.goo.engine.gl

import ch.lkmc.goo.engine.core.BrushDynamics
import ch.lkmc.goo.engine.core.BrushFalloff
import ch.lkmc.goo.engine.core.FalloffProfile
import ch.lkmc.goo.engine.core.GlobalField
import ch.lkmc.goo.engine.core.Lens
import ch.lkmc.goo.engine.core.LensType
import ch.lkmc.goo.engine.core.StampMode
import ch.lkmc.goo.engine.core.TAU
import kotlin.test.Test
import kotlin.test.assertContains

class GlShaderContractTest {

    @Test
    fun `stamp shader pins mode branches and brush dynamics`() {
        val shader = GlShaders.STAMP_FRAG

        assertContains(shader, "if (u_mode == 5)")
        assertContains(shader, "else if (u_mode == 3)")
        assertContains(shader, "else if (u_mode == 4)")
        assertContains(shader, "if (u_mode == 0)")
        assertContains(shader, "(u_mode == 1 ? -1.0 : 1.0)")
        assertContains(shader, "clamp(cur.z + w * 0.3")
        assertContains(shader, "mix(cur.xyz, blur, w * 0.22)")
        assertContains(shader, "cur * (1.0 - w * 0.22)")
        assertContains(shader, "float ramp = w * centerRamp(d)")
        assertContains(shader, "ramp * 0.004")
        assertContains(shader, "d <= 0.7 ? 1.0")
        assertContains(shader, "d / 0.08")
    }

    @Test
    fun `stamp shader pins the second-wave kernel branches`() {
        val shader = GlShaders.STAMP_FRAG

        // Each mode's wire id must appear as its own branch, or a stamp
        // would silently fall through to the radial default. Ids come
        // from the enum so a reorder cannot quietly re-map behaviour.
        assertContains(shader, "u_mode == ${StampMode.VORTEX.shaderId}")
        assertContains(shader, "u_mode == ${StampMode.COMB.shaderId}")
        assertContains(shader, "u_mode == ${StampMode.RIPPLE.shaderId}")
        assertContains(shader, "u_mode == ${StampMode.FAULT.shaderId}")
        assertContains(shader, "u_profile == ${FalloffProfile.DRIP.shaderId}")
        assertContains(shader, "u_mode == ${StampMode.GUARD.shaderId}")

        // The anisotropic profile must reshape the WEIGHT's distance only;
        // the radial direction stays on the true offset.
        assertContains(shader, "float distR = length(fromCenter)")
    }

    /**
     * The duplication between `BrushDynamics` and the GLSL literals is
     * deliberate — the shader cannot read Kotlin — but until now it was
     * only a documented convention, guarded by comments and by assertions
     * that hard-coded the same numbers twice. That is not a guard: a
     * retune could satisfy both and still desync the CPU reference from
     * the GPU, which shows up as preview and export disagreeing and then
     * gets baked into the document by replay.
     *
     * These assertions build the expected substring FROM the constant, so
     * changing one in Kotlin fails the build until the shader follows.
     * DRIP_LOBE matters most: it is a three-way dependency, since
     * `StampBounds` sizes the melt scissor from it too.
     */
    @Test
    fun `shader literals are derived from the Kotlin constants they mirror`() {
        val stamp = GlShaders.STAMP_FRAG

        assertContains(stamp, "metric.y *= ${BrushDynamics.DRIP_LOBE}")
        assertContains(stamp, "ramp * ${BrushDynamics.SWIRL_STEP_UV}")
        assertContains(stamp, "side * w * ${BrushDynamics.FAULT_STEP_UV}")
        assertContains(
            stamp,
            "ramp * ${BrushDynamics.RIPPLE_STEP_UV} * " +
                "sin($TAU * ${BrushDynamics.RIPPLE_BANDS} * d)",
        )
        assertContains(stamp, "cos($TAU * s * ${BrushDynamics.COMB_TEETH})")
        assertContains(stamp, "ramp * ${BrushDynamics.RADIAL_STEP_UV}")
        assertContains(stamp, "w * ${BrushDynamics.BLEND_STEP}")
        assertContains(stamp, "w * ${BrushDynamics.FUSE_STEP}")
        assertContains(stamp, "w * ${BrushDynamics.FREEZE_STEP}")
        assertContains(stamp, "d / ${BrushDynamics.CENTER_RAMP_END}")
        assertContains(stamp, "d <= ${BrushFalloff.PLATEAU_EDGE}")

        val warp = GlShaders.WARP_FRAG
        assertContains(warp, "u_g[1] * ${GlobalField.TWIRL_MAX_RAD}")
        assertContains(warp, "u_g[0] * ${GlobalField.BULGE_SCALE}")
        assertContains(warp, "u_g[2] * ${GlobalField.AXIS_SCALE}")
        assertContains(warp, "u_g[4] * ${GlobalField.SPIKE_SCALE}")
        assertContains(warp, "u_g[5] * ${GlobalField.STATIC_SCALE}")
        assertContains(warp, "sin(${GlobalField.SPIKE_COUNT}.0 * phi)")
        assertContains(warp, "uv.x * ${GlobalField.STATIC_CELLS}.0")

        // Lenses (proposal 0006). The type comparisons are pinned too:
        // a shaderId is a wire value, so a reordered enum that silently
        // re-mapped BULGE onto PINCH would be a saved project loading
        // back inside out — the exact failure the explicit ids prevent.
        assertContains(warp, "lens.w * ${GlobalField.LENS_SCALE} * lens.z")
        assertContains(warp, "lens.w * ${GlobalField.LENS_TWIRL_RAD} * window")
        assertContains(warp, "lens.z * ${GlobalField.LENS_CORE}")
        assertContains(warp, "if (type == ${LensType.VORTEX.shaderId})")
        assertContains(warp, "type == ${LensType.FISHEYE.shaderId}")
        assertContains(warp, "type == ${LensType.PINCH.shaderId} ? 1.0 : -1.0")
        assertContains(warp, "i >= u_lensCount")
        assertContains(warp, "for (int i = 0; i < ${Lens.CAPACITY}; i++)")
        // Freeze (proposal 0002). Three separate claims, and all three
        // are the kind that fail silently on screen rather than loudly:
        // the varnish must brake the stamp pass, it must brake the
        // ANALYTIC warps too (or a lever thaws everything the moment it
        // is pulled), and the frost sheen must be gated on a uniform no
        // export path sets.
        assertContains(stamp, "u_strength * guard")
        assertContains(warp, "globalDisp(v_uv) * thawed")
        assertContains(warp, "u_showFreeze")
    }

    @Test
    fun `warp shader pins deterministic hash and global scales`() {
        val shader = GlShaders.WARP_FRAG

        assertContains(shader, "x * 1664525u + y * 1013904223u + seed * 2654435761u")
        assertContains(shader, "h *= 2246822519u")
        assertContains(shader, "float theta = u_g[1] * 2.5 * shape")
        assertContains(shader, "u_g[0] * 0.35")
        assertContains(shader, "u_g[2] * 0.3")
        assertContains(shader, "u_g[4] * 0.08")
        assertContains(shader, "sin(8.0 * phi)")
        assertContains(shader, "uv.x * 24.0")
        assertContains(shader, "u_g[5] * 0.05")
    }
}
