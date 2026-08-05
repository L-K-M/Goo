package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.StrokeLog
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
}
