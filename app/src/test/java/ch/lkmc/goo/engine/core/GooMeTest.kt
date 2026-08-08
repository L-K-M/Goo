package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Goo Me deck (proposal 0007). The generator's output is *baked into
 * the document* — dealt strokes are recorded like any others — so a bad
 * band or an off-canvas landmark is not a transient glitch but a
 * permanent, faithfully replayed one. These are the guardrails on the
 * curation.
 */
class GooMeTest {

    private val portrait = 3f / 4f
    private val square = 1f
    private val panorama = 16f / 9f

    /** The editor's own brush limits: a deal must stay inside them. */
    private val minRadius = 0.04f
    private val maxRadius = 0.28f

    private fun seeds(count: Int) = (1L..count).map { it * 7919L }

    @Test
    fun `same seed deals the same hand`() {
        val a = GooMe.deal(seed = 12345L, aspect = square)
        val b = GooMe.deal(seed = 12345L, aspect = square)
        assertEquals(a.strokes, b.strokes)
        assertEquals(a.globals, b.globals)
    }

    @Test
    fun `different seeds deal different hands`() {
        val hands = seeds(40).map { GooMe.deal(it, square).strokes }.toSet()
        // 40 draws from a 10-recipe deck with diced parameters: anything
        // close to 1 means the seed is not reaching the generator.
        assertTrue(hands.size > 20, "only ${hands.size} distinct hands in 40 deals")
    }

    @Test
    fun `every recipe in the deck is reachable`() {
        // Recipes are private, so identify them by their tool signature —
        // no two entries in the deck share one.
        val signatures = seeds(400).map { seed ->
            GooMe.deal(seed, square).strokes.map { it.tool }
        }.toSet()
        assertEquals(GooMe.deckSize, signatures.size)
    }

    @Test
    fun `every deal lands strokes with stamps`() {
        for (aspect in listOf(portrait, square, panorama)) {
            for (seed in seeds(60)) {
                val deal = GooMe.deal(seed, aspect)
                assertTrue(deal.strokes.isNotEmpty(), "empty deal at seed $seed / $aspect")
                for (stroke in deal.strokes) {
                    assertTrue(
                        stroke.stamps.isNotEmpty(),
                        "stampless ${stroke.tool} at seed $seed / $aspect",
                    )
                }
            }
        }
    }

    @Test
    fun `every stamp lands inside the frame`() {
        for (aspect in listOf(portrait, square, panorama)) {
            for (seed in seeds(60)) {
                for (stroke in GooMe.deal(seed, aspect).strokes) {
                    for (stamp in stroke.stamps) {
                        assertTrue(
                            stamp.cx in 0f..1f && stamp.cy in 0f..1f,
                            "off-canvas ${stroke.tool} stamp (${stamp.cx}, ${stamp.cy}) " +
                                "at seed $seed / aspect $aspect",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `radii and strengths stay inside the editor's own limits`() {
        for (seed in seeds(120)) {
            for (stroke in GooMe.deal(seed, square).strokes) {
                assertTrue(
                    stroke.radius in minRadius..maxRadius,
                    "radius ${stroke.radius} outside the slider at seed $seed",
                )
                // Strength carries the tool's own scale, exactly as
                // beginStroke applies it — so the ceiling is that scale.
                assertTrue(
                    stroke.strength > 0f && stroke.strength <= stroke.tool.strengthScale,
                    "strength ${stroke.strength} for ${stroke.tool} at seed $seed",
                )
            }
        }
    }

    @Test
    fun `the deck never deals Fusion`() {
        // FUSE paints the second photo through a mask. With no photo B —
        // and the button cannot require one — it would apply invisibly:
        // a deal that appears to do nothing, which is the one outcome a
        // slot machine must never produce.
        for (seed in seeds(400)) {
            assertFalse(
                GooMe.deal(seed, square).strokes.any { it.tool == BrushTool.FUSE },
                "dealt FUSE at seed $seed",
            )
        }
    }

    @Test
    fun `a deal moves at most one lever and never past its stop`() {
        val start = GlobalParams()
        for (seed in seeds(200)) {
            val globals = GooMe.deal(seed, square, from = start).globals
            val moved = globals.toArray().zip(start.toArray()).count { (a, b) -> a != b }
            assertTrue(moved <= 1, "$moved levers moved at seed $seed")
            for (value in globals.toArray()) {
                assertTrue(value in -1f..1f, "lever at $value, seed $seed")
            }
        }
    }

    @Test
    fun `a deal leaves the levers it does not name alone`() {
        // The user's own table survives a deal: dealing over a pulled
        // Spike must not quietly zero it.
        val start = GlobalParams(spike = 0.7f, static = -0.2f)
        for (seed in seeds(120)) {
            val globals = GooMe.deal(seed, square, from = start).globals
            val changed = globals.toArray().zip(start.toArray())
                .withIndex()
                .filter { (_, pair) -> pair.first != pair.second }
            assertTrue(changed.size <= 1, "seed $seed changed ${changed.size} levers")
        }
    }

    @Test
    fun `dealt holds are exactly what the pump would have emitted`() {
        // The architectural claim of proposal 0007 is that a deal is made
        // of things the user could have painted. For pumped tools that
        // means the stamps come from PumpStamps and not from a second
        // copy of the ramp — the place a generator would most easily
        // drift away from the palette.
        var checked = 0
        for (seed in seeds(200)) {
            for (stroke in GooMe.deal(seed, square).strokes) {
                if (!stroke.tool.pumped) continue
                val first = stroke.stamps.first()
                stroke.stamps.forEachIndexed { i, stamp ->
                    assertEquals(
                        PumpStamps.at(stroke.tool, first.cx, first.cy, tick = i + 1),
                        stamp,
                        "${stroke.tool} tick ${i + 1} at seed $seed",
                    )
                }
                checked++
            }
        }
        assertTrue(checked > 0, "no pumped strokes were dealt at all")
    }

    @Test
    fun `dealt drags carry the resampler's per-stamp deltas`() {
        // Same claim for directional tools: consecutive stamp centers are
        // one resampler spacing apart in aspect space, and each delta is
        // the step from the previous center. A hand-rolled path would
        // almost certainly get the delta-from-last-stamp rule wrong.
        var checked = 0
        for (seed in seeds(200)) {
            for (stroke in GooMe.deal(seed, square).strokes) {
                // stampsOnDown tools prepend a touch-down stamp that the
                // resampler did not emit, so the chain starts at index 1
                // for them — same as a real drag with one of those. No
                // recipe drags one today; skip rather than special-case.
                if (stroke.tool.pumped || stroke.tool.stampsOnDown) continue
                if (stroke.stamps.size < 2) continue
                for (i in 1 until stroke.stamps.size) {
                    val previous = stroke.stamps[i - 1]
                    val stamp = stroke.stamps[i]
                    assertEquals(stamp.cx - previous.cx, stamp.dx, 1e-5f)
                    assertEquals(stamp.cy - previous.cy, stamp.dy, 1e-5f)
                }
                checked++
            }
        }
        assertTrue(checked > 0, "no dragged strokes were dealt at all")
    }

    @Test
    fun `landmarks follow the image shape rather than the frame`() {
        // Placement is aspect-space, so the eyes stay a face-width apart
        // on a panorama instead of drifting toward the edges. Measured on
        // the one recipe that puts a stroke on each eye: its two stamps
        // must sit the same aspect-space distance apart at every shape.
        val distances = listOf(portrait, square, panorama).map { aspect ->
            val eyes = seeds(400)
                .map { GooMe.deal(it, aspect).strokes }
                .first { hand -> hand.size == 3 && hand[0].tool == BrushTool.GROW }
            val a = eyes[0].stamps.first()
            val b = eyes[1].stamps.first()
            (b.cx - a.cx) * aspect
        }
        // Same recipe, same seed sequence, so the jitter is identical too:
        // any difference would be the aspect leaking into the layout.
        for (d in distances) assertEquals(distances.first(), d, 1e-4f)
    }

    @Test
    fun `a non-positive aspect is rejected rather than dealt onto`() {
        assertFails { GooMe.deal(seed = 1L, aspect = 0f) }
        assertFails { GooMe.deal(seed = 1L, aspect = -2f) }
    }

    @Test
    fun `with sets one lever and clamps it`() {
        val start = GlobalParams(bulge = 0.1f, twirl = 0.2f)
        assertEquals(
            GlobalParams(bulge = 0.1f, twirl = 0.2f, spike = 0.5f),
            start.with(GooMe.Lever.SPIKE, 0.5f),
        )
        assertEquals(1f, start.with(GooMe.Lever.BULGE, 4f).bulge)
        assertEquals(-1f, start.with(GooMe.Lever.STATIC, -9f).static)
    }
}
