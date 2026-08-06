package ch.lkmc.goo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shelf's ceiling deletes the user's work, so its rules get pinned
 * here rather than trusted: what goes, in what order, and above all what
 * can never go.
 */
class ProjectShelfTest {

    private fun entry(id: String, age: Long, mb: Long = 1L) =
        ProjectShelf.Entry(id, updatedAtMillis = age, bytes = mb * 1024 * 1024)

    @Test
    fun `a shelf inside both limits loses nothing`() {
        val entries = (1..5).map { entry("p$it", it.toLong()) }
        assertTrue(ProjectShelf.overflow(entries, keep = "p5").isEmpty())
    }

    @Test
    fun `over the count, the oldest go first`() {
        val entries = (1..6).map { entry("p$it", it.toLong()) }
        assertEquals(
            listOf("p2", "p1"),
            ProjectShelf.overflow(entries, keep = "p6", maxProjects = 4),
        )
    }

    @Test
    fun `over the byte budget, the oldest go first`() {
        // Four 10 MB projects against a 25 MB ceiling: the newest two fit.
        val entries = (1..4).map { entry("p$it", it.toLong(), mb = 10) }
        assertEquals(
            listOf("p2", "p1"),
            ProjectShelf.overflow(
                entries,
                keep = "p4",
                maxBytes = 25L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun `the project just saved is never the casualty`() {
        // The one being written is the OLDEST here (an autosave of a
        // project resumed days ago) and alone blows the whole budget. It
        // still stays: a save must never delete its own work.
        val entries = listOf(
            entry("old-but-saving", age = 1, mb = 500),
            entry("newer", age = 9, mb = 1),
        )
        val doomed = ProjectShelf.overflow(entries, keep = "old-but-saving")
        assertEquals(listOf("newer"), doomed)
    }

    @Test
    fun `a save with nothing kept yet still bounds the shelf`() {
        // keep = null is the pre-save sweep case: no project is immune.
        val entries = (1..3).map { entry("p$it", it.toLong()) }
        assertEquals(listOf("p1"), ProjectShelf.overflow(entries, maxProjects = 2))
    }

    @Test
    fun `projects saved in the same millisecond order deterministically`() {
        val entries = listOf(entry("b", 7), entry("a", 7), entry("c", 9))
        assertEquals(
            ProjectShelf.overflow(entries, keep = "c", maxProjects = 2),
            ProjectShelf.overflow(entries.reversed(), keep = "c", maxProjects = 2),
        )
    }

    @Test
    fun `the shipped limits leave room for a normal shelf of photos`() {
        // Twenty 4 MB JPEGs — an ordinary phone's worth of sessions —
        // must fit, or the count cap would never be the binding one.
        val entries = (1..ProjectShelf.MAX_PROJECTS).map { entry("p$it", it.toLong(), mb = 4) }
        assertTrue(ProjectShelf.overflow(entries, keep = "p1").isEmpty())
    }
}
