package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushToolTest {

    @Test
    fun `shader ids are unique and stable`() {
        assertEquals(
            StampMode.entries.size,
            StampMode.entries.map { it.shaderId }.toSet().size,
        )
        assertEquals(
            FalloffProfile.entries.size,
            FalloffProfile.entries.map { it.shaderId }.toSet().size,
        )
        // Wire values are a contract with the GLSL uber-shader.
        assertEquals(0, StampMode.DIRECTIONAL.shaderId)
        assertEquals(1, StampMode.INFLATE.shaderId)
        assertEquals(2, StampMode.DEFLATE.shaderId)
        assertEquals(3, StampMode.RELAX.shaderId)
        assertEquals(4, StampMode.ERASE.shaderId)
    }

    @Test
    fun `pumped tools are exactly the hold-in-place field tools`() {
        // Drag tools (directional smears AND the Fusion through-paint)
        // stamp along the path; hold-tools pump on a clock.
        val pumpedModes = setOf(
            StampMode.INFLATE, StampMode.DEFLATE, StampMode.RELAX, StampMode.ERASE,
        )
        BrushTool.entries.forEach { tool ->
            assertEquals(
                tool.mode in pumpedModes,
                tool.pumped,
                "pumped mismatch for $tool",
            )
        }
    }

    @Test
    fun `strength scales stay in a sane range`() {
        BrushTool.entries.forEach { tool ->
            assertTrue(tool.strengthScale > 0f && tool.strengthScale <= 1f, "$tool")
        }
    }

    @Test
    fun `mirror reflects across the vertical center line`() {
        val stamp = Stamp(cx = 0.2f, cy = 0.7f, dx = 0.03f, dy = -0.01f)
        val mirrored = BrushTool.SMEAR.mirrorStamp(stamp)
        assertEquals(0.8f, mirrored.cx, 1e-6f)
        assertEquals(0.7f, mirrored.cy, 1e-6f)
        assertEquals(-0.03f, mirrored.dx, 1e-6f)
        assertEquals(-0.01f, mirrored.dy, 1e-6f)
    }

    @Test
    fun `mirroring twice is the identity for every tool`() {
        val stamp = Stamp(0.31f, 0.62f, 0.011f, -0.007f)
        BrushTool.entries.forEach { tool ->
            assertEquals(stamp, tool.mirrorStamp(tool.mirrorStamp(stamp)), "$tool")
        }
    }

    @Test
    fun `radial mirror twins keep their scalar delta unflipped`() {
        val stamp = Stamp(0.25f, 0.5f, 0f, 0f)
        val twin = BrushTool.GROW.mirrorStamp(stamp)
        assertEquals(0.75f, twin.cx, 1e-6f)
        assertEquals(0f, twin.dx)
        assertEquals(0f, twin.dy)
    }
}
