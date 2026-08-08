package ch.lkmc.goo.data

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scratch file a capture lands in.
 *
 * Only the sweep is testable here — the rest is `Context` and
 * `FileProvider`, which this project has no instrumented tests for by
 * design. The sweep is also the part with a rule that can be wrong in
 * two directions, so it is the part worth testing.
 */
class CameraCaptureTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "capture-test-${hashCode()}")
        .apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun capture(name: String, ageMs: Long, now: Long): File =
        File(dir, name).apply {
            writeText("x")
            setLastModified(now - ageMs)
        }

    @Test
    fun `an old capture is swept`() {
        val now = 1_000_000_000L
        val old = capture("snap-1.jpg", CameraCapture.MAX_AGE_MS * 2, now)
        CameraCapture.sweep(dir, now)
        assertFalse(old.exists(), "a capture from a previous session must not survive")
    }

    @Test
    fun `a capture still in flight is left alone`() {
        // The sweep runs BEFORE the shot it is making room for, and on a
        // slow device the editor may still be reading the previous one.
        val now = 1_000_000_000L
        val fresh = capture("snap-2.jpg", 1_000L, now)
        CameraCapture.sweep(dir, now)
        assertTrue(fresh.exists(), "a recent capture must survive the sweep")
    }

    @Test
    fun `the sweep touches nothing it did not write`() {
        // Same cache directory could gain a neighbour; deleting by age
        // alone would eat it.
        val now = 1_000_000_000L
        val stranger = capture("something-else.jpg", CameraCapture.MAX_AGE_MS * 5, now)
        CameraCapture.sweep(dir, now)
        assertTrue(stranger.exists(), "the sweep must only claim its own prefix")
    }

    @Test
    fun `a missing directory is not an error`() {
        // First run on a fresh install: nextFile mkdirs, but sweep must
        // survive being handed a directory that is not there yet.
        CameraCapture.sweep(File(dir, "nope"), 0L)
    }
}
