package ch.lkmc.goo.engine.gl

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
        assertContains(shader, "clamp(cur.z + w * 0.30")
        assertContains(shader, "mix(cur, blur, w * 0.22)")
        assertContains(shader, "cur * (1.0 - w * 0.22)")
        assertContains(shader, "float ramp = w * centerRamp(d)")
        assertContains(shader, "ramp * 0.004")
        assertContains(shader, "d <= 0.7 ? 1.0")
        assertContains(shader, "d / 0.08")
    }

    @Test
    fun `stamp shader pins the second-wave kernels and their constants`() {
        val shader = GlShaders.STAMP_FRAG

        // Each new mode's wire id must appear as its own branch, or a
        // stamp would silently fall through to the radial default.
        assertContains(shader, "u_mode == 6")   // VORTEX
        assertContains(shader, "u_mode == 7")   // COMB
        assertContains(shader, "u_mode == 8")   // RIPPLE
        assertContains(shader, "u_mode == 9")   // FAULT

        // Literals mirrored from BrushDynamics — the documented-duplication
        // convention SOL-41 pinned for the hash and the global scales.
        assertContains(shader, "ramp * 0.0035")             // SWIRL_STEP_UV
        assertContains(shader, "cos(6.2831853 * s * 3.0)")  // TAU, COMB_TEETH
        assertContains(shader, "ramp * 0.010 * sin(6.2831853 * 3.0 * d)")
        assertContains(shader, "side * w * 0.010")          // FAULT_STEP_UV
        assertContains(shader, "metric.y *= 0.45")          // DRIP_LOBE

        // The anisotropic profile must reshape the WEIGHT's distance only;
        // the radial direction stays on the true offset.
        assertContains(shader, "float distR = length(fromCenter)")
        assertContains(shader, "u_profile == 3")
    }

    @Test
    fun `warp shader pins deterministic hash and global scales`() {
        val shader = GlShaders.WARP_FRAG

        assertContains(shader, "x * 1664525u + y * 1013904223u + seed * 2654435761u")
        assertContains(shader, "h *= 2246822519u")
        assertContains(shader, "float theta = u_g[1] * 2.5 * shape")
        assertContains(shader, "u_g[0] * 0.35")
        assertContains(shader, "u_g[2] * 0.30")
        assertContains(shader, "u_g[4] * 0.08")
        assertContains(shader, "sin(8.0 * phi)")
        assertContains(shader, "uv.x * 24.0")
        assertContains(shader, "u_g[5] * 0.05")
    }
}
