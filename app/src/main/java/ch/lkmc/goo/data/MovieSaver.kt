package ch.lkmc.goo.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * GOOvie MP4s to disk — ImageSaver's twin for the Movies collection
 * (PLAN.md §6): MediaStore + IS_PENDING on API 29+, app-owned external
 * storage on 26–28 (shared-collection writes would need the permission
 * the no-permissions rule forbids), share hand-off via the same
 * FileProvider cache the image path uses.
 */
class MovieSaver(private val context: Context) {

    sealed interface SaveResult {
        data class Gallery(val uri: Uri) : SaveResult
        data class AppStorage(val file: File) : SaveResult
    }

    /** Scratch file the encoder muxes into; caller owns deletion. */
    suspend fun createWorkFile(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "movies").apply { mkdirs() }
        // One movie at a time; a crashed predecessor must not accumulate.
        dir.listFiles()?.forEach { it.delete() }
        File(dir, fileName(System.currentTimeMillis()))
    }

    suspend fun save(movie: File): SaveResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(movie)
        } else {
            saveToAppStorage(movie)
        }
    }

    /** Share-ready copy in the FileProvider cache; content:// uri. */
    suspend fun writeShareCache(movie: File): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, movie.name)
        movie.copyTo(file, overwrite = true)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun saveToMediaStore(movie: File): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, movie.name)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Goo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                movie.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("cannot open $uri for writing")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("MediaStore finalization failed")
            }
            return SaveResult.Gallery(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppStorage(movie: File): SaveResult {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "movies").apply { mkdirs() }
        val file = File(dir, movie.name)
        try {
            movie.copyTo(file, overwrite = true)
        } catch (e: Exception) {
            // Same discipline as the MediaStore path: never leave a
            // truncated MP4 behind (disk-full mid-copy).
            file.delete()
            throw e
        }
        return SaveResult.AppStorage(file)
    }

    private fun fileName(timestamp: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(timestamp)
        return "goovie_$stamp.mp4"
    }

    companion object {
        const val MIME_MP4 = "video/mp4"
    }
}
