package ch.lkmc.goo.data

/**
 * How much saved goo the app is willing to keep, and what goes when that
 * ceiling is reached.
 *
 * The shelf is a RECENTS list, not an archive. Every project keeps a
 * full-size copy of its source photo, and the editor now autosaves when
 * it goes to the background — so without a ceiling, a few weeks of casual
 * use quietly turns into a gigabyte of app-private storage that nothing
 * but a manual sweep of the In room ever reclaims.
 *
 * The policy is deliberately the one users already understand from every
 * recent-files list: newest first, oldest out. It is stated in the In
 * room rather than left as a surprise ([ProjectShelf.MAX_PROJECTS] is what
 * that line names), and the project being saved right now is never a
 * candidate — a save must never be the thing that deletes its own work.
 *
 * Pure and separate from [ProjectStore] so the decision is unit-tested
 * without a filesystem (AGENTS.md: decision logic stays testable).
 */
object ProjectShelf {

    /** How many projects the In room keeps. */
    const val MAX_PROJECTS = 20

    /**
     * How much disk the shelf may hold, sources included. 256 MiB is
     * roughly sixty 12-megapixel JPEGs — far above the count cap for
     * ordinary photos, so this only binds for someone shooting 50 MP
     * PNGs, which is exactly when a count cap alone would be too loose.
     */
    const val MAX_BYTES = 256L * 1024 * 1024

    /** One project as the shelf sees it: how old, and how big. */
    data class Entry(val id: String, val updatedAtMillis: Long, val bytes: Long)

    /**
     * Which of [entries] must go, newest-kept-first.
     *
     * [keep] (the project just saved) is retained whatever it costs: a
     * single photo bigger than the whole byte budget still saves, it just
     * empties the shelf behind it. Ties on timestamp break by id so the
     * result is deterministic — two projects can share a millisecond.
     */
    fun overflow(
        entries: List<Entry>,
        keep: String? = null,
        maxProjects: Int = MAX_PROJECTS,
        maxBytes: Long = MAX_BYTES,
    ): List<String> {
        val ordered = entries
            .filterNot { it.id == keep }
            .sortedWith(compareByDescending<Entry> { it.updatedAtMillis }.thenBy { it.id })
        val kept = entries.firstOrNull { it.id == keep }
        var count = if (kept != null) 1 else 0
        var bytes = kept?.bytes ?: 0L
        val doomed = mutableListOf<String>()
        for (entry in ordered) {
            count++
            bytes += entry.bytes
            if (count > maxProjects || bytes > maxBytes) doomed += entry.id
        }
        return doomed
    }
}
