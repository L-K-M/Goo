package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeRevisionId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EditorViewModel.UiState.hasUnsavedWork] is the whole exit guard: it
 * decides whether Back leaves silently or stops to ask. A false negative
 * destroys a session with no warning and no undo, which is the failure
 * this flag exists to prevent — so each signal it reads gets its own case.
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

    @Test
    fun `an untouched photo leaves without a prompt`() {
        assertFalse(state().hasUnsavedWork)
    }

    @Test
    fun `strokes or moved levers count as work`() {
        // canReset is exactly "strokes committed, or levers off identity"
        // (EditorViewModel.refreshHistoryFlags) — the Reset bead's own gate.
        assertTrue(state(canReset = true).hasUnsavedWork)
    }

    @Test
    fun `a punched keyframe counts even over an unwarped photo`() {
        // Pins carry lever values, and a strip is authored work in its own
        // right: losing it silently is the same injury as losing strokes.
        assertTrue(state(keyframes = listOf(keyframe())).hasUnsavedWork)
    }

    @Test
    fun `a crop counts precisely because it clears everything else`() {
        // applyCrop restarts the document: log cleared, levers zeroed. So
        // canReset reads false exactly when the reframe is the only thing
        // left to lose — the case a canReset-only guard would wave through.
        assertFalse(state(cropped = true).canReset)
        assertTrue(state(cropped = true).hasUnsavedWork)
    }

    @Test
    fun `work in any one place is enough`() {
        assertTrue(state(canReset = true, keyframes = listOf(keyframe())).hasUnsavedWork)
        assertTrue(state(canReset = true, cropped = true).hasUnsavedWork)
        assertTrue(state(keyframes = listOf(keyframe()), cropped = true).hasUnsavedWork)
    }

    // ---- Saved projects ------------------------------------------------
    // Now that a document can be on disk (ANALYSIS SOL-34), "unsaved" is a
    // comparison, not a flag: work that differs from what was written.

    @Test
    fun `a document that matches the saved one leaves without asking`() {
        val saved = state(canReset = true, keyframes = listOf(keyframe()))
        assertTrue(saved.hasUnsavedWork)
        // Reopening a project lands here: there IS work, and it is on disk.
        val reopened = saved.copy(savedSignature = saved.signature)
        assertTrue(reopened.hasWork)
        assertFalse(reopened.hasUnsavedWork)
    }

    @Test
    fun `one more stroke after a save arms the guard again`() {
        val saved = state(canReset = true).let { it.copy(savedSignature = it.signature) }
        // A committed stroke moves the log to a new revision, and ids are
        // never reused — which is exactly why the signature can rely on it.
        val gooedSince = saved.copy(revisionId = StrokeRevisionId(7))
        assertTrue(gooedSince.hasUnsavedWork)
    }

    @Test
    fun `moving a lever after a save arms the guard again`() {
        val saved = state(canReset = true).let { it.copy(savedSignature = it.signature) }
        assertTrue(saved.copy(globals = GlobalParams(twirl = 0.4f)).hasUnsavedWork)
    }

    @Test
    fun `punching a keyframe after a save arms the guard again`() {
        val saved = state(canReset = true).let { it.copy(savedSignature = it.signature) }
        assertTrue(saved.copy(keyframes = listOf(keyframe())).hasUnsavedWork)
    }

    @Test
    fun `a re-crop or a swapped Fusion photo arms the guard again`() {
        // Neither shows up in any other term: two different crop rects both
        // read as `cropped`, two different photo Bs both read as "has a B".
        // documentEpoch is the whole reason the guard sees them at all.
        val saved = state(canReset = true, cropped = true)
            .let { it.copy(savedSignature = it.signature) }
        assertFalse(saved.hasUnsavedWork)
        assertTrue(saved.copy(documentEpoch = saved.documentEpoch + 1).hasUnsavedWork)
    }

    @Test
    fun `saving an untouched photo still leaves silently`() {
        // Nothing to save, nothing saved: the guard must not appear just
        // because savedSignature is null on a blank document.
        assertFalse(state().hasUnsavedWork)
        assertFalse(state().copy(savedSignature = state().signature).hasUnsavedWork)
    }
}
