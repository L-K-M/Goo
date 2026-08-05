package ch.lkmc.goo.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ch.lkmc.goo.data.ExportFormat
import ch.lkmc.goo.data.ImageLoader
import ch.lkmc.goo.data.ImageSaver
import ch.lkmc.goo.data.MovieSaver
import ch.lkmc.goo.data.OnboardingPrefs
import ch.lkmc.goo.engine.core.BrushDynamics
import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.GoovieTimeline
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.lerp
import ch.lkmc.goo.engine.core.ExportSize
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeResampler
import ch.lkmc.goo.engine.core.StrokeRevision
import ch.lkmc.goo.engine.gl.GlWarpRenderer
import ch.lkmc.goo.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * The Goo room's state holder.
 *
 * Owns the document (the [StrokeLog]) and the live-stroke pipeline
 * (touch → [StrokeResampler] → stamps). The GL renderer is deliberately
 * NOT owned here — the screen wires engine commands to it — so the
 * ViewModel stays a pure JVM citizen: everything in it is unit-testable
 * and survives the GL surface being destroyed and rebuilt underneath it.
 *
 * Stroke lifecycle: [beginStroke] → [extendStroke] (each call returns the
 * new stamps for the GL side to apply incrementally) → [endStroke]
 * (commits to the log). Undo/redo/reset return the stroke list the engine
 * must rebuild from, or null when they were no-ops.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val imageLoader: ImageLoader,
    private val imageSaver: ImageSaver,
    private val movieSaver: MovieSaver,
    private val onboardingPrefs: OnboardingPrefs,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val bitmap: Bitmap? = null,
        val error: String? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val canReset: Boolean = false,
        val exporting: Boolean = false,
        /** Aspect-space brush radius (fraction of image height). */
        val brushRadius: Float = DEFAULT_RADIUS,
        /** Fusion's photo B, cover-cropped to A's UV space; null = none. */
        val bitmapB: Bitmap? = null,
        val tool: BrushTool = BrushTool.SMEAR,
        /** User strength slider; scaled per tool at stroke creation. */
        val brushStrength: Float = DEFAULT_STRENGTH,
        /** Mirror toggle: every stamp gets a vertically reflected twin. */
        val mirrored: Boolean = false,
        /** Global-effect levers — live document state, not history. */
        val globals: GlobalParams = GlobalParams(),
        /** First-ever image: float the "drag to goo" hint until a stroke. */
        val showHint: Boolean = false,
        /** GOOvie mode: the strip is open; canvas edits are paused. */
        val goovieMode: Boolean = false,
        /** Captured keyframes in playback order (pins into the log). */
        val keyframes: List<Keyframe> = emptyList(),
        /** Continuous strip position: [0, size-1]; ints sit on keyframes. */
        val scrubPos: Float = 0f,
        /** Looping playback is running. */
        val playing: Boolean = false,
        /** Strip selection for delete/reorder; -1 = none. */
        val selectedKeyframe: Int = -1,
        /** MP4 export in flight; [movieProgress] in [0,1] feeds the bar. */
        val exportingMovie: Boolean = false,
        val movieProgress: Float = 0f,
    )

    /** Everything the GL thread needs to show one scrub position. */
    data class TweenRequest(
        val revisionA: StrokeRevision,
        val revisionB: StrokeRevision,
        val t: Float,
        val lerpedGlobals: GlobalParams,
    )

    /** One-shot outcomes of export/share runs, consumed by the screen. */
    sealed interface ExportEvent {
        /** Saved; [toGallery] false means the API 26–28 app-storage path. */
        data class Saved(val toGallery: Boolean) : ExportEvent
        data class ShareReady(val uri: Uri, val mimeType: String) : ExportEvent
        data class Failed(val message: String) : ExportEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _exportEvents = Channel<ExportEvent>(Channel.BUFFERED)
    val exportEvents: Flow<ExportEvent> = _exportEvents.receiveAsFlow()

    /**
     * Bridge to the screen-owned GL renderer: runs a block on the GL
     * thread and requests a redraw. Set while the surface exists; the
     * ViewModel deliberately never owns the renderer (it outlives the
     * surface).
     */
    var engineBridge: ((GlWarpRenderer.() -> Unit) -> Unit)? = null

    private var sessionFile: File? = null
    private var sessionFileB: File? = null
    private var exportJob: Job? = null

    private val log = StrokeLog()
    private var resampler: StrokeResampler? = null
    private var liveStamps = mutableListOf<Stamp>()
    private var pumpJob: Job? = null
    private var pumpPoint: Pair<Float, Float>? = null
    private var mirrorLive = false

    /**
     * Parameters frozen at [beginStroke]: the stamps were spaced and
     * GPU-stamped with these, so the committed stroke must record exactly
     * them — a second finger moving the Size slider mid-drag must not make
     * replays disagree with what was drawn.
     */
    private var liveParams: Stroke? = null

    /** Strokes the engine should replay when (re)building its field. */
    val strokesSnapshot: List<Stroke> get() = log.strokes

    init {
        _uiState.update { it.copy(showHint = !onboardingPrefs.smearHintSeen) }
        savedStateHandle.get<FloatArray>(KEY_GLOBALS)?.let { a ->
            GlobalParams.fromArray(a)?.let { g ->
                _uiState.update { it.copy(globals = g) }
                refreshHistoryFlags()
            }
        }
        val route = savedStateHandle.toRoute<EditorRoute>()
        viewModelScope.launch {
            try {
                // After process death the picker grant on the original URI
                // is gone; the session copy made on first import is the
                // stable source (that's why it exists).
                val restored = savedStateHandle.get<String>(KEY_SESSION_FILE)
                    ?.let(::File)
                    ?.takeIf(File::exists)
                val file = restored ?: imageLoader.importImage(route.imageUri.toUri()).also {
                    savedStateHandle[KEY_SESSION_FILE] = it.path
                }
                sessionFile = file
                // A restored Fusion photo B must survive the sweep too.
                val restoredB = savedStateHandle.get<String>(KEY_SESSION_B)
                    ?.let(::File)
                    ?.takeIf(File::exists)
                // Previous sessions' copies are garbage now (REVIEW.md G-1).
                imageLoader.sweepSessions(keep = setOfNotNull(file, restoredB))
                val bitmap = imageLoader.decodePreview(file)
                _uiState.update { it.copy(loading = false, bitmap = bitmap) }
                // Restore B after A: the cover-crop needs A's dimensions.
                // The screen syncs bitmapB to the engine like it does A.
                // A failed B decode degrades to no-B (key cleared so the
                // next restore doesn't retry a bad file) — it must never
                // take photo A's whole editor down with it.
                if (restoredB != null) {
                    try {
                        val bitmapB =
                            imageLoader.decodeCover(restoredB, bitmap.width, bitmap.height)
                        sessionFileB = restoredB
                        _uiState.update { it.copy(bitmapB = bitmapB) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        savedStateHandle.remove<String>(KEY_SESSION_B)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "could not open image") }
            }
        }
    }

    fun setBrushRadius(radius: Float) {
        _uiState.update { it.copy(brushRadius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)) }
    }

    /**
     * Import Fusion's photo B: copy to a session file (picker grants are
     * transient), cover-crop to A's UV space, hand to the screen via
     * state. Replaces any previous B; the mask survives a swap.
     */
    fun importSecondImage(uri: Uri) {
        val bitmap = _uiState.value.bitmap ?: return
        viewModelScope.launch {
            try {
                val previousB = sessionFileB
                val file = imageLoader.importImage(uri)
                // Decode BEFORE committing the session pointer: an
                // undecodable pick must fail this import only — never
                // poison later exports/restores or evict a working B.
                val bitmapB = try {
                    imageLoader.decodeCover(file, bitmap.width, bitmap.height)
                } catch (e: Exception) {
                    // Deliberately includes cancellation: the just-copied
                    // file has no owner yet (sessionFileB still points at
                    // the old B, the key was never written), so deleting
                    // here is orphan cleanup, not a stray side effect.
                    file.delete()
                    throw e
                }
                sessionFileB = file
                savedStateHandle[KEY_SESSION_B] = file.path
                previousB?.delete()
                _uiState.update { it.copy(bitmapB = bitmapB) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportEvents.trySend(
                    ExportEvent.Failed(e.message ?: "could not open second image"),
                )
            }
        }
    }

    /**
     * Remove Fusion's photo B: delete its session copy, clear state, and
     * fall back to Smear — a FUSE brush with no B paints an invisible mask
     * (u_hasB gates the display, not the accumulation), which reads as a
     * dead tool. The painted mask itself stays in the field: UnGoo erases
     * it, and a later B reveals it again (same policy as a swap).
     */
    fun clearSecondImage() {
        sessionFileB?.delete()
        sessionFileB = null
        savedStateHandle.remove<String>(KEY_SESSION_B)
        _uiState.update { it.copy(bitmapB = null, tool = BrushTool.SMEAR) }
    }

    /** Engine-side failures (GL thread) surface as the screen's error state. */
    fun reportEngineError(message: String) {
        _uiState.update { it.copy(loading = false, error = message) }
    }

    // ---- Live stroke ---------------------------------------------------
    // Directional tools stamp along the resampled drag path; pumped tools
    // (Grow/Shrink/Smooth/UnGoo) stamp on a clock at the held point — the
    // KPT feel where holding still keeps working. Mirror twins are added
    // at emission, so stored strokes replay identically everywhere
    // (undo rebuilds, export) with no mirror logic downstream.

    fun beginStroke(u: Float, v: Float) {
        val bitmap = _uiState.value.bitmap ?: return
        val state = _uiState.value
        // The strip is playback territory; the canvas doesn't paint there.
        if (state.goovieMode) return
        if (state.showHint) {
            onboardingPrefs.smearHintSeen = true
            _uiState.update { it.copy(showHint = false) }
        }
        val aspect = bitmap.width.toFloat() / bitmap.height
        val radius = state.brushRadius
        val tool = state.tool
        mirrorLive = state.mirrored
        liveParams = Stroke(
            tool = tool,
            radius = radius,
            strength = state.brushStrength * tool.strengthScale,
            stamps = emptyList(),
        )
        liveStamps = mutableListOf()
        if (tool.pumped) {
            pumpPoint = Pair(u, v)
            // Stamp once at touch-down, before the pump's first tick: a
            // quick tap otherwise starts and ends the stroke between ticks
            // and applies nothing at all (KPT applied one shot per click).
            val first = emit(listOf(Stamp(u, v, 0f, 0f)))
            if (first.isNotEmpty()) {
                liveParams?.let { params ->
                    engineBridge?.invoke { stampBatch(params, first) }
                }
            }
            startPump()
        } else {
            resampler = StrokeResampler(radius = radius, aspect = aspect)
                .also { it.begin(u, v) }
        }
    }

    /** @return newly produced stamps for incremental GPU stamping. */
    fun extendStroke(u: Float, v: Float): List<Stamp> {
        if (liveParams?.tool?.pumped == true) {
            // Pumped tools just track the finger; the ticker emits.
            pumpPoint = Pair(u, v)
            return emptyList()
        }
        val r = resampler ?: return emptyList()
        val fresh = r.extend(u, v, mutableListOf())
        return emit(fresh)
    }

    /** Record [fresh] (plus mirror twins) and return what to stamp now. */
    private fun emit(fresh: List<Stamp>): List<Stamp> {
        if (fresh.isEmpty()) return fresh
        val tool = liveParams?.tool ?: return emptyList()
        val batch = if (mirrorLive) {
            fresh.flatMap { listOf(it, tool.mirrorStamp(it)) }
        } else {
            fresh
        }
        liveStamps.addAll(batch)
        return batch
    }

    private fun startPump() {
        pumpJob?.cancel()
        pumpJob = viewModelScope.launch {
            while (true) {
                val (u, v) = pumpPoint ?: break
                val params = liveParams ?: break
                val batch = emit(listOf(Stamp(u, v, 0f, 0f)))
                engineBridge?.invoke { stampBatch(params, batch) }
                delay(BrushDynamics.PUMP_INTERVAL_MS)
            }
        }
    }

    private fun stopPump() {
        pumpJob?.cancel()
        pumpJob = null
        pumpPoint = null
    }

    /** Commit the live stroke. @return it, if it produced any stamps. */
    fun endStroke(): Stroke? {
        stopPump()
        val params = liveParams ?: return null
        resampler = null
        liveParams = null
        if (liveStamps.isEmpty()) return null
        val stroke = params.copy(stamps = liveStamps.toList())
        liveStamps = mutableListOf()
        log.push(stroke)
        refreshHistoryFlags()
        return stroke
    }

    /** The (frozen) parameters of the stroke being drawn right now. */
    fun liveStrokeParams(): Stroke = liveParams ?: _uiState.value.let {
        Stroke(
            tool = it.tool,
            radius = it.brushRadius,
            strength = it.brushStrength * it.tool.strengthScale,
            stamps = emptyList(),
        )
    }

    fun setTool(tool: BrushTool) {
        // A second finger can tap a chip mid-gesture. Commit (not discard)
        // the live stroke — its stamps are already in the GPU field, and
        // committing keeps screen ≡ document (same policy as gesture
        // CANCEL); discarding would leave visible warp no log entry knows
        // about, breaking undo and export.
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        _uiState.update { it.copy(tool = tool) }
    }

    fun setBrushStrength(value: Float) {
        _uiState.update { it.copy(brushStrength = value.coerceIn(0.05f, 1f)) }
    }

    fun toggleMirror() {
        _uiState.update { it.copy(mirrored = !it.mirrored) }
    }

    // ---- History -------------------------------------------------------
    // A history action while a second finger is mid-stroke first discards
    // the live stroke. Its stamps are already on the GPU field, so the
    // caller MUST rebuild even when the log op itself was a no-op (null) —
    // otherwise the discarded head stays visible while the document says
    // it never happened, and undo/export silently disagree with the
    // screen. Hence: null only when nothing was discarded AND the log
    // didn't move.

    fun undo(): List<Stroke>? = withDiscardGuard { log.undo() }

    fun redo(): List<Stroke>? = withDiscardGuard { log.redo() }

    /**
     * Shared spine of undo/redo: discard any live stroke (its stamps are
     * on the GPU field), run the log op, and when the op was a no-op but
     * a stroke WAS in flight, return the current list anyway so the
     * caller still rebuilds (wiping the orphaned head).
     */
    private inline fun withDiscardGuard(op: () -> List<Stroke>?): List<Stroke>? {
        val discarded = discardLiveStroke()
        val restored = op()
        refreshHistoryFlags()
        return restored ?: log.strokes.takeIf { discarded }
    }

    /** @return the (empty) stroke list to rebuild with, or null if no-op. */
    fun reset(): List<Stroke>? {
        val discarded = discardLiveStroke()
        // Reset means "back to the photo": levers zero too.
        setGlobals(GlobalParams())
        if (log.isEmpty) return log.strokes.takeIf { discarded }
        log.reset()
        refreshHistoryFlags()
        return log.strokes
    }

    // ---- Global levers -------------------------------------------------
    // Levers are live document state, not history entries (PLAN.md §4.1):
    // pulling one back to center undoes it exactly, so undo/redo stay
    // stroke-only. The screen syncs the renderer whenever they change.

    fun setGlobals(globals: GlobalParams) {
        _uiState.update { it.copy(globals = globals) }
        savedStateHandle[KEY_GLOBALS] = globals.toArray()
        refreshHistoryFlags()
    }

    // ---- GOOvies -------------------------------------------------------
    // A keyframe pins an immutable stroke revision plus globals — the
    // document stays the single source of truth and endpoints materialize by
    // replay (PLAN.md §4.1). Pins survive later undo branches because their
    // revision identity and ancestry never change.

    fun toggleGoovie() {
        // A second finger can tap the Movie bead mid-gesture. Commit (not
        // discard) the live stroke — same policy as setTool and gesture
        // CANCEL: its stamps are already on the field, and committing
        // keeps screen ≡ document. Entry then gates beginStroke, so no
        // live stroke can exist inside goovie mode.
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        _uiState.update {
            if (it.goovieMode) {
                it.copy(goovieMode = false, playing = false)
            } else {
                it.copy(goovieMode = true, scrubPos = GoovieTimeline.clamp(it.scrubPos, it.keyframes.size))
            }
        }
    }

    fun captureKeyframe() {
        // Punching mid-gesture (a second finger, or the brush-rail bead):
        // commit the live stroke — same policy as setTool/toggleGoovie:
        // its stamps are already on the field, and the pin should include
        // the drag anyway. In goovie mode no live stroke can exist, so
        // this is a no-op there.
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        _uiState.update { s ->
            if (s.keyframes.size >= MAX_KEYFRAMES) return@update s
            val kf = Keyframe(revision = log.currentRevision, globals = s.globals)
            val list = s.keyframes + kf
            s.copy(
                keyframes = list,
                selectedKeyframe = list.size - 1,
                scrubPos = (list.size - 1).toFloat(),
            )
        }
    }

    fun selectKeyframe(index: Int) {
        _uiState.update { s ->
            if (index !in s.keyframes.indices) return@update s
            s.copy(selectedKeyframe = index, scrubPos = index.toFloat(), playing = false)
        }
    }

    fun deleteSelectedKeyframe() {
        _uiState.update { s ->
            val i = s.selectedKeyframe
            if (i !in s.keyframes.indices) return@update s
            val list = s.keyframes.toMutableList().apply { removeAt(i) }
            val newSelected = if (list.isEmpty()) -1 else i.coerceAtMost(list.size - 1)
            s.copy(
                keyframes = list,
                selectedKeyframe = newSelected,
                scrubPos = GoovieTimeline.clamp(newSelected.toFloat(), list.size),
                playing = false,
            )
        }
    }

    /** Move the selected keyframe one slot left/right in playback order. */
    fun moveSelectedKeyframe(delta: Int) {
        _uiState.update { s ->
            val i = s.selectedKeyframe
            val j = i + delta
            if (i !in s.keyframes.indices || j !in s.keyframes.indices) return@update s
            val list = s.keyframes.toMutableList().apply {
                val tmp = this[i]
                this[i] = this[j]
                this[j] = tmp
            }
            s.copy(keyframes = list, selectedKeyframe = j, scrubPos = j.toFloat())
        }
    }

    fun scrubTo(p: Float) {
        _uiState.update {
            it.copy(
                scrubPos = GoovieTimeline.clamp(p, it.keyframes.size),
                selectedKeyframe = -1,
                playing = false,
            )
        }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { s ->
            if (playing && s.keyframes.size < 2) s
            else s.copy(playing = playing, selectedKeyframe = if (playing) -1 else s.selectedKeyframe)
        }
    }

    /** Frame-loop tick from the screen while [UiState.playing]. */
    fun advancePlayback(dtSeconds: Float) {
        _uiState.update { s ->
            if (!s.playing) s
            else s.copy(scrubPos = GoovieTimeline.advance(s.scrubPos, dtSeconds, s.keyframes.size))
        }
    }

    /**
     * Export the GOOvie as MP4: the GL thread renders every tweened frame
     * into the encoder surface (renderMovie), then the result lands in the
     * Movies collection [share] false, or the share sheet [share] true —
     * through the same ExportEvent flow the image path uses.
     */
    fun exportGoovie(share: Boolean) {
        val s = _uiState.value
        if (s.exportingMovie || s.keyframes.size < 2) return
        val bridge = engineBridge
        if (bridge == null) {
            _exportEvents.trySend(ExportEvent.Failed("editor is not ready"))
            return
        }
        _uiState.update { it.copy(exportingMovie = true, movieProgress = 0f, playing = false) }
        val keyframes = s.keyframes
        // The work file's mkdirs/cleanup is disk I/O — off the main thread.
        viewModelScope.launch {
            // A failure here must not crash the coroutine or wedge the
            // exporting flag (GLM PR review): report like a render failure.
            val workFile = try {
                movieSaver.createWorkFile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(exportingMovie = false, movieProgress = 0f) }
                _exportEvents.send(ExportEvent.Failed(e.message ?: "movie export failed"))
                return@launch
            }
            bridge.invoke {
                renderMovie(
                    keyframes = keyframes,
                    outputFile = workFile,
                    // MutableStateFlow.update is thread-safe; GL thread is fine.
                    onProgress = { p -> _uiState.update { it.copy(movieProgress = p) } },
                    onResult = { ok -> onMovieRendered(ok, workFile, share) },
                )
            }
        }
    }

    /** GL-thread completion → IO save → main-thread events. */
    private fun onMovieRendered(ok: Boolean, workFile: File, share: Boolean) {
        viewModelScope.launch {
            try {
                if (!ok) {
                    _exportEvents.send(ExportEvent.Failed("movie export failed"))
                    return@launch
                }
                if (share) {
                    val uri = movieSaver.writeShareCache(workFile)
                    _exportEvents.send(ExportEvent.ShareReady(uri, MovieSaver.MIME_MP4))
                } else {
                    val result = movieSaver.save(workFile)
                    _exportEvents.send(
                        ExportEvent.Saved(toGallery = result is MovieSaver.SaveResult.Gallery),
                    )
                }
            } catch (e: Exception) {
                _exportEvents.send(ExportEvent.Failed(e.message ?: "movie save failed"))
            } finally {
                workFile.delete()
                _uiState.update { it.copy(exportingMovie = false, movieProgress = 0f) }
                // The movie loop left the endpoint cache at ITS last
                // segment; the screen's tween effect won't refire (nothing
                // it keys on changed), so re-sync the preview's scrub
                // position explicitly. Cache contents stay valid (the log
                // can't have changed under a queued rebuild without
                // invalidation), so this is at most two replays.
                tweenRequest()?.let { r ->
                    engineBridge?.invoke {
                        tweenTo(r.revisionA, r.revisionB, r.t, r.lerpedGlobals)
                    }
                }
            }
        }
    }

    /** The engine payload for the current scrub position; null = live. */
    fun tweenRequest(): TweenRequest? {
        val s = _uiState.value
        if (!s.goovieMode || s.keyframes.size < 2) return null
        val size = s.keyframes.size
        val k = GoovieTimeline.segment(s.scrubPos, size)
        val t = GoovieTimeline.fraction(s.scrubPos, size)
        val a = s.keyframes[k]
        val b = s.keyframes[k + 1]
        return TweenRequest(
            revisionA = a.revision,
            revisionB = b.revision,
            t = t,
            lerpedGlobals = a.globals.lerp(b.globals, t),
        )
    }

    /**
     * Drop the in-flight stroke without committing. @return true when
     * there was one — callers then owe the engine a rebuild, because the
     * stroke's stamped head is on the GPU field already.
     */
    private fun discardLiveStroke(): Boolean {
        val hadLive = liveParams != null
        stopPump()
        resampler = null
        liveParams = null
        liveStamps = mutableListOf()
        return hadLive
    }

    // ---- Export --------------------------------------------------------

    /** Render at export resolution and save to the gallery/app storage. */
    fun export(format: ExportFormat, quality: Int) = runExport { bitmap ->
        val result = imageSaver.save(bitmap, format, quality)
        _exportEvents.send(ExportEvent.Saved(toGallery = result is ImageSaver.SaveResult.Gallery))
    }

    /** Render at export resolution into the share cache and announce it. */
    fun share(format: ExportFormat, quality: Int) = runExport { bitmap ->
        val uri = imageSaver.writeShareCache(bitmap, format, quality)
        _exportEvents.send(ExportEvent.ShareReady(uri, format.mimeType))
    }

    /**
     * The export spine: decode the session file at the capped export size
     * (EXIF-rotated), have the GL thread replay the stroke log against it
     * with the identical shaders, then hand the result to [sink].
     */
    private fun runExport(sink: suspend (Bitmap) -> Unit) {
        if (_uiState.value.exporting) return
        val bridge = engineBridge
        val session = sessionFile
        if (bridge == null || session == null) {
            _exportEvents.trySend(ExportEvent.Failed("editor is not ready"))
            return
        }
        // Main thread: the log is main-confined; snapshot before bridging.
        val strokes = log.strokes
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(exporting = true) }
            var full: Bitmap? = null
            var fullB: Bitmap? = null
            var warped: Bitmap? = null
            try {
                val maxTex = suspendCancellableCoroutine { cont ->
                    bridge { if (cont.isActive) cont.resume(maxTextureSize) }
                }
                val cap = ExportSize.exportLongSideCap(maxTex)
                val decoded = imageLoader.decodePreview(session, cap)
                full = decoded
                // Fusion's B at export size, cover-cropped to A's export
                // dims — same UV space as the preview pair. Never recycled
                // on the cancel path (a queued GL upload may still hold
                // it, same as `full`); GC reclaims.
                val decodedB = sessionFileB?.let {
                    imageLoader.decodeCover(it, decoded.width, decoded.height)
                }
                fullB = decodedB
                val rendered = suspendCancellableCoroutine { cont ->
                    bridge {
                        exportBitmap(decoded, decodedB, strokes) { out ->
                            // Cancelled mid-render: nobody will ever see
                            // this bitmap — free ~50 MB now, not at GC.
                            if (cont.isActive) cont.resume(out) else out?.recycle()
                        }
                    }
                }
                val result = rendered
                    ?: throw IllegalStateException("engine could not render the export")
                warped = result
                full.recycle()
                full = null
                fullB?.recycle()
                fullB = null
                sink(rendered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportEvents.send(ExportEvent.Failed(e.message ?: "export failed"))
            } finally {
                // On cancellation a queued GL upload may still hold the
                // source bitmap — drop the references and let GC reclaim
                // rather than recycling under the GL thread's feet.
                if (coroutineContext.isActive) {
                    full?.recycle()
                    fullB?.recycle()
                    warped?.recycle()
                }
                _uiState.update { it.copy(exporting = false) }
            }
        }
    }

    /**
     * Called when the GL surface goes away mid-export (rotation, error
     * pane): the bridged continuations would otherwise never resume —
     * the dead surface's queue drops events — leaving the export wedged
     * with `exporting = true` forever.
     */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        // The job's finally lands a dispatcher tick later; restore idle
        // state synchronously so callers see it immediately (idempotent
        // with the finally's own reset). exportingMovie too: a queued
        // renderMovie is DROPPED when the GL thread exits (surface
        // teardown/rotation) — its onResult never fires, and the VM
        // outlives the surface, so the flag would wedge forever.
        _uiState.update { it.copy(exporting = false, exportingMovie = false, movieProgress = 0f) }
    }

    private fun refreshHistoryFlags() {
        _uiState.update {
            it.copy(
                canUndo = log.canUndo,
                canRedo = log.canRedo,
                // Levers count: an image warped only by levers must still
                // offer "back to the photo".
                canReset = !log.isEmpty || !it.globals.isIdentity,
            )
        }
    }

    // No explicit bitmap.recycle() on clear: on an activity-finish path the
    // ViewModel is cleared BEFORE the GL surface detaches, and a queued
    // setImage could still upload the bitmap — recycling here would crash
    // the GL thread. minSdk 26 bitmaps are native-heap; GC reclaims them.

    companion object {
        const val DEFAULT_RADIUS = 0.12f
        const val MIN_RADIUS = 0.04f
        const val MAX_RADIUS = 0.28f

        /** Strength slider default: strong but shy of finger-lock 1:1. */
        const val DEFAULT_STRENGTH = 0.85f

        private const val KEY_SESSION_FILE = "sessionFile"
        private const val KEY_SESSION_B = "sessionFileB"
        private const val KEY_GLOBALS = "globals"

        /** KPT Goo's strip held 64; so does ours. */
        const val MAX_KEYFRAMES = 64
    }
}
