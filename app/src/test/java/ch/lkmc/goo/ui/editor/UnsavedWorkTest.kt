package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeRevisionId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EditorViewModel.UiState.hasUnwrittenChanges] is the editor's whole
 * answer to "is this on disk yet". Three things read it and each one
 * fails differently:
 *
 * - the write on the way out — a false negative here loses the session,
 *   which is the injury this flag exists to prevent;
 * - the checkpoint loop — a false POSITIVE here is a loop that rewrites
 *   an unchanged document forever;
 * - the rail's Save bead, which is lit by it and so has to go dark the
 *   moment a write lands, or it lies about the state of the work.
 *
 * So each signal it reads gets its own case.
 *
 * One signal is missing on purpose: `bitmapB` (a picked Fusion photo).
 * `Bitmap` is a final Android class with no JVM-constructible instance,
 * and this module's test tier is deliberately pure JVM — no Robolectric,
 * no mocking framework (see ANALYSIS.md's declined "dead deps" entry).
 * Pulling one in to pin a single `!= null` term costs more than it
 * guards. If a Robolectric or mockk tier ever lands for other reasons,
 * this is the first test to add.
 */
class UnsavedWorkTest {

    private fun state(
        canReset: Boolean = false,
        keyframes: List<Keyframe> = emptyList(),
        cropped: Boolean = false,
    ) = EditorViewModel.UiState(
        canReset = canReset,
        keyframes = keyframes,
        cropped = cropped,
    )

    private fun keyframe(): Keyframe {
        val log = StrokeLog()
        return Keyframe(revision = log.currentRevision, globals = GlobalParams())
    }

    /** The state as it stands right after a write of it lands. */
    private fun EditorViewModel.UiState.written() = copy(savedSignature = signature)

    @Test
    fun `an untouched photo has nothing to write`() {
        // Opening a photo and backing straight out must not put a project
        // on the shelf — there is no work in it.
        assertFalse(state().hasUnwrittenChanges)
        assertFalse(state().written().hasUnwrittenChanges)
    }

    @Test
    fun `strokes or moved levers count as work`() {
        // canReset is exactly "strokes committed, or levers off identity"
        // (EditorViewModel.refreshHistoryFlags) — the Reset bead's own gate.
        assertTrue(state(canReset = true).hasUnwrittenChanges)
    }

    @Test
    fun `a punched keyframe counts even over an unwarped photo`() {
        // Pins carry lever values, and a strip is authored work in its own
        // right: losing it silently is the same injury as losing strokes.
        assertTrue(state(keyframes = listOf(keyframe())).hasUnwrittenChanges)
    }

    @Test
    fun `a crop counts precisely because it clears everything else`() {
        // applyCrop restarts the document: log cleared, levers zeroed. So
        // canReset reads false exactly when the reframe is the only thing
        // left to lose — the case a canReset-only check would wave through.
        assertFalse(state(cropped = true).canReset)
        assertTrue(state(cropped = true).hasUnwrittenChanges)
    }

    @Test
    fun `work in any one place is enough`() {
        assertTrue(state(canReset = true, keyframes = listOf(keyframe())).hasUnwrittenChanges)
        assertTrue(state(canReset = true, cropped = true).hasUnwrittenChanges)
        assertTrue(state(keyframes = listOf(keyframe()), cropped = true).hasUnwrittenChanges)
    }

    // ---- Against what is on disk ---------------------------------------
    // Since projects persist (ANALYSIS SOL-34) this is a comparison, not a
    // flag — and the comparison has to settle, or the checkpoint loop
    // never stops writing.

    @Test
    fun `a document that matches the written one is settled`() {
        val work = state(canReset = true, keyframes = listOf(keyframe()))
        assertTrue(work.hasUnwrittenChanges)
        // Reopening a project lands here, and so does the instant after
        // any save: there IS work, and it is already on disk.
        assertTrue(work.written().hasWork)
        assertFalse(work.written().hasUnwrittenChanges)
    }

    @Test
    fun `one more stroke after a write counts again`() {
        val written = state(canReset = true).written()
        // A committed stroke moves the log to a new revision, and ids are
        // never reused — which is exactly why the signature can rely on it.
        assertTrue(written.copy(revisionId = StrokeRevisionId(7)).hasUnwrittenChanges)
    }

    @Test
    fun `moving a lever after a write counts again`() {
        val written = state(canReset = true).written()
        assertTrue(written.copy(globals = GlobalParams(twirl = 0.4f)).hasUnwrittenChanges)
    }

    @Test
    fun `punching a keyframe after a write counts again`() {
        val written = state(canReset = true).written()
        assertTrue(written.copy(keyframes = listOf(keyframe())).hasUnwrittenChanges)
    }

    @Test
    fun `a re-crop or a swapped Fusion photo counts again`() {
        // Neither shows up in any other term: two different crop rects both
        // read as `cropped`, two different photo Bs both read as "has a B".
        // documentEpoch is the whole reason this sees them at all.
        val written = state(canReset = true, cropped = true).written()
        assertFalse(written.hasUnwrittenChanges)
        assertTrue(written.copy(documentEpoch = written.documentEpoch + 1).hasUnwrittenChanges)
    }
}
