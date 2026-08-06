package ch.lkmc.goo.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * GOOvie files to disk — ImageSaver's twin for the animation formats
 * (PLAN.md §6): MediaStore + IS_PENDING on API 29+, app-owned external
 * storage on 26–28 (shared-collection writes would need the permission
 * the no-permissions rule forbids), share hand-off via the same
 * FileProvider cache the image path uses.
 *
 * Which collection depends on the format, not on this class: an MP4 is a
 * video in Movies, a GIF is an image in Pictures ([MovieFormat]).
 */
class MovieSaver(private val context: Context) {

    sealed interface SaveResult {
        data class Gallery(val uri: Uri) : SaveResult
        data class AppStorage(val file: File) : SaveResult
    }

    /** Scratch file the encoder writes into; caller owns deletion. */
    suspend fun createWorkFile(format: MovieFormat): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "movies").apply { mkdirs() }
        // One movie at a time; a crashed predecessor must not accumulate.
        dir.listFiles()?.forEach { it.delete() }
        File(dir, format.fileName(System.currentTimeMillis()))
    }

    suspend fun save(movie: File, format: MovieFormat): SaveResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(movie, format)
        } else {
            saveToAppStorage(movie, format)
        }
    }

    /** Share-ready copy in the FileProvider cache; content:// uri. */
    suspend fun writeShareCache(movie: File): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share")
        val file = ShareCache.destination(dir, movie.name)
        movie.copyTo(file)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun saveToMediaStore(movie: File, format: MovieFormat): SaveResult {
        val resolver = context.contentResolver
        val collection = if (format.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        // The column names are identical strings across Video/Images
        // (MediaColumns), so one ContentValues serves both collections.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, movie.name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${format.relativeDirectory}/Meltorama")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                movie.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("cannot open $uri for writing")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("MediaStore finalization failed")
            }
            return SaveResult.Gallery(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppStorage(movie: File, format: MovieFormat): SaveResult {
        val dir = context.getExternalFilesDir(format.relativeDirectory)
            // The internal fallback follows the format too (GLM on PR #42):
            // a GIF that lands here is still an image, and a debugger
            // hunting for one should not have to know about this branch.
            ?: File(context.filesDir, format.relativeDirectory).apply { mkdirs() }
        val file = File(dir, movie.name)
        try {
            movie.copyTo(file, overwrite = true)
        } catch (e: Exception) {
            // Same discipline as the MediaStore path: never leave a
            // truncated file behind (disk-full mid-copy).
            file.delete()
            throw e
        }
        return SaveResult.AppStorage(file)
    }
}
