package ch.lkmc.goo.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ch.lkmc.goo.engine.core.BrushTool

class DockStateTest {

    @Test
    fun `goovie beats levers, levers beats brush`() {
        assertEquals(EditorPanel.GOOVIE, resolvePanel(goovieMode = true, showLevers = true))
        assertEquals(EditorPanel.GOOVIE, resolvePanel(goovieMode = true, showLevers = false))
        assertEquals(EditorPanel.LEVERS, resolvePanel(goovieMode = false, showLevers = true))
        assertEquals(EditorPanel.BRUSH, resolvePanel(goovieMode = false, showLevers = false))
    }

    @Test
    fun `every tool lands in exactly one family`() {
        val families = BrushTool.entries.groupBy { it.family() }
        assertEquals(BrushTool.entries.size, families.values.sumOf { it.size })
        // Families follow the stamp behavior, not the marketing name:
        // drag tools are the directional ones, pump tools hold-in-place,
        // Fusion is its own family.
        assertEquals(
            setOf(BrushTool.SMEAR, BrushTool.MOVE, BrushTool.SMUDGE, BrushTool.NUDGE),
            families[ToolFamily.DRAG]?.toSet(),
        )
        assertEquals(
            setOf(BrushTool.GROW, BrushTool.SHRINK, BrushTool.SMOOTH, BrushTool.UNGOO),
            families[ToolFamily.PUMP]?.toSet(),
        )
        assertEquals(listOf(BrushTool.FUSE), families[ToolFamily.FUSE])
    }

    @Test
    fun `dock rows cover the palette in enum order`() {
        val rows = toolDockRows()
        assertEquals(BrushTool.entries, rows.flatten())
        // Drag row first; Fusion shares the pump row, last.
        assertEquals(ToolFamily.DRAG, rows.first().first().family())
        assertEquals(BrushTool.FUSE, rows.last().last())
        assertTrue(rows.size <= 2, "a row per family would waste tray height")
    }
}
