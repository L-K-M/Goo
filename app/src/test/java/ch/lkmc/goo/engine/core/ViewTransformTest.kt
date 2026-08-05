package ch.lkmc.goo.engine.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewTransformTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue(
            kotlin.math.abs(expected - actual) < eps,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun `identity maps points to themselves`() {
        val id = ViewTransform()
        assertTrue(id.isIdentity)
        val (x, y) = id.apply(123f, 456f)
        assertEquals(123f, x)
        assertEquals(456f, y)
    }

    @Test
    fun `apply then invert round-trips`() {
        val v = ViewTransform(scale = 2.5f, rotation = 0.7f, tx = 40f, ty = -25f)
        val (px, py) = v.apply(100f, 200f)
        val (bx, by) = v.invert(px, py)
        assertClose(100f, bx)
        assertClose(200f, by)
    }

    @Test
    fun `gesture zoom keeps the point under the fingers fixed`() {
        // The canvas point currently shown at the centroid must still be
        // shown at the centroid (plus pan) after the step.
        val v = ViewTransform(scale = 1.5f, rotation = 0.3f, tx = 30f, ty = 60f)
        val cx = 250f
        val cy = 400f
        val (canvasX, canvasY) = v.invert(cx, cy)
        val stepped = v.gesture(cx, cy, panX = 0f, panY = 0f, zoom = 1.8f, rotationDelta = 0.4f)
        val (nx, ny) = stepped.apply(canvasX, canvasY)
        assertClose(cx, nx)
        assertClose(cy, ny)
    }

    @Test
    fun `gesture pan moves the anchored point by exactly the pan`() {
        val v = ViewTransform(scale = 2f, rotation = -0.2f, tx = -10f, ty = 5f)
        val cx = 100f
        val cy = 100f
        val (canvasX, canvasY) = v.invert(cx, cy)
        val stepped = v.gesture(cx, cy, panX = 33f, panY = -12f, zoom = 1f, rotationDelta = 0f)
        val (nx, ny) = stepped.apply(canvasX, canvasY)
        assertClose(cx + 33f, nx)
        assertClose(cy - 12f, ny)
    }

    @Test
    fun `scale clamps and the centroid anchor survives the clamp`() {
        val v = ViewTransform(scale = 6f)
        val stepped = v.gesture(50f, 50f, 0f, 0f, zoom = 10f, rotationDelta = 0f)
        assertEquals(ViewTransform.MAX_SCALE, stepped.scale)
        // Anchor property still holds with the adjusted effective zoom.
        val (canvasX, canvasY) = v.invert(50f, 50f)
        val (nx, ny) = stepped.apply(canvasX, canvasY)
        assertClose(50f, nx)
        assertClose(50f, ny)
        // Lower clamp too.
        val small = ViewTransform(scale = 0.6f).gesture(0f, 0f, 0f, 0f, 0.1f, 0f)
        assertEquals(ViewTransform.MIN_SCALE, small.scale)
    }

    @Test
    fun `rotation accumulates across steps`() {
        var v = ViewTransform()
        repeat(4) {
            v = v.gesture(0f, 0f, 0f, 0f, 1f, (PI / 4).toFloat())
        }
        assertClose(PI.toFloat(), v.rotation)
    }

    @Test
    fun `rotation stays wrapped over long spinning sessions`() {
        var v = ViewTransform()
        repeat(1000) {
            v = v.gesture(100f, 100f, 0f, 0f, 1f, 0.37f)
        }
        assertTrue(
            v.rotation > -PI.toFloat() && v.rotation <= PI.toFloat() + 1e-4f,
            "rotation must stay in (-pi, pi], got ${v.rotation}",
        )
    }

    @Test
    fun `a near-full turn stores negative so reset takes the short way`() {
        // 350° of accumulated rotation wraps to -10°: the reset lerp then
        // springs 10° forward instead of 350° backward.
        var v = ViewTransform()
        repeat(35) {
            v = v.gesture(0f, 0f, 0f, 0f, 1f, (10.0 * PI / 180.0).toFloat())
        }
        assertTrue(v.rotation < 0f, "350° should wrap negative, got ${v.rotation}")
        assertClose((-10.0 * PI / 180.0).toFloat(), v.rotation, 1e-3f)
        val mid = v.lerp(ViewTransform(), 0.5f)
        assertClose((-5.0 * PI / 180.0).toFloat(), mid.rotation, 1e-3f)
    }

    @Test
    fun `normalizeAngle keeps exact zero exact`() {
        assertEquals(0f, ViewTransform.normalizeAngle(0f))
        assertClose(-0.1f, ViewTransform.normalizeAngle((2.0 * PI).toFloat() - 0.1f), 1e-4f)
    }

    @Test
    fun `shader coefficients match the applied matrix`() {
        val v = ViewTransform(scale = 3f, rotation = 0.5f, tx = 7f, ty = 9f)
        // apply(1, 0) - t == (a, b) by construction.
        val (x, y) = v.apply(1f, 0f)
        assertClose(v.a, x - v.tx)
        assertClose(v.b, y - v.ty)
    }

    @Test
    fun `lerp hits both endpoints and the midpoint`() {
        val from = ViewTransform(2f, 1f, 10f, 20f)
        val to = ViewTransform()
        assertEquals(from, from.lerp(to, 0f))
        assertEquals(to, from.lerp(to, 1f))
        val mid = from.lerp(to, 0.5f)
        assertClose(1.5f, mid.scale)
        assertClose(0.5f, mid.rotation)
        assertClose(5f, mid.tx)
        assertClose(10f, mid.ty)
    }

    @Test
    fun `viewport rebase keeps the same source point centered`() {
        val oldFit = FitTransform(400f, 700f, 1200f, 800f)
        val newFit = FitTransform(700f, 400f, 1200f, 800f)
        val oldView = ViewTransform(scale = 2.4f, rotation = 0.35f, tx = -180f, ty = 90f)

        val (oldCanvasX, oldCanvasY) = oldView.invert(
            oldFit.viewWidth / 2f,
            oldFit.viewHeight / 2f,
        )
        val expectedUv = oldFit.viewToUv(oldCanvasX, oldCanvasY)
        val rebased = oldView.rebase(oldFit, newFit)
        val (newCanvasX, newCanvasY) = rebased.invert(
            newFit.viewWidth / 2f,
            newFit.viewHeight / 2f,
        )
        val actualUv = newFit.viewToUv(newCanvasX, newCanvasY)

        assertClose(expectedUv.first, actualUv.first)
        assertClose(expectedUv.second, actualUv.second)
        assertEquals(oldView.scale, rebased.scale)
        assertEquals(oldView.rotation, rebased.rotation)
    }

    @Test
    fun `identity remains identity when the viewport changes`() {
        val oldFit = FitTransform(400f, 700f, 1200f, 800f)
        val newFit = FitTransform(700f, 400f, 1200f, 800f)

        val rebased = ViewTransform().rebase(oldFit, newFit)

        assertTrue(rebased.isIdentity, "got $rebased")
    }
}
