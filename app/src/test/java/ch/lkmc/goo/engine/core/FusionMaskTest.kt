package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FusionMaskTest {

    private fun field() = DisplacementField(64, 64, aspect = 1f)

    private fun stroke(tool: BrushTool, strength: Float = 1f) = Stroke(
        tool = tool,
        radius = 0.3f,
        strength = strength,
        stamps = emptyList(),
    )

    private val center = Stamp(cx = 0.5f, cy = 0.5f, dx = 0.02f, dy = 0f)

    @Test
    fun `fuse accumulates mask under the brush and clamps at one`() {
        val f = field()
        f.applyStamp(stroke(BrushTool.FUSE), center)
        val after1 = f.sampleMask(0.5f, 0.5f)
        assertTrue(after1 > 0.2f, "one stamp lays visible flow, got $after1")
        repeat(20) { f.applyStamp(stroke(BrushTool.FUSE), center) }
        assertEquals(1f, f.sampleMask(0.5f, 0.5f), 1e-4f)
        // Soft edge: outside the radius stays untouched.
        assertEquals(0f, f.sampleMask(0.05f, 0.05f))
    }

    @Test
    fun `fuse leaves displacement untouched`() {
        val f = field()
        f.applyStamp(stroke(BrushTool.SMEAR), center)
        val dxBefore = f.sampleX(0.5f, 0.5f)
        f.applyStamp(stroke(BrushTool.FUSE), center)
        assertEquals(dxBefore, f.sampleX(0.5f, 0.5f))
    }

    @Test
    fun `smearing moves the painted mask with the goo`() {
        // The probe must start OUTSIDE the painted blob: the 10-stamp blob
        // saturates at 1.0 well past the drag distance, so a probe inside
        // it reads 1.0 whether the mask moved or stayed put — asserting
        // there passes even with the warp-of-warp mask carry broken.
        val f = field()
        repeat(10) { f.applyStamp(stroke(BrushTool.FUSE), center) }
        val u = 0.8f
        val maskBefore = f.sampleMask(u, 0.5f)
        assertTrue(maskBefore < 0.05f, "probe must start unpainted, got $maskBefore")
        // Drag right at the probe: content (and its mask) is fetched from
        // the left — inside the painted zone.
        f.applyStamp(stroke(BrushTool.SMEAR), Stamp(cx = u, cy = 0.5f, dx = 0.1f, dy = 0f))
        val maskAtDragged = f.sampleMask(u, 0.5f)
        assertTrue(maskAtDragged > 0.5f, "mask should follow the smear, got $maskAtDragged")
        // Exact semantics: the dragged point shows the mask of the point
        // its content came from (both rode the same warp-of-warp lookup),
        // so it must match a pristine blob sampled at the warped source.
        val src = f.warpedSource(u, 0.5f)
        val pristine = field()
        repeat(10) { pristine.applyStamp(stroke(BrushTool.FUSE), center) }
        assertEquals(pristine.sampleMask(src.first, src.second), maskAtDragged, 0.05f)
    }

    @Test
    fun `ungoo erases the mask along with the warp`() {
        val f = field()
        repeat(10) { f.applyStamp(stroke(BrushTool.FUSE), center) }
        assertTrue(f.sampleMask(0.5f, 0.5f) > 0.9f)
        repeat(60) { f.applyStamp(stroke(BrushTool.UNGOO), center) }
        assertTrue(f.sampleMask(0.5f, 0.5f) < 0.05f, "ungoo un-fuses")
    }

    @Test
    fun `smooth blurs the mask edge`() {
        val f = field()
        // Hard mask edge: one half-row set directly.
        for (ix in 0 until 32) {
            f.data[(32 * 64 + ix) * DisplacementField.CHANNELS + 2] = 1f
        }
        val edgeBefore = f.sampleMask(0.5f, 32.5f / 64)
        repeat(10) { f.applyStamp(stroke(BrushTool.SMOOTH), center) }
        val edgeAfter = f.sampleMask(0.5f, 32.5f / 64)
        assertTrue(edgeAfter != edgeBefore, "smooth touches the mask")
    }

    @Test
    fun `replay reproduces the mask deterministically`() {
        val stampList = listOf(
            center,
            Stamp(cx = 0.4f, cy = 0.6f, dx = 0f, dy = 0f),
        )
        val strokes = listOf(
            Stroke(BrushTool.FUSE, 0.3f, 1f, stampList),
            Stroke(BrushTool.SMEAR, 0.3f, 1f, listOf(Stamp(0.5f, 0.5f, 0.03f, 0f))),
        )
        val f1 = field().apply { replay(strokes) }
        val f2 = field().apply { replay(strokes) }
        assertTrue(f1.data.contentEquals(f2.data))
        assertTrue(f1.sampleMask(0.5f, 0.5f) > 0f)
    }
}
