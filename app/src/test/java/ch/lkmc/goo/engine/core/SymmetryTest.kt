package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kaleidoscope dial (proposal 0005).
 *
 * The dial is pure geometry over stamps, so all of it is testable — and
 * the one failure mode that hides on a square test image (rotating in UV
 * space instead of aspect space) gets a test of its own.
 */
class SymmetryTest {

    private val smear = BrushTool.SMEAR
    private val grow = BrushTool.GROW

    // ---- Nothing changes when the dial is off --------------------------

    @Test
    fun `one sector is the identity, allocating no copies`() {
        val s = Stamp(0.3f, 0.7f, 0.01f, -0.02f)
        assertEquals(listOf(s), Symmetry.expand(smear, s, 1.5f, sectors = 1))
        assertEquals(listOf(s), Symmetry.expand(smear, s, 1.5f, sectors = 0))
    }

    @Test
    fun `one sector with mirror is exactly what Mirror always did`() {
        // The existing toggle must be bit-identical after this change:
        // the dial composes with Mirror, it does not redefine it.
        val s = Stamp(0.3f, 0.7f, 0.01f, -0.02f)
        val family = Symmetry.family(smear, s, 1.5f, sectors = 1, mirrored = true)
        assertEquals(listOf(s, smear.mirrorStamp(s)), family)
    }

    // ---- Counts --------------------------------------------------------

    @Test
    fun `the family size is sectors times the mirror factor`() {
        val s = Stamp(0.4f, 0.4f, 0.01f, 0f)
        for (n in Symmetry.SECTORS) {
            assertEquals(n, Symmetry.family(smear, s, 1f, n, mirrored = false).size)
            assertEquals(2 * n, Symmetry.family(smear, s, 1f, n, mirrored = true).size)
        }
    }

    // ---- The bug that hides on square images ---------------------------

    @Test
    fun `rotation happens in aspect space, so arms stay congruent`() {
        // Rotating the raw UV offset would make a landscape photo's
        // mandala elliptical: arms at 90° would sit at a different
        // PHYSICAL distance from the center than arms at 0°.
        val aspect = 16f / 9f
        val s = Stamp(cx = 0.5f + 0.2f / aspect, cy = 0.5f, dx = 0f, dy = 0f)
        val copies = Symmetry.expand(grow, s, aspect, sectors = 6)
        val radii = copies.map {
            val ax = (it.cx - 0.5f) * aspect
            val ay = it.cy - 0.5f
            sqrt(ax * ax + ay * ay)
        }
        val first = radii.first()
        for (r in radii) assertEquals(first, r, first * 1e-4f)
    }

    @Test
    fun `arms are evenly spaced around the center`() {
        val copies = Symmetry.expand(grow, Stamp(0.8f, 0.5f, 0f, 0f), 1f, sectors = 4)
        // Four arms from a point due right: right, down, left, up.
        val expected = listOf(0.8f to 0.5f, 0.5f to 0.8f, 0.2f to 0.5f, 0.5f to 0.2f)
        copies.forEachIndexed { i, c ->
            assertEquals("arm $i cx", expected[i].first, c.cx, 1e-5f)
            assertEquals("arm $i cy", expected[i].second, c.cy, 1e-5f)
        }
    }

    // ---- What happens to the delta -------------------------------------

    @Test
    fun `a directional delta rotates with its copy`() {
        // A smear pushing right, copied a quarter turn round, must push
        // down — otherwise every arm smears the same way and the pattern
        // is not symmetric at all.
        val copies = Symmetry.expand(smear, Stamp(0.8f, 0.5f, 0.02f, 0f), 1f, sectors = 4)
        assertEquals(0.02f, copies[0].dx, 1e-6f)
        assertEquals(0f, copies[0].dy, 1e-6f)
        assertEquals(0f, copies[1].dx, 1e-6f)
        assertEquals(0.02f, copies[1].dy, 1e-6f)
    }

    @Test
    fun `a radial tool's copies move only their center`() {
        val copies = Symmetry.expand(grow, Stamp(0.8f, 0.5f, 0f, 0f), 1f, sectors = 3)
        for (c in copies) {
            assertEquals(0f, c.dx, 0f)
            assertEquals(0f, c.dy, 0f)
        }
    }

    @Test
    fun `a chirality survives rotation unchanged`() {
        // VORTEX's dx is a handedness, not a direction. Rotation
        // preserves handedness, so every arm must spin the same way —
        // rotating the value would turn a sign into a fraction and the
        // arms would wind at different rates.
        val s = Stamp(0.8f, 0.5f, BrushTool.VORTEX.chirality, 0f)
        val copies = Symmetry.expand(BrushTool.VORTEX, s, 1.6f, sectors = 6)
        for (c in copies) assertEquals(BrushTool.VORTEX.chirality, c.dx, 0f)
    }

    @Test
    fun `mirrored copies still reverse a chirality`() {
        // Rotation preserves handedness; reflection reverses it. Both
        // have to remain true once they compose, or a kaleidoscope of
        // whirlpools all turns the same way.
        val s = Stamp(0.8f, 0.5f, BrushTool.VORTEX.chirality, 0f)
        val family = Symmetry.family(BrushTool.VORTEX, s, 1f, sectors = 3, mirrored = true)
        assertEquals(6, family.size)
        val rotated = family.filterIndexed { i, _ -> i % 2 == 0 }
        val reflected = family.filterIndexed { i, _ -> i % 2 == 1 }
        for (c in rotated) assertEquals(BrushTool.VORTEX.chirality, c.dx, 0f)
        for (c in reflected) assertEquals(-BrushTool.VORTEX.chirality, c.dx, 0f)
    }

    // ---- Order and bounds ----------------------------------------------

    @Test
    fun `the original is always the first copy applied`() {
        // Warp-of-warp composition is ordered, so a stroke must replay in
        // the order it was stamped; the leader going first is the part a
        // reader will assume.
        val s = Stamp(0.31f, 0.62f, 0.01f, 0.01f)
        assertEquals(s, Symmetry.expand(smear, s, 1.4f, 8).first())
        assertEquals(s, Symmetry.family(smear, s, 1.4f, 8, mirrored = true).first())
    }

    @Test
    fun `copies that leave the frame are kept, not dropped`() {
        // An off-canvas center still reaches in-canvas texels within its
        // radius; dropping it here would make strokes near the border
        // stop dead instead of blending. StampBounds decides what cannot
        // reach at all.
        //
        // It takes a CORNER to escape: rotation preserves radius, so a
        // point inside the frame's inscribed circle always maps back
        // inside a square frame however many sectors there are. Only a
        // stamp further out than that circle — and a sector count that
        // swings it onto an axis — leaves.
        val copies = Symmetry.expand(grow, Stamp(0.95f, 0.95f, 0f, 0f), 1f, sectors = 8)
        assertEquals(8, copies.size)
        assertTrue("expected a copy outside the frame", copies.any { it.cx !in 0f..1f })
    }

    @Test
    fun `the dial refuses counts past the cap`() {
        val s = Stamp(0.5f, 0.5f, 0f, 0f)
        assertThrows(IllegalArgumentException::class.java) {
            Symmetry.expand(smear, s, 1f, Symmetry.MAX_SECTORS + 1)
        }
    }

    @Test
    fun `every dial position is within the cap`() {
        assertTrue(Symmetry.SECTORS.all { it in 1..Symmetry.MAX_SECTORS })
        assertEquals(1, Symmetry.SECTORS.first())
        assertEquals(Symmetry.SECTORS, Symmetry.SECTORS.sorted())
    }

    @Test
    fun `copies stay finite for every tool and aspect`() {
        for (tool in BrushTool.entries) {
            for (aspect in listOf(1f, 16f / 9f, 9f / 16f)) {
                val family = Symmetry.family(
                    tool, Stamp(0.5f, 0.5f, tool.chirality, 0.01f), aspect, 12, mirrored = true,
                )
                assertTrue(
                    "$tool at $aspect produced a non-finite stamp",
                    family.all {
                        it.cx.isFinite() && it.cy.isFinite() &&
                            it.dx.isFinite() && it.dy.isFinite()
                    },
                )
            }
        }
    }

    @Test
    fun `a stamp on the center rotates to itself`() {
        // The one degenerate case: all arms coincide, which is where the
        // field piles up N-fold. It must not produce NaN.
        val copies = Symmetry.expand(grow, Stamp(0.5f, 0.5f, 0f, 0f), 1.7f, sectors = 12)
        for (c in copies) {
            assertEquals(0.5f, c.cx, 1e-6f)
            assertEquals(0.5f, c.cy, 1e-6f)
        }
        assertTrue(copies.all { abs(it.cx - 0.5f) < 1e-6f })
    }
}
