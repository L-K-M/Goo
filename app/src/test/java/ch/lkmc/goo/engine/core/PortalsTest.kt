package ch.lkmc.goo.engine.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goo Portals (proposal 0012).
 *
 * Like [Symmetry], the whole feature is pure geometry over stamps, so all
 * of it is testable on the JVM — and the two failure modes that hide on a
 * square test image (measuring ring distance in raw UV, and the same for
 * the minimum separation) each get a test that only fails on a
 * non-square photo.
 */
class PortalsTest {

    /** Landscape, so aspect actually does something. */
    private val wide = 16f / 9f
    private val pair = PortalPair(au = 0.2f, av = 0.5f, bu = 0.7f, bv = 0.3f)
    private val radius = 0.1f

    // ---- Which ring did the stroke start in? ---------------------------

    @Test
    fun `a stroke in A copies toward B and one in B copies toward A`() {
        val fromA = Portals.shiftAt(pair.au, pair.av, radius, wide, pair)!!
        val fromB = Portals.shiftAt(pair.bu, pair.bv, radius, wide, pair)!!
        assertEquals(pair.bu - pair.au, fromA.first, 1e-6f)
        assertEquals(pair.bv - pair.av, fromA.second, 1e-6f)
        // Exactly the inverse translation, which is the whole claim that
        // the relation is symmetric rather than one-directional.
        assertEquals(-fromA.first, fromB.first, 1e-6f)
        assertEquals(-fromA.second, fromB.second, 1e-6f)
    }

    @Test
    fun `swapping the labels inverts the translation for a given label`() {
        // The acceptance sketch says "reversing A and B produces the
        // inverse translation", which is true read label-wise: whatever
        // is now called A copies the other way than it did.
        val swapped = PortalPair(pair.bu, pair.bv, pair.au, pair.av)
        val fromA = Portals.shiftAt(pair.au, pair.av, radius, wide, pair)!!
        val fromSwappedA = Portals.shiftAt(swapped.au, swapped.av, radius, wide, swapped)!!
        assertEquals(-fromA.first, fromSwappedA.first, 1e-6f)
        assertEquals(-fromA.second, fromSwappedA.second, 1e-6f)
    }

    @Test
    fun `swapping the labels changes nothing about a given place`() {
        // And this is the stronger property the user actually
        // experiences, which the label-wise reading can be mistaken for
        // contradicting: which ring you happened to plant FIRST is not
        // part of the geometry. A stroke started at one physical spot
        // lands its twin at the same physical spot either way.
        val swapped = PortalPair(pair.bu, pair.bv, pair.au, pair.av)
        val here = Portals.shiftAt(pair.au, pair.av, radius, wide, pair)!!
        val stillHere = Portals.shiftAt(pair.au, pair.av, radius, wide, swapped)!!
        assertEquals(here.first, stillHere.first, 1e-6f)
        assertEquals(here.second, stillHere.second, 1e-6f)
    }

    @Test
    fun `a stroke outside both rings is not copied`() {
        // The selectivity claim: the pair can sit on the photo while the
        // rest of it is gooed normally.
        assertNull(Portals.shiftAt(0.95f, 0.95f, radius, wide, pair))
    }

    @Test
    fun `the ring is the brush disc, so a bigger brush catches more`() {
        // Just outside a 0.1 ring, comfortably inside a 0.3 one.
        val u = pair.au + 0.15f / wide
        assertNull(Portals.shiftAt(u, pair.av, 0.1f, wide, pair))
        assertTrue(Portals.shiftAt(u, pair.av, 0.3f, wide, pair) != null)
    }

    @Test
    fun `the ring is round in pixels, not in UV`() {
        // THE aspect test. On a 16:9 photo, a horizontal UV offset covers
        // 16/9 as much image as the same vertical one, so a UV-space
        // distance check would accept this point (0.15 < 0.16) while the
        // aspect-space truth — 0.15 * 16/9 = 0.267 — puts it well outside
        // a 0.16 ring. A square test image cannot tell the two apart.
        val horizontal = Portals.shiftAt(pair.au + 0.15f, pair.av, 0.16f, wide, pair)
        val vertical = Portals.shiftAt(pair.au, pair.av + 0.15f, 0.16f, wide, pair)
        assertNull("a horizontal 0.15 UV step is 0.267 away on a wide photo", horizontal)
        assertTrue("a vertical 0.15 UV step is 0.15 away on any photo", vertical != null)
    }

    @Test
    fun `overlapping rings resolve to the nearer center`() {
        val close = PortalPair(au = 0.40f, av = 0.5f, bu = 0.45f, bv = 0.5f)
        val nearA = Portals.shiftAt(0.405f, 0.5f, radius = 0.5f, aspect = 1f, pair = close)!!
        val nearB = Portals.shiftAt(0.445f, 0.5f, radius = 0.5f, aspect = 1f, pair = close)!!
        assertTrue("nearer A copies toward B", nearA.first > 0f)
        assertTrue("nearer B copies toward A", nearB.first < 0f)
    }

    // ---- The twin ------------------------------------------------------

    @Test
    fun `the twin is the leader translated, with its delta untouched`() {
        val s = Stamp(cx = 0.3f, cy = 0.6f, dx = 0.02f, dy = -0.01f)
        val t = Portals.twin(s, 0.25f, -0.1f)
        assertEquals(0.55f, t.cx, 1e-6f)
        assertEquals(0.5f, t.cy, 1e-6f)
        // Translation preserves vectors and handedness, so unlike a
        // mirror or a rotation this needs no per-mode delta rule.
        assertEquals(s.dx, t.dx, 0f)
        assertEquals(s.dy, t.dy, 0f)
    }

    @Test
    fun `every mode keeps its delta through a portal`() {
        // mirrorsDelta and rotatesDelta both exist because reflection
        // reverses direction and rotation turns it. Translation does
        // neither, and this is the test that says so for the whole
        // palette rather than for one brush.
        val s = Stamp(cx = 0.3f, cy = 0.6f, dx = 0.02f, dy = -0.01f)
        for (tool in BrushTool.entries) {
            val t = Portals.twin(s, 0.1f, 0.1f)
            assertEquals("${tool.mode} dx", s.dx, t.dx, 0f)
            assertEquals("${tool.mode} dy", s.dy, t.dy, 0f)
        }
    }

    @Test
    fun `smear deltas stay parallel between leader and twin`() {
        val s = Stamp(cx = 0.3f, cy = 0.6f, dx = 0.03f, dy = 0.04f)
        val (leader, twin) = Portals.expand(s, Pair(0.4f, -0.2f))
        // Parallel means the cross product vanishes; equal deltas is the
        // stronger property, but the acceptance sketch asks for parallel,
        // so check the thing that was promised.
        assertEquals(0f, leader.dx * twin.dy - leader.dy * twin.dx, 1e-9f)
    }

    // ---- Expansion -----------------------------------------------------

    @Test
    fun `no shift means no copy and no allocation of one`() {
        val s = Stamp(0.3f, 0.7f, 0.01f, -0.02f)
        assertEquals(listOf(s), Portals.expand(s, null))
    }

    @Test
    fun `expansion is leader first, twin second`() {
        val s = Stamp(0.3f, 0.7f, 0.01f, -0.02f)
        val out = Portals.expand(s, Pair(0.2f, 0.1f))
        assertEquals(2, out.size)
        // Warp-of-warp composition is ordered, so this is not cosmetic:
        // swapping these two replays a different picture.
        assertEquals(s, out[0])
        assertEquals(Portals.twin(s, 0.2f, 0.1f), out[1])
    }

    @Test
    fun `a twin whose center leaves the frame is kept`() {
        // Symmetry keeps off-canvas rotations for the same reason: an
        // off-canvas center still moves in-canvas texels inside its
        // radius, and dropping it would make a linked stroke stop dead
        // at the border instead of running off it. StampBounds is the
        // one place that call belongs.
        val s = Stamp(0.9f, 0.5f, 0f, 0f)
        val out = Portals.expand(s, Pair(0.4f, 0f))
        assertEquals(2, out.size)
        assertTrue("the twin is off-canvas", out[1].cx > 1f)
    }

    @Test
    fun `a twin is never itself twinned`() {
        // Recursion is forbidden by construction: expand takes a stamp
        // and returns at most two, and nothing feeds its output back in.
        // The count is the proof, so pin it.
        val s = Stamp(0.5f, 0.5f, 0f, 0f)
        assertEquals(2, Portals.expand(s, Pair(0.1f, 0.1f)).size)
    }

    // ---- Placement -----------------------------------------------------

    @Test
    fun `coincident rings are refused`() {
        assertFalse(Portals.separated(0.5f, 0.5f, 0.5f, 0.5f, radius, wide))
    }

    @Test
    fun `the separation floor is one brush radius in aspect space`() {
        // Margins on both sides rather than the exact boundary. `0.4f +
        // 0.1f` is 0.099999994 away from `0.4f`, so an at-the-floor
        // assertion here does not test the floor — it tests which way
        // float rounding fell, and it fails while the code is correct.
        assertTrue(Portals.separated(0.5f, 0.4f, 0.5f, 0.4f + radius * 1.1f, radius, wide))
        assertFalse(Portals.separated(0.5f, 0.4f, 0.5f, 0.4f + radius * 0.9f, radius, wide))
    }

    @Test
    fun `the separation floor is round in pixels too`() {
        // The same UV gap, horizontal vs vertical, on a wide photo: the
        // horizontal one covers 16/9 as much image and clears a floor the
        // vertical one does not. Measured in raw UV these would agree,
        // and the bug would be invisible on a square photo.
        val gap = 0.07f
        assertTrue(Portals.separated(0.4f, 0.5f, 0.4f + gap, 0.5f, 0.1f, wide))
        assertFalse(Portals.separated(0.4f, 0.5f, 0.4f, 0.5f + gap, 0.1f, wide))
    }

    @Test
    fun `a bigger brush demands a bigger gap`() {
        assertTrue(Portals.separated(0.2f, 0.5f, 0.4f, 0.5f, radius = 0.1f, aspect = 1f))
        assertFalse(Portals.separated(0.2f, 0.5f, 0.4f, 0.5f, radius = 0.3f, aspect = 1f))
    }

    // ---- Composition with the other modifiers --------------------------

    @Test
    fun `portals inside symmetry is not the same as symmetry inside portals`() {
        // Rotation and translation do not commute, so the order in
        // EditorViewModel.emit is a real decision. If this ever passes,
        // the two orders have become interchangeable and the comment
        // explaining the choice has silently become false.
        val s = Stamp(0.3f, 0.7f, 0.02f, 0f)
        val shift = Pair(0.3f, 0.1f)
        val portalsInner = Portals.expand(s, shift)
            .flatMap { Symmetry.family(BrushTool.SMEAR, it, wide, 4, mirrored = false) }
        val portalsOuter = Symmetry.family(BrushTool.SMEAR, s, wide, 4, mirrored = false)
            .flatMap { Portals.expand(it, shift) }
        assertNotEquals(portalsInner, portalsOuter)
    }

    @Test
    fun `portals with mirror produces four heads in a fixed order`() {
        val s = Stamp(0.3f, 0.7f, 0.02f, 0.01f)
        val shift = Pair(0.3f, 0.1f)
        val tool = BrushTool.SMEAR
        val heads = Portals.expand(s, shift)
            .flatMap { Symmetry.family(tool, it, wide, sectors = 1, mirrored = true) }
        assertEquals(4, heads.size)
        val twin = Portals.twin(s, shift.first, shift.second)
        assertEquals(listOf(s, tool.mirrorStamp(s), twin, tool.mirrorStamp(twin)), heads)
    }

    @Test
    fun `the pair alone leaves every existing stroke bit-identical`() {
        // Portals off must be the same program it was before the feature
        // existed — the same guarantee `sectors = 1` carries.
        val s = Stamp(0.3f, 0.7f, 0.01f, -0.02f)
        assertEquals(
            Symmetry.family(BrushTool.SMEAR, s, wide, 3, mirrored = true),
            Portals.expand(s, null)
                .flatMap { Symmetry.family(BrushTool.SMEAR, it, wide, 3, mirrored = true) },
        )
    }

    // ---- Invariance ----------------------------------------------------

    @Test
    fun `the relation is invariant under image aspect ratio`() {
        // The translation itself is aspect-free: converting to aspect
        // space and back is the identity for a pure shift. This pins that
        // — if someone later "fixes" twin() to divide by aspect, the
        // linked stroke would land somewhere else on every non-square
        // photo and nothing else in the suite would notice.
        val inside = Pair(pair.au, pair.av)
        val shifts = listOf(1f, wide, 9f / 16f).map {
            Portals.shiftAt(inside.first, inside.second, radius, it, pair)!!
        }
        for (sh in shifts) {
            assertTrue(abs(sh.first - shifts[0].first) < 1e-6f)
            assertTrue(abs(sh.second - shifts[0].second) < 1e-6f)
        }
    }
}
