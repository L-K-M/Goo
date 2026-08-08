package ch.lkmc.goo.engine.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.ExportSize
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.GlobalWobble
import ch.lkmc.goo.engine.core.GoovieTimeline
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.Lens
import ch.lkmc.goo.engine.core.MovieSpec
import ch.lkmc.goo.engine.core.ReplayCheckpoints
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.StampBounds
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeRevision
import ch.lkmc.goo.engine.core.StrokeRevisionId
import ch.lkmc.goo.engine.core.ViewTransform
import ch.lkmc.goo.engine.core.lerp
import ch.lkmc.goo.engine.core.leversAt
import ch.lkmc.goo.engine.core.leverProgress
import ch.lkmc.goo.engine.core.tweenProgress
import ch.lkmc.goo.engine.media.GifEncoder
import ch.lkmc.goo.engine.media.MovieEncoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
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
 * The stamp pass draws a fullscreen quad per stamp but scissors it to the
 * brush disc ([StampBounds]), because every fragment outside that disc
 * would write back the texel it just read. The field is deliberately
 * small (≤[FIELD_MAX_DIM] on the long side — displacement is smooth by
 * nature, half preview resolution is beyond Liquify-mesh fidelity), so
 * the unscissored version already fit in a frame; scissoring is what
 * keeps a long pumped hold or an N-fold symmetry fan-out affordable,
 * where stamp COUNT rather than field size is the problem.
 */
class GlWarpRenderer(
    /**
     * Called on the GL thread when the context can't run the engine, with
     * the string RESOURCE to show — the renderer has no locale, and the
     * message reaches a user.
     */
    private val onUnsupported: (Int) -> Unit,
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

    // A saved field state and the stroke prefix it stands for, so undo
    // does not replay a pumped hold's few hundred stamps to reach a
    // state it had a moment ago (REVIEW.md G-6). Both are null together;
    // either being stale only costs a full replay.
    private var checkpoint: FieldSnapshot? = null
    private var checkpointStrokes: List<Stroke>? = null

    /** Extension list of the current context; set once per onSurfaceCreated. */
    private var extensions = ""

    /** GL_MAX_TEXTURE_SIZE of the current context; export sizing input. */
    var maxTextureSize = 2048
        private set

    /** Aspect (w/h) of the current image; 1 until an image is set. */
    private var aspect = 1f

    // Uniform locations (stamp pass), resolved once per context.
    private var uField = 0
    private var uTarget = 0
    private var uCenter = 0
    private var uDelta = 0
    private var uRadius = 0
    private var uStrength = 0
    private var uAspect = 0
    private var uMode = 0
    private var uProfile = 0
    private var uGuarded = 0
    private var uFieldTexel = 0
    private var uImage = 0
    private var uFieldWarp = 0
    private var uRectPx = 0
    private var uViewport = 0
    private var uView = 0
    private var uGAspect = 0
    private var uGlobals = 0
    private var uLens = 0
    private var uLensType = 0
    private var uLensCount = 0
    private var uShowFreeze = 0
    private var uFieldB = 0
    private var uTween = 0
    private var uImageB = 0
    private var uHasB = 0

    // Reused across frames: the warp pass runs every draw, and rebuilding
    // these per frame would allocate on the render thread (PLAN.md §4.1).
    private val lensPack = FloatArray(Lens.CAPACITY * 4)
    private val lensTypes = IntArray(Lens.CAPACITY)

    /** Live lever values; uploaded to the warp pass every draw. */
    private var globalParams = GlobalParams()

    /** The modulation rig (proposal 0009); still by default. */
    private var wobble = GlobalWobble()
    /** Whether the preview should show the frost sheen (Freeze armed). */
    private var showFreezeMask = false

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
    // the warp pass mixes them (PLAN.md §4.1). tweenT < 0 means live
    // mode. The cache is keyed by revision ID: revisions are immutable
    // and their ids are never reused, so the same id always replays to
    // the same field — a soundness the old stroke-COUNT key never had (a
    // count could name different strokes after an undo, which is why
    // `rebuild` used to have to invalidate here).
    private var endpointA: PingPongField? = null
    private var endpointB: PingPongField? = null

    // ---- Rewind targets (proposal 0008, ADR 0003) ----------------------

    /**
     * Resolves a [Stroke.targetRevision] to that revision's strokes.
     *
     * Handed in from outside, exactly as keyframes are: a stroke records
     * WHICH revision, never a pointer to one, so `StrokeRevision` stays
     * immutable and ignorant of GL while the log stays ignorant of the
     * renderer. Null (unset, or an id that no longer resolves) makes a
     * Rewind stamp a no-op rather than a crash, which is the only safe
     * answer at a GL boundary.
     */
    var revisionResolver: ((Long) -> List<Stroke>?)? = null

    /**
     * Scratch fields for materializing Rewind targets, one per nesting
     * level.
     *
     * Nesting is reachable — rewind, then punch, then rewind to THAT
     * frame — and the reference graph is a finite DAG pointing strictly
     * backwards, so the recursion terminates. A level is only allocated
     * if a document actually reaches it, which is why this is a growable
     * list rather than a fixed cap: a cap would have to choose between
     * wasting memory nobody needs and silently replaying a deep document
     * wrong.
     */
    private val recallFields = mutableListOf<PingPongField>()
    private var recallDepth = 0
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

    /**
     * Install the modulation rig used by the movie walk. The preview
     * evaluates its own phase and arrives here as ordinary lever values
     * through [setGlobalParams]; this is the export path's copy, so a
     * rendered movie wobbles even though nothing is driving a clock.
     */
    fun setWobble(rig: GlobalWobble) {
        wobble = rig
    }

    /**
     * Show or hide the frost sheen over frozen regions. Preview chrome,
     * never document: no export path reads it.
     */
    fun setShowFreezeMask(show: Boolean) {
        showFreezeMask = show
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
        // FIRST, before a single uniform is set. recallTarget replays a
        // whole revision through this same function, and the recursive
        // call sets every uniform for ITS strokes — radius, strength,
        // mode, profile, guard, texel. Materializing after the uniform
        // block would therefore leave this stroke drawing with the last
        // inner stroke's parameters, which is not a subtle error but is
        // an invisible one: GL cannot run in this environment, and every
        // test here passes on a shader that never executed.
        val recallTexture = recallTarget(stroke, like = target)
        program.use()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glUniform1f(uRadius, stroke.radius)
        GLES30.glUniform1f(uStrength, stroke.strength)
        GLES30.glUniform1f(uAspect, imageAspect)
        GLES30.glUniform1i(uMode, stroke.tool.mode.shaderId)
        GLES30.glUniform1i(uProfile, stroke.tool.profile.shaderId)
        // The varnish brakes every mode but the two that apply and
        // remove it (StampMode.respectsFreeze).
        GLES30.glUniform1i(uGuarded, if (stroke.tool.mode.respectsFreeze) 1 else 0)
        GLES30.glUniform2f(uFieldTexel, 1f / target.width, 1f / target.height)
        GLES30.glUniform1i(uField, 0)
        // Unit 1 is the Rewind target. Bound for every stroke because
        // GLSL cannot branch on whether a sampler is bound, and an
        // unsampled texture unit costs nothing; for anything but RECALL
        // this binds the field to itself, which the shader never reads.
        GLES30.glUniform1i(uTarget, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, recallTexture ?: target.readTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for (stamp in stamps) {
            // Only the brush disc can change; everything else the pass
            // would write is an identity copy of itself (REVIEW.md G-3).
            val rect = StampBounds.of(
                cx = stamp.cx,
                cy = stamp.cy,
                radius = stroke.radius,
                aspect = imageAspect,
                width = target.width,
                height = target.height,
                // Melt's lobe is not a disc — it reaches well below the
                // stamp, and a symmetric rect would clip the wax.
                profile = stroke.tool.profile,
            )
            target.renderPassIn(rect) { readTexture ->
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, readTexture)
                GLES30.glUniform2f(uCenter, stamp.cx, stamp.cy)
                GLES30.glUniform2f(uDelta, stamp.dx, stamp.dy)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
        }
        GLES30.glDisableVertexAttribArray(0)
        // Leave unit 1 clean. A field texture still bound to it while a
        // LATER pass renders INTO that same field is a feedback loop —
        // undefined results, and the kind that shows as an intermittent
        // smear rather than an error.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * The texture a Rewind stroke blends toward, or null when [stroke]
     * is not a Rewind (or its target no longer resolves).
     *
     * Materialized on demand into this nesting level's scratch field.
     * There is no cache by revision id here on purpose: a Rewind stroke
     * is replayed only during a full rebuild or an export, both of which
     * are already bulk operations, and a per-id field cache would hold a
     * full-size RGBA16F texture per distinct target for the lifetime of
     * the renderer. The live painting path never comes through here —
     * `stampBatch` stamps into the live field, and the target for THAT
     * is materialized once when the stroke begins.
     */
    private fun recallTarget(stroke: Stroke, like: PingPongField): Int? {
        val id = stroke.targetRevision ?: return null
        val strokes = revisionResolver?.invoke(id) ?: return null
        val field = ensureRecallField(recallDepth, like)
        recallDepth++
        try {
            field.clear()
            for (s in strokes) stampInto(field, aspect, s, s.stamps)
        } finally {
            recallDepth--
        }
        return field.readTexture
    }

    private fun ensureRecallField(depth: Int, like: PingPongField): PingPongField {
        while (recallFields.size <= depth) {
            recallFields.add(
                PingPongField(like.width, like.height, PingPongField.hasHalfFloat(extensions)),
            )
        }
        val existing = recallFields[depth]
        if (existing.width == like.width && existing.height == like.height) return existing
        existing.delete()
        return PingPongField(like.width, like.height, PingPongField.hasHalfFloat(extensions))
            .also { recallFields[depth] = it }
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

    /**
     * Replay [strokes] into the field — undo/redo/reset path.
     *
     * Starts from the checkpoint when one still covers a prefix of the
     * log (REVIEW.md G-6) and from identity otherwise, then takes a new
     * checkpoint if [ReplayCheckpoints] says the saving has become worth
     * a blit. Everything about it is a cache: if the snapshot is
     * unusable or stale the result is identical, only slower.
     */
    fun rebuild(strokes: List<Stroke>) {
        strokesToReplay = strokes
        // Deliberately does NOT touch the endpoint cache: it is keyed by
        // revision ID now, and a revision the log has moved away from (or
        // truncated) is still the same immutable document state, so its
        // materialized field stays correct. Keyframes pinning it must
        // keep showing it — that independence is the point. Paths that
        // recreate field STORAGE (context loss, new image) null the cache
        // themselves in recreateGlObjects.
        val f = field ?: return
        val plan = ReplayCheckpoints.plan(strokes, checkpointStrokes)
        val restored = plan.restoreCheckpoint &&
            checkpoint?.let { f.restoreFrom(it) } == true
        val from = if (restored) plan.replayFrom else 0
        if (!restored) f.clear()
        for (i in from until strokes.size) {
            if (i == plan.snapshotAfter) takeCheckpoint(f, strokes, i)
            val stroke = strokes[i]
            stampBatch(stroke, stroke.stamps)
        }
    }

    /** Snapshot the field as it stands, standing for `strokes[0 until count]`. */
    private fun takeCheckpoint(f: PingPongField, strokes: List<Stroke>, count: Int) {
        val snapshot = checkpoint?.takeIf { it.width == f.width && it.height == f.height }
            ?: FieldSnapshot(f.width, f.height, PingPongField.hasHalfFloat(extensions))
                .also { checkpoint?.delete(); checkpoint = it }
        if (!snapshot.isUsable) {
            // The driver refused the framebuffer; stop asking on every
            // rebuild and let replay run from identity forever after.
            checkpoint = null
            checkpointStrokes = null
            return
        }
        f.copyInto(snapshot)
        // Copy the prefix: the caller's list may be a view onto a log
        // that keeps changing, and a checkpoint that silently redefines
        // which strokes it stands for would be a correctness bug rather
        // than a slow cache.
        checkpointStrokes = strokes.subList(0, count).toList()
    }

    /** Forget the checkpoint; the field storage it described is gone. */
    private fun dropCheckpoint() {
        checkpoint?.delete()
        checkpoint = null
        checkpointStrokes = null
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
        speed: Float,
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
            // Locals as well as the teardown vars: the frame walk is an
            // inline lambda, and a captured `var` gets no smart cast.
            val movieEncoder = MovieEncoder(vw, vh, outputFile)
            encoder = movieEncoder
            val surface = createRecordableEglSurface(display, context, movieEncoder.inputSurface)
            eglSurface = surface

            val total = MovieSpec.totalFrames(keyframes.size, speed)
            // The per-frame eglMakeCurrent below is deliberately
            // re-asserted (near no-op when already current) so this loop
            // never depends on a non-local "nothing changed the surface"
            // invariant — endpoint materialization inside the walk is FBO
            // work, surface-agnostic but not surface-preserving in spirit.
            eachTweenFrame(keyframes, total, MovieSpec.FPS) { frame ->
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    error("eglMakeCurrent(encoder) failed")
                }
                drawTweenQuad(program, vw, vh)
                EGLExt.eglPresentationTimeANDROID(display, surface, MovieSpec.ptsNanos(frame))
                if (!EGL14.eglSwapBuffers(display, surface)) error("eglSwapBuffers failed")
                movieEncoder.drain(endOfStream = false)
                if (frame % 6 == 0) onProgress(frame.toFloat() / total)
            }
            movieEncoder.finish()
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

    /**
     * Render the whole GOOvie into a looping (or one-shot) GIF.
     *
     * Same tween walk as [renderMovie], different sink: there is no codec
     * surface, so frames go into an offscreen FBO at GIF size, come back
     * through glReadPixels, and are palettised and LZW-packed straight
     * into [outputFile] one frame at a time ([GifEncoder]). No EGL surface
     * juggling and no recordable-config requirement — an FBO is legal on
     * whatever surface is current.
     *
     * GL-thread command, fail-soft like [renderMovie]: any GL or IO
     * failure lands in onResult(false).
     */
    fun renderGif(
        keyframes: List<Keyframe>,
        speed: Float,
        loop: Boolean,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onResult: (Boolean) -> Unit,
    ) {
        val bitmap = sourceBitmap
        val program = warpProgram
        if (!contextReady || bitmap == null || program == null || keyframes.size < 2) {
            onResult(false)
            return
        }
        // Preview scrub state, restored whatever happens below.
        val savedT = tweenT
        val savedGlobals = tweenGlobals

        val (gw, gh) = MovieSpec.gifSize(bitmap.width, bitmap.height)
        // Frame rate, not strip length, absorbs an over-long GOOvie: the
        // GIF keeps the MP4's duration and gets choppier instead.
        val fps = MovieSpec.gifFps(keyframes.size, speed)
        val total = MovieSpec.totalFrames(keyframes.size, speed, fps)
        val delay = MovieSpec.gifDelayCentis(fps)

        var outTex: IntArray? = null
        var outFbo: IntArray? = null
        var ok = false
        try {
            val texture = IntArray(1)
            GLES30.glGenTextures(1, texture, 0)
            outTex = texture
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, gw, gh, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST,
            )
            val framebuffer = IntArray(1)
            GLES30.glGenFramebuffers(1, framebuffer, 0)
            outFbo = framebuffer
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture[0], 0,
            )
            check(
                GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                    GLES30.GL_FRAMEBUFFER_COMPLETE,
            ) { "gif framebuffer incomplete" }

            val buffer = ByteBuffer.allocateDirect(gw * gh * 4).order(ByteOrder.nativeOrder())
            val pixels = IntArray(gw * gh)
            BufferedOutputStream(FileOutputStream(outputFile)).use { stream ->
                val gif = GifEncoder(stream, gw, gh, loop)
                eachTweenFrame(keyframes, total, fps) { frame ->
                    // tweenTo materializes endpoints through its own FBO
                    // binds, so the target is re-asserted every frame.
                    drawTweenQuad(program, gw, gh, framebuffer = framebuffer[0], flipY = true)
                    buffer.rewind()
                    GLES30.glReadPixels(
                        0, 0, gw, gh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer,
                    )
                    readArgb(buffer, pixels)
                    gif.addFrame(pixels, delay)
                    // Denser than the MP4's throttle: a GIF frame costs a
                    // palette pass, so the bar has to move on fewer frames.
                    if (frame % 3 == 0) onProgress(frame.toFloat() / total)
                }
                gif.finish()
            }
            onProgress(1f)
            ok = true
        } catch (t: Throwable) {
            Log.w(TAG, "gif export failed", t)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            outFbo?.let { GLES30.glDeleteFramebuffers(1, it, 0) }
            outTex?.let { GLES30.glDeleteTextures(1, it, 0) }
            tweenT = savedT
            tweenGlobals = savedGlobals
        }
        onResult(ok)
    }

    /**
     * Walk one full pass over the strip, leaving the renderer on frame
     * [frame]'s tween before each [body] call. Shared by both export
     * sinks so MP4 and GIF can never disagree about what a GOOvie is.
     *
     * Each keyframe pins its own revision: the movie renders exactly what
     * the strip shows, whatever the editor's undo cursor sits on.
     */
    private inline fun eachTweenFrame(
        keyframes: List<Keyframe>,
        totalFrames: Int,
        /** Frame rate of THIS encode — the GIF ladder is not [MovieSpec.FPS]. */
        fps: Int,
        body: (Int) -> Unit,
    ) {
        // Cap against the loop this export will actually produce. The
        // speed control can shorten it by 4x after the rig was dialled,
        // and the GIF ladder can drop the frame rate on top of that, so
        // the seconds have to be derived from what is about to be
        // encoded rather than from the document.
        val safe = wobble.cappedFor(
            if (totalFrames > 1 && fps > 0) (totalFrames - 1).toFloat() / fps else 0f,
        )
        for (frame in 0 until totalFrames) {
            val p = MovieSpec.positionAt(frame, totalFrames, keyframes.size)
            val k = GoovieTimeline.segment(p, keyframes.size)
            val t = GoovieTimeline.fraction(p, keyframes.size)
            val a = keyframes[k]
            val b = keyframes[k + 1]
            // Same curve the strip previews with, so the export cannot
            // disagree with what the scrub showed.
            val curve = a.easing
            // The wobble rides ON TOP of the interpolated levers, so a
            // strip and a wobble give authored motion with a shimmer on
            // it rather than one overriding the other. Phase comes from
            // the frame index, never a clock, so this walk is what the
            // preview shows and two renders agree exactly.
            val lerped = a.globals.lerp(b.globals, leverProgress(t, curve))
            val phase = if (totalFrames > 1) frame.toFloat() / (totalFrames - 1) else 0f
            tweenTo(
                a.revision,
                b.revision,
                tweenProgress(t, curve),
                leversAt(lerped, safe, phase),
            )
            body(frame)
        }
    }

    /** RGBA8 readback rows → opaque ARGB ints, in place, no allocation. */
    private fun readArgb(buffer: ByteBuffer, pixels: IntArray) {
        buffer.rewind()
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            buffer.get() // alpha: GIF has none, and the warp pass is opaque
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Full-frame warp draw of the current tween state (movie pass). */
    private fun drawTweenQuad(
        program: GlProgram,
        movieWidth: Int,
        movieHeight: Int,
        framebuffer: Int = 0,
        /** Flip vertically for glReadPixels' bottom-up row order (GIF). */
        flipY: Boolean = false,
    ) {
        // Throw, don't skip: a silent return here would swap-present
        // uninitialized frames and report the export as a SUCCESS. The
        // renderMovie catch turns this into onResult(false).
        val f = checkNotNull(field) { "field lost during movie export" }
        // Bind (and size) the target here, not at the call site: every FBO
        // user in this file unbinds after itself (stampInto,
        // PingPongField.clear), so the tween walk's endpoint
        // materialization leaves 0 bound — the GIF pass needs its own FBO
        // re-asserted per frame, and depending on a non-local invariant
        // would let any future FBO code path corrupt an export invisibly.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, movieWidth, movieHeight)
        val a = endpointA
        val b = endpointB
        val tweening = tweenT >= 0f && a != null && b != null
        val vw = movieWidth.toFloat()
        val vh = movieHeight.toFloat()
        drawWarpQuad(
            program = program,
            // MediaCodec is an EGL window surface like preview, so it uses
            // the ordinary upright rectangle. The offscreen paths (GIF,
            // still export) flip: glReadPixels hands back bottom-up rows.
            rectX = 0f,
            rectY = if (flipY) vh else 0f,
            rectW = vw,
            rectH = if (flipY) -vh else vh,
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
            onUnsupported(R.string.error_gpu_unsupported)
            return
        }
        contextReady = true

        stampProgram = GlProgram(GlShaders.QUAD_VERT, GlShaders.STAMP_FRAG).also {
            uField = it.uniform("u_field")
            uTarget = it.uniform("u_target")
            uCenter = it.uniform("u_center")
            uDelta = it.uniform("u_delta")
            uRadius = it.uniform("u_radius")
            uStrength = it.uniform("u_strength")
            uAspect = it.uniform("u_aspect")
            uMode = it.uniform("u_mode")
            uProfile = it.uniform("u_profile")
            uGuarded = it.uniform("u_guarded")
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
            uLens = it.uniform("u_lens")
            uLensType = it.uniform("u_lensType")
            uLensCount = it.uniform("u_lensCount")
            uShowFreeze = it.uniform("u_showFreeze")
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
        // Same reasoning: drop the checkpoint WITHOUT deleting it, since
        // its texture name belongs to the context that just died.
        checkpoint = null
        checkpointStrokes = null
        rebuildImageState()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        // The console deck (ui/theme MeltDeck) behind the letterbox.
        GLES30.glClearColor(0.043f, 0.055f, 0.110f, 1f)
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
            // Preview only, and only while the tool is armed: the mask is
            // something to see while painting it, not part of the picture.
            showFreeze = showFreezeMask,
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
        /**
         * Light the frost sheen over the freeze mask. Defaults OFF, so a
         * path that does not mention it cannot render chrome into a
         * saved picture — the two export paths rely on exactly that.
         */
        showFreeze: Boolean = false,
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
        uploadLenses(globals)
        GLES30.glUniform1f(uShowFreeze, if (showFreeze) 1f else 0f)
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

    /**
     * Fill the lens uniform pack from [globals] and upload it.
     *
     * Lenses pulled to zero are dropped rather than uploaded as no-ops,
     * so a rack the user has emptied costs the shader nothing — and the
     * count, not the array length, is what bounds the loop.
     */
    private fun uploadLenses(globals: GlobalParams) {
        val lenses = globals.activeLenses()
        if (lenses.isEmpty()) {
            // The count alone is enough — the shader's loop breaks on it
            // immediately. A zero-count glUniform*v is a legal no-op, but
            // some ES drivers log it, and there is nothing to say here.
            GLES30.glUniform1i(uLensCount, 0)
            return
        }
        lenses.forEachIndexed { i, lens ->
            val base = i * 4
            lensPack[base] = lens.u
            lensPack[base + 1] = lens.v
            lensPack[base + 2] = lens.radius
            lensPack[base + 3] = lens.strength
            lensTypes[i] = lens.type.shaderId
        }
        // Only `count` entries are read, so the tail can stay stale.
        GLES30.glUniform4fv(uLens, lenses.size, lensPack, 0)
        GLES30.glUniform1iv(uLensType, lenses.size, lensTypes, 0)
        GLES30.glUniform1i(uLensCount, lenses.size)
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
        // on the next tweenTo (keys cleared so the cache can't lie about
        // fields this context no longer owns).
        endpointA?.delete()
        endpointB?.delete()
        endpointA = null
        endpointB = null
        loadedRevisionA = null
        loadedRevisionB = null
        // The checkpoint describes the OLD field's contents (and possibly
        // its dimensions, since a new image resizes the field), so it has
        // to go with it. rebuild() below takes a fresh one if the log has
        // earned it.
        dropCheckpoint()
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
