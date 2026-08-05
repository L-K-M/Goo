package ch.lkmc.goo.engine.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import ch.lkmc.goo.engine.core.ExportSize
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.GoovieTimeline
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.MovieSpec
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeRevision
import ch.lkmc.goo.engine.core.StrokeRevisionId
import ch.lkmc.goo.engine.core.ViewTransform
import ch.lkmc.goo.engine.core.lerp
import ch.lkmc.goo.engine.media.MovieEncoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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

    /** Extension list of the current context; set once per onSurfaceCreated. */
    private var extensions = ""

    /** GL_MAX_TEXTURE_SIZE of the current context; export sizing input. */
    var maxTextureSize = 2048
        private set

    /** Aspect (w/h) of the current image; 1 until an image is set. */
    private var aspect = 1f

    // Uniform locations (stamp pass), resolved once per context.
    private var uField = 0
    private var uCenter = 0
    private var uDelta = 0
    private var uRadius = 0
    private var uStrength = 0
    private var uAspect = 0
    private var uMode = 0
    private var uProfile = 0
    private var uFieldTexel = 0
    private var uImage = 0
    private var uFieldWarp = 0
    private var uRectPx = 0
    private var uViewport = 0
    private var uView = 0
    private var uGAspect = 0
    private var uGlobals = 0
    private var uFieldB = 0
    private var uTween = 0
    private var uImageB = 0
    private var uHasB = 0

    /** Live lever values; uploaded to the warp pass every draw. */
    private var globalParams = GlobalParams()

    /** The editor's pan/zoom/rotate; preview-only (export/movie identity). */
    private var viewTransform = ViewTransform()

    /** Set by WarpSurfaceView: whether the context config is recordable. */
    var recordableConfig: (() -> Boolean)? = null

    // Fusion: photo B, cover-cropped to A's UV space by the caller.
    // Retained like sourceBitmap for context recovery.
    private var sourceBitmapB: Bitmap? = null
    private var sourceTextureB = 0

    // ---- GOOvie tween state --------------------------------------------
    // Two endpoint fields materialized by replaying immutable revisions;
    // the warp pass mixes them (PLAN.md §4.1). tweenT < 0 means live mode.
    private var endpointA: PingPongField? = null
    private var endpointB: PingPongField? = null
    private var loadedRevisionA: StrokeRevisionId? = null
    private var loadedRevisionB: StrokeRevisionId? = null
    private var tweenT = -1f
    private var tweenGlobals = GlobalParams()

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

    /**
     * Update the lever values used by every subsequent warp draw —
     * preview and export alike. GL-thread command; cheap (uniforms only).
     */
    fun setGlobalParams(params: GlobalParams) {
        globalParams = params
    }

    /** Update the preview's view transform. GL-thread command; uniforms only. */
    fun setViewTransform(view: ViewTransform) {
        viewTransform = view
    }

    /**
     * Install (or clear) Fusion's photo B. [bitmap] must already be
     * cover-cropped to A's UV space (ImageLoader.decodeCover) so the two
     * images align texel-for-texel under the shared warp.
     */
    fun setImageB(bitmap: Bitmap?) {
        sourceBitmapB = bitmap
        if (!contextReady) return
        uploadImageB()
    }

    private fun uploadImageB() {
        if (sourceTextureB != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(sourceTextureB), 0)
            sourceTextureB = 0
        }
        val bitmap = sourceBitmapB ?: return
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        sourceTextureB = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureB)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Stamp a batch from the live stroke into the preview field. */
    fun stampBatch(stroke: Stroke, stamps: List<Stamp>) {
        val f = field ?: return
        stampInto(f, aspect, stroke, stamps)
    }

    /**
     * Stamp [stamps] of [stroke] into [target] — shared by the live
     * preview path and the export replay (the whole point: both fields are
     * built by literally the same code).
     */
    private fun stampInto(target: PingPongField, imageAspect: Float, stroke: Stroke, stamps: List<Stamp>) {
        val program = stampProgram ?: return
        program.use()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glUniform1f(uRadius, stroke.radius)
        GLES30.glUniform1f(uStrength, stroke.strength)
        GLES30.glUniform1f(uAspect, imageAspect)
        GLES30.glUniform1i(uMode, stroke.tool.mode.shaderId)
        GLES30.glUniform1i(uProfile, stroke.tool.profile.shaderId)
        GLES30.glUniform2f(uFieldTexel, 1f / target.width, 1f / target.height)
        GLES30.glUniform1i(uField, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for (stamp in stamps) {
            target.renderPass { readTexture ->
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
        // A rebuild may recreate field storage or switch document state;
        // force endpoint cache validation on the next scrub.
        loadedRevisionA = null
        loadedRevisionB = null
        val f = field ?: return
        f.clear()
        for (stroke in strokes) stampBatch(stroke, stroke.stamps)
    }

    /**
     * Enter/refresh a GOOvie tween: materialize the segment's endpoint
     * fields for [revisionA]/[revisionB] and mix them at [t] with
     * [lerpedGlobals] on the levers. Endpoint replays are cached by stable
     * revision ID — scrubbing within a segment is uniform-only, and stepping
     * to a neighboring segment reuses the shared endpoint by swapping
     * slots instead of replaying it.
     */
    fun tweenTo(
        revisionA: StrokeRevision,
        revisionB: StrokeRevision,
        t: Float,
        lerpedGlobals: GlobalParams,
    ) {
        val f = field ?: return
        // Adjacent-segment moves: the endpoint we need may already be
        // loaded in the other slot.
        if (revisionA.id != loadedRevisionA && revisionA.id == loadedRevisionB ||
            revisionB.id != loadedRevisionB && revisionB.id == loadedRevisionA
        ) {
            val tmpField = endpointA
            endpointA = endpointB
            endpointB = tmpField
            val tmpRevision = loadedRevisionA
            loadedRevisionA = loadedRevisionB
            loadedRevisionB = tmpRevision
        }
        if (loadedRevisionA != revisionA.id) {
            loadedRevisionA = revisionA.id
            materializeInto(ensureEndpointA(f), revisionA)
        }
        if (loadedRevisionB != revisionB.id) {
            loadedRevisionB = revisionB.id
            materializeInto(ensureEndpointB(f), revisionB)
        }
        tweenT = t.coerceIn(0f, 1f)
        tweenGlobals = lerpedGlobals
    }

    /** Leave GOOvie mode: the warp pass reads the live field again. */
    fun clearTween() {
        tweenT = -1f
    }

    /**
     * Render the whole GOOvie into an MP4 (PLAN.md §5): every tweened
     * frame draws into the MediaCodec input surface through an EGL window
     * surface made current on THIS thread's own context — same context,
     * so the source texture and endpoint fields are directly usable, no
     * share groups. Offline pacing via eglPresentationTimeANDROID.
     *
     * GL-thread command. Blocks preview redraws for the duration (seconds;
     * [onProgress] feeds the UI). Fail-soft like exportBitmap: any codec
     * or EGL failure lands in onResult(false), never a crash.
     */
    fun renderMovie(
        keyframes: List<Keyframe>,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onResult: (Boolean) -> Unit,
    ) {
        val bitmap = sourceBitmap
        val program = warpProgram
        if (!contextReady || bitmap == null || program == null || keyframes.size < 2 ||
            recordableConfig?.invoke() != true
        ) {
            onResult(false)
            return
        }
        // Preview scrub state, restored whatever happens below.
        val savedT = tweenT
        val savedGlobals = tweenGlobals

        val display = EGL14.eglGetCurrentDisplay()
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val context = EGL14.eglGetCurrentContext()

        var encoder: MovieEncoder? = null
        var eglSurface: android.opengl.EGLSurface? = null
        var ok = false
        try {
            val (vw, vh) = MovieSpec.videoSize(bitmap.width, bitmap.height)
            encoder = MovieEncoder(vw, vh, outputFile)
            eglSurface = createRecordableEglSurface(display, context, encoder.inputSurface)

            val total = MovieSpec.totalFrames(keyframes.size)
            for (frame in 0 until total) {
                val p = MovieSpec.positionAt(frame, total, keyframes.size)
                val k = GoovieTimeline.segment(p, keyframes.size)
                val t = GoovieTimeline.fraction(p, keyframes.size)
                val a = keyframes[k]
                val b = keyframes[k + 1]
                // Endpoint materialization is FBO work — surface-agnostic,
                // safe whichever window surface is current. The per-frame
                // eglMakeCurrent below is deliberately re-asserted (near
                // no-op when already current) so this loop never depends
                // on a non-local "nothing changed the surface" invariant.
                tweenTo(
                    a.revision,
                    b.revision,
                    t,
                    a.globals.lerp(b.globals, t),
                )
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    error("eglMakeCurrent(encoder) failed")
                }
                GLES30.glViewport(0, 0, vw, vh)
                drawTweenQuad(program, vw, vh)
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, MovieSpec.ptsNanos(frame))
                if (!EGL14.eglSwapBuffers(display, eglSurface)) error("eglSwapBuffers failed")
                encoder.drain(endOfStream = false)
                if (frame % 6 == 0) onProgress(frame.toFloat() / total)
            }
            encoder.finish()
            // The %6 throttle tops out ~98%; land the bar before dismissal.
            onProgress(1f)
            ok = true
        } catch (t: Throwable) {
            Log.w(TAG, "movie export failed", t)
        } finally {
            EGL14.eglMakeCurrent(display, prevDraw, prevRead, context)
            eglSurface?.let { EGL14.eglDestroySurface(display, it) }
            encoder?.release()
            tweenT = savedT
            tweenGlobals = savedGlobals
        }
        onResult(ok)
    }

    /** Full-frame warp draw of the current tween state (movie pass). */
    private fun drawTweenQuad(program: GlProgram, movieWidth: Int, movieHeight: Int) {
        // Throw, don't skip: a silent return here would swap-present
        // uninitialized frames and report the export as a SUCCESS. The
        // renderMovie catch turns this into onResult(false).
        val f = checkNotNull(field) { "field lost during movie export" }
        // Defensive: every FBO user in this file unbinds after itself
        // (stampInto, PingPongField.clear), so 0 is already bound — but
        // this draw targets the encoder window surface, and depending on
        // a non-local invariant here would let any future FBO code path
        // corrupt movie export invisibly. Make it self-sufficient.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val a = endpointA
        val b = endpointB
        val tweening = tweenT >= 0f && a != null && b != null
        val vw = movieWidth.toFloat()
        val vh = movieHeight.toFloat()
        drawWarpQuad(
            program = program,
            // Full-frame flipped (negative height, same trick as before
            // the pixel-space rework) at identity view: the movie renders
            // the document, never the navigation.
            rectX = 0f, rectY = vh, rectW = vw, rectH = -vh,
            viewportW = vw, viewportH = vh,
            viewA = 1f, viewB = 0f, viewTx = 0f, viewTy = 0f,
            imageTex = sourceTexture,
            fieldTexA = if (tweening) a!!.readTexture else f.readTexture,
            fieldTexB = if (tweening) b!!.readTexture else f.readTexture,
            imageBTex = if (sourceTextureB != 0) sourceTextureB else sourceTexture,
            hasB = sourceTextureB != 0,
            tween = if (tweening) tweenT else 0f,
            globals = if (tweening) tweenGlobals else globalParams,
            imageAspect = aspect,
            blend = false,
        )
    }

    /** EGL window surface over the codec input surface, on the context's own config. */
    private fun createRecordableEglSurface(
        display: android.opengl.EGLDisplay,
        context: android.opengl.EGLContext,
        surface: android.view.Surface,
    ): android.opengl.EGLSurface {
        // Same-config surfaces are ALWAYS compatible with the context (EGL
        // 1.4 §2.2); a re-chosen "equivalent" config is driver goodwill and
        // an EGL_BAD_MATCH away from silently killing movie export on some
        // device. The renderMovie gate on recordableConfig guarantees the
        // context's config carries EGL_RECORDABLE_ANDROID — the chooser
        // picked it under that constraint.
        val config = contextConfig(display, context) ?: run {
            // Attribute re-choose can only differ from the context's config
            // on drivers where eglQueryContext just failed — log it so a
            // field BAD_MATCH failure is diagnosable, and keep the attempt:
            // it may still match, and failing fast would only lose exports
            // that would have worked.
            Log.w(TAG, "context config unqueryable; falling back to attribute choose")
            chooseRecordableConfig(display)
        }
        val egl = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(egl != null && egl != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return egl
    }

    /** The exact EGLConfig [context] was created from, or null if unqueryable. */
    private fun contextConfig(
        display: android.opengl.EGLDisplay,
        context: android.opengl.EGLContext,
    ): android.opengl.EGLConfig? {
        val id = IntArray(1)
        if (!EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)) return null
        // With EGL_CONFIG_ID present all other attributes are ignored
        // (EGL 1.4 §3.4.1) — an exact single-config lookup.
        val attrs = intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    /** Attribute-based fallback when the context config can't be queried. */
    private fun chooseRecordableConfig(display: android.opengl.EGLDisplay): android.opengl.EGLConfig {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0,
        ) { "no recordable EGL config" }
        return configs[0]!!
    }

    private fun ensureEndpointA(like: PingPongField): PingPongField =
        endpointA?.takeIf { it.width == like.width && it.height == like.height }
            ?: PingPongField(like.width, like.height, PingPongField.hasHalfFloat(extensions))
                .also { endpointA?.delete(); endpointA = it }

    private fun ensureEndpointB(like: PingPongField): PingPongField =
        endpointB?.takeIf { it.width == like.width && it.height == like.height }
            ?: PingPongField(like.width, like.height, PingPongField.hasHalfFloat(extensions))
                .also { endpointB?.delete(); endpointB = it }

    private fun materializeInto(target: PingPongField, revision: StrokeRevision) {
        target.clear()
        for (stroke in revision.materialize()) {
            stampInto(target, aspect, stroke, stroke.stamps)
        }
    }

    // ---- GLSurfaceView.Renderer ----------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val maxTex = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        if (maxTex[0] > 0) maxTextureSize = maxTex[0]
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
            uMode = it.uniform("u_mode")
            uProfile = it.uniform("u_profile")
            uFieldTexel = it.uniform("u_fieldTexel")
        }
        warpProgram = GlProgram(GlShaders.WARP_VERT, GlShaders.WARP_FRAG).also {
            uImage = it.uniform("u_image")
            uFieldWarp = it.uniform("u_field")
            uRectPx = it.uniform("u_rectPx")
            uViewport = it.uniform("u_viewport")
            uView = it.uniform("u_view")
            uGAspect = it.uniform("u_gAspect")
            uGlobals = it.uniform("u_g")
            uFieldB = it.uniform("u_fieldB")
            uTween = it.uniform("u_tween")
            uImageB = it.uniform("u_imageB")
            uHasB = it.uniform("u_hasB")
        }

        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        quadVbo = vbo[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, quad.capacity() * 4, quad.position(0), GLES30.GL_STATIC_DRAW,
        )

        // Fresh context: previous textures/FBOs are gone. Rebuild from the
        // retained bitmap + stroke snapshot. Endpoints null WITHOUT delete
        // for the same reason as field — their names belong to the dead
        // context, and deleting them here would poke the new one.
        sourceTexture = 0
        sourceTextureB = 0
        field = null
        endpointA = null
        endpointB = null
        loadedRevisionA = null
        loadedRevisionB = null
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

        // GOOvie scrub: mix the endpoint fields and show lerped levers.
        // Live mode: field on both samplers, t=0 — mix degenerates.
        val a = endpointA
        val b = endpointB
        val tweening = tweenT >= 0f && a != null && b != null
        drawWarpQuad(
            program = program,
            // Fitted rect in pixels (top-left origin); the view similarity
            // pans/zooms/rotates it — preview only.
            rectX = fit.offsetX, rectY = fit.offsetY,
            rectW = fit.fittedWidth, rectH = fit.fittedHeight,
            viewportW = viewWidth.toFloat(), viewportH = viewHeight.toFloat(),
            viewA = viewTransform.a, viewB = viewTransform.b,
            viewTx = viewTransform.tx, viewTy = viewTransform.ty,
            imageTex = sourceTexture,
            fieldTexA = if (tweening) a!!.readTexture else f.readTexture,
            fieldTexB = if (tweening) b!!.readTexture else f.readTexture,
            imageBTex = if (sourceTextureB != 0) sourceTextureB else sourceTexture,
            hasB = sourceTextureB != 0,
            tween = if (tweening) tweenT else 0f,
            globals = if (tweening) tweenGlobals else globalParams,
            imageAspect = aspect,
            // Bitmaps upload premultiplied; without premul-correct blending
            // a transparent PNG's clear regions would render black instead
            // of showing the table. Preview only — export/movie composite
            // onto their own cleared/overwritten buffers.
            blend = true,
        )
    }

    /**
     * The one true warp draw: every consumer (preview frame, GOOvie movie
     * frame, still export) goes through here so sampler layout and
     * uniform plumbing can never drift apart between paths (they were
     * three hand-synced copies before v1 polish).
     */
    private fun drawWarpQuad(
        program: GlProgram,
        rectX: Float,
        rectY: Float,
        rectW: Float,
        rectH: Float,
        viewportW: Float,
        viewportH: Float,
        viewA: Float,
        viewB: Float,
        viewTx: Float,
        viewTy: Float,
        imageTex: Int,
        fieldTexA: Int,
        fieldTexB: Int,
        imageBTex: Int,
        hasB: Boolean,
        tween: Float,
        globals: GlobalParams,
        imageAspect: Float,
        blend: Boolean,
    ) {
        program.use()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glUniform4f(uRectPx, rectX, rectY, rectW, rectH)
        GLES30.glUniform2f(uViewport, viewportW, viewportH)
        GLES30.glUniform4f(uView, viewA, viewB, viewTx, viewTy)
        GLES30.glUniform1i(uImage, 0)
        GLES30.glUniform1i(uFieldWarp, 1)
        GLES30.glUniform1i(uFieldB, 2)
        GLES30.glUniform1i(uImageB, 3)
        GLES30.glUniform1f(uTween, tween)
        GLES30.glUniform1f(uHasB, if (hasB) 1f else 0f)
        GLES30.glUniform1f(uGAspect, imageAspect)
        GLES30.glUniform1fv(uGlobals, 6, globals.toArray(), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTexA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTexB)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageBTex)
        if (blend) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        if (blend) GLES30.glDisable(GLES30.GL_BLEND)
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
        val (fieldW, fieldH) = ExportSize.fieldDimensions(bitmap.width, bitmap.height)
        field = PingPongField(
            width = fieldW,
            height = fieldH,
            halfFloatRenderable = PingPongField.hasHalfFloat(extensions),
        )
        // Endpoint caches die with the image/context; they re-materialize
        // on the next tweenTo (counts reset so the cache can't lie).
        endpointA?.delete()
        endpointB?.delete()
        endpointA = null
        endpointB = null
        loadedRevisionA = null
        loadedRevisionB = null
        uploadImageB()
        rebuild(strokesToReplay)
    }

    /**
     * Render [strokes] applied to [source] at [source]'s full size into an
     * offscreen buffer and hand the resulting bitmap to [onResult] (GL
     * thread). Same shaders, same stamp code, fresh field at the standard
     * field density — the preview-parity path (PLAN.md §5.4). [onResult]
     * receives null when the context isn't ready.
     *
     * All temporary GL objects are released before returning; the preview
     * field and source texture are untouched.
     */
    fun exportBitmap(
        source: Bitmap,
        sourceB: Bitmap?,
        strokes: List<Stroke>,
        onResult: (Bitmap?) -> Unit,
    ) {
        if (!contextReady || warpProgram == null) {
            onResult(null)
            return
        }
        // Fail soft, never crash the GL thread: export is the app's
        // allocation peak, and a paused surface (Home pressed while the
        // export decode ran) no-ops GL calls — both make the setup below
        // throwable. An uncaught throw here kills the process.
        var tex: IntArray? = null
        var texB: IntArray? = null
        var exportField: PingPongField? = null
        var outTex: IntArray? = null
        var outFbo: IntArray? = null
        try {
            onResult(renderExport(source, sourceB, strokes,
                allocTex = { tex = it },
                allocTexB = { texB = it },
                allocField = { exportField = it },
                allocOutTex = { outTex = it },
                allocOutFbo = { outFbo = it }))
        } catch (e: Exception) {
            Log.e(TAG, "export render failed", e)
            onResult(null)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            outFbo?.let { GLES30.glDeleteFramebuffers(1, it, 0) }
            outTex?.let { GLES30.glDeleteTextures(1, it, 0) }
            tex?.let { GLES30.glDeleteTextures(1, it, 0) }
            texB?.let { GLES30.glDeleteTextures(1, it, 0) }
            exportField?.delete()
        }
    }

    private inline fun renderExport(
        source: Bitmap,
        sourceB: Bitmap?,
        strokes: List<Stroke>,
        allocTex: (IntArray) -> Unit,
        allocTexB: (IntArray) -> Unit,
        allocField: (PingPongField) -> Unit,
        allocOutTex: (IntArray) -> Unit,
        allocOutFbo: (IntArray) -> Unit,
    ): Bitmap? {
        val exportAspect = source.width.toFloat() / source.height

        // Temporary source texture.
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        allocTex(tex)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Fusion's photo B at export size (cover-cropped upstream to A's
        // UV space, same as the preview path).
        var texBId = 0
        if (sourceB != null) {
            val texB = IntArray(1)
            GLES30.glGenTextures(1, texB, 0)
            allocTexB(texB)
            texBId = texB[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, sourceB, 0)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        // Fresh field, replayed from the log (never the preview field: its
        // texel grid belongs to the preview bitmap).
        val (fieldW, fieldH) = ExportSize.fieldDimensions(source.width, source.height)
        val exportField = PingPongField(
            width = fieldW,
            height = fieldH,
            halfFloatRenderable = PingPongField.hasHalfFloat(extensions),
        )
        allocField(exportField)
        for (stroke in strokes) stampInto(exportField, exportAspect, stroke, stroke.stamps)

        // Offscreen color buffer at export size.
        val outTex = IntArray(1)
        GLES30.glGenTextures(1, outTex, 0)
        allocOutTex(outTex)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, source.width, source.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val outFbo = IntArray(1)
        GLES30.glGenFramebuffers(1, outFbo, 0)
        allocOutFbo(outFbo)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex[0], 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) return null

        // Warp pass into the offscreen buffer. u_rectPx with negative height
        // flips vertically so glReadPixels' bottom-up rows come out as a
        // top-down bitmap — no CPU row flip needed. Export renders the
        // live document, never a scrub: t = 0 and both field samplers on
        // the export field.
        val program = warpProgram!!
        GLES30.glViewport(0, 0, source.width, source.height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawWarpQuad(
            program = program,
            // Full-frame flipped (negative height: glReadPixels' bottom-up
            // rows come out top-down) at identity view — exports render
            // the document, never the navigation.
            rectX = 0f, rectY = source.height.toFloat(),
            rectW = source.width.toFloat(), rectH = -source.height.toFloat(),
            viewportW = source.width.toFloat(), viewportH = source.height.toFloat(),
            viewA = 1f, viewB = 0f, viewTx = 0f, viewTy = 0f,
            imageTex = tex[0],
            fieldTexA = exportField.readTexture,
            fieldTexB = exportField.readTexture,
            imageBTex = if (texBId != 0) texBId else tex[0],
            hasB = texBId != 0,
            tween = 0f,
            globals = globalParams,
            imageAspect = exportAspect,
            blend = false,
        )

        // Read back and package.
        val buffer = ByteBuffer.allocateDirect(source.width * source.height * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(
            0, 0, source.width, source.height,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer,
        )
        val result = createBitmap(source.width, source.height)
        buffer.rewind()
        result.copyPixelsFromBuffer(buffer)
        return result
    }

    private fun createQuadBuffer(): FloatBuffer {
        // One TRIANGLE_STRIP quad of NDC corners, shared by both passes:
        // QUAD_VERT uses it as a fullscreen quad, WARP_VERT remaps it into
        // the letterboxed image rect via u_rectPx.
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        return ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(corners)
            .apply { position(0) }
    }

    private companion object {
        const val TAG = "GlWarpRenderer"

        /** EGL_RECORDABLE_ANDROID — not in the EGL14 constant set. */
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
