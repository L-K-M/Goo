package ch.lkmc.goo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import ch.lkmc.goo.engine.core.CoverCrop
import ch.lkmc.goo.engine.core.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

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

    /**
     * Copy [uri]'s bytes to a private session file and return it.
     *
     * Bundled samples arrive as `file:///android_asset/<path>` (the WebView
     * convention) and stream straight from the APK; everything else goes
     * through the content resolver (Photo Picker grants).
     */
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        copyToSession { output ->
            openStream(uri)?.use { input -> input.copyTo(output) }
                ?: throw FileNotFoundException("cannot open $uri")
        }
    }

    /**
     * Copy an app-private [file] into a session file — reopening a saved
     * project (ProjectStore).
     *
     * The copy is deliberate: the editor treats its session files as
     * scratch it owns (a Fusion swap deletes the file it replaces, a
     * sweep collects what no session claims), and a saved project must
     * only ever change when the user saves it.
     */
    suspend fun importFile(file: File): File = withContext(Dispatchers.IO) {
        copyToSession { output -> file.inputStream().use { it.copyTo(output) } }
    }

    /**
     * Run [write] into a temp file and rename it into place, so a
     * mid-copy failure can never leave a truncated file that looks like
     * a session.
     */
    private inline fun copyToSession(write: (java.io.OutputStream) -> Unit): File {
        val dir = File(context.cacheDir, "sessions").apply { mkdirs() }
        val file = File(dir, "session-${UUID.randomUUID()}.img")
        val tmp = File(dir, "${file.name}.tmp")
        try {
            tmp.outputStream().use(write)
            check(tmp.renameTo(file)) { "could not finalize session file" }
            return file
        } finally {
            tmp.delete()
        }
    }

    private fun openStream(uri: Uri) =
        uri.path?.takeIf { uri.scheme == "file" && it.startsWith(ASSET_PREFIX) }
            ?.let { context.assets.open(it.removePrefix(ASSET_PREFIX)) }
            ?: context.contentResolver.openInputStream(uri)

    /**
     * Decode [file] at preview scale: subsampled near [maxDimension] on
     * the long side (never below it, then scaled exactly to it), EXIF
     * orientation applied, ARGB_8888 (GL upload requires a software
     * bitmap — never Bitmap.Config.HARDWARE here).
     *
     * [crop] (normalized, upright space) is cut out after orientation;
     * the subsample target is raised so the CROP lands near
     * [maxDimension] — capped at [CROP_DECODE_CAP] so a sliver crop of
     * a huge photo can't force an unbounded full-frame decode.
     */
    suspend fun decodePreview(
        file: File,
        maxDimension: Int = PREVIEW_MAX_DIM,
        crop: CropRect? = null,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalArgumentException("not a decodable image: ${file.name}")
            }

            // The crop rect lives in UPRIGHT space; raw bounds are
            // pre-rotation. A transposing EXIF orientation swaps which
            // raw axis each crop fraction applies to — feed the target
            // computation upright dims so the math never mixes spaces.
            val rect = crop?.takeIf { !it.isFullFrame }
            val target = if (rect == null) maxDimension else {
                val transposed = isExifTransposed(file)
                cropDecodeTarget(
                    uprightWidth = if (transposed) bounds.outHeight else bounds.outWidth,
                    uprightHeight = if (transposed) bounds.outWidth else bounds.outHeight,
                    crop = rect,
                    maxDimension = maxDimension,
                )
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = if (rect == null) {
                    SampleSizeCalculator.calculate(bounds.outWidth, bounds.outHeight, target)
                } else {
                    cappedSampleSize(
                        bounds.outWidth, bounds.outHeight, target,
                        cap = maxOf(CROP_DECODE_CAP, maxDimension),
                    )
                }
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.path, options)
                ?: throw IllegalArgumentException("decode failed: ${file.name}")

            val upright = applyExifOrientation(decoded, file)
            val framed = cropTo(upright, rect)
            scaleLongSideTo(framed, maxDimension)
        }

    /** Cut [crop] out of [bitmap], recycling the source when distinct. */
    private fun cropTo(bitmap: Bitmap, crop: CropRect?): Bitmap {
        if (crop == null) return bitmap
        val r = crop.pixelRect(bitmap.width, bitmap.height)
        val out = Bitmap.createBitmap(bitmap, r[0], r[1], r[2], r[3])
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    private fun isExifTransposed(file: File): Boolean =
        when (ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
            -> true
            else -> false
        }

    /**
     * Decode [file] cover-cropped and scaled to exactly [targetWidth] ×
     * [targetHeight] — Fusion's photo B path: B must live in A's UV
     * space texel-for-texel (preview and export both call this with
     * their respective A dimensions). EXIF-upright, ARGB_8888.
     */
    suspend fun decodeCover(file: File, targetWidth: Int, targetHeight: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val upright = decodePreview(file, maxDimension = maxOf(targetWidth, targetHeight))
            var cropped: Bitmap? = null
            try {
                val crop = CoverCrop.rect(
                    upright.width, upright.height,
                    targetWidth.toFloat() / targetHeight,
                )
                val c = Bitmap.createBitmap(upright, crop[0], crop[1], crop[2], crop[3])
                cropped = c
                val scaled = c.scale(targetWidth, targetHeight)
                // createBitmap/scale may return their input; never recycle a
                // bitmap that is also the result. When the crop is full-frame
                // (c === upright) but the scale still resizes, upright is
                // NOT the result and must be recycled — checking it against
                // c here would skip exactly that case and leak it.
                if (c !== upright && c !== scaled) c.recycle()
                if (upright !== scaled) upright.recycle()
                scaled
            } catch (e: Exception) {
                // Eager-free on the failure path too (an OOM mid-scale is
                // exactly when a stranded multi-megapixel bitmap hurts).
                cropped?.takeIf { it !== upright }?.recycle()
                upright.recycle()
                throw e
            }
        }

    /**
     * Delete session files except [keep] (REVIEW.md G-1): the previous
     * session's copies are garbage the moment a new image is imported,
     * and relying on the OS cache eviction alone lets tens of full-size
     * copies pile up first. Fusion sessions keep two files (A and B).
     */
    suspend fun sweepSessions(keep: Set<File>) = withContext(Dispatchers.IO) {
        val keepPaths = keep.map { it.path }.toSet()
        File(context.cacheDir, "sessions").listFiles()
            ?.filter { it.path !in keepPaths }
            ?.forEach { it.delete() }
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

        /** file:// URI prefix meaning "stream from the APK's assets". */
        const val ASSET_PREFIX = "/android_asset/"

        /**
         * Ceiling for the crop-raised decode: a MIN_FRACTION sliver
         * would otherwise ask for 10× the target (40960 px on an
         * export — gigabytes). Enforced on the DECODED long side by
         * [cappedSampleSize] — the request target alone can't bound
         * anything, because subsampling is power-of-two and never
         * undershoots, landing decodes anywhere in [target, 2·target).
         * With enforcement, the crop path's transient full-frame
         * bitmap stays ≤ 64 MB ARGB at the 4096 default. Never lowers
         * an uncropped-size request: the effective cap is
         * max(this, maxDimension) — export caps can legitimately
         * exceed 4096 on big-GPU devices.
         */
        const val CROP_DECODE_CAP = 4096

        /**
         * Long-side decode target such that [crop] of an
         * [uprightWidth] × [uprightHeight] image lands near
         * [maxDimension] — clamped to [maxDimension, max(cap, maxDimension)].
         * Pure math, unit-tested.
         */
        fun cropDecodeTarget(
            uprightWidth: Int,
            uprightHeight: Int,
            crop: CropRect,
            maxDimension: Int,
        ): Int {
            val croppedLong = maxOf(crop.width * uprightWidth, crop.height * uprightHeight)
            if (croppedLong <= 0f) return maxDimension
            val factor = maxOf(uprightWidth, uprightHeight) / croppedLong
            val cap = maxOf(CROP_DECODE_CAP, maxDimension)
            return (maxDimension * factor).toInt().coerceIn(maxDimension, cap)
        }

        /**
         * Sample size for a cropped decode: the [SampleSizeCalculator]
         * choice for [target], then doubled until the DECODED long side
         * fits [cap]. The calculator never undershoots its target, so a
         * 48 MP photo at target 4096 would otherwise decode FULL FRAME
         * (~183 MB) — the cap must bind the decode, not the request.
         * Undershooting the raised target only costs crop sharpness;
         * blowing the cap costs the process. Long side is
         * transpose-invariant, so raw (pre-EXIF) dims are fine here.
         */
        fun cappedSampleSize(rawWidth: Int, rawHeight: Int, target: Int, cap: Int): Int {
            var sample = SampleSizeCalculator.calculate(rawWidth, rawHeight, target)
            val long = maxOf(rawWidth, rawHeight)
            while (long / sample > cap) sample *= 2
            return sample
        }
    }
}
