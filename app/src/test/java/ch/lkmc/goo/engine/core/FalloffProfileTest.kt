package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FalloffProfileTest {

    private val samples = (0..100).map { it / 100f }

    @Test
    fun `every profile is 1 at the center and 0 at the rim`() {
        FalloffProfile.entries.forEach { p ->
            assertEquals(1f, BrushFalloff.weight(0f, p), 1e-6f, "$p center")
            assertEquals(0f, BrushFalloff.weight(1f, p), 1e-6f, "$p rim")
            assertEquals(0f, BrushFalloff.weight(1.5f, p), 1e-6f, "$p beyond")
        }
    }

    @Test
    fun `every profile is monotonically non-increasing`() {
        FalloffProfile.entries.forEach { p ->
            samples.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    BrushFalloff.weight(b, p) <= BrushFalloff.weight(a, p) + 1e-6f,
                    "$p not monotone at $a -> $b",
                )
            }
        }
    }

    @Test
    fun `feather is softer than smoothstep everywhere inside`() {
        samples.filter { it > 0f && it < 1f }.forEach { d ->
            assertTrue(
                BrushFalloff.weight(d, FalloffProfile.FEATHER) <=
                    BrushFalloff.weight(d, FalloffProfile.SMOOTHSTEP) + 1e-6f,
            )
        }
    }

    @Test
    fun `plateau is flat out to its edge then falls`() {
        assertEquals(1f, BrushFalloff.weight(0.3f, FalloffProfile.PLATEAU), 1e-6f)
        assertEquals(1f, BrushFalloff.weight(BrushFalloff.PLATEAU_EDGE, FalloffProfile.PLATEAU), 1e-6f)
        assertTrue(BrushFalloff.weight(0.85f, FalloffProfile.PLATEAU) < 1f)
    }

    @Test
    fun `center ramp kills the singularity and saturates`() {
        assertEquals(0f, BrushFalloff.centerRamp(0f), 1e-6f)
        assertEquals(1f, BrushFalloff.centerRamp(BrushDynamics.CENTER_RAMP_END), 1e-6f)
        assertEquals(1f, BrushFalloff.centerRamp(0.5f), 1e-6f)
        assertTrue(BrushFalloff.centerRamp(BrushDynamics.CENTER_RAMP_END / 2) in 0.01f..0.99f)
    }
}
