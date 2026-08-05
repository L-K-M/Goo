package ch.lkmc.goo.data

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExportFormatTest {

    @Test
    fun `file names distinguish exports within one second`() {
        val first = ExportFormat.JPEG.fileName(1_754_409_012_100L)
        val second = ExportFormat.JPEG.fileName(1_754_409_012_101L)

        assertNotEquals(first, second)
        assertTrue(first.endsWith(".jpg"))
    }
}
