package ch.lkmc.goo.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Somewhere for the camera to put the photo it just took.
 *
 * ### Why this needs no permission
 *
 * The capture goes through `ACTION_IMAGE_CAPTURE`, which hands the job
 * to whatever camera app the user already has. That app holds the camera
 * permission; Meltorama never touches the camera itself and so never
 * asks for anything. The app's promise — "no permissions, no network,
 * nothing leaves your device" — survives intact, and the manifest still
 * declares zero permissions.
 *
 * **That is conditional on not declaring one.** If Meltorama ever adds
 * `android.permission.CAMERA` to the manifest, Android starts REQUIRING
 * it to be granted before `ACTION_IMAGE_CAPTURE` will run, and a feature
 * that needed no permission suddenly needs one. Declaring the permission
 * would make this strictly worse, which is the opposite of what anyone
 * would expect from adding it.
 *
 * ### Where the file goes
 *
 * The app's own cache, served to the camera app through the existing
 * `FileProvider`. Not `MediaStore`, deliberately: writing to the shared
 * gallery would put a photo in the user's camera roll that they did not
 * ask to keep, from a tap they meant as "let me goo this". If they want
 * it in the gallery, Out already does that on purpose.
 *
 * The file is therefore scratch. The editor copies the bytes into its
 * own session file the moment the image opens, so nothing downstream
 * depends on this URI surviving.
 */
object CameraCapture {

    /** Cache subdirectory, mirrored in `xml/file_paths.xml`. */
    const val DIR = "capture"

    private const val PREFIX = "snap-"
    private const val SUFFIX = ".jpg"

    /**
     * Whether any app on this device can answer `ACTION_IMAGE_CAPTURE`.
     *
     * Devices without a camera app are rare but real — some tablets,
     * most emulators — and a button that throws
     * `ActivityNotFoundException` when tapped is worse than one that was
     * never offered. Resolving the intent needs the `<queries>` entry in
     * the manifest on API 30+; without it this would answer "no" on
     * every modern device and the button would never appear at all.
     */
    fun isAvailable(context: Context): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return intent.resolveActivity(context.packageManager) != null ||
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .isNotEmpty()
    }

    /**
     * A fresh file for the next capture, with the stale ones swept.
     *
     * Cleanup happens HERE rather than after each capture because the
     * capture that matters most is the one that never came back: a
     * cancelled shot, or a camera app killed mid-photo, leaves a
     * zero-byte file nobody will ever delete on the way out. Sweeping on
     * the way IN catches those, and it also means a crash cannot leave
     * the cache growing without bound.
     *
     * [now] is a parameter so the age rule is testable without a clock.
     */
    fun nextFile(context: Context, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        sweep(dir, now)
        return File(dir, "$PREFIX$now$SUFFIX")
    }

    /** The content URI the camera app writes through. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    /**
     * Delete captures older than [MAX_AGE_MS].
     *
     * Age rather than count: a capture is scratch the moment the editor
     * has copied it, so anything from a previous session is certainly
     * dead. Keeping the recent ones matters because this runs *before*
     * the shot it is making room for, and on a slow device the editor
     * may still be reading the previous one.
     */
    internal fun sweep(dir: File, now: Long) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.startsWith(PREFIX)) continue
            if (now - f.lastModified() > MAX_AGE_MS) f.delete()
        }
    }

    /** An hour: long past any live capture, short of hoarding. */
    const val MAX_AGE_MS = 60L * 60L * 1000L
}
