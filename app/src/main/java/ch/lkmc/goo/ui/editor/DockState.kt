package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.StampMode

/** Which mode the editor's dock shows; GOOVIE follows the ViewModel. */
enum class EditorPanel { BRUSH, LEVERS, GOOVIE }

/**
 * The dock's tabs resolve from two independent flags: goovie mode is
 * ViewModel-owned (it gates strokes and history), the levers toggle is
 * screen-local. Goovie wins — the strip must never leave a half-visible
 * levers rig underneath.
 */
fun resolvePanel(goovieMode: Boolean, showLevers: Boolean): EditorPanel = when {
    goovieMode -> EditorPanel.GOOVIE
    showLevers -> EditorPanel.LEVERS
    else -> EditorPanel.BRUSH
}

/** Brush families: the dock groups the palette by shared stamp behavior. */
enum class ToolFamily { DRAG, PUMP, FUSE }

fun BrushTool.family(): ToolFamily = when (mode) {
    StampMode.DIRECTIONAL -> ToolFamily.DRAG
    StampMode.INFLATE, StampMode.DEFLATE, StampMode.RELAX, StampMode.ERASE -> ToolFamily.PUMP
    StampMode.FUSE -> ToolFamily.FUSE
}

/**
 * Palette rows for the dock: one row per family in enum order, except the
 * FUSE singleton which shares the PUMP row — a row of one bead wastes the
 * tray's scarcest resource (height). Enum-driven, so a new BrushTool
 * lands in its family row with no UI edit.
 */
fun toolDockRows(): List<List<BrushTool>> {
    val grouped = BrushTool.entries.groupBy { it.family() }
    val drag = grouped[ToolFamily.DRAG].orEmpty()
    val pump = grouped[ToolFamily.PUMP].orEmpty()
    val fuse = grouped[ToolFamily.FUSE].orEmpty()
    return listOf(drag, pump + fuse).filter { it.isNotEmpty() }
}
