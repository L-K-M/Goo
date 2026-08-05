package ch.lkmc.goo.data

import java.io.File
import java.util.UUID

/** Bounded retention for FileProvider shares that receivers may open later. */
internal object ShareCache {

    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_EXISTING_FILES = 15
    private const val MAX_EXISTING_BYTES = 256L * 1024 * 1024

    fun destination(directory: File, requestedName: String, now: Long = System.currentTimeMillis()): File {
        check(directory.isDirectory || directory.mkdirs()) { "could not create share cache" }
        sweep(directory, now)

        val dot = requestedName.lastIndexOf('.')
        val suffix = UUID.randomUUID().toString().take(8)
        val uniqueName = if (dot > 0) {
            "${requestedName.substring(0, dot)}-$suffix${requestedName.substring(dot)}"
        } else {
            "$requestedName-$suffix"
        }
        return File(directory, uniqueName)
    }

    internal fun sweep(directory: File, now: Long) {
        val recent = directory.listFiles()
            .orEmpty()
            .filter { file ->
                val expired = now - file.lastModified() > MAX_AGE_MS
                if (expired) file.delete()
                !expired && file.exists()
            }
            .sortedByDescending(File::lastModified)

        var retainedBytes = 0L
        recent.forEachIndexed { index, file ->
            val length = file.length()
            // Always retain the newest file even if it alone exceeds the
            // byte budget; it is the one most likely to have an active grant.
            val overCount = index >= MAX_EXISTING_FILES
            val overBytes = index > 0 && retainedBytes + length > MAX_EXISTING_BYTES
            if (overCount || overBytes) {
                file.delete()
            } else {
                retainedBytes += length
            }
        }
    }
}
