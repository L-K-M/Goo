package ch.lkmc.goo.engine.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * The GL side of the engine: owns the source texture, the ping-pong
 * displacement field, and the two programs; draws warp frames and stamps
 * brush kernels.
 *
 * Threading contract: every mutation arrives on the GL thread via
 * [GLSurfaceView.queueEvent] (the view wrapper enforces this); the UI
 * thread only reads nothing and owns nothing here. GPU state is a cache
 * (PLAN.md §5.5): [onSurfaceCreated] rebuilds everything from [sourceBitmap]
 * and the last stroke snapshot, so EGL context loss costs a replay, never
 * work.
 *
 * The stamp pass renders a fullscreen quad per stamp at field resolution.
 * The field is deliberately small (≤[FIELD_MAX_DIM] on the long side —
 * displacement is smooth by nature, half preview resolution is beyond
 * Liquify-mesh fidelity), so a dozen stamps a frame cost ~5 Mpx of fill:
 * comfortable 60fps headroom on mid-range GPUs. A scissored sub-quad
 * optimization is possible if profiling ever disagrees.
 */
class GlWarpRenderer(
    /** Called on the GL thread when the context can't run the engine. */
    private val onUnsupported: (String) -> Unit,
) : GLSurfaceView.Renderer {

    // ---- State owned by the GL thread ----------------------------------
    private var sourceBitmap: Bitmap? = null
    private var sourceTexture = 0
    private var field: PingPongField? = null
    private var stampProgram: GlProgram? = null
    private var warpProgram: GlProgram? = null
    private var quad: FloatBuffer = createQuadBuffer()
    private var quadVbo = 0
    private var viewWidth = 1
    private var viewHeight = 1
    private var strokesToReplay: List<Stroke> = emptyList()
    private var contextReady = false

    /** Aspect (w/h) of the current image; 1 until an image is set. */
    private var aspect = 1f

    // Uniform locations (stamp pass), resolved once per context.
    private var uField = 0
    private var uCenter = 0
    private var uDelta = 0
    private var uRadius = 0
    private var uStrength = 0
    private var uAspect = 0
    private var uImage = 0
    private var uFieldWarp = 0
    private var uRect = 0

    // ---- Commands (call on the GL thread via queueEvent) ---------------

    /**
     * Install the image and replay [strokes] into a fresh field. Also the
     * context-recreation path, which is why the bitmap is retained.
     */
    fun setImage(bitmap: Bitmap, strokes: List<Stroke>) {
        sourceBitmap = bitmap
        aspect = bitmap.width.toFloat() / bitmap.height
        strokesToReplay = strokes
        if (contextReady) rebuildImageState()
    }

    /** Stamp a batch from the live stroke into the field. */
    fun stampBatch(stroke: Stroke, stamps: List<Stamp>) {
        val f = field ?: return
        val program = stampProgram ?: return
        program.use()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glUniform1f(uRadius, stroke.radius)
        GLES30.glUniform1f(uStrength, stroke.strength)
        GLES30.glUniform1f(uAspect, aspect)
        GLES30.glUniform1i(uField, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for (stamp in stamps) {
            f.renderPass { readTexture ->
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, readTexture)
                GLES30.glUniform2f(uCenter, stamp.cx, stamp.cy)
                GLES30.glUniform2f(uDelta, stamp.dx, stamp.dy)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Record a committed stroke so context-loss recovery replays it. The
     * live [stampBatch] path already applied its stamps; without this the
     * recovery snapshot would silently drop everything committed since the
     * last setImage/rebuild.
     */
    fun commit(stroke: Stroke) {
        strokesToReplay = strokesToReplay + stroke
    }

    /** Clear the field and replay [strokes] — undo/redo/reset path. */
    fun rebuild(strokes: List<Stroke>) {
        strokesToReplay = strokes
        val f = field ?: return
        f.clear()
        for (stroke in strokes) stampBatch(stroke, stroke.stamps)
    }

    // ---- GLSurfaceView.Renderer ----------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        if (!PingPongField.supported(extensions)) {
            contextReady = false
            onUnsupported("This device's GPU can't render goo (no float render targets).")
            return
        }
        contextReady = true

        stampProgram = GlProgram(GlShaders.QUAD_VERT, GlShaders.STAMP_FRAG).also {
            uField = it.uniform("u_field")
            uCenter = it.uniform("u_center")
            uDelta = it.uniform("u_delta")
            uRadius = it.uniform("u_radius")
            uStrength = it.uniform("u_strength")
            uAspect = it.uniform("u_aspect")
        }
        warpProgram = GlProgram(GlShaders.WARP_VERT, GlShaders.WARP_FRAG).also {
            uImage = it.uniform("u_image")
            uFieldWarp = it.uniform("u_field")
            uRect = it.uniform("u_rect")
        }

        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        quadVbo = vbo[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, quad.capacity() * 4, quad.position(0), GLES30.GL_STATIC_DRAW,
        )

        // Fresh context: previous textures/FBOs are gone. Rebuild from the
        // retained bitmap + stroke snapshot.
        sourceTexture = 0
        field = null
        rebuildImageState()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        // The goo table (ui/theme GooTable) behind the letterbox.
        GLES30.glClearColor(0.067f, 0.125f, 0.114f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val bitmap = sourceBitmap ?: return
        val f = field ?: return
        val program = warpProgram ?: return

        val fit = FitTransform(
            viewWidth.toFloat(), viewHeight.toFloat(),
            bitmap.width.toFloat(), bitmap.height.toFloat(),
        )
        // NDC rect of the fitted image: x,y = bottom-left corner (GL's y is
        // up; FitTransform's offsets are top-left, so the bottom edge is
        // viewHeight − (offsetY + fittedHeight)).
        val ndcX = fit.offsetX / viewWidth * 2f - 1f
        val ndcY = (viewHeight - fit.offsetY - fit.fittedHeight) / viewHeight * 2f - 1f
        val ndcW = fit.fittedWidth / viewWidth * 2f
        val ndcH = fit.fittedHeight / viewHeight * 2f

        program.use()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glUniform4f(uRect, ndcX, ndcY, ndcW, ndcH)
        GLES30.glUniform1i(uImage, 0)
        GLES30.glUniform1i(uFieldWarp, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, f.readTexture)
        // Bitmaps upload premultiplied; without premul-correct blending a
        // transparent PNG's clear regions would render black instead of
        // showing the table. Warp pass only — the stamp pass must write
        // raw field values.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisableVertexAttribArray(0)
    }

    // ---- Internals -----------------------------------------------------

    /**
     * (Re)create the source texture and the field for the current bitmap,
     * then replay the stroke snapshot. GL thread, context ready.
     */
    private fun rebuildImageState() {
        val bitmap = sourceBitmap ?: return

        if (sourceTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(sourceTexture), 0)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        sourceTexture = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        field?.delete()
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val long = maxOf(bitmap.width, bitmap.height).toFloat()
        val scale = (FIELD_MAX_DIM / long).coerceAtMost(1f)
        field = PingPongField(
            width = (bitmap.width * scale).roundToInt().coerceAtLeast(2),
            height = (bitmap.height * scale).roundToInt().coerceAtLeast(2),
            halfFloatRenderable = PingPongField.hasHalfFloat(extensions),
        )
        rebuild(strokesToReplay)
    }

    private fun createQuadBuffer(): FloatBuffer {
        // One TRIANGLE_STRIP quad of NDC corners, shared by both passes:
        // QUAD_VERT uses it as a fullscreen quad, WARP_VERT remaps it into
        // the letterboxed image rect via u_rect.
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        return ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(corners)
            .apply { position(0) }
    }

    private companion object {
        /**
         * Field long-side cap. Displacement is smooth; a ≤1024 field under
         * a ≤2048 preview keeps stamp fill cheap with no visible loss.
         */
        const val FIELD_MAX_DIM = 1024f
    }
}
