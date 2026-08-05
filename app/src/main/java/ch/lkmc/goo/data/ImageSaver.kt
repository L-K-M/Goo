package ch.lkmc.goo.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The Out room's disk half: writes finished bitmaps where the platform
 * wants them (PLAN.md §6).
 *
 * - API 29+: MediaStore contribution with IS_PENDING — lands in
 *   Pictures/Meltorama, visible in every gallery, zero permissions.
 * - API 26–28: shared-collection inserts would need
 *   WRITE_EXTERNAL_STORAGE, which the no-permissions rule forbids, so the
 *   file goes to app-owned external storage and the share sheet is the
 *   hand-off to the gallery of the user's choice.
 */
class ImageSaver(private val context: Context) {

    sealed interface SaveResult {
        /** Saved into the shared gallery (API 29+). */
        data class Gallery(val uri: Uri) : SaveResult

        /** Saved to app-owned storage (API 26–28 legacy path). */
        data class AppStorage(val file: File) : SaveResult
    }

    suspend fun save(bitmap: Bitmap, format: ExportFormat, quality: Int): SaveResult =
        withContext(Dispatchers.IO) {
            val name = format.fileName(System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(bitmap, format, quality, name)
            } else {
                saveToAppStorage(bitmap, format, quality, name)
            }
        }

    /**
     * Write a share-ready copy into the FileProvider-served cache dir and
     * return its content:// uri.
     */
    suspend fun writeShareCache(bitmap: Bitmap, format: ExportFormat, quality: Int): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share")
            val file = ShareCache.destination(
                dir,
                format.fileName(System.currentTimeMillis()),
            )
            file.outputStream().use { out ->
                check(bitmap.compress(format.compressFormat, quality, out)) { "compress failed" }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    private fun saveToMediaStore(
        bitmap: Bitmap,
        format: ExportFormat,
        quality: Int,
        name: String,
    ): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Meltorama")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                check(bitmap.compress(format.compressFormat, quality, out)) { "compress failed" }
            } ?: throw IOException("cannot open $uri for writing")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("MediaStore finalization failed")
            }
            return SaveResult.Gallery(uri)
        } catch (e: Exception) {
            // Never leave a pending ghost row behind.
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppStorage(
        bitmap: Bitmap,
        format: ExportFormat,
        quality: Int,
        name: String,
    ): SaveResult {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "pictures").apply { mkdirs() }
        // Legacy storage does not uniquify DISPLAY_NAME like MediaStore. A
        // short random suffix prevents concurrent/same-clock exports from
        // replacing an earlier result.
        val dot = name.lastIndexOf('.')
        val uniqueName = if (dot > 0) {
            "${name.substring(0, dot)}-${UUID.randomUUID().toString().take(8)}${name.substring(dot)}"
        } else {
            "$name-${UUID.randomUUID().toString().take(8)}"
        }
        val file = File(dir, uniqueName)
        val temporary = File(dir, ".$uniqueName.tmp")
        try {
            temporary.outputStream().use { out ->
                check(bitmap.compress(format.compressFormat, quality, out)) { "compress failed" }
            }
            if (!temporary.renameTo(file)) {
                throw IOException("could not finalize image")
            }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
        return SaveResult.AppStorage(file)
    }
}
