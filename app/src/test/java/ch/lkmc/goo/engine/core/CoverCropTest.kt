package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverCropTest {

    @Test
    fun `wider source trims sides symmetrically`() {
        val r = CoverCrop.rect(2000, 1000, targetAspect = 1f)
        assertEquals(500, r[0])
        assertEquals(0, r[1])
        assertEquals(1000, r[2])
        assertEquals(1000, r[3])
    }

    @Test
    fun `taller source trims top and bottom`() {
        val r = CoverCrop.rect(1000, 2000, targetAspect = 1f)
        assertEquals(0, r[0])
        assertEquals(500, r[1])
        assertEquals(1000, r[2])
        assertEquals(1000, r[3])
    }

    @Test
    fun `matching aspect is a full-frame crop`() {
        val r = CoverCrop.rect(1600, 900, targetAspect = 16f / 9f)
        assertEquals(0, r[0])
        assertEquals(0, r[1])
        // Integer truncation may shave a pixel; never exceed the source.
        assertTrue(r[2] in 1599..1600)
        assertEquals(900, r[3])
    }

    @Test
    fun `crop aspect approximates the target for skewed pairs`() {
        val r = CoverCrop.rect(3000, 4000, targetAspect = 4f / 3f)
        val aspect = r[2].toFloat() / r[3]
        assertEquals(4f / 3f, aspect, 0.01f)
        assertTrue(r[0] >= 0 && r[1] >= 0)
        assertTrue(r[0] + r[2] <= 3000 && r[1] + r[3] <= 4000)
    }

    @Test
    fun `degenerate one-pixel sources stay valid`() {
        val r = CoverCrop.rect(1, 1, targetAspect = 2f)
        assertTrue(r[2] >= 1 && r[3] >= 1)
    }
}
