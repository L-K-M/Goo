package ch.lkmc.goo.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShareCacheTest {

    @Test
    fun `destination keeps recent predecessors and removes expired files`() {
        withDirectory { dir ->
            val now = 1_000_000_000L
            val recent = file(dir, "recent.jpg", now - DAY_MS)
            val expired = file(dir, "expired.jpg", now - 8 * DAY_MS)

            val destination = ShareCache.destination(dir, "recent.jpg", now)

            assertTrue(recent.exists())
            assertFalse(expired.exists())
            assertNotEquals(recent.name, destination.name)
            assertTrue(destination.name.endsWith(".jpg"))
        }
    }

    @Test
    fun `sweep retains only the newest bounded set`() {
        withDirectory { dir ->
            val now = 1_000_000_000L
            repeat(20) { index -> file(dir, "$index.png", now - index) }

            ShareCache.sweep(dir, now)

            assertEquals(15, dir.listFiles().orEmpty().size)
            assertTrue(File(dir, "0.png").exists())
            assertFalse(File(dir, "19.png").exists())
        }
    }

    private fun file(directory: File, name: String, modified: Long): File =
        File(directory, name).apply {
            writeText("share")
            assertTrue(setLastModified(modified))
        }

    private inline fun withDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("goo-share-cache").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
