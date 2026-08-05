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

    @Test
    fun `speed scales the frame count, not the frame rate`() {
        // Same strip, half the frames at 2×: the encoder clock never moves.
        assertEquals(73, MovieSpec.totalFrames(3, MovieSpeed.NORMAL.multiplier))
        assertEquals(37, MovieSpec.totalFrames(3, MovieSpeed.DOUBLE.multiplier))
        assertEquals(19, MovieSpec.totalFrames(3, MovieSpeed.QUADRUPLE.multiplier))
        assertEquals(145, MovieSpec.totalFrames(3, MovieSpeed.HALF.multiplier))
    }

    @Test
    fun `duration follows speed`() {
        val normal = MovieSpec.durationSeconds(3, MovieSpeed.NORMAL.multiplier)
        val double = MovieSpec.durationSeconds(3, MovieSpeed.DOUBLE.multiplier)

        assertEquals(2.4f, normal, 0.05f)
        assertEquals(1.2f, double, 0.05f)
        assertEquals(0f, MovieSpec.durationSeconds(1))
    }

    @Test
    fun `a two-frame movie is the floor at any speed`() {
        // Even a degenerate 2-keyframe strip at 4× must still have a
        // segment to walk — positionAt divides by totalFrames - 1.
        for (speed in MovieSpeed.entries) {
            assertTrue(MovieSpec.totalFrames(2, speed.multiplier) >= 2)
            assertTrue(MovieSpec.totalFrames(2, speed.multiplier, MovieSpec.GIF_FPS_LADDER.last()) >= 2)
        }
    }

    @Test
    fun `gif sizing caps far below the video cap`() {
        val (w, h) = MovieSpec.gifSize(4000, 3000)
        assertEquals(480, w)
        assertEquals(360, h)
        // Never upscales, same as the video path.
        assertEquals(Pair(200, 100), MovieSpec.gifSize(200, 100))
    }

    @Test
    fun `gif frame rate drops rather than dropping the strip`() {
        // A short strip gets the full rate…
        assertEquals(20, MovieSpec.gifFps(3))
        // …and a long one slows down instead of being truncated.
        val fps = MovieSpec.gifFps(64)
        assertTrue(fps < 20, "expected a reduced rate, got $fps")
        assertTrue(MovieSpec.totalFrames(64, 1f, fps) <= MovieSpec.GIF_MAX_FRAMES)
        // Whatever the rate, the GIF is as long as the MP4 would be.
        assertEquals(
            MovieSpec.durationSeconds(64, 1f, MovieSpec.FPS),
            MovieSpec.durationSeconds(64, 1f, fps),
            0.5f,
        )
    }

    @Test
    fun `gif delays are whole centiseconds a viewer will honour`() {
        assertEquals(5, MovieSpec.gifDelayCentis(20))
        assertEquals(10, MovieSpec.gifDelayCentis(10))
        for (fps in MovieSpec.GIF_FPS_LADDER) {
            assertEquals(100, fps * MovieSpec.gifDelayCentis(fps), "fps $fps must divide 100")
            assertTrue(MovieSpec.gifDelayCentis(fps) >= 2)
        }
    }
}
