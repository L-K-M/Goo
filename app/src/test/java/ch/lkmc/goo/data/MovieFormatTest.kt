package ch.lkmc.goo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MovieFormatTest {

    @Test
    fun `file names carry the format and distinguish exports within one second`() {
        val first = MovieFormat.GIF.fileName(1_754_409_012_100L)
        val second = MovieFormat.GIF.fileName(1_754_409_012_101L)

        assertNotEquals(first, second)
        assertTrue(first.startsWith("goovie_"))
        assertTrue(first.endsWith(".gif"))
        assertTrue(MovieFormat.MP4.fileName(0L).endsWith(".mp4"))
    }

    @Test
    fun `a GIF is an image, an MP4 is a video`() {
        // Which MediaStore collection the saver writes into hangs off this.
        assertTrue(MovieFormat.MP4.isVideo)
        assertEquals("video/mp4", MovieFormat.MP4.mimeType)
        assertTrue(!MovieFormat.GIF.isVideo)
        assertEquals("image/gif", MovieFormat.GIF.mimeType)
    }
}
