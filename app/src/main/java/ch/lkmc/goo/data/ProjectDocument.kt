package ch.lkmc.goo.data

import ch.lkmc.goo.engine.core.CropRect
import ch.lkmc.goo.engine.core.Easing
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.GlobalWobble
import ch.lkmc.goo.engine.core.StrokeLogSnapshot
import kotlinx.serialization.Serializable

/**
 * A saved Meltorama project: everything the Goo room holds that a
 * re-decode of the source photo cannot rebuild (ANALYSIS SOL-34).
 *
 * The pixels are NOT in here — the source (and Fusion's photo B) sit
 * beside this file as their original bytes, exactly as imported, so the
 * crop stays a rect applied at decode and reopening a project costs no
 * generation of re-encoding. What this file stores is the document: the
 * stroke log as a normalized revision table, the lever values, the crop,
 * and the GOOvie pins as revision ids.
 *
 * Every field has a default so an older file (or a newer one written by a
 * later version, read after a downgrade) loads with the terms it does
 * carry rather than failing outright; [schema] exists for the day that
 * stops being enough.
 */
@Serializable
data class ProjectDocument(
    val schema: Int = SCHEMA,
    /**
     * Wall-clock of the save that wrote this file. The In room sorts on
     * the file's own mtime instead (listing must not parse every
     * document); this is the same instant recorded INSIDE the document,
     * so a folder that arrives without its timestamps — a manual copy, a
     * restore — still knows how old it is.
     */
    val updatedAtMillis: Long = 0L,
    /** File name (never a path) of the source bytes in the project folder. */
    val source: String = SOURCE_FILE,
    /** Fusion's photo B, same folder, or null when there is none. */
    val fusion: String? = null,
    val crop: CropRecord? = null,
    val globals: GlobalParams = GlobalParams(),
    /**
     * The modulation rig (proposal 0009). Defaulted, so a project written
     * before the Wobbulator existed loads perfectly still.
     */
    val wobble: GlobalWobble = GlobalWobble(),
    val log: StrokeLogSnapshot = StrokeLogSnapshot(),
    /** GOOvie strip in playback order; ids point into [log]'s table. */
    val keyframes: List<KeyframeRecord> = emptyList(),
) {
    /**
     * The crop as the engine's own type, or null for the full frame.
     * Routed through [CropRect.fromArray] so a hand-edited or truncated
     * file gets the same structural validation a restored session does —
     * a malformed rect uncrops, it never produces a degenerate decode.
     */
    fun cropRect(): CropRect? = crop?.let {
        CropRect.fromArray(floatArrayOf(it.left, it.top, it.right, it.bottom))
    }

    /**
     * True when [source]/[fusion] name files inside the project folder.
     * Nothing in the app writes anything else, so this is purely a guard
     * against a file that arrived some other way (a restored backup, a
     * rooted device, a bug): a name carrying a separator would let a
     * project point at bytes outside its own folder.
     */
    fun namesAreLocal(): Boolean = isLocalName(source) && (fusion?.let(::isLocalName) ?: true)

    companion object {
        /** Bump when a change stops being backward-compatible. */
        const val SCHEMA = 1

        const val SOURCE_FILE = "source.img"
        const val FUSION_FILE = "fusion.img"

        private fun isLocalName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\')
    }
}

/** [CropRect] on the wire — named fields, so the file stays readable. */
@Serializable
data class CropRecord(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        fun of(rect: CropRect) = CropRecord(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/**
 * One GOOvie keyframe on the wire. A pin is a (revision, levers) pair, and
 * the revision travels as its id — the revision itself lives once in the
 * log's table however many pins share it.
 */
@Serializable
data class KeyframeRecord(
    val revision: Long,
    val globals: GlobalParams = GlobalParams(),
    /**
     * How the segment leaving this pin moves (proposal 0011). Defaulted
     * so projects written before curves existed load as what they were
     * made with — every GOOvie punched until now was linear.
     */
    val easing: Easing = Easing.DEFAULT,
)
