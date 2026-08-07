package ch.lkmc.goo.engine.core

/**
 * The stamp a pumped tool drops on each tick of a hold.
 *
 * Most pumped tools carry no delta at all — the kernel is radial and the
 * finger's position is the whole input. Two do, and both encode
 * something in the delta that the kernel then reads back:
 *
 * - the swirls put their CHIRALITY in `dx`'s sign, so a mirrored twin
 *   (which negates `dx`) counter-rotates for free;
 * - Melt puts a downward RUN in `dy` that grows with the hold, which is
 *   what makes wax accelerate as it falls.
 *
 * This lives in `engine/core` rather than in the ViewModel because the
 * run schedule is exactly the kind of thing that goes wrong quietly: the
 * stamps are recorded, so an off-by-one in the ramp is baked into the
 * document and reproduced faithfully forever after by replay, undo and
 * export. Being pure, it is also testable, which the ViewModel is not
 * (REVIEW G-W2).
 */
object PumpStamps {

    /**
     * The stamp for tick [tick] of a hold at ([u], [v]).
     *
     * [tick] counts applications of this stroke from 1, *including* the
     * one [BrushTool.stampsOnDown] applies at touch-down — see
     * [firstPumpTick], which is what keeps the caller from spending tick
     * 1 twice.
     */
    fun at(tool: BrushTool, u: Float, v: Float, tick: Int): Stamp {
        require(tick >= 1) { "ticks count from 1: $tick" }
        if (tool.chirality != 0f) return Stamp(u, v, tool.chirality, 0f)
        if (tool != BrushTool.MELT) return Stamp(u, v, 0f, 0f)
        // Down is +v: stamp-space UV has a top-left origin, and the
        // DIRECTIONAL kernel negates the delta, so a positive dy samples
        // from higher up and the content appears lower.
        return Stamp(u, v, 0f, meltRun(tick, u))
    }

    /**
     * The first tick the pump clock should emit, given that a tool with
     * [BrushTool.stampsOnDown] has already spent tick 1 on touch-down.
     */
    fun firstPumpTick(tool: BrushTool): Int = if (tool.stampsOnDown) 2 else 1

    /**
     * How far one melt stamp pulls on tick [tick], at image column [u].
     *
     * Linear in the tick until [BrushDynamics.DRIP_MAX_UV], because
     * warp-of-warp assumes small deltas and a run past that folds the
     * composition instead of stretching it.
     *
     * The column wander uses the same integer-hash value noise the Static
     * lever does, so it is bit-identical on CPU and GPU (SOL-41) — but it
     * is sampled on the stamp's own x rather than a stored seed, so a
     * melt needs nothing added to the document to replay identically. A
     * wide brush separates into fingers rather than sliding as one sheet.
     */
    fun meltRun(tick: Int, u: Float): Float {
        val wander = GlobalField.valueNoise(u * BrushDynamics.DRIP_CELLS, 0f, seed = 4u)
        val ramp = (tick * BrushDynamics.DRIP_STEP_UV)
            .coerceAtMost(BrushDynamics.DRIP_MAX_UV)
        return ramp * (0.35f + 0.65f * wander)
    }
}
