package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rewind (proposal 0008, ADR 0003) — the first stroke that is not
 * self-contained.
 *
 * The kernel is one line and the interesting failures are all in the
 * document model: a reference that keeps nothing alive, a save that
 * drops the target, a file whose reference points forward. Each of
 * those produces something that looks like it works until the exact
 * moment it does not.
 */
class RewindTest {

    // ODD on purpose: texel centers sit at (i + 0.5) / n, so 0.5 is a
    // texel CENTRE at 49 and a texel EDGE at 48. On an even grid every
    // reading at the middle is a bilinear average of four texels with
    // sub-unit weights, and the exact BLEND_STEP assertion below turns
    // into a tolerance covering an artefact of the test's own geometry.
    private fun field() = DisplacementField(49, 49, aspect = 1f)

    private fun stroke(
        tool: BrushTool,
        radius: Float = 0.3f,
        strength: Float = 1f,
        target: Long? = null,
    ) = Stroke(tool, radius, strength, stamps = emptyList(), targetRevision = target)

    private val center = Stamp(cx = 0.5f, cy = 0.5f, dx = 0.02f, dy = 0f)

    /** A field pushed hard in +x everywhere under the brush. */
    private fun pushed() = field().also {
        it.applyStamp(stroke(BrushTool.MOVE, radius = 5f), Stamp(0.5f, 0.5f, 0.4f, 0f))
    }

    // ---- The kernel ------------------------------------------------------

    @Test
    fun `a rewind stamp moves the field toward the target`() {
        val f = field()
        val target = pushed()
        val want = target.sampleX(0.5f, 0.5f)
        assertTrue(abs(want) > 0.01f, "the target must actually differ, got $want")

        f.applyStamp(stroke(BrushTool.REWIND, target = 1L), center, target)

        val got = f.sampleX(0.5f, 0.5f)
        // One stamp is a BLEND_STEP of the way there, not a jump: Rewind
        // is UnGoo aimed elsewhere, and UnGoo dissolves rather than
        // teleports.
        assertEquals(want * BrushDynamics.BLEND_STEP, got, 1e-4f)
    }

    @Test
    fun `holding converges on the target`() {
        // 80 stamps converge because (1 - BLEND_STEP)^80 is ~2e-9 at
        // today's 0.22. The count is coupled to that constant: below
        // about 0.076 this starts failing, and the connection to
        // whatever change broke it would not be obvious.
        val f = field()
        val target = pushed()
        repeat(80) { f.applyStamp(stroke(BrushTool.REWIND, target = 1L), center, target) }
        assertEquals(target.sampleX(0.5f, 0.5f), f.sampleX(0.5f, 0.5f), 1e-3f)
    }

    @Test
    fun `rewinding restores the fusion mask and the varnish too`() {
        // The whole vec4 is mixed, unlike RELAX. Nobody wrote this; it
        // falls out of the field being one value, and it is the reason
        // "paint back to before the fusion" works.
        val target = field().also {
            it.applyStamp(stroke(BrushTool.FUSE, radius = 5f), Stamp(0.5f, 0.5f, 0f, 0f))
            it.applyStamp(stroke(BrushTool.FREEZE, radius = 5f), Stamp(0.5f, 0.5f, 0f, 0f))
        }
        assertTrue(target.sampleMask(0.5f, 0.5f) > 0.05f)
        assertTrue(target.sampleFreeze(0.5f, 0.5f) > 0.05f)

        val f = field()
        repeat(80) { f.applyStamp(stroke(BrushTool.REWIND, target = 1L), center, target) }
        assertEquals(target.sampleMask(0.5f, 0.5f), f.sampleMask(0.5f, 0.5f), 1e-3f)
        assertEquals(target.sampleFreeze(0.5f, 0.5f), f.sampleFreeze(0.5f, 0.5f), 1e-3f)
    }

    @Test
    fun `a rewind outside the brush leaves the field alone`() {
        val f = field()
        val target = pushed()
        f.applyStamp(stroke(BrushTool.REWIND, radius = 0.05f, target = 1L), center, target)
        // Far corner: the target is displaced there too, so a kernel
        // that ignored its falloff would visibly pull it.
        assertEquals(0f, f.sampleX(0.05f, 0.05f), 1e-5f)
    }

    @Test
    fun `a missing target is refused rather than blended toward nothing`() {
        // In a reference implementation whose entire job is to be the
        // thing the shader is checked against, a silent no-op reads as
        // "the brush is weak" and would be believed.
        assertFailsWith<IllegalArgumentException> {
            field().applyStamp(stroke(BrushTool.REWIND, target = 1L), center)
        }
    }

    @Test
    fun `a mismatched target is refused`() {
        assertFailsWith<IllegalArgumentException> {
            field().applyStamp(
                stroke(BrushTool.REWIND, target = 1L),
                center,
                DisplacementField(9, 9, aspect = 1f),
            )
        }
    }

    // ---- Retention (ADR 0003, the sharp edge) ----------------------------

    private fun log(): Pair<StrokeLog, StrokeRevision> {
        val log = StrokeLog()
        log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)))
        return log to log.currentRevision
    }

    @Test
    fun `the log holds the revision a stroke reads from`() {
        val (log, pinned) = log()
        log.push(
            stroke(BrushTool.REWIND, target = pinned.id.value).copy(stamps = listOf(center)),
            pinned,
        )
        assertNotNull(log.revisionById(pinned.id.value))
    }

    @Test
    fun `an id alone is refused, because an id keeps nothing alive`() {
        val (log, pinned) = log()
        assertFailsWith<IllegalArgumentException> {
            log.push(stroke(BrushTool.REWIND, target = pinned.id.value).copy(stamps = listOf(center)))
        }
        assertFailsWith<IllegalArgumentException> {
            log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)), pinned)
        }
    }

    @Test
    fun `a target that is not the stroke's target is refused`() {
        val (log, pinned) = log()
        assertFailsWith<IllegalArgumentException> {
            log.push(
                stroke(BrushTool.REWIND, target = pinned.id.value + 99).copy(stamps = listOf(center)),
                pinned,
            )
        }
    }

    // ---- Persistence -----------------------------------------------------

    @Test
    fun `a saved project keeps the target even after the keyframe is gone`() {
        // Round-tripping a Rewind stroke with its target present, and
        // NOT the retention walk — the target here is still on the
        // history parent chain, so this passes even with target
        // following removed entirely.
        //
        // It said "THE test for this feature" until review pointed out
        // that it cannot fail for the reason it claimed. That is the
        // same defect shape this file warns about elsewhere: a check
        // that reads as coverage and is not. The retention walk is
        // covered by the two tests below, where the target sits off the
        // history chain.
        val (log, pinned) = log()
        log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)))
        log.push(
            stroke(BrushTool.REWIND, target = pinned.id.value).copy(stamps = listOf(center)),
            pinned,
        )
        // No pins: the keyframe has been deleted.
        val snap = log.snapshot()
        assertTrue(
            snap.revisions.any { it.id == pinned.id.value },
            "the target must be in the file even with no keyframe pinning it",
        )
        assertNotNull(StrokeLog().restore(snap))
    }

    @Test
    fun `a target on a branch that was undone away still survives a save`() {
        // Backwards-pointing in ID is NOT the same as being an ANCESTOR,
        // and the snapshot walk climbs parents. Undo past a keyframe and
        // push something else, and the pinned revision becomes a sibling
        // branch: smaller id, on nobody's parent chain.
        val log = StrokeLog()
        log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)))
        log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)))
        val pinned = log.currentRevision
        log.undo()
        // Truncates the branch `pinned` is on.
        log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)))
        log.push(
            stroke(BrushTool.REWIND, target = pinned.id.value).copy(stamps = listOf(center)),
            pinned,
        )
        // Keyframe deleted: nothing pins it any more except the stroke.
        val snap = log.snapshot()
        assertTrue(
            snap.revisions.any { it.id == pinned.id.value },
            "a target on a truncated branch must still reach the file",
        )
        // The consequence if it does not: restore refuses the whole
        // document, so the project stops opening at all. Dropping the
        // target is not a degraded picture, it is data loss.
        assertNotNull(StrokeLog().restore(snap), "the saved file must load back")
    }

    @Test
    fun `a transitive target chain survives a save`() {
        // ADR 0003 says the walk follows targets TRANSITIVELY, because a
        // target's own strokes may themselves be Rewinds. A one-level
        // walk passes every other retention test here.
        val (log, pinned) = log()
        log.push(
            stroke(BrushTool.REWIND, target = pinned.id.value).copy(stamps = listOf(center)),
            pinned,
        )
        val mid = log.currentRevision
        log.undo()
        log.push(stroke(BrushTool.SMEAR).copy(stamps = listOf(center)))
        log.push(
            stroke(BrushTool.REWIND, target = mid.id.value).copy(stamps = listOf(center)),
            mid,
        )
        val snap = log.snapshot()
        assertTrue(
            snap.revisions.any { it.id == pinned.id.value },
            "the ROOT of a target chain must survive, not just the near end",
        )
        assertNotNull(StrokeLog().restore(snap))
    }

    @Test
    fun `restoring rebuilds the retention map`() {
        val (log, pinned) = log()
        log.push(
            stroke(BrushTool.REWIND, target = pinned.id.value).copy(stamps = listOf(center)),
            pinned,
        )
        val fresh = StrokeLog()
        assertNotNull(fresh.restore(log.snapshot()))
        assertNotNull(
            fresh.revisionById(pinned.id.value),
            "a reloaded project must resolve its own Rewind targets",
        )
    }

    @Test
    fun `a forward or dangling reference is refused`() {
        // Acyclicity is what replay termination depends on, so it is
        // enforced on every file accepted rather than promised about the
        // ones we wrote.
        val record = StrokeRevisionRecord(
            id = 1,
            parent = null,
            stroke = stroke(BrushTool.REWIND, target = 7L).copy(stamps = listOf(center)),
        )
        val snap = StrokeLogSnapshot(revisions = listOf(record), history = listOf(1), cursor = 0)
        assertNull(StrokeLog().restore(snap))
    }

    @Test
    fun `a stroke that disagrees with itself about having a target is refused`() {
        val orphan = StrokeRevisionRecord(
            id = 1,
            parent = null,
            stroke = stroke(BrushTool.REWIND).copy(stamps = listOf(center)),
        )
        assertNull(
            StrokeLog().restore(
                StrokeLogSnapshot(listOf(orphan), history = listOf(1), cursor = 0),
            ),
        )
        val impostor = StrokeRevisionRecord(
            id = 1,
            parent = null,
            stroke = stroke(BrushTool.SMEAR, target = 1L).copy(stamps = listOf(center)),
        )
        assertNull(
            StrokeLog().restore(
                StrokeLogSnapshot(listOf(impostor), history = listOf(1), cursor = 0),
            ),
        )
    }

    @Test
    fun `old projects load unchanged`() {
        // targetRevision defaults to null, which is the entire backward
        // compatibility story — pin it, because a default that stops
        // being a default breaks every file ever written.
        val old = StrokeRevisionRecord(
            id = 1,
            parent = null,
            stroke = stroke(BrushTool.SMEAR).copy(stamps = listOf(center)),
        )
        val restored = StrokeLog().restore(
            StrokeLogSnapshot(listOf(old), history = listOf(1), cursor = 0),
        )
        assertNotNull(restored)
        assertNull(restored.getValue(StrokeRevisionId(1)).materialize().first().targetRevision)
    }

    // ---- The tool row ----------------------------------------------------

    @Test
    fun `exactly one tool needs a target`() {
        assertEquals(
            listOf(BrushTool.REWIND),
            BrushTool.entries.filter { it.needsTarget },
        )
    }
}
