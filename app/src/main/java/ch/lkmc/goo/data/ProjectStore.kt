package ch.lkmc.goo.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Saved projects on disk (ANALYSIS SOL-34).
 *
 * One folder per project under `filesDir/projects/`, holding the document
 * ([ProjectDocument]) beside the bytes it needs:
 *
 * ```
 * projects/<id>/project.json   the document — written LAST, the commit point
 *               source.img     the photo, exactly as imported
 *               fusion.img     Fusion's photo B (optional)
 *               thumb.jpg      what the In room shows
 * ```
 *
 * `filesDir`, not `cacheDir`: a session copy is scratch the OS may evict
 * at any time, but a saved project is the user's work and has to still be
 * there next week (SOL-20's "durable drafts belong in deliberate private
 * storage"). Still app-private — the app has no storage permission and
 * wants none; finished PICTURES go to the gallery through the Out room,
 * projects stay editable documents of the app.
 *
 * Writes are per-file tmp+rename, with `project.json` renamed last: an
 * interrupted save can leave a stale document or an orphaned temp file,
 * never a document pointing at bytes that were never written. Loads
 * validate rather than trust — a half-written or hand-edited folder
 * reports itself missing instead of taking the In room down.
 */
class ProjectStore(private val context: Context) {

    /** What the In room needs to draw one tile; no document parsing. */
    data class Summary(
        val id: String,
        /** Last save, from the document file's own timestamp. */
        val updatedAtMillis: Long,
        /** The saved preview, or null when a save could not render one. */
        val thumbnail: File?,
        /**
         * Everything this project's folder occupies. Measured on every
         * listing — a handful of stat calls per project, against a screen
         * that already decodes a preview for each one — because the In
         * room shows what the shelf costs, and a stale number is worse
         * than no number when someone is deciding what to delete.
         */
        val bytes: Long,
    )

    /** A project opened for editing: the document plus its own bytes. */
    data class Loaded(
        val id: String,
        val document: ProjectDocument,
        val source: File,
        val fusion: File?,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Saved projects, most recently saved first. */
    suspend fun list(): List<Summary> = withContext(Dispatchers.IO) {
        projectsDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val document = File(dir, DOCUMENT_FILE)
                // A folder mid-save (or mid-delete) has no document yet;
                // it is not a project until the rename lands.
                if (!document.isFile || !File(dir, ProjectDocument.SOURCE_FILE).isFile) {
                    return@mapNotNull null
                }
                Summary(
                    id = dir.name,
                    updatedAtMillis = document.lastModified(),
                    thumbnail = File(dir, THUMBNAIL_FILE).takeIf(File::isFile),
                    bytes = dir.listFiles()?.sumOf(File::length) ?: 0L,
                )
            }
            ?.sortedByDescending { it.updatedAtMillis }
            .orEmpty()
    }

    /** @return the project, or null when [id] names nothing usable. */
    suspend fun load(id: String): Loaded? = withContext(Dispatchers.IO) {
        val dir = projectDir(id) ?: return@withContext null
        val file = File(dir, DOCUMENT_FILE).takeIf(File::isFile) ?: return@withContext null
        val document = try {
            json.decodeFromString<ProjectDocument>(file.readText())
        } catch (e: Exception) {
            // Corrupt beats crashing: the In room drops the tile and the
            // rest of the user's projects still open.
            return@withContext null
        }
        if (!document.namesAreLocal()) return@withContext null
        val source = File(dir, document.source).takeIf(File::isFile) ?: return@withContext null
        Loaded(
            id = id,
            document = document,
            source = source,
            // A missing photo B degrades to no Fusion, exactly like a
            // failed decode does in the editor — it never fails the open.
            fusion = document.fusion?.let { File(dir, it) }?.takeIf(File::isFile),
        )
    }

    /**
     * Write [document] as project [id] (a fresh id when null), copying
     * [source]/[fusion] into the folder and compressing [thumbnail] for
     * the In room.
     *
     * @return the id the project now lives under.
     */
    suspend fun save(
        id: String?,
        document: ProjectDocument,
        source: File,
        fusion: File?,
        thumbnail: Bitmap?,
    ): String = withContext(Dispatchers.IO) {
        val projectId = id?.takeIf(::isValidId) ?: newId()
        val dir = File(projectsDir(), projectId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create the project folder")
        // Leftovers from an interrupted save: never load-visible (they
        // are not the document), but they would waste a photo's worth of
        // storage until the next successful write reused the name.
        dir.listFiles()?.filter { it.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }

        val stamped = document.copy(
            schema = ProjectDocument.SCHEMA,
            updatedAtMillis = System.currentTimeMillis(),
            source = ProjectDocument.SOURCE_FILE,
            fusion = if (fusion != null) ProjectDocument.FUSION_FILE else null,
        )
        // Bytes first, document last: the rename of project.json is what
        // makes all of this a project, so everything it names must
        // already be on disk when it lands.
        copyInto(dir, ProjectDocument.SOURCE_FILE, source)
        if (fusion != null) {
            copyInto(dir, ProjectDocument.FUSION_FILE, fusion)
        } else {
            File(dir, ProjectDocument.FUSION_FILE).delete()
        }
        // A null preview leaves any earlier one in place rather than
        // blanking the tile: the renderer could not answer this time (no
        // surface, or it timed out), and the old picture of the same
        // project still identifies it on the shelf.
        if (thumbnail != null) writeThumbnail(dir, thumbnail)
        writeAtomically(File(dir, DOCUMENT_FILE)) { out ->
            out.write(json.encodeToString(stamped).toByteArray())
        }
        projectId
    }

    /** Delete a project and everything in its folder. Missing = done. */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        deleteBlocking(id)
    }

    /**
     * Delete every saved project. The In room's "throw them all away",
     * which exists because nothing evicts on its own any more: the shelf
     * grows until the user says otherwise, so the user needs a way to say
     * it that is not twenty long-presses.
     */
    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        projectsDir().listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { deleteBlocking(it.name) }
    }

    /**
     * Free space on the volume the projects live on — the other half of
     * the number the In room shows. "148 MB of goo" means nothing on its
     * own; "148 MB of goo, 1.2 GB free" is a decision.
     */
    // Lint prefers StorageManager#getAllocatableBytes, which counts space
    // the system COULD reclaim by clearing caches. That is the right
    // number for an app about to allocate; it is the wrong one to print
    // on a screen, because it is larger than the free space the user sees
    // in Settings and would make the readout look like it is lying.
    @SuppressLint("UsableSpace")
    suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        // usableSpace, not freeSpace: it accounts for the reserve the
        // filesystem keeps back, so it is the number the user can
        // actually spend.
        runCatching { context.filesDir.usableSpace }.getOrDefault(0L)
    }

    private fun deleteBlocking(id: String) {
        val dir = projectDir(id) ?: return
        // The document goes first, so a partial delete (storage full of
        // open handles, a kill mid-loop) can never leave a listable
        // project whose pixels are gone.
        File(dir, DOCUMENT_FILE).delete()
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun projectsDir(): File = File(context.filesDir, PROJECTS_DIR).apply { mkdirs() }

    /** The folder for [id], or null when the id is not one we write. */
    private fun projectDir(id: String): File? =
        if (isValidId(id)) File(projectsDir(), id).takeIf(File::isDirectory) else null

    private fun copyInto(dir: File, name: String, file: File) {
        val destination = File(dir, name)
        // Session files are write-once — ImageLoader writes each one to a
        // temp name under a fresh UUID and renames it into place, and a
        // new photo always means a new file. So a destination that is the
        // same length and no older than its source IS that source, and
        // re-copying it would only burn the megabytes again on every
        // autosave. Anything else copies.
        if (destination.isFile &&
            destination.length() == file.length() &&
            destination.lastModified() >= file.lastModified()
        ) {
            return
        }
        writeAtomically(destination) { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun writeThumbnail(dir: File, bitmap: Bitmap) {
        writeAtomically(File(dir, THUMBNAIL_FILE)) { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)) {
                "could not compress the project preview"
            }
        }
    }

    private fun writeAtomically(destination: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(destination.parentFile, "${destination.name}$TMP_SUFFIX")
        try {
            tmp.outputStream().use(write)
            if (!tmp.renameTo(destination)) throw IOException("could not finalize ${destination.name}")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val PROJECTS_DIR = "projects"
        private const val DOCUMENT_FILE = "project.json"
        private const val THUMBNAIL_FILE = "thumb.jpg"
        private const val TMP_SUFFIX = ".tmp"
        private const val THUMBNAIL_QUALITY = 85

        /**
         * The preview's long side. Big enough for a tile on a tablet at
         * 3× density, small enough that saving one is a rounding error
         * next to copying the source photo.
         */
        const val THUMBNAIL_MAX_DIM = 512

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * Ids are generated here and only here, so this is a guard
         * against ids arriving from somewhere else (a saved-state bundle
         * an attacker can write, a stale navigation argument): anything
         * with a separator or a dot in it could otherwise walk out of the
         * projects folder.
         */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /** Sortable-ish and collision-proof without reading the folder. */
        fun newId(): String =
            "p-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }
}
