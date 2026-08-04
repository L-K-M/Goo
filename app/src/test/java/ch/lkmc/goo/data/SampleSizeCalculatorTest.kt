package ch.lkmc.goo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SampleSizeCalculatorTest {

    @Test
    fun `small images decode at full size`() {
        assertEquals(1, SampleSizeCalculator.calculate(1024, 768, 2048))
    }

    @Test
    fun `sample size halves only while the long side stays at or above target`() {
        // 8000x6000 at target 2048: /2 -> 4000 (ok), /4 -> 2000 < 2048
        // undershoots => 2.
        assertEquals(2, SampleSizeCalculator.calculate(8000, 6000, 2048))
        // 12000x9000: /4 -> 3000 still >= 2048, /8 -> 1500 undershoots => 4.
        assertEquals(4, SampleSizeCalculator.calculate(12000, 9000, 2048))
    }

    @Test
    fun `extreme panoramas subsample on the long side alone`() {
        // 20000x4000: the short side is near target but the long side is
        // 10x over — without long-side-only logic this decoded at full
        // size (320 MB). /8 -> 2500 >= 2048, /16 -> 1250 undershoots => 8.
        assertEquals(8, SampleSizeCalculator.calculate(20000, 4000, 2048))
        assertEquals(8, SampleSizeCalculator.calculate(4000, 20000, 2048))
    }

    @Test
    fun `result is always a power of two`() {
        for (w in intArrayOf(1, 500, 2048, 5000, 10000, 50000)) {
            val s = SampleSizeCalculator.calculate(w, w, 2048)
            assertEquals(0, s and (s - 1), "not a power of two: $s for $w")
        }
    }

    @Test
    fun `degenerate inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { SampleSizeCalculator.calculate(0, 100, 2048) }
        assertFailsWith<IllegalArgumentException> { SampleSizeCalculator.calculate(100, 100, 0) }
    }
}
