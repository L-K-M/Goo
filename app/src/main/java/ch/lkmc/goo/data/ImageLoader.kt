package ch.lkmc.goo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Brings a picked photo into an editing session.
 *
 * The picked URI is copied into app-private storage first ([importImage]):
 * Photo Picker grants are transient (gone after process death), and both
 * the preview decode now and the full-resolution export decode later need
 * stable access to identical bytes. The session file is the source of
 * truth for pixels; EXIF orientation is applied at decode so everything
 * downstream sees an upright image.
 */
class ImageLoader(private val context: Context) {

    /** Copy [uri]'s bytes to a private session file and return it. */
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "sessions").apply { mkdirs() }
        val file = File(dir, "session-${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException("cannot open $uri")
        file
    }

    /**
     * Decode [file] at preview scale: subsampled near [maxDimension] on
     * the long side (never below it, then scaled exactly to it), EXIF
     * orientation applied, ARGB_8888 (GL upload requires a software
     * bitmap — never Bitmap.Config.HARDWARE here).
     */
    suspend fun decodePreview(file: File, maxDimension: Int = PREVIEW_MAX_DIM): Bitmap =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalArgumentException("not a decodable image: ${file.name}")
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = SampleSizeCalculator.calculate(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.path, options)
                ?: throw IllegalArgumentException("decode failed: ${file.name}")

            val upright = applyExifOrientation(decoded, file)
            scaleLongSideTo(upright, maxDimension)
        }

    private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleLongSideTo(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val long = maxOf(bitmap.width, bitmap.height)
        if (long <= maxDimension) return bitmap
        val factor = maxDimension.toFloat() / long
        val scaled = bitmap.scale(
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    companion object {
        /** Preview long-side target (PLAN.md §4.1's edit-small resolution). */
        const val PREVIEW_MAX_DIM = 2048
    }
}
