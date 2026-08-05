package ch.lkmc.goo.data

import android.graphics.Bitmap

/** The two ways a goo leaves the app. */
enum class ExportFormat(
    val mimeType: String,
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
    val supportsQuality: Boolean,
) {
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, supportsQuality = true),
    PNG("image/png", "png", Bitmap.CompressFormat.PNG, supportsQuality = false),
    ;

    /** goo-20260805-153012-123.jpg; legacy storage adds a UUID suffix. */
    fun fileName(timestampMillis: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            .format(java.util.Date(timestampMillis))
        return "goo-$stamp.$extension"
    }
}
