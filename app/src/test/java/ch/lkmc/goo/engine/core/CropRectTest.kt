package ch.lkmc.goo.engine.core

import ch.lkmc.goo.data.ImageLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropRectTest {

    private val quarter = CropRect(0.25f, 0.25f, 0.75f, 0.75f)

    // ---- full-frame detection ------------------------------------------

    @Test
    fun `FULL is full frame and real crops are not`() {
        assertTrue(CropRect.FULL.isFullFrame)
        // Handle jitter at the borders still counts as full frame.
        assertTrue(CropRect(0.004f, 0f, 1f, 0.996f).isFullFrame)
        assertFalse(quarter.isFullFrame)
        assertFalse(CropRect(0f, 0f, 1f, 0.9f).isFullFrame)
    }

    // ---- composition ----------------------------------------------------

    @Test
    fun `compose maps an inner rect through the outer frame`() {
        // The center half of the center half = the center quarter.
        val composed = quarter.compose(CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        assertEquals(0.375f, composed.left, 1e-6f)
        assertEquals(0.375f, composed.top, 1e-6f)
        assertEquals(0.625f, composed.right, 1e-6f)
        assertEquals(0.625f, composed.bottom, 1e-6f)
    }

    @Test
    fun `compose with FULL is identity on both sides`() {
        assertEquals(quarter, CropRect.FULL.compose(quarter))
        assertEquals(quarter, quarter.compose(CropRect.FULL))
    }

    // ---- corner drags ---------------------------------------------------

    @Test
    fun `dragCorner moves only its two edges`() {
        val r = CropRect.FULL.dragCorner(0, 0.2f, 0.3f)
        assertEquals(CropRect(0.2f, 0.3f, 1f, 1f), r)
        val r3 = CropRect.FULL.dragCorner(3, 0.8f, 0.7f)
        assertEquals(CropRect(0f, 0f, 0.8f, 0.7f), r3)
    }

    @Test
    fun `dragCorner clamps to the image and to the minimum size`() {
        // Way past the opposite edge: stops MIN_FRACTION short of it.
        val r = CropRect.FULL.dragCorner(0, 2f, 2f)
        assertEquals(1f - CropRect.MIN_FRACTION, r.left, 1e-6f)
        assertEquals(1f - CropRect.MIN_FRACTION, r.top, 1e-6f)
        // Way outside the image: pinned to the border.
        val r2 = quarter.dragCorner(0, -1f, -1f)
        assertEquals(0f, r2.left)
        assertEquals(0f, r2.top)
    }

    // ---- inside drags ---------------------------------------------------

    @Test
    fun `dragInside translates without resizing`() {
        val r = quarter.dragInside(0.1f, -0.1f)
        assertEquals(0.35f, r.left, 1e-6f)
        assertEquals(0.15f, r.top, 1e-6f)
        assertEquals(quarter.width, r.width, 1e-6f)
        assertEquals(quarter.height, r.height, 1e-6f)
    }

    @Test
    fun `dragInside stops at the borders`() {
        val r = quarter.dragInside(10f, -10f)
        assertEquals(0.5f, r.left, 1e-6f)
        assertEquals(1f, r.right, 1e-6f)
        assertEquals(0f, r.top, 1e-6f)
        assertEquals(0.5f, r.bottom, 1e-6f)
    }

    // ---- pixel mapping --------------------------------------------------

    @Test
    fun `pixelRect maps to exact bounds`() {
        val r = quarter.pixelRect(400, 200)
        assertEquals(100, r[0])
        assertEquals(50, r[1])
        assertEquals(200, r[2])
        assertEquals(100, r[3])
    }

    @Test
    fun `pixelRect never leaves the bitmap or collapses`() {
        // A sliver at the far corner of a tiny bitmap: still >= 1px, in.
        val sliver = CropRect(0.98f, 0.98f, 1f, 1f)
        val r = sliver.pixelRect(3, 3)
        assertTrue(r[0] in 0..2 && r[1] in 0..2)
        assertTrue(r[2] >= 1 && r[3] >= 1)
        assertTrue(r[0] + r[2] <= 3 && r[1] + r[3] <= 3)
        // Full frame maps to the full bitmap.
        val full = CropRect.FULL.pixelRect(123, 77)
        assertEquals(listOf(0, 0, 123, 77), full.toList())
    }

    // ---- persistence ----------------------------------------------------

    @Test
    fun `toArray fromArray roundtrips`() {
        assertEquals(quarter, CropRect.fromArray(quarter.toArray()))
    }

    @Test
    fun `fromArray rejects garbage but keeps composed slivers`() {
        assertNull(CropRect.fromArray(null))
        assertNull(CropRect.fromArray(floatArrayOf(0f, 0f, 1f)))
        // Inverted and out-of-range rects are corrupt restores.
        assertNull(CropRect.fromArray(floatArrayOf(0.8f, 0f, 0.2f, 1f)))
        assertNull(CropRect.fromArray(floatArrayOf(-0.5f, 0f, 1f, 1f)))
        // Below MIN_FRACTION is VALID here: stacked re-crops compose to
        // sides thinner than one gesture's floor, and restore must not
        // silently uncrop them after process death.
        val sliver = CropRect.fromArray(floatArrayOf(0f, 0f, 0.05f, 1f))
        assertEquals(CropRect(0f, 0f, 0.05f, 1f), sliver)
    }

    @Test
    fun `stacked deep crops survive a persistence roundtrip`() {
        // Two minimum-size crops in a row: absolute side = 0.01, well
        // under MIN_FRACTION — exactly what applyCrop stores.
        val outer = CropRect(0f, 0f, CropRect.MIN_FRACTION, CropRect.MIN_FRACTION)
        val composed = outer.compose(
            CropRect(0f, 0f, CropRect.MIN_FRACTION, CropRect.MIN_FRACTION),
        )
        assertTrue(composed.width < CropRect.MIN_FRACTION)
        assertEquals(composed, CropRect.fromArray(composed.toArray()))
    }

    // ---- decode target --------------------------------------------------

    @Test
    fun `cropDecodeTarget raises the request so the crop lands on target`() {
        // Half-width crop of a 4000x2000 photo: target doubles.
        val t = ImageLoader.cropDecodeTarget(
            uprightWidth = 4000, uprightHeight = 2000,
            crop = CropRect(0f, 0f, 0.5f, 1f), maxDimension = 2048,
        )
        assertEquals(4096, t)
    }

    @Test
    fun `cropDecodeTarget is capped for sliver crops`() {
        val sliver = CropRect(0f, 0f, CropRect.MIN_FRACTION, CropRect.MIN_FRACTION)
        val t = ImageLoader.cropDecodeTarget(8000, 8000, sliver, 2048)
        assertEquals(ImageLoader.CROP_DECODE_CAP, t)
    }

    @Test
    fun `cropDecodeTarget never lowers a request above the cap`() {
        // Export caps can exceed 4096 on big GPUs; a mild crop must not
        // shrink the decode below the uncropped request.
        val t = ImageLoader.cropDecodeTarget(
            uprightWidth = 10000, uprightHeight = 10000,
            crop = CropRect(0f, 0f, 0.9f, 0.9f), maxDimension = 8192,
        )
        assertEquals(8192, t)
    }

    @Test
    fun `cappedSampleSize binds the DECODED size not the request`() {
        // 48 MP photo, target at the 4096 cap: the power-of-two
        // calculator alone picks sample 1 (8000/2 = 4000 < 4096) and
        // would decode the full ~183 MB frame. The cap must bind the
        // decode itself.
        assertEquals(2, ImageLoader.cappedSampleSize(8000, 6000, target = 4096, cap = 4096))
        // Same photo through an 8192 export cap: still bounded.
        assertEquals(1, ImageLoader.cappedSampleSize(8000, 6000, target = 8192, cap = 8192))
        // Square just under 2x the cap — the worst overshoot case.
        assertEquals(2, ImageLoader.cappedSampleSize(8191, 8191, target = 4096, cap = 4096))
    }

    @Test
    fun `cappedSampleSize leaves fitting decodes alone`() {
        assertEquals(1, ImageLoader.cappedSampleSize(3000, 2000, target = 2048, cap = 4096))
        assertEquals(1, ImageLoader.cappedSampleSize(4096, 4096, target = 4096, cap = 4096))
    }

    @Test
    fun `cropDecodeTarget uses the cropped LONG side`() {
        // Tall crop of a wide image: the crop's long side is vertical.
        // Image 4000x2000; crop w=0.25 (1000px), h=1.0 (2000px) — the
        // cropped long side is 2000, factor 2, not 4.
        val t = ImageLoader.cropDecodeTarget(
            uprightWidth = 4000, uprightHeight = 2000,
            crop = CropRect(0f, 0f, 0.25f, 1f), maxDimension = 1000,
        )
        assertEquals(2000, t)
    }
}
