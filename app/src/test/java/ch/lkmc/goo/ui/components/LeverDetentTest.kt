package ch.lkmc.goo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeverDetentTest {

    @Test
    fun `settle snaps the detent zone to exact zero`() {
        assertEquals(0f, LeverDetent.settle(0.059f))
        assertEquals(0f, LeverDetent.settle(-0.059f))
        assertEquals(0f, LeverDetent.settle(0f))
    }

    @Test
    fun `settle leaves values outside the detent alone`() {
        assertEquals(0.06f, LeverDetent.settle(0.06f))
        assertEquals(-0.5f, LeverDetent.settle(-0.5f))
        assertEquals(1f, LeverDetent.settle(1.7f), "clamps to range")
        assertEquals(-1f, LeverDetent.settle(-2f))
    }

    @Test
    fun `crossedCenter fires on sign changes and landing on zero`() {
        assertTrue(LeverDetent.crossedCenter(-0.1f, 0.1f))
        assertTrue(LeverDetent.crossedCenter(0.1f, -0.1f))
        assertTrue(LeverDetent.crossedCenter(0.1f, 0f))
        assertTrue(LeverDetent.crossedCenter(-0.1f, 0f))
    }

    @Test
    fun `crossedCenter stays quiet otherwise`() {
        assertFalse(LeverDetent.crossedCenter(0.1f, 0.2f))
        assertFalse(LeverDetent.crossedCenter(-0.3f, -0.1f))
        assertFalse(LeverDetent.crossedCenter(0f, 0f), "sitting at zero")
        assertFalse(LeverDetent.crossedCenter(0f, 0.1f), "leaving zero")
    }

    @Test
    fun `dragToDelta maps half a track to one lever unit`() {
        assertEquals(1f, LeverDetent.dragToDelta(300f, 600f))
        assertEquals(-0.5f, LeverDetent.dragToDelta(-150f, 600f))
        assertEquals(0f, LeverDetent.dragToDelta(100f, 0f), "degenerate track")
    }
}
