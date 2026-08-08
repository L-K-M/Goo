package ch.lkmc.goo.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scissor rect is a performance optimization that must never be a
 * correctness one: whatever it excludes, the engine stops writing.
 *
 * The anchor test is [coversEveryTexelTheReferenceChanges], which does not
 * trust the analytic derivation at all — it stamps through
 * [DisplacementField] (the CPU reference the shader is a transliteration
 * of) and asserts that every texel the reference actually moved lies
 * inside the rect.
 */
class StampBoundsTest {

    // ---- The anchor: agreement with the reference implementation -------

    /**
     * What the scissor is allowed to discard, and why this number.
     *
     * Outside the brush disc the falloff is 0, so every kernel reduces to
     * writing the texel back — but the DIRECTIONAL family writes it back
     * through `sample(u + 0, v + 0)`, and the UV round trip
     * `(ix + 0.5) / width * width - 0.5` is not exactly `ix` in float. At
     * `ix = 61` the absolute error is about `61.5 × 1.2e-7 ≈ 7e-6` of a
     * texel, so the bilinear blend leaks that fraction of the difference
     * to a neighbour. [seed] deliberately makes that difference large
     * (the mask channel steps by 1/3 between adjacent texels), which puts
     * the drift at ~2.6e-6 — measured, not assumed, by
     * [driftOutsideTheDiscIsRoundingNotSignal].
     *
     * A real field is smooth by nature (PLAN.md §4.1) so its neighbours
     * differ far less. And a kernel genuinely reaching past its falloff
     * would move things by order `RADIAL_STEP_UV` (0.004) or
     * `BLEND_STEP` (0.22) — three to five orders of magnitude above this
     * line. The tolerance is loose enough to ignore rounding and still
     * enormously tighter than any real effect.
     */
    private val materialChange = 1e-5f

    @Test
    fun coversEveryTexelTheReferenceChanges() {
        val cases = listOf(
            Triple(0.5f, 0.5f, 0.20f),
            Triple(0.5f, 0.5f, 0.05f),
            Triple(0.13f, 0.77f, 0.30f),
            Triple(0.92f, 0.08f, 0.12f),
            Triple(0.0f, 0.0f, 0.25f),
            Triple(1.0f, 1.0f, 0.25f),
        )
        // Non-square on purpose: an aspect bug is invisible at 1:1.
        for (aspect in listOf(1f, 16f / 9f, 9f / 16f)) {
            val h = 64
            val w = (h * aspect).toInt().coerceAtLeast(2)
            for ((cx, cy, radius) in cases) {
                for (tool in BrushTool.entries) {
                    assertRectCoversChanges(tool, cx, cy, radius, aspect, w, h)
                }
            }
        }
    }

    private fun assertRectCoversChanges(
        tool: BrushTool,
        cx: Float,
        cy: Float,
        radius: Float,
        aspect: Float,
        w: Int,
        h: Int,
    ) {
        val before = DisplacementField(w, h, aspect)
        // A non-trivial starting field: an identity field hides any mode
        // whose kernel scales what is already there (RELAX, ERASE).
        seed(before)
        val after = DisplacementField(w, h, aspect).also { copyInto(before, it) }

        val stroke = Stroke(
            tool,
            radius,
            strength = 1f,
            stamps = emptyList(),
            targetRevision = if (tool.needsTarget) 0L else null,
        )
        // Rewind's target must DIFFER from the field everywhere, or the
        // stamp writes nothing and the tool would pass this test by
        // doing nothing at all. A differently-seeded field guarantees a
        // real change inside the disc — which is exactly what the rect
        // is being asked to contain.
        val target = if (tool.needsTarget) {
            DisplacementField(w, h, aspect).also { seed(it, phase = 1.7f) }
        } else {
            null
        }
        after.applyStamp(stroke, Stamp(cx, cy, dx = 0.03f, dy = -0.02f), target)

        val rect = StampBounds.of(cx, cy, radius, aspect, w, h, tool.profile)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val delta = maxChange(before, after, x, y)
                if (delta <= materialChange) continue
                val inside = x >= rect.x0 && x < rect.x1 && y >= rect.y0 && y < rect.y1
                assertTrue(
                    "$tool changed texel ($x,$y) by $delta outside $rect " +
                        "(c=($cx,$cy) r=$radius aspect=$aspect field=${w}x$h)",
                    inside,
                )
            }
        }
    }

    /**
     * The other half of the argument: what falls outside the rect is not
     * merely small, it is rounding noise several orders of magnitude
     * below anything visible in a displacement measured in UV units.
     *
     * If a future kernel ever reaches beyond its own falloff this test
     * fails loudly, which is the point — [coversEveryTexelTheReferenceChanges]
     * alone would let a real effect hide under the tolerance.
     */
    @Test
    fun driftOutsideTheDiscIsRoundingNotSignal() {
        val w = 113
        val h = 64
        val aspect = 16f / 9f
        var worst = 0f
        for (tool in BrushTool.entries) {
            val before = DisplacementField(w, h, aspect).also { seed(it) }
            val after = DisplacementField(w, h, aspect).also { copyInto(before, it) }
            val stroke = Stroke(
                tool,
                radius = 0.2f,
                strength = 1f,
                stamps = emptyList(),
                targetRevision = if (tool.needsTarget) 0L else null,
            )
            val target = if (tool.needsTarget) {
                DisplacementField(w, h, aspect).also { seed(it, phase = 1.7f) }
            } else {
                null
            }
            after.applyStamp(stroke, Stamp(0.5f, 0.5f, dx = 0.03f, dy = -0.02f), target)
            val rect = StampBounds.of(0.5f, 0.5f, 0.2f, aspect, w, h, tool.profile)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (x >= rect.x0 && x < rect.x1 && y >= rect.y0 && y < rect.y1) continue
                    worst = maxOf(worst, maxChange(before, after, x, y))
                }
            }
        }
        assertTrue(
            "outside-the-disc drift $worst is too large to be rounding — " +
                "a kernel is reaching past its falloff",
            worst < materialChange,
        )
    }

    /**
     * [phase] offsets every channel, so a second call makes a field that
     * differs from the first at every texel — what Rewind needs for a
     * target it will actually move toward.
     */
    private fun seed(f: DisplacementField, phase: Float = 0f) {
        for (y in 0 until f.height) {
            for (x in 0 until f.width) {
                val i = (y * f.width + x) * DisplacementField.CHANNELS
                f.data[i] = 0.01f * ((x % 7) - 3) + 0.02f * phase
                f.data[i + 1] = 0.01f * ((y % 5) - 2) - 0.02f * phase
                f.data[i + 2] = (((x + y) % 3) / 3f + 0.25f * phase).coerceIn(0f, 1f)
            }
        }
    }

    private fun copyInto(from: DisplacementField, to: DisplacementField) {
        from.data.copyInto(to.data)
    }

    /** Largest absolute change across the texel's channels. */
    private fun maxChange(a: DisplacementField, b: DisplacementField, x: Int, y: Int): Float {
        val i = (y * a.width + x) * DisplacementField.CHANNELS
        var worst = 0f
        for (c in 0 until DisplacementField.CHANNELS) {
            worst = maxOf(worst, kotlin.math.abs(a.data[i + c] - b.data[i + c]))
        }
        return worst
    }

    // ---- Geometry -----------------------------------------------------

    @Test
    fun discIsRoundInPixelsOnANonSquareField() {
        // A 2:1 image at 2:1 field dims: the disc must be as many texels
        // wide as it is tall, which is only true if the aspect divide
        // lands on u. Getting it backwards squares the error — 4x here,
        // not the 1 texel that floor/ceil quantization can cost.
        val rect = StampBounds.of(
            cx = 0.5f, cy = 0.5f, radius = 0.2f, aspect = 2f,
            width = 200, height = 100, margin = 0,
        )
        assertTrue(
            "expected a round disc, got ${rect.width}x${rect.height}",
            kotlin.math.abs(rect.width - rect.height) <= 2,
        )
    }

    @Test
    fun radiusScalesTheRectButNotItsCenter() {
        val small = StampBounds.of(0.5f, 0.5f, 0.05f, 1f, 128, 128, margin = 0)
        val big = StampBounds.of(0.5f, 0.5f, 0.20f, 1f, 128, 128, margin = 0)
        assertTrue(big.area > small.area)
        assertEquals(small.x0 + small.x1, big.x0 + big.x1)
        assertEquals(small.y0 + small.y1, big.y0 + big.y1)
    }

    @Test
    fun aFullFrameRadiusCoversTheWholeField() {
        val rect = StampBounds.of(0.5f, 0.5f, 4f, 1f, 64, 64)
        assertEquals(TexelRect.full(64, 64), rect)
    }

    @Test
    fun scissoringIsWorthItForATypicalBrush() {
        // The premise of G-3: a normal stamp touches a small fraction of
        // the field. If this ever stops holding, the optimization is not
        // worth its bookkeeping.
        val rect = StampBounds.of(0.5f, 0.5f, 0.1f, 1f, 1024, 1024)
        val full = TexelRect.full(1024, 1024)
        assertTrue(
            "expected a typical stamp to touch <10% of the field, got ${rect.area}/${full.area}",
            rect.area * 10 < full.area,
        )
    }

    // ---- Off-canvas and degenerate input ------------------------------

    @Test
    fun aStampClearOfTheFieldIsEmpty() {
        // Mirror and (proposed) kaleidoscope twins legitimately land off
        // canvas; the caller skips the pass entirely.
        assertTrue(StampBounds.of(-3f, 0.5f, 0.1f, 1f, 64, 64).isEmpty)
        assertTrue(StampBounds.of(0.5f, 4f, 0.1f, 1f, 64, 64).isEmpty)
    }

    @Test
    fun aStampOverlappingAnEdgeIsClampedButNotEmpty() {
        val rect = StampBounds.of(-0.05f, 0.5f, 0.1f, 1f, 64, 64)
        assertFalse(rect.isEmpty)
        assertEquals(0, rect.x0)
        assertTrue(rect.x1 in 1..64)
    }

    @Test
    fun aHugeRadiusSaturatesInsteadOfWrapping() {
        // Float.toInt() on a value past Int.MAX wraps; a wrapped rect
        // would land on the wrong side of the field and blank the frame.
        val rect = StampBounds.of(0.5f, 0.5f, 1e30f, 1f, 64, 64)
        assertEquals(TexelRect.full(64, 64), rect)
    }

    @Test
    fun nonFiniteAndNonPositiveInputIsEmptyRatherThanWild() {
        assertTrue(StampBounds.of(0.5f, 0.5f, 0f, 1f, 64, 64).isEmpty)
        assertTrue(StampBounds.of(0.5f, 0.5f, -1f, 1f, 64, 64).isEmpty)
        assertTrue(StampBounds.of(0.5f, 0.5f, Float.NaN, 1f, 64, 64).isEmpty)
        assertTrue(StampBounds.of(Float.NaN, 0.5f, 0.1f, 1f, 64, 64).isEmpty)
        assertTrue(StampBounds.of(0.5f, Float.POSITIVE_INFINITY, 0.1f, 1f, 64, 64).isEmpty)
    }

    // ---- TexelRect ----------------------------------------------------

    @Test
    fun unionGrowsToCoverBothAndIgnoresEmpty() {
        val a = TexelRect(10, 10, 20, 20)
        val b = TexelRect(15, 5, 30, 12)
        assertEquals(TexelRect(10, 5, 30, 20), a.union(b))
        assertEquals(a, a.union(TexelRect.EMPTY))
        assertEquals(a, TexelRect.EMPTY.union(a))
        assertEquals(TexelRect.EMPTY, TexelRect.EMPTY.union(TexelRect.EMPTY))
    }

    @Test
    fun containsDecidesWhetherARepairIsWorthMaking() {
        val big = TexelRect(0, 0, 100, 100)
        val small = TexelRect(10, 10, 20, 20)
        assertTrue(big.contains(small))
        assertFalse(small.contains(big))
        assertTrue(big.contains(big))
        // Nothing to repair is trivially covered; nothing can cover
        // something out of an empty rect.
        assertTrue(big.contains(TexelRect.EMPTY))
        assertTrue(TexelRect.EMPTY.contains(TexelRect.EMPTY))
        assertFalse(TexelRect.EMPTY.contains(small))
        // Overlapping but not covering.
        assertFalse(TexelRect(0, 0, 15, 100).contains(small))
    }

    @Test
    fun invertedRectsReadAsEmptyRatherThanNegative() {
        val inverted = TexelRect(20, 20, 10, 10)
        assertTrue(inverted.isEmpty)
        assertEquals(0, inverted.width)
        assertEquals(0L, inverted.area)
    }
}
