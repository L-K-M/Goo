package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieSpecTest {

    @Test
    fun `videoSize fits the cap and stays even`() {
        val (w, h) = MovieSpec.videoSize(4000, 3000)
        assertEquals(1920, w)
        assertEquals(1440, h)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun `videoSize never upscales small images`() {
        val (w, h) = MovieSpec.videoSize(640, 480)
        assertEquals(640, w)
        assertEquals(480, h)
    }

    @Test
    fun `videoSize rounds odd results down to even`() {
        // 1001×601 → scale 1 (under cap) → 1000×600.
        val (w, h) = MovieSpec.videoSize(1001, 601)
        assertEquals(1000, w)
        assertEquals(600, h)
    }

    @Test
    fun `videoSize handles portrait long edges`() {
        val (w, h) = MovieSpec.videoSize(3000, 4000)
        assertEquals(1440, w)
        assertEquals(1920, h)
    }

    @Test
    fun `totalFrames covers each segment at the playback rate`() {
        // 3 keyframes = 2 segments × 1.2s × 30fps = 72, plus closing frame.
        assertEquals(73, MovieSpec.totalFrames(3))
        assertEquals(0, MovieSpec.totalFrames(1))
        assertEquals(0, MovieSpec.totalFrames(0))
    }

    @Test
    fun `positionAt spans the strip exactly`() {
        val total = MovieSpec.totalFrames(3)
        assertEquals(0f, MovieSpec.positionAt(0, total, 3))
        assertEquals(2f, MovieSpec.positionAt(total - 1, total, 3))
        // Monotonic.
        var last = -1f
        for (f in 0 until total) {
            val p = MovieSpec.positionAt(f, total, 3)
            assertTrue(p > last)
            last = p
        }
    }

    @Test
    fun `ptsNanos is monotonic at the frame rate`() {
        assertEquals(0L, MovieSpec.ptsNanos(0))
        assertEquals(1_000_000_000L / 30, MovieSpec.ptsNanos(1))
        assertEquals(1_000_000_000L, MovieSpec.ptsNanos(30))
    }
}
