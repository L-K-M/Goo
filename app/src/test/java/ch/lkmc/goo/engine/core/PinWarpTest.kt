package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taffy Pins as a document edit (proposal 0016).
 *
 * `RigidMlsTest` covers the solver. This covers what happens when its
 * output becomes a thing the log stores: the exclusive stamps-XOR-warp
 * invariant, the field composition, and the persistence contract the
 * proposal says must be pinned before shipping because "persistence is a
 * shipped contract".
 */
class PinWarpTest {

    // ODD, so 0.5 is a texel centre rather than an edge — the same
    // reason RewindTest's field is odd.
    private fun field() = DisplacementField(65, 65, aspect = 1f)

    private fun hold(u: Float, v: Float) = MlsControl(u, v, u, v)
    private fun pull(su: Float, sv: Float, tu: Float, tv: Float) =
        MlsControl(su, sv, tu, tv)

    private fun warp(
        controls: List<MlsControl> = listOf(
            hold(0.2f, 0.2f),
            hold(0.8f, 0.2f),
            pull(0.5f, 0.5f, 0.62f, 0.62f),
        ),
    ) = PinWarp(controls)

    private fun pinStroke(w: PinWarp = warp()) = Stroke(
        tool = BrushTool.PINS,
        radius = 0f,
        strength = 0f,
        stamps = emptyList(),
        pinWarp = w,
    )

    private fun smear() = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.3f,
        strength = 1f,
        stamps = listOf(Stamp(0.5f, 0.5f, 0.02f, 0f)),
    )

    // ---- Stamps XOR a warp -----------------------------------------------

    @Test
    fun `an edit carries exactly one kind of payload`() {
        assertTrue(pinStroke().hasContent)
        assertTrue(smear().hasContent)
        // Neither: nothing to draw. Ordinary — a drag too short to
        // clear the resampler's spacing produces one every time — so it
        // is DROPPED, not refused.
        assertFalse(smear().copy(stamps = emptyList()).hasContent)
        // Both: a contradiction with no defined replay order for its two
        // halves. It has content, so the drop does not catch it, and it
        // has to be refused instead.
        val both = pinStroke().copy(stamps = listOf(Stamp(0f, 0f, 0f, 0f)))
        assertTrue(both.hasContent)
        assertFalse(both.isCoherent)
        assertTrue(smear().isCoherent && pinStroke().isCoherent)
    }

    @Test
    fun `the log refuses an edit that draws nothing`() {
        val log = StrokeLog()
        log.push(smear().copy(stamps = emptyList()))
        log.push(pinStroke().copy(pinWarp = null))
        assertTrue(log.isEmpty, "neither push should have committed anything")
    }

    @Test
    fun `only Pins may carry a warp, and it must`() {
        val log = StrokeLog()
        assertFailsWith<IllegalArgumentException> {
            // Both payloads: refused loudly rather than dropped, which
            // is what separates a caller bug from a too-short drag.
            log.push(smear().copy(pinWarp = warp()))
        }
        assertFailsWith<IllegalArgumentException> {
            // A PINS stroke with stamps instead of a warp: hasContent is
            // true, so it gets past the drop and must be caught by the
            // tool check rather than replayed as a brush.
            log.push(pinStroke().copy(pinWarp = null, stamps = listOf(Stamp(0.5f, 0.5f, 0f, 0f))))
        }
    }

    @Test
    fun `a pin pull is one undoable revision`() {
        // "Releasing one puck adds one undoable revision; Undo restores
        // the exact base field and Redo restores the pull."
        val log = StrokeLog()
        log.push(smear())
        val before = log.strokes
        log.push(pinStroke())
        assertEquals(2, log.strokes.size)
        log.undo()
        assertEquals(before, log.strokes)
        log.redo()
        assertEquals(2, log.strokes.size)
        assertNotNull(log.strokes.last().pinWarp)
    }

    @Test
    fun `a deal never carries a pin warp`() {
        // pushBatch filters on the same content rule as push. Goo Me
        // deals brush strokes today; this pins that a warp cannot ride
        // in through the batch door without a decision.
        val log = StrokeLog()
        log.pushBatch(listOf(pinStroke(), smear()))
        assertEquals(1, log.strokes.size)
        assertNull(log.strokes.single().pinWarp)
    }

    // ---- Validation ------------------------------------------------------

    @Test
    fun `a warp with no moving control is refused`() {
        // All holds and no puck is a very expensive identity.
        assertFalse(warp(listOf(hold(0.2f, 0.2f), hold(0.8f, 0.8f))).isValid)
    }

    @Test
    fun `an empty or oversized control set is refused`() {
        assertFalse(PinWarp(emptyList()).isValid)
        val many = List(RigidMls.MAX_CONTROLS + 1) { pull(0.1f * it, 0.5f, 0.1f * it, 0.6f) }
        assertFalse(PinWarp(many).isValid)
    }

    @Test
    fun `non-finite values are refused, and replaced rather than clamped`() {
        val bad = warp(listOf(hold(0.2f, 0.2f), pull(Float.NaN, 0.5f, 0.6f, 0.7f)))
        assertFalse(bad.isValid)
        // NaN.coerceIn(a, b) is NaN, so a clamp looks like a guard and
        // is not one (the lesson Lens.sanitized records).
        val fixed = bad.sanitized()
        assertTrue(fixed.controls.all { it.isFinite }, "sanitized left a NaN: ${fixed.controls}")
        assertTrue(PinWarp(fixed.controls, reach = Float.NaN).sanitized().reach.isFinite())
    }

    @Test
    fun `sanitizing bounds the control count and the knobs`() {
        val many = List(RigidMls.MAX_CONTROLS + 4) { pull(0.05f * it, 0.5f, 0.05f * it, 0.6f) }
        val s = PinWarp(many, reach = 99f, rubber = -3f).sanitized()
        assertEquals(RigidMls.MAX_CONTROLS, s.controls.size)
        assertEquals(RigidMls.MAX_REACH, s.reach)
        assertEquals(0f, s.rubber)
    }

    // ---- The field pass --------------------------------------------------

    @Test
    fun `a hold pin does not move the picture under it`() {
        // The MVP's first acceptance line, measured on the FIELD rather
        // than on the solver: "every explicit hold pin remains fixed
        // within a documented field-texel tolerance".
        val f = field()
        f.applyPinWarp(warp())
        val moved = hypot(f.sampleX(0.2f, 0.2f), f.sampleY(0.2f, 0.2f))
        assertTrue(moved < 1e-3f, "a pinned texel moved by $moved")
    }

    @Test
    fun `the puck's destination samples the puck's origin`() {
        val f = field()
        f.applyPinWarp(warp())
        // Backward map: D at the target IS (source - target).
        assertEquals(0.5f - 0.62f, f.sampleX(0.62f, 0.62f), 2e-3f)
        assertEquals(0.5f - 0.62f, f.sampleY(0.62f, 0.62f), 2e-3f)
    }

    @Test
    fun `a pin pull composes with the goo already there`() {
        // D'(x) = w(x) + D(x + w(x)): the pull acts on the picture as it
        // currently looks, not on the original photo. A pass that
        // overwrote instead of composing would erase every earlier
        // stroke and look, at a glance, like a very effective pull.
        val f = field()
        f.applyStamp(
            Stroke(BrushTool.MOVE, radius = 5f, strength = 1f, stamps = emptyList()),
            Stamp(0.5f, 0.5f, 0.2f, 0f),
        )
        val before = f.sampleX(0.2f, 0.2f)
        assertTrue(abs(before) > 0.01f, "the pre-existing goo must be visible, got $before")
        // Pin that corner and pull elsewhere; the old displacement there
        // has to survive.
        f.applyPinWarp(warp())
        assertEquals(before, f.sampleX(0.2f, 0.2f), 5e-3f)
    }

    @Test
    fun `the fusion mask and the varnish ride the same lookup`() {
        val f = field()
        f.applyStamp(
            Stroke(BrushTool.FUSE, radius = 5f, strength = 1f, stamps = emptyList()),
            Stamp(0.5f, 0.5f, 0f, 0f),
        )
        f.applyStamp(
            Stroke(BrushTool.FREEZE, radius = 5f, strength = 1f, stamps = emptyList()),
            Stamp(0.5f, 0.5f, 0f, 0f),
        )
        val mask = f.sampleMask(0.2f, 0.2f)
        val frost = f.sampleFreeze(0.2f, 0.2f)
        assertTrue(mask > 0.01f && frost > 0.01f)
        f.applyPinWarp(warp())
        // Carried, not dropped: a pass that only wrote xy would silently
        // erase a Fusion reveal and every varnish stroke.
        assertEquals(mask, f.sampleMask(0.2f, 0.2f), 5e-3f)
        assertEquals(frost, f.sampleFreeze(0.2f, 0.2f), 5e-3f)
    }

    @Test
    fun `no texel comes out non-finite`() {
        // A NaN in a displacement field is permanent and replays
        // faithfully. Controls on the frame edge, one on a texel centre,
        // and a hard pull.
        val f = field()
        f.applyPinWarp(
            PinWarp(
                RigidMls.corners() + listOf(hold(0f, 0.5f), pull(0.5f, 0.5f, 0.95f, 0.05f)),
            ),
        )
        for (i in f.data.indices) {
            assertTrue(f.data[i].isFinite(), "non-finite at $i: ${f.data[i]}")
        }
    }

    @Test
    fun `the same controls give the same field every time`() {
        // "Preview is recomputed from the mode-entry base, so identical
        // final controls produce identical output regardless of pointer
        // event count."
        val a = field().also { it.applyPinWarp(warp()) }
        val b = field().also { it.applyPinWarp(warp()) }
        assertTrue(a.data.contentEquals(b.data))
    }

    @Test
    fun `an invalid warp never reaches the field`() {
        assertFailsWith<IllegalArgumentException> {
            field().applyPinWarp(PinWarp(emptyList()))
        }
    }

    @Test
    fun `applyStamp refuses a pin warp mode outright`() {
        // PINWARP travels the same u_mode wire as the stamp kernels, so
        // the one thing it must never do is get quietly stamped.
        assertFailsWith<IllegalStateException> {
            field().applyStamp(pinStroke(), Stamp(0.5f, 0.5f, 0f, 0f))
        }
    }

    // ---- Persistence -----------------------------------------------------

    @Test
    fun `a pin pull round-trips through a save`() {
        val log = StrokeLog()
        log.push(smear())
        log.push(pinStroke())
        val fresh = StrokeLog()
        assertNotNull(fresh.restore(log.snapshot()))
        val restored = fresh.strokes.last()
        assertEquals(BrushTool.PINS, restored.tool)
        assertEquals(warp(), restored.pinWarp)
    }

    @Test
    fun `old projects load unchanged`() {
        // pinWarp defaults to null, which is the whole backward
        // compatibility story.
        val record = StrokeRevisionRecord(id = 1, parent = null, stroke = smear())
        val restored = StrokeLog().restore(
            StrokeLogSnapshot(listOf(record), history = listOf(1), cursor = 0),
        )
        assertNotNull(restored)
        assertNull(restored.getValue(StrokeRevisionId(1)).materialize().single().pinWarp)
    }

    @Test
    fun `a malformed pin payload is refused rather than half-restored`() {
        // "Malformed and unsupported pin payloads are rejected rather
        // than half-restored" — the proposal names this as a shipped
        // contract that must be pinned before shipping.
        for (broken in listOf(
            pinStroke(PinWarp(emptyList())),
            pinStroke(warp(listOf(hold(0.2f, 0.2f), pull(Float.NaN, 0.5f, 0.6f, 0.7f)))),
            pinStroke(warp(listOf(hold(0.2f, 0.2f), hold(0.8f, 0.8f)))),
            // Right payload, wrong tool.
            smear().copy(pinWarp = warp()),
            // Right tool, no payload and no stamps.
            pinStroke().copy(pinWarp = null),
        )) {
            val snap = StrokeLogSnapshot(
                listOf(StrokeRevisionRecord(id = 1, parent = null, stroke = broken)),
                history = listOf(1),
                cursor = 0,
            )
            assertNull(StrokeLog().restore(snap), "accepted a malformed payload: $broken")
        }
    }

    @Test
    fun `the solver version is recorded so old pulls can stay as drawn`() {
        assertEquals(PinWarp.SOLVER_VERSION, pinStroke().pinWarp!!.solverVersion)
    }
}
