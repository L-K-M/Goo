package ch.lkmc.goo.engine.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic between a Pins gesture and a payload the log accepts
 * (proposal 0016).
 *
 * Kept out of the ViewModel so it can be tested without one — the same
 * split `EchoOffset` and `Portals` already use.
 */
class PinPullTest {

    private val wide = 16f / 9f
    private fun puck(su: Float, sv: Float, tu: Float, tv: Float) = MlsControl(su, sv, tu, tv)

    @Test
    fun `a tap is not a pull`() {
        assertNull(PinPull.warpFor(emptyList(), puck(0.5f, 0.5f, 0.5f, 0.5f)))
        // Just under the floor: a stray touch inside the mode must not
        // cost an undo step and a full-field replay pass.
        assertNull(
            PinPull.warpFor(emptyList(), puck(0.5f, 0.5f, 0.5f + PinPull.MIN_PULL * 0.5f, 0.5f)),
        )
    }

    @Test
    fun `a real drag is a pull`() {
        assertNotNull(PinPull.warpFor(emptyList(), puck(0.5f, 0.5f, 0.62f, 0.62f)))
    }

    @Test
    fun `the frame corners come along, quietly`() {
        val warp = PinPull.warpFor(listOf(0.3f to 0.3f), puck(0.5f, 0.5f, 0.65f, 0.5f))!!
        // 4 corners + 1 hold + 1 puck.
        assertEquals(6, warp.controls.size)
        val corners = warp.controls.filter { it.weight == RigidMls.CORNER_WEIGHT }
        assertEquals(4, corners.size)
        assertTrue(
            corners.all { it.su == it.tu && it.sv == it.tv },
            "corners are holds, not pulls",
        )
        // Quieter than anything the user placed, which is the entire
        // reason they can stabilise the frame without flattening the
        // interior.
        assertTrue(RigidMls.CORNER_WEIGHT < 1f)
    }

    @Test
    fun `a full rack lands exactly on the control cap`() {
        // 4 corners + 5 holds + 1 puck = 10 = MAX_CONTROLS. Bounded by
        // construction rather than by a check, so this is the test that
        // says the arithmetic still adds up.
        val holds = List(PinPull.MAX_HOLDS) { 0.15f * (it + 1) to 0.3f }
        val warp = PinPull.warpFor(holds, puck(0.5f, 0.7f, 0.65f, 0.8f))!!
        assertEquals(RigidMls.MAX_CONTROLS, warp.controls.size)
        assertTrue(warp.isValid)
    }

    @Test
    fun `extra holds are dropped rather than overflowing the solver`() {
        val holds = List(PinPull.MAX_HOLDS + 4) { 0.08f * (it + 1) to 0.3f }
        val warp = PinPull.warpFor(holds, puck(0.5f, 0.7f, 0.65f, 0.8f))!!
        assertEquals(RigidMls.MAX_CONTROLS, warp.controls.size)
    }

    @Test
    fun `the pull the user made is the pull that gets committed`() {
        val p = puck(0.4f, 0.6f, 0.55f, 0.72f)
        val warp = PinPull.warpFor(emptyList(), p)!!
        val moving = warp.controls.filter { it.su != it.tu || it.sv != it.tv }
        assertEquals(listOf(p), moving)
    }

    // ---- Grabbing a pin --------------------------------------------------

    @Test
    fun `a tap on a pin finds it, and the nearest one wins`() {
        val holds = listOf(0.30f to 0.5f, 0.34f to 0.5f)
        assertEquals(0, PinPull.nearestHold(holds, 0.305f, 0.5f, 1f))
        assertEquals(1, PinPull.nearestHold(holds, 0.338f, 0.5f, 1f))
    }

    @Test
    fun `a tap on empty photo finds nothing`() {
        assertNull(PinPull.nearestHold(listOf(0.3f to 0.3f), 0.8f, 0.8f, 1f))
    }

    @Test
    fun `the grab radius is round in pixels`() {
        // THE aspect test. On a 16:9 photo a horizontal UV step covers
        // 16/9 as much image as the same vertical one, so a raw-UV check
        // would grab this pin and the aspect-space truth does not. A
        // square test image cannot tell the two apart.
        val holds = listOf(0.5f to 0.5f)
        val step = PinPull.PIN_TOUCH_RADIUS * 0.9f
        assertNull(
            PinPull.nearestHold(holds, 0.5f + step, 0.5f, wide),
            "a horizontal step is further than it looks on a wide photo",
        )
        assertEquals(0, PinPull.nearestHold(holds, 0.5f, 0.5f + step, wide))
    }

    // ---- Shape independence ----------------------------------------------

    @Test
    fun `the same pull on two photo shapes deforms the same pixels`() {
        // "Portrait and landscape photos agree in pixel-space geometry."
        fun probe(aspect: Float): Pair<Float, Float> {
            fun at(ax: Float, ay: Float) = 0.5f + ax / aspect to 0.5f + ay
            val (hu, hv) = at(-0.2f, 0f)
            val (su, sv) = at(0.1f, 0f)
            val (tu, tv) = at(0.2f, 0.08f)
            val warp = PinPull.warpFor(listOf(hu to hv), puck(su, sv, tu, tv))!!
            val (qu, qv) = at(0f, 0.05f)
            val (ou, ov) = RigidMls.deform(qu, qv, warp.controls, aspect, warp.reach, warp.rubber)
            return (ou - qu) * aspect to ov - qv
        }
        val a = probe(wide)
        val b = probe(9f / 16f)
        assertTrue(hypot(a.first - b.first, a.second - b.second) < 3e-3f, "$a vs $b")
    }
}
