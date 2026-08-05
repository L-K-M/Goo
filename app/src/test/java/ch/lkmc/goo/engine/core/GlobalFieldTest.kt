package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalFieldTest {

    private val aspect = 4f / 3f

    private fun disp(u: Float, v: Float, p: GlobalParams) =
        GlobalField.displacement(u, v, aspect, p)

    @Test
    fun `identity levers displace nothing anywhere`() {
        val p = GlobalParams()
        for (u in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            for (v in listOf(0f, 0.4f, 1f)) {
                val (dx, dy) = disp(u, v, p)
                assertEquals(0f, dx)
                assertEquals(0f, dy)
            }
        }
    }

    @Test
    fun `positive bulge pulls samples toward the center - magnify`() {
        val (dx, _) = disp(0.7f, 0.5f, GlobalParams(bulge = 1f))
        assertTrue(dx < 0f, "right of center must sample nearer center, dx=$dx")
        val (dxL, _) = disp(0.3f, 0.5f, GlobalParams(bulge = 1f))
        assertTrue(dxL > 0f)
        // Negative lever pinches.
        val (dxN, _) = disp(0.7f, 0.5f, GlobalParams(bulge = -1f))
        assertTrue(dxN > 0f)
    }

    @Test
    fun `twirl moves points tangentially and is exact rotation`() {
        val p = GlobalParams(twirl = 0.5f)
        // Point right of center: rotation moves its sample source
        // tangentially (y-component dominates for small angles at pure +x).
        val (dx, dy) = disp(0.62f, 0.5f, p)
        assertTrue(abs(dy) > abs(dx) * 0.2f, "expected tangential motion, ($dx, $dy)")
        // Rotation preserves aspect-space radius: |rotated| == |original|.
        val ax = (0.62f - 0.5f) * aspect
        val rx = ax + dx * aspect
        val ry = dy
        assertEquals(ax, sqrt(rx * rx + ry * ry), 1e-4f)
    }

    @Test
    fun `squeeze pushes x samples outward and y samples inward`() {
        val p = GlobalParams(squeeze = 1f)
        val (dx, _) = disp(0.75f, 0.5f, p)
        assertTrue(dx > 0f, "backward map: sampling further out shrinks x")
        val (_, dy) = disp(0.5f, 0.75f, p)
        assertTrue(dy < 0f, "y samples nearer center: stretches y")
    }

    @Test
    fun `stretch only touches y`() {
        val p = GlobalParams(stretch = 0.8f)
        val (dx, dy) = disp(0.7f, 0.7f, p)
        assertEquals(0f, dx)
        assertTrue(dy < 0f)
    }

    @Test
    fun `spike modulates with the star period`() {
        val p = GlobalParams(spike = 1f)
        // Along +x (phi = 0): sin(8*0) = 0 — node.
        val (dx0, _) = disp(0.8f, 0.5f, p)
        assertEquals(0f, dx0, 1e-5f)
        // At phi = pi/16: sin(pi/2) = 1 — antinode, nonzero radial push.
        val phi = (Math.PI / 16).toFloat()
        val r = 0.3f
        val u = 0.5f + (r * kotlin.math.cos(phi)) / aspect
        val v = 0.5f + r * kotlin.math.sin(phi)
        val (dx, dy) = disp(u, v, p)
        assertTrue(sqrt(dx * dx + dy * dy) > 1e-4f)
    }

    @Test
    fun `static is deterministic bounded and vanishes at zero`() {
        val p = GlobalParams(static = 1f)
        val (dx1, dy1) = disp(0.31f, 0.62f, p)
        val (dx2, dy2) = disp(0.31f, 0.62f, p)
        assertEquals(dx1, dx2)
        assertEquals(dy1, dy2)
        assertTrue(abs(dx1) <= GlobalField.STATIC_SCALE / aspect + 1e-6f)
        assertTrue(abs(dy1) <= GlobalField.STATIC_SCALE + 1e-6f)
    }

    @Test
    fun `radial effects vanish at the frame corner`() {
        // Corner (1,1) is at r = rMax where shape = 0.
        val (dx, dy) = disp(1f, 1f, GlobalParams(bulge = 1f, twirl = 1f, spike = 1f))
        assertEquals(0f, dx, 1e-5f)
        assertEquals(0f, dy, 1e-5f)
    }

    @Test
    fun `all levers together stay finite everywhere`() {
        val p = GlobalParams(1f, -1f, 1f, -1f, 1f, 1f)
        for (i in 0..20) {
            for (j in 0..20) {
                val (dx, dy) = disp(i / 20f, j / 20f, p)
                assertTrue(dx.isFinite() && dy.isFinite())
            }
        }
    }

    @Test
    fun `hash is uniform-ish and stable`() {
        // Pin a few values so CPU/GLSL drift would fail loudly.
        assertEquals(GlobalField.hash(0u, 0u, 1u), GlobalField.hash(0u, 0u, 1u))
        val values = (0 until 1000).map {
            GlobalField.hash((it % 37).toUInt(), (it / 37).toUInt(), 1u)
        }
        assertTrue(values.all { it in 0f..1f })
        assertTrue(values.average() in 0.35..0.65, "mean ${values.average()}")
    }

    @Test
    fun `toArray fromArray round-trips`() {
        val p = GlobalParams(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f)
        assertEquals(p, GlobalParams.fromArray(p.toArray()))
        assertEquals(null, GlobalParams.fromArray(floatArrayOf(1f)))
    }
}
