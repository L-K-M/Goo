package ch.lkmc.goo.data

import android.os.Environment

/** The two ways a GOOvie leaves the app. */
enum class MovieFormat(
    val mimeType: String,
    val extension: String,
    /**
     * GIFs are images to the platform: they belong in Pictures and in the
     * Images MediaStore collection, next to the still exports, not in
     * Movies where no gallery would animate them.
     */
    val isVideo: Boolean,
) {
    MP4("video/mp4", "mp4", isVideo = true),
    GIF("image/gif", "gif", isVideo = false),
    ;

    /** `Movies/` or `Pictures/` — the collection this format lives in. */
    val relativeDirectory: String
        get() = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

    /** goovie_20260805-153012-123.mp4 */
    fun fileName(timestampMillis: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            .format(java.util.Date(timestampMillis))
        return "goovie_$stamp.$extension"
    }
}
