package ch.lkmc.goo.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checkpoint clocks. Both matter for a different user: the one who
 * pauses (quiet) and the one who never does (ceiling).
 */
class AutosavePolicyTest {

    @Test
    fun `a fresh change waits for a quiet moment`() {
        assertEquals(AutosavePolicy.QUIET_MS, AutosavePolicy.delayMs(0))
    }

    @Test
    fun `the quiet wait never pushes a write past the ceiling`() {
        // 5s of headroom left: waiting the full quiet period would blow it.
        val dirtyFor = AutosavePolicy.ONE_CHECKPOINT_MS - 5_000L
        assertEquals(5_000L, AutosavePolicy.delayMs(dirtyFor))
        assertTrue(AutosavePolicy.delayMs(dirtyFor) + dirtyFor <= AutosavePolicy.ONE_CHECKPOINT_MS)
    }

    @Test
    fun `at or past the ceiling the write is due now`() {
        // The steady gooer: never idle long enough for the quiet clock, so
        // this zero is the only thing that ever checkpoints them.
        assertEquals(0L, AutosavePolicy.delayMs(AutosavePolicy.ONE_CHECKPOINT_MS))
        assertEquals(0L, AutosavePolicy.delayMs(AutosavePolicy.ONE_CHECKPOINT_MS * 10))
    }

    @Test
    fun `the wait is never negative`() {
        for (dirtyFor in listOf(0L, 1L, 60_000L, 90_000L, Long.MAX_VALUE / 2)) {
            assertTrue(AutosavePolicy.delayMs(dirtyFor) >= 0L, "negative wait at $dirtyFor")
        }
    }

    @Test
    fun `the ceiling leaves room for at least one quiet wait`() {
        // Otherwise the quiet clock could never fire before the ceiling,
        // and every checkpoint would land mid-stroke instead of in a pause.
        assertTrue(AutosavePolicy.ONE_CHECKPOINT_MS > AutosavePolicy.QUIET_MS)
    }
}
