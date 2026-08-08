package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Funhouse lenses (proposal 0006) — placed, persistent warps.
 *
 * The shader is a transliteration of [GlobalField.displacement], so
 * everything provable about a lens is provable here, on the CPU, where
 * this project can actually run it.
 */
class LensTest {

    private val square = 1f
    private val wide = 16f / 9f

    private fun displacementAt(u: Float, v: Float, aspect: Float, lens: Lens) =
        GlobalField.displacement(u, v, aspect, GlobalParams(lenses = listOf(lens)))

    /** How far the sample point moves, in aspect space. */
    private fun magnitude(u: Float, v: Float, aspect: Float, lens: Lens): Float {
        val (du, dv) = displacementAt(u, v, aspect, lens)
        return hypot(du * aspect, dv)
    }

    // ---- The window ------------------------------------------------------

    @Test
    fun `a lens does nothing outside its own radius`() {
        val lens = Lens(u = 0.5f, v = 0.5f, radius = 0.2f, strength = 1f)
        for (type in LensType.entries) {
            val at = lens.copy(type = type)
            // Just outside the rim, and far away.
            assertEquals(0f, magnitude(0.5f, 0.5f + 0.2001f, square, at), 1e-7f)
            assertEquals(0f, magnitude(0.05f, 0.05f, square, at), 1e-7f)
        }
    }

    @Test
    fun `a lens reaches zero continuously at its rim`() {
        // A hard edge would print a visible disc outline into the photo —
        // the one artifact a piece of glass must not have.
        val lens = Lens(u = 0.5f, v = 0.5f, radius = 0.25f, strength = 1f)
        for (type in LensType.entries) {
            val at = lens.copy(type = type)
            val justInside = magnitude(0.5f, 0.5f + 0.2495f, square, at)
            assertTrue(
                justInside < 1e-3f,
                "$type still displaces $justInside a texel inside the rim",
            )
        }
    }

    @Test
    fun `a lens pulled to zero is identity, and costs no slot`() {
        val dead = Lens(u = 0.4f, v = 0.4f, strength = 0f)
        assertTrue(dead.isIdentity)
        assertTrue(GlobalParams(lenses = listOf(dead)).isIdentity)
        assertEquals(0, GlobalParams(lenses = listOf(dead)).activeLenses().size)
        assertEquals(0f, magnitude(0.4f, 0.4f, square, dead), 1e-7f)
    }

    @Test
    fun `the rack never hands the shader more lenses than it has slots`() {
        val many = (0 until Lens.CAPACITY + 3).map {
            Lens(u = 0.1f * it, v = 0.5f, strength = 0.5f)
        }
        assertEquals(Lens.CAPACITY, GlobalParams(lenses = many).activeLenses().size)
    }

    // ---- What each type actually does ------------------------------------

    @Test
    fun `bulge samples nearer its center and pinch samples further`() {
        // Backward mapping: a displacement pointing AT the center means
        // the fragment shows content from nearer the center, which is
        // magnification. Getting this backwards is the classic bug, and
        // it looks plausible — just inside out.
        val center = Pair(0.5f, 0.5f)
        val probe = Pair(0.5f, 0.6f) // below center, so the offset is +v

        val bulge = Lens(center.first, center.second, radius = 0.25f, type = LensType.BULGE, strength = 1f)
        val (_, bulgeDv) = displacementAt(probe.first, probe.second, square, bulge)
        assertTrue(bulgeDv < 0f, "bulge must sample toward the center, got $bulgeDv")

        val pinch = bulge.copy(type = LensType.PINCH)
        val (_, pinchDv) = displacementAt(probe.first, probe.second, square, pinch)
        assertTrue(pinchDv > 0f, "pinch must sample away from the center, got $pinchDv")

        // And they are exact opposites — one constant, two directions.
        assertEquals(-bulgeDv, pinchDv, 1e-6f)
    }

    @Test
    fun `negative strength runs a lens backwards`() {
        // Which is what lets a lens tween through zero instead of jumping
        // when a GOOvie segment carries it from a bulge to a pinch.
        val probe = Pair(0.5f, 0.58f)
        val bulge = Lens(0.5f, 0.5f, radius = 0.25f, type = LensType.BULGE, strength = 0.8f)
        val (_, forward) = displacementAt(probe.first, probe.second, square, bulge)
        val (_, backward) = displacementAt(probe.first, probe.second, square, bulge.copy(strength = -0.8f))
        assertEquals(-forward, backward, 1e-6f)
    }

    @Test
    fun `fisheye magnifies its core evenly where bulge is still ramping up`() {
        // The difference between the two types IS the flat core: a bulge
        // barely moves anything near its center, a fisheye is already at
        // full displacement there.
        val lens = Lens(0.5f, 0.5f, radius = 0.3f, type = LensType.FISHEYE, strength = 1f)
        // A fraction of the RADIUS, not of the default strength: the
        // probe is a position, and tying it to a strength constant would
        // move it the day that constant is retuned.
        val nearCore = 0.5f + lens.radius * 0.2f

        val fisheye = magnitude(0.5f, nearCore, square, lens)
        val bulge = magnitude(0.5f, nearCore, square, lens.copy(type = LensType.BULGE))
        assertTrue(
            fisheye > bulge * 1.5f,
            "fisheye ($fisheye) should be well past bulge ($bulge) inside the core",
        )
    }

    @Test
    fun `a vortex lens turns content without moving it in or out`() {
        // Rotation preserves radius. If it did not, the "swirl" would be
        // a swirl plus a bulge, and pulling the strength back would not
        // undo it cleanly.
        val lens = Lens(0.5f, 0.5f, radius = 0.3f, type = LensType.VORTEX, strength = 0.9f)
        val u = 0.5f
        val v = 0.5f + 0.15f
        val (du, dv) = displacementAt(u, v, square, lens)
        val before = hypot((u - 0.5f) * square, v - 0.5f)
        val after = hypot((u + du - 0.5f) * square, v + dv - 0.5f)
        assertEquals(before, after, 1e-4f)
        // And it did turn: a no-op would also preserve the radius.
        assertTrue(hypot(du, dv) > 1e-3f)
    }

    @Test
    fun `a vortex reverses with its strength`() {
        val lens = Lens(0.5f, 0.5f, radius = 0.3f, type = LensType.VORTEX, strength = 0.5f)
        val (du, _) = displacementAt(0.5f, 0.65f, square, lens)
        val (duBack, _) = displacementAt(0.5f, 0.65f, square, lens.copy(strength = -0.5f))
        assertTrue(du * duBack < 0f, "counter-rotation expected, got $du and $duBack")
    }

    // ---- Shape independence ----------------------------------------------

    @Test
    fun `a lens is round in pixels on any image shape`() {
        // Radii are aspect-space, like brush radii. On a 16:9 photo a lens
        // measured in raw UV would be an ellipse — visibly an oval piece
        // of glass, which is the kind of thing nobody notices in code and
        // everybody notices on screen.
        val lens = Lens(0.5f, 0.5f, radius = 0.2f, type = LensType.BULGE, strength = 1f)
        // Same aspect-space offset, once horizontal and once vertical.
        val offset = 0.1f
        val horizontal = magnitude(0.5f + offset / wide, 0.5f, wide, lens)
        val vertical = magnitude(0.5f, 0.5f + offset, wide, lens)
        assertEquals(horizontal, vertical, 1e-5f)
    }

    @Test
    fun `lens shape is scale-free, so only its reach grows with size`() {
        // Displacement scales with the lens radius. Normalized by that
        // radius the curve is IDENTICAL at every size — the two lenses
        // are the same piece of glass at two scales, not two different
        // strengths — while absolute reach grows, which is what makes a
        // small lens a tight bulge and a large one a slow swell.
        val small = Lens(0.5f, 0.5f, radius = 0.1f, type = LensType.BULGE, strength = 1f)
        val large = small.copy(radius = 0.3f)
        // Compare at the same FRACTION of each radius.
        val steepness = { lens: Lens ->
            magnitude(0.5f, 0.5f + lens.radius * 0.5f, square, lens) / lens.radius
        }
        assertEquals(steepness(small), steepness(large), 1e-5f)
        // Absolute reach still grows with size, which is the point.
        assertTrue(
            magnitude(0.5f, 0.5f + large.radius * 0.5f, square, large) >
                magnitude(0.5f, 0.5f + small.radius * 0.5f, square, small),
        )
    }

    // ---- Composition -----------------------------------------------------

    @Test
    fun `lenses add to the levers rather than replacing them`() {
        val levers = GlobalParams(bulge = 0.5f)
        val lens = Lens(0.25f, 0.25f, radius = 0.15f, type = LensType.BULGE, strength = 1f)
        val at = Pair(0.25f, 0.3f)

        val leversOnly = GlobalField.displacement(at.first, at.second, square, levers)
        val both = GlobalField.displacement(
            at.first,
            at.second,
            square,
            levers.copy(lenses = listOf(lens)),
        )
        val lensOnly = displacementAt(at.first, at.second, square, lens)

        assertEquals(leversOnly.first + lensOnly.first, both.first, 1e-6f)
        assertEquals(leversOnly.second + lensOnly.second, both.second, 1e-6f)
    }

    @Test
    fun `two lenses that do not overlap do not disturb each other`() {
        val left = Lens(0.2f, 0.5f, radius = 0.12f, type = LensType.BULGE, strength = 1f)
        val right = Lens(0.8f, 0.5f, radius = 0.12f, type = LensType.PINCH, strength = 1f)
        val alone = displacementAt(0.2f, 0.55f, square, left)
        val together = GlobalField.displacement(
            0.2f,
            0.55f,
            square,
            GlobalParams(lenses = listOf(left, right)),
        )
        assertEquals(alone.first, together.first, 1e-7f)
        assertEquals(alone.second, together.second, 1e-7f)
    }

    // ---- Tweening --------------------------------------------------------

    @Test
    fun `a lens travels between two keyframes`() {
        // The animation payoff of the whole proposal: a bulge that walks
        // across a face over a segment, which no brush can record because
        // strokes are instant and lenses are positions.
        val from = GlobalParams(lenses = listOf(Lens(0.2f, 0.3f, radius = 0.1f, strength = 0.4f)))
        val to = GlobalParams(lenses = listOf(Lens(0.8f, 0.7f, radius = 0.3f, strength = 1f)))

        val half = from.lerp(to, 0.5f).lenses.single()
        assertEquals(0.5f, half.u, 1e-6f)
        assertEquals(0.5f, half.v, 1e-6f)
        assertEquals(0.2f, half.radius, 1e-6f)
        assertEquals(0.7f, half.strength, 1e-6f)

        assertEquals(from.lenses, from.lerp(to, 0f).lenses)
        assertEquals(to.lenses, from.lerp(to, 1f).lenses)
    }

    @Test
    fun `a lens added between pins dissolves in rather than popping`() {
        val none = GlobalParams()
        val one = GlobalParams(lenses = listOf(Lens(0.5f, 0.5f, strength = 1f)))

        assertEquals(0f, none.lerp(one, 0f).lenses.single().strength, 1e-6f)
        assertEquals(0.5f, none.lerp(one, 0.5f).lenses.single().strength, 1e-6f)
        assertEquals(1f, none.lerp(one, 1f).lenses.single().strength, 1e-6f)

        // And out again the other way.
        assertEquals(1f, one.lerp(none, 0f).lenses.single().strength, 1e-6f)
        assertEquals(0f, one.lerp(none, 1f).lenses.single().strength, 1e-6f)
    }

    @Test
    fun `a lens changing type switches at the midpoint, at its weakest`() {
        // There is no half-way between a pinch and a swirl, so the type
        // has to jump. It jumps where the jump is least visible.
        val from = GlobalParams(lenses = listOf(Lens(0.5f, 0.5f, type = LensType.BULGE, strength = 1f)))
        val to = GlobalParams(lenses = listOf(Lens(0.5f, 0.5f, type = LensType.VORTEX, strength = -1f)))

        assertEquals(LensType.BULGE, from.lerp(to, 0.49f).lenses.single().type)
        assertEquals(LensType.VORTEX, from.lerp(to, 0.51f).lenses.single().type)
        // At the switch the lens is passing through zero strength, so the
        // discontinuity displaces nothing.
        assertTrue(abs(from.lerp(to, 0.5f).lenses.single().strength) < 1e-6f)
    }

    // ---- Persistence -----------------------------------------------------

    @Test
    fun `the saved-state pack round-trips`() {
        val lenses = listOf(
            Lens(0.1f, 0.2f, radius = 0.3f, type = LensType.FISHEYE, strength = -0.4f),
            Lens(0.9f, 0.8f, radius = 0.07f, type = LensType.VORTEX, strength = 1f),
        )
        assertEquals(lenses, Lens.unpack(Lens.pack(lenses)))
        assertEquals(emptyList(), Lens.unpack(Lens.pack(emptyList())))
    }

    @Test
    fun `a malformed pack is refused rather than half-read`() {
        assertNull(Lens.unpack(FloatArray(Lens.PACK_STRIDE + 1)))
        // A type id no LensType claims — a file from a future version, or
        // a corrupt one. Either way, guessing would place a lens the user
        // never made.
        val bad = Lens.pack(listOf(Lens(0.5f, 0.5f))).also { it[4] = 99f }
        assertNull(Lens.unpack(bad))
        // More lenses than there are slots.
        assertNull(Lens.unpack(FloatArray((Lens.CAPACITY + 1) * Lens.PACK_STRIDE)))
    }

    @Test
    fun `sanitizing clamps a lens into the ranges the shader assumes`() {
        val wild = Lens(u = -3f, v = 9f, radius = 40f, strength = -7f).sanitized()
        assertEquals(0f, wild.u)
        assertEquals(1f, wild.v)
        assertEquals(Lens.MAX_RADIUS, wild.radius)
        assertEquals(-1f, wild.strength)
    }

    @Test
    fun `a non-finite lens is replaced, not clamped`() {
        // coerceIn passes NaN through unchanged, so a clamp reads as a
        // guard without being one. A NaN here would reach the uniform
        // upload and the shader, where it paints a broken frame with
        // nothing to diagnose it by.
        val broken = Lens(
            u = Float.NaN,
            v = Float.POSITIVE_INFINITY,
            radius = Float.NaN,
            strength = Float.NEGATIVE_INFINITY,
        ).sanitized()
        assertTrue(broken.u.isFinite() && broken.v.isFinite())
        assertTrue(broken.radius.isFinite() && broken.strength.isFinite())
        assertEquals(Lens.DEFAULT_RADIUS, broken.radius)
        // Inert, not merely legal: an identity lens never reaches the GPU.
        assertEquals(0f, broken.strength)
        assertTrue(broken.isIdentity)
        // And a pack carrying one comes back sane rather than poisoned.
        val fromDisk = Lens.unpack(Lens.pack(listOf(Lens(u = Float.NaN, v = 0.5f))))
        assertTrue(fromDisk!!.single().u.isFinite())
    }

    @Test
    fun `lenses count toward whether there is anything to reset`() {
        // Reset is the only way to clear the rack, so a photo warped by a
        // lens alone must still offer "back to the photo".
        val onlyALens = GlobalParams(lenses = listOf(Lens(0.5f, 0.5f, strength = 0.5f)))
        assertNotEquals(true, onlyALens.isIdentity)
        assertTrue(GlobalParams().isIdentity)
    }
}
