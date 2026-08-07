package ch.lkmc.goo.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pump's tick schedule.
 *
 * This is worth pinning precisely because it is invisible when wrong:
 * the stamps are recorded, so an off-by-one in Melt's acceleration ramp
 * is baked into the document and then reproduced faithfully forever by
 * replay, undo and export. It was found by review rather than by a test
 * once already — hence this file.
 */
class PumpStampsTest {

    // ---- The touch-down handoff ---------------------------------------

    @Test
    fun `a tool that stamps on touch-down does not spend tick 1 twice`() {
        // beginStroke applies tick 1 itself; the clock must continue from
        // 2. Restarting at 1 doubles Melt's first run and shifts the whole
        // ramp by one.
        for (tool in BrushTool.entries.filter { it.pumped }) {
            val expected = if (tool.stampsOnDown) 2 else 1
            assertEquals(
                "first pump tick for $tool",
                expected,
                PumpStamps.firstPumpTick(tool),
            )
        }
    }

    @Test
    fun `the full hold sequence is strictly increasing with no repeat`() {
        val tool = BrushTool.MELT
        val u = 0.5f
        val ticks = buildList {
            if (tool.stampsOnDown) add(1)
            var t = PumpStamps.firstPumpTick(tool)
            repeat(5) { add(t); t++ }
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ticks)
        val runs = ticks.map { PumpStamps.meltRun(it, u) }
        for (i in 1 until runs.size) {
            assertTrue(
                "run must grow: $runs",
                runs[i] > runs[i - 1],
            )
        }
    }

    // ---- Melt's ramp ---------------------------------------------------

    @Test
    fun `the melt run grows with the hold and then stops growing`() {
        val u = 0.5f
        val early = PumpStamps.meltRun(1, u)
        val later = PumpStamps.meltRun(10, u)
        assertTrue("$later should exceed $early", later > early)

        // Past the cap it must flatten: warp-of-warp assumes small deltas,
        // and an uncapped ramp folds the composition instead of
        // stretching it.
        val atCap = PumpStamps.meltRun(1_000, u)
        val farPastCap = PumpStamps.meltRun(1_000_000, u)
        assertEquals(atCap, farPastCap, 1e-9f)
        assertTrue(
            "capped run $atCap must not exceed DRIP_MAX_UV",
            atCap <= BrushDynamics.DRIP_MAX_UV + 1e-6f,
        )
    }

    @Test
    fun `runs point downward`() {
        // +v is down (top-left origin), and the DIRECTIONAL kernel negates
        // the delta, so a positive dy is what makes content appear lower.
        val s = PumpStamps.at(BrushTool.MELT, 0.5f, 0.5f, tick = 3)
        assertTrue("expected a positive (downward) dy, got ${s.dy}", s.dy > 0f)
        assertEquals(0f, s.dx, 0f)
    }

    @Test
    fun `neighbouring columns run at different lengths`() {
        // Without the wander a wide brush slides as one rubber sheet
        // instead of separating into fingers.
        val runs = (0..20).map { PumpStamps.meltRun(20, it / 20f) }
        assertTrue("expected varied runs, got $runs", runs.distinct().size > 10)
    }

    @Test
    fun `the same column always runs the same length`() {
        // No seed and no clock: reproducibility is what lets a melt replay
        // from the log without storing anything extra.
        repeat(3) {
            assertEquals(
                PumpStamps.meltRun(7, 0.31f),
                PumpStamps.meltRun(7, 0.31f),
                0f,
            )
        }
    }

    // ---- Chirality -----------------------------------------------------

    @Test
    fun `the swirls carry chirality and nothing else`() {
        val cw = PumpStamps.at(BrushTool.VORTEX, 0.4f, 0.6f, tick = 9)
        val ccw = PumpStamps.at(BrushTool.UNWIND, 0.4f, 0.6f, tick = 9)
        assertEquals(0.4f, cw.cx, 0f)
        assertEquals(0.6f, cw.cy, 0f)
        assertTrue("chirality must oppose", cw.dx * ccw.dx < 0f)
        // A swirl's strength comes from the pump cadence, not the tick, so
        // the stamp must not drift with hold time.
        assertEquals(cw, PumpStamps.at(BrushTool.VORTEX, 0.4f, 0.6f, tick = 400))
    }

    @Test
    fun `the plain radial tools carry no delta at all`() {
        for (tool in listOf(BrushTool.GROW, BrushTool.SHRINK, BrushTool.SMOOTH, BrushTool.UNGOO)) {
            val s = PumpStamps.at(tool, 0.5f, 0.5f, tick = 4)
            assertEquals("$tool", 0f, s.dx, 0f)
            assertEquals("$tool", 0f, s.dy, 0f)
        }
    }

    @Test
    fun `ticks count from one`() {
        runCatching { PumpStamps.at(BrushTool.MELT, 0.5f, 0.5f, tick = 0) }
            .onSuccess { error("tick 0 should be rejected") }
    }

    // ---- The invariant the anisotropic profile depends on --------------

    @Test
    fun `DRIP is only ever paired with a directional kernel`() {
        // DisplacementField reshapes the WEIGHT's distance for DRIP but
        // leaves the radial DIRECTION on the true offset. That is only
        // safe while DRIP never meets a radial mode; enforcing it here
        // means a future pairing fails loudly instead of quietly
        // disagreeing with itself.
        BrushTool.entries
            .filter { it.profile == FalloffProfile.DRIP }
            .forEach {
                assertEquals(
                    "$it pairs DRIP with a radial kernel",
                    StampMode.DIRECTIONAL,
                    it.mode,
                )
            }
    }
}
