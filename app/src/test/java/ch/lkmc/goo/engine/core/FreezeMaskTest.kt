package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The varnish (proposal 0002) — the palette's only brake.
 *
 * A protect mask is a multiplier on stamp weight, which is what makes
 * one new mode aim every brush in the app, present and future. These
 * tests pin the multiplier and the three decisions around it that would
 * each produce something plausible-but-wrong on screen: which modes are
 * exempt, whether the mask travels with content, and whether the levers
 * respect it.
 */
class FreezeMaskTest {

    private fun field() = DisplacementField(64, 64, aspect = 1f)

    private fun stroke(tool: BrushTool, strength: Float = 1f, radius: Float = 0.3f) = Stroke(
        tool = tool,
        radius = radius,
        strength = strength,
        stamps = emptyList(),
    )

    private val center = Stamp(cx = 0.5f, cy = 0.5f, dx = 0.02f, dy = 0f)

    /**
     * A target for Rewind: a field displaced everywhere, so a Rewind
     * that ignored the varnish would visibly drag the frozen texel
     * toward it. Passing a target that happened to match the live field
     * would make the brake untestable for this tool — the stamp would
     * be a no-op whether or not the brake worked.
     */
    private fun rewindTarget() = field().also { f ->
        f.applyStamp(stroke(BrushTool.MOVE, radius = 5f), Stamp(0.5f, 0.5f, 0.4f, 0.4f))
    }

    private fun DisplacementField.stampAny(tool: BrushTool, stamp: Stamp = center) {
        applyStamp(
            stroke(tool),
            stamp,
            target = if (tool.needsTarget) rewindTarget() else null,
        )
    }

    /** Varnish the middle solid. */
    private fun DisplacementField.freezeCenter(times: Int = 20) {
        repeat(times) { applyStamp(stroke(BrushTool.FREEZE), center) }
    }

    // ---- The mask itself -------------------------------------------------

    @Test
    fun `freeze accumulates varnish under the brush and clamps at one`() {
        val f = field()
        f.applyStamp(stroke(BrushTool.FREEZE), center)
        val after1 = f.sampleFreeze(0.5f, 0.5f)
        assertTrue(after1 > 0.1f, "one stamp lays visible varnish, got $after1")
        assertTrue(after1 < 1f, "one stamp must not saturate — feathering needs the ramp")

        f.freezeCenter()
        assertEquals(1f, f.sampleFreeze(0.5f, 0.5f), 1e-4f)
        // Soft edge: outside the radius stays bare.
        assertEquals(0f, f.sampleFreeze(0.05f, 0.05f))
    }

    @Test
    fun `freeze leaves displacement and the fusion mask alone`() {
        val f = field()
        f.applyStamp(stroke(BrushTool.SMEAR), center)
        f.applyStamp(stroke(BrushTool.FUSE), center)
        val dx = f.sampleX(0.5f, 0.5f)
        val dy = f.sampleY(0.5f, 0.5f)
        val fusion = f.sampleMask(0.5f, 0.5f)

        f.applyStamp(stroke(BrushTool.FREEZE), center)

        assertEquals(dx, f.sampleX(0.5f, 0.5f))
        assertEquals(dy, f.sampleY(0.5f, 0.5f))
        assertEquals(fusion, f.sampleMask(0.5f, 0.5f))
    }

    // ---- What the brake actually brakes ----------------------------------

    @Test
    fun `a frozen region does not move under any warp brush`() {
        // The whole promise: get the left eye right, then stretch the
        // rest of the face without ruining it.
        for (tool in BrushTool.entries) {
            if (!tool.mode.respectsFreeze || tool.mode == StampMode.FUSE) continue
            // Pins is not a stamp and has no weight for the varnish to
            // scale. It is deliberately NOT braked by the mask — see
            // DisplacementField.applyPinWarp — so it is excluded here
            // rather than silently expected to pass.
            if (tool.isPinWarp) continue
            val f = field()
            f.freezeCenter()
            f.stampAny(tool)
            val moved = abs(f.sampleX(0.5f, 0.5f)) + abs(f.sampleY(0.5f, 0.5f))
            assertTrue(moved < 1e-4f, "$tool moved a frozen texel by $moved")
        }
    }

    @Test
    fun `an unfrozen region is untouched by the varnish existing`() {
        // The brake must not be a global damper: paint varnish in one
        // corner, and a smear in the other has to behave exactly as it
        // did before there was any mask at all.
        val bare = field()
        val varnished = field()
        varnished.applyStamp(
            stroke(BrushTool.FREEZE, radius = 0.15f),
            Stamp(0.15f, 0.15f, 0f, 0f),
        )
        val far = Stamp(cx = 0.8f, cy = 0.8f, dx = 0.02f, dy = 0.01f)
        bare.applyStamp(stroke(BrushTool.SMEAR), far)
        varnished.applyStamp(stroke(BrushTool.SMEAR), far)

        assertEquals(bare.sampleX(0.8f, 0.8f), varnished.sampleX(0.8f, 0.8f), 1e-6f)
        assertEquals(bare.sampleY(0.8f, 0.8f), varnished.sampleY(0.8f, 0.8f), 1e-6f)
    }

    @Test
    fun `half-set varnish drags half as far`() {
        // Partial varnish IS the feathering mechanism — an edge that
        // blends rather than snapping. It is also why a hard edge under a
        // mask boundary shears; there is no fix for that inside a
        // continuous displacement field, which is why the ramp is slow
        // enough to place deliberately.
        val free = field()
        val half = field()
        // One stamp of varnish is a fraction, by design (see above).
        half.applyStamp(stroke(BrushTool.FREEZE), center)
        val varnish = half.sampleFreeze(0.5f, 0.5f)
        assertTrue(varnish > 0f && varnish < 1f)

        free.applyStamp(stroke(BrushTool.SMEAR), center)
        half.applyStamp(stroke(BrushTool.SMEAR), center)

        val expected = free.sampleX(0.5f, 0.5f) * (1f - varnish)
        assertEquals(expected, half.sampleX(0.5f, 0.5f), 1e-4f)
    }

    @Test
    fun `fusion respects the varnish too`() {
        // "Keep this part of the photo" has to mean keeping the photo,
        // not merely keeping its geometry.
        val f = field()
        f.freezeCenter()
        f.applyStamp(stroke(BrushTool.FUSE), center)
        assertEquals(0f, f.sampleMask(0.5f, 0.5f), 1e-4f)
    }

    // ---- The two exemptions ----------------------------------------------

    @Test
    fun `the brake does not brake itself`() {
        // Without the exemption, varnish at full strength would make a
        // region permanently unreachable by anything except a global
        // Reset — including by more varnish and by the thaw.
        assertTrue(!StampMode.GUARD.respectsFreeze, "GUARD must be exempt")
        assertTrue(!StampMode.ERASE.respectsFreeze, "ERASE must be exempt")

        val f = field()
        f.freezeCenter()
        assertEquals(1f, f.sampleFreeze(0.5f, 0.5f), 1e-4f)

        // Still reachable: UnGoo thaws it back out.
        repeat(40) { f.applyStamp(stroke(BrushTool.UNGOO), center) }
        assertTrue(
            f.sampleFreeze(0.5f, 0.5f) < 0.05f,
            "UnGoo must thaw, got ${f.sampleFreeze(0.5f, 0.5f)}",
        )
    }

    @Test
    fun `thawing gives the picture back to the rest of the palette`() {
        val f = field()
        f.freezeCenter()
        repeat(40) { f.applyStamp(stroke(BrushTool.UNGOO), center) }
        f.applyStamp(stroke(BrushTool.SMEAR), center)
        assertTrue(
            abs(f.sampleX(0.5f, 0.5f)) > 1e-3f,
            "a thawed region must smear again",
        )
    }

    @Test
    fun `smoothing the goo does not erode the varnish`() {
        // Smooth blurs the DISPLACEMENT. Letting it blur the mask as well
        // would make the varnish quietly leak away under a tool nobody
        // aimed at it.
        val f = field()
        f.freezeCenter()
        val before = f.sampleFreeze(0.5f, 0.5f)
        repeat(10) { f.applyStamp(stroke(BrushTool.SMOOTH), center) }
        assertEquals(before, f.sampleFreeze(0.5f, 0.5f), 1e-5f)
    }

    // ---- Document space, not content space -------------------------------

    @Test
    fun `the varnish stays where it was painted when goo moves past it`() {
        // The real disagreement between the two source proposals. The
        // fusion mask rides the warp lookup, because painted-through
        // photo B is CONTENT. The varnish must not: "this eye stays here"
        // is a statement about the document, and a protection that
        // travelled with the content it is preventing from travelling
        // would be incoherent. It is also what Liquify does.
        val f = field()
        // Varnish a small patch away from the drag's center.
        val patch = Stamp(cx = 0.3f, cy = 0.5f, dx = 0f, dy = 0f)
        repeat(20) { f.applyStamp(stroke(BrushTool.FREEZE, radius = 0.1f), patch) }
        val before = f.sampleFreeze(0.3f, 0.5f)
        assertTrue(before > 0.9f)

        // A big smear whose brush covers the patch and drags rightward.
        repeat(10) {
            f.applyStamp(
                stroke(BrushTool.SMEAR, radius = 0.45f),
                Stamp(cx = 0.5f, cy = 0.5f, dx = 0.03f, dy = 0f),
            )
        }

        // Still exactly where it was painted, and not smeared along.
        assertEquals(before, f.sampleFreeze(0.3f, 0.5f), 1e-5f)
    }

    @Test
    fun `the varnish replays identically from the log`() {
        // Freeze strokes are ordinary log entries, so undo, export replay
        // and persistence come free — but only if replay is exact.
        val strokes = listOf(
            stroke(BrushTool.FREEZE).copy(stamps = listOf(center, center, center)),
            stroke(BrushTool.SMEAR).copy(stamps = listOf(center, center)),
        )
        val a = field().apply { replay(strokes) }
        val b = field().apply { replay(strokes) }
        assertTrue(a.data.contentEquals(b.data))

        // And the order matters — varnish laid AFTER a smear does not
        // retroactively undo it, which is the honest behaviour of a log.
        val reversed = field().apply { replay(strokes.reversed()) }
        assertTrue(!a.data.contentEquals(reversed.data))
    }

    @Test
    fun `the field spends its last channel and no more`() {
        // RGBA16F: xy displacement, z fusion, w freeze. After this the
        // field is full — the next per-texel quantity needs a second
        // texture and doubles the ping-pong pair.
        assertEquals(4, DisplacementField.CHANNELS)
        val f = field()
        assertEquals(64 * 64 * 4, f.data.size)
    }
}
