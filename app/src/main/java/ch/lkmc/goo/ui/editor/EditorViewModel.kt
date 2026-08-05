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
import ch.lkmc.goo.engine.core.CropRect
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
import ch.lkmc.goo.engine.core.StrokeRevisionId
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
        /** A replacement Fusion source is being copied and decoded. */
        val importingPhotoB: Boolean = false,
        val tool: BrushTool = BrushTool.SMEAR,
        /** User strength slider; scaled per tool at stroke creation. */
        val brushStrength: Float = DEFAULT_STRENGTH,
        /** Mirror toggle: every stamp gets a vertically reflected twin. */
        val mirrored: Boolean = false,
        /** Global-effect levers — live document state, not history. */
        val globals: GlobalParams = GlobalParams(),
        /** First-ever image: float the "drag to goo" hint until a stroke. */
        val showHint: Boolean = false,
        /** GOOvie mode: the strip is open. Gooing still works (see [goovieLive]). */
        val goovieMode: Boolean = false,
        /**
         * In GOOvie mode, the canvas shows the LIVE document instead of the
         * scrub tween — set by any edit, cleared by any strip navigation.
         * Stamps only ever land in the live field, so painting while a
         * tween is on screen would be invisible; this flag is what makes
         * "touch the canvas in the strip and it just goos" honest.
         */
        val goovieLive: Boolean = false,
        /**
         * The live document revision's id — exactly what a punch would
         * pin. Ids are never reused, so id equality IS document-state
         * equality; this is what keyframes get compared against. Null
         * only until the first refresh.
         */
        val revisionId: StrokeRevisionId? = null,
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
        /** A crop is active (the overlay offers "full picture" only then). */
        val cropped: Boolean = false,
    ) {
        /**
         * The selected pin is behind the live document: Punch and Update
         * would now produce different keyframes. Drives the strip's nudge
         * and the brush rail's Update chip — the affordance that answers
         * "I edited the photo but my keyframe didn't change".
         */
        val selectedKeyframeStale: Boolean
            get() {
                val kf = keyframes.getOrNull(selectedKeyframe) ?: return false
                return kf.revisionId != revisionId || kf.globals != globals
            }

        /**
         * There is work in this session that leaving would destroy.
         *
         * The document lives in this ViewModel and nowhere else until
         * SOL-34 lands project persistence, so Back is as destructive as
         * Reset — and unlike Reset it isn't undoable. Everything a user
         * would call "my work" counts:
         *
         * - [canReset]: committed strokes, or levers off identity;
         * - [keyframes]: a punched GOOvie strip, even over an unwarped
         *   photo (pins carry lever values too);
         * - [bitmapB]: a Fusion photo picked and cover-cropped — chosen
         *   work, even before a mask stroke lands on it;
         * - [cropped]: a reframe, which *clears* strokes and levers, so
         *   canReset says "nothing to lose" precisely when a crop is the
         *   only thing left to lose.
         *
         * Deliberately NOT cleared by exporting: a saved JPEG is a
         * picture, not a document — leaving still forfeits every stroke
         * behind it. One extra tap beats silently ending a session the
         * user could have kept editing.
         */
        val hasUnsavedWork: Boolean
            get() = canReset || keyframes.isNotEmpty() || bitmapB != null || cropped
    }

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
    private var secondImageJob: Job? = null
    private var secondImageRequestId = 0L

    /**
     * Active crop in ORIGINAL-image space (null = full frame). The
     * session file always keeps the original bytes — the rect is applied
     * at decode, so export quality never pays for a re-encode and
     * "back to the full picture" is always possible.
     */
    private var cropRect: CropRect? = null

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
                // Restore the crop before the decode that uses it. A
                // malformed rect restores as null (full frame) — the
                // strokes it framed died with the process anyway.
                cropRect = CropRect.fromArray(savedStateHandle.get<FloatArray>(KEY_CROP))
                // A restored Fusion photo B must survive the sweep too.
                val restoredB = savedStateHandle.get<String>(KEY_SESSION_B)
                    ?.let(::File)
                    ?.takeIf(File::exists)
                // Previous sessions' copies are garbage now (REVIEW.md G-1).
                imageLoader.sweepSessions(keep = setOfNotNull(file, restoredB))
                val bitmap = imageLoader.decodePreview(file, crop = cropRect)
                _uiState.update {
                    it.copy(loading = false, bitmap = bitmap, cropped = cropRect != null)
                }
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
        val requestId = ++secondImageRequestId
        secondImageJob?.cancel()
        _uiState.update { it.copy(importingPhotoB = true) }
        secondImageJob = viewModelScope.launch {
            var candidateFile: File? = null
            var candidateBitmap: Bitmap? = null
            try {
                val previousB = sessionFileB
                val file = imageLoader.importImage(uri)
                candidateFile = file
                // Decode BEFORE committing the session pointer: an
                // undecodable pick must fail this import only — never
                // poison later exports/restores or evict a working B.
                val bitmapB = imageLoader.decodeCover(file, bitmap.width, bitmap.height)
                candidateBitmap = bitmapB
                sessionFileB = file
                savedStateHandle[KEY_SESSION_B] = file.path
                previousB?.delete()
                _uiState.update { it.copy(bitmapB = bitmapB) }
                candidateFile = null
                candidateBitmap = null
            } catch (e: CancellationException) {
                candidateBitmap?.recycle()
                candidateFile?.delete()
                throw e
            } catch (e: Exception) {
                candidateBitmap?.recycle()
                candidateFile?.delete()
                if (requestId == secondImageRequestId) {
                    // Keep a working old B during a failed swap. On an
                    // initial failure, leave no invisible FUSE tool active.
                    _uiState.update {
                        it.copy(tool = if (it.bitmapB == null) BrushTool.SMEAR else it.tool)
                    }
                    _exportEvents.trySend(
                        ExportEvent.Failed(e.message ?: "could not open second image"),
                    )
                }
            } finally {
                if (requestId == secondImageRequestId) {
                    _uiState.update { it.copy(importingPhotoB = false) }
                    secondImageJob = null
                }
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
        secondImageRequestId++
        secondImageJob?.cancel()
        secondImageJob = null
        sessionFileB?.delete()
        sessionFileB = null
        savedStateHandle.remove<String>(KEY_SESSION_B)
        _uiState.update {
            it.copy(bitmapB = null, importingPhotoB = false, tool = BrushTool.SMEAR)
        }
    }

    /**
     * Reframe the picture. [rect] is relative to the image currently on
     * screen (itself possibly cropped) and composes into original space;
     * null restores the full original frame.
     *
     * Crop is a document-space change: strokes and keyframes record UVs
     * of the frame they were painted on, so the document restarts —
     * history cleared hard (undoing across a reframe would replay
     * old-space strokes onto new-space pixels), keyframes dropped,
     * levers zeroed. Validate-then-commit like [importSecondImage]: a
     * failed decode leaves the current document untouched. The screen's
     * bitmap/bitmapB/globals sync effects carry the swap to the GL side.
     */
    fun applyCrop(rect: CropRect?) {
        val s = _uiState.value
        if (s.exporting || s.exportingMovie || s.bitmap == null) return
        val file = sessionFile ?: return
        val absolute = rect
            ?.let { (cropRect ?: CropRect.FULL).compose(it) }
            ?.takeIf { !it.isFullFrame }
        if (absolute == null && cropRect == null) return
        discardLiveStroke()
        viewModelScope.launch {
            try {
                val bitmap = imageLoader.decodePreview(file, crop = absolute)
                // B follows into the new frame; a failure degrades to
                // no-B rather than blocking the crop (restore policy).
                val fileB = sessionFileB
                var bitmapB: Bitmap? = null
                if (fileB != null) {
                    try {
                        bitmapB = imageLoader.decodeCover(fileB, bitmap.width, bitmap.height)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        clearSecondImage()
                    }
                }
                cropRect = absolute
                savedStateHandle[KEY_CROP] = absolute?.toArray()
                savedStateHandle[KEY_GLOBALS] = GlobalParams().toArray()
                log.clearHistory()
                _uiState.update {
                    it.copy(
                        bitmap = bitmap,
                        bitmapB = bitmapB,
                        cropped = absolute != null,
                        globals = GlobalParams(),
                        keyframes = emptyList(),
                        scrubPos = 0f,
                        playing = false,
                        selectedKeyframe = -1,
                        goovieMode = false,
                    )
                }
                refreshHistoryFlags()
                // The old preview bitmap is NOT recycled here: a queued
                // GL setImage may still be uploading it (same policy as
                // the on-clear note at the bottom of this file).
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // The point of validate-then-commit is that a failed decode
                // leaves the document standing — and OOM is this path's
                // likeliest failure, yet it's an Error that would sail past
                // catch(Exception) and crash instead of degrading.
                _exportEvents.send(ExportEvent.Failed("not enough memory to crop"))
            } catch (e: Exception) {
                _exportEvents.send(ExportEvent.Failed(e.message ?: "could not crop"))
            }
        }
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

    /** @return true when a stroke actually started; false = canvas locked. */
    fun beginStroke(u: Float, v: Float): Boolean {
        val bitmap = _uiState.value.bitmap ?: return false
        val state = _uiState.value
        // A movie render owns the GL thread for seconds; stamps queued
        // behind it would land on a field nobody is looking at, and the
        // export's own snapshot wouldn't contain them either.
        if (state.exportingMovie) return false
        // No invisible goo: while B is importing (or missing entirely for
        // FUSE) a mask stroke would paint nothing the user can see/judge.
        if (state.importingPhotoB ||
            state.tool == BrushTool.FUSE && state.bitmapB == null
        ) {
            return false
        }
        // Gooing inside the strip is allowed — that IS how you author the
        // next keyframe. What isn't possible is painting into a tween: the
        // stamps go to the live field, so drop the preview to live first.
        goLive()
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
        } else {
            resampler = StrokeResampler(radius = radius, aspect = aspect)
                .also { it.begin(u, v) }
        }
        if (tool.stampsOnDown) {
            // Radial/field tools and Fusion all have useful stationary
            // semantics. Directional brushes still wait for movement.
            val first = emit(listOf(Stamp(u, v, 0f, 0f)))
            if (first.isNotEmpty()) {
                liveParams?.let { params ->
                    engineBridge?.invoke { stampBatch(params, first) }
                }
            }
        }
        if (tool.pumped) {
            startPump()
        }
        return true
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
                // beginStroke already applied the one-shot click. Wait before
                // repeating so a quick tap is exactly one application.
                delay(BrushDynamics.PUMP_INTERVAL_MS)
                val (u, v) = pumpPoint ?: break
                val params = liveParams ?: break
                val batch = emit(listOf(Stamp(u, v, 0f, 0f)))
                engineBridge?.invoke { stampBatch(params, batch) }
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
        if (_uiState.value.showHint) {
            // A touch-down or sub-spacing directional drag is not a
            // successful edit. Retire onboarding only after real work lands.
            onboardingPrefs.smearHintSeen = true
            _uiState.update { it.copy(showHint = false) }
        }
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
        // History moves the document, so the strip must show the document.
        goLive()
        val discarded = discardLiveStroke()
        val restored = op()
        refreshHistoryFlags()
        return restored ?: log.strokes.takeIf { discarded }
    }

    /** @return the (empty) stroke list to rebuild with, or null if no-op. */
    fun reset(): List<Stroke>? {
        goLive()
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
        // Unreachable from the strip today (the levers bead leaves goovie
        // mode before opening the panel), but every other document
        // mutation goes live and this one shouldn't be the exception that
        // depends on a UI routing detail staying put. No-op outside the
        // strip. Raised by GLM on PR #26.
        goLive()
        _uiState.update { it.copy(globals = globals) }
        savedStateHandle[KEY_GLOBALS] = globals.toArray()
        refreshHistoryFlags()
    }

    // ---- GOOvies -------------------------------------------------------
    // A keyframe pins (revision, globals): the immutable StrokeRevision
    // it was punched from, plus the levers. Endpoints materialize by
    // replay (PLAN.md §4.1). Keyframes live in session memory only, like
    // the stroke log whose revisions they pin.
    //
    // Revisions, not prefix counts, are what make each keyframe its own
    // thing: the editor's undo cursor moves freely — all the way back to
    // the untouched photo, which is how you punch a closing frame — and
    // no pin moves with it. Counts indexed into the CURRENT state, so
    // undo used to flatten the entire strip.
    //
    // Editing stays LIVE while the strip is open, and nothing an edit can
    // do disturbs a pin: the revision a keyframe holds is immutable, so
    // gooing, undo, redo, Reset and a truncated redo branch all leave it
    // exactly where it was punched. (The renderer's endpoint cache is
    // keyed by the same never-reused revision ids, which is why `rebuild`
    // no longer invalidates it.) What the strip really can't do is paint
    // into a tween — stamps land in the live field, which a tween isn't
    // showing — hence [goLive].

    /**
     * Drop the strip's preview from the tween to the live document, so the
     * goo about to land is actually visible. Pins are untouched: Punch
     * adds a keyframe for the new state, Update re-pins the selected one.
     * No-op outside the strip, and idempotent inside it.
     */
    private fun goLive() {
        val s = _uiState.value
        if (!s.goovieMode || s.goovieLive) return
        _uiState.update { it.copy(goovieLive = true, playing = false) }
        // Eagerly, not via the screen's tween effect: the first stamps of
        // this stroke reach the GL thread before the next recomposition
        // does, and they must not be swallowed by a stale tween.
        engineBridge?.invoke { clearTween() }
    }

    fun toggleGoovie() {
        // A second finger can tap the Movie bead mid-gesture. Commit (not
        // discard) the live stroke — same policy as setTool and gesture
        // CANCEL: its stamps are already on the field, and committing
        // keeps screen ≡ document.
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        _uiState.update {
            if (it.goovieMode) {
                it.copy(goovieMode = false, playing = false, goovieLive = false)
            } else {
                it.copy(
                    goovieMode = true,
                    // Entry shows the movie, not the live head — the strip
                    // is a player first.
                    goovieLive = false,
                    scrubPos = GoovieTimeline.clamp(it.scrubPos, it.keyframes.size),
                )
            }
        }
    }

    fun captureKeyframe() {
        // Punching mid-gesture (a second finger, or the brush-rail bead):
        // commit the live stroke — same policy as setTool/toggleGoovie:
        // its stamps are already on the field, and the pin should include
        // the drag anyway.
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        _uiState.update { s ->
            if (s.keyframes.size >= MAX_KEYFRAMES) return@update s
            val kf = Keyframe(revision = log.currentRevision, globals = s.globals)
            val list = s.keyframes + kf
            s.copy(
                keyframes = list,
                selectedKeyframe = list.size - 1,
                scrubPos = (list.size - 1).toFloat(),
                // The new pin IS the live state, so the strip can show the
                // movie again without the picture changing under the user.
                goovieLive = false,
            )
        }
    }

    /**
     * Re-pin the selected keyframe to the live document — the missing
     * "edit this step" verb. There is no per-keyframe canvas to edit: you
     * goo the photo until it looks right, then re-punch the keyframe that
     * should hold it. Without this, edits made after a punch could only
     * ever become a NEW keyframe.
     */
    fun repunchSelectedKeyframe() {
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        _uiState.update { s ->
            val i = s.selectedKeyframe
            if (i !in s.keyframes.indices) return@update s
            val list = s.keyframes.toMutableList().apply {
                this[i] = Keyframe(revision = log.currentRevision, globals = s.globals)
            }
            s.copy(
                keyframes = list,
                scrubPos = i.toFloat(),
                playing = false,
                // Same as a punch: the pin now matches what's on screen.
                goovieLive = false,
            )
        }
    }

    fun selectKeyframe(index: Int) {
        _uiState.update { s ->
            if (index !in s.keyframes.indices) return@update s
            s.copy(
                selectedKeyframe = index,
                scrubPos = index.toFloat(),
                playing = false,
                goovieLive = false,
            )
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
                // Any strip navigation means "show me the movie again".
                goovieLive = false,
            )
        }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { s ->
            if (playing && s.keyframes.size < 2) s
            else s.copy(
                playing = playing,
                selectedKeyframe = if (playing) -1 else s.selectedKeyframe,
                goovieLive = if (playing) false else s.goovieLive,
            )
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
        // The keyframes pin their own revisions, so this is the whole
        // document the render needs — the log's cursor doesn't come into it.
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
                // invalidation), so this is at most two replays. A null
                // request means the strip was showing live goo when the
                // export started — restore that, don't leave a tween on.
                val r = tweenRequest()
                engineBridge?.invoke {
                    if (r == null) clearTween()
                    else tweenTo(r.revisionA, r.revisionB, r.t, r.lerpedGlobals)
                }
            }
        }
    }

    /** The engine payload for the current scrub position; null = live. */
    fun tweenRequest(): TweenRequest? {
        val s = _uiState.value
        if (!s.goovieMode || s.goovieLive || s.keyframes.size < 2) return null
        val size = s.keyframes.size
        val k = GoovieTimeline.segment(s.scrubPos, size)
        val t = GoovieTimeline.fraction(s.scrubPos, size)
        val a = s.keyframes[k]
        val b = s.keyframes[k + 1]
        // Straight from the pins: the log's current cursor is irrelevant
        // to what the strip shows.
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
        // The crop rides along: strokes record UVs of the cropped frame,
        // so the pair must stay consistent whatever happens mid-flight.
        val strokes = log.strokes
        val crop = cropRect
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
                val decoded = imageLoader.decodePreview(session, cap, crop)
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
                // Feeds UiState.selectedKeyframeStale — every path that
                // moves the log or the levers already lands here.
                revisionId = log.currentRevision.id,
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
        private const val KEY_CROP = "crop"

        /** KPT Goo's strip held 64; so does ours. */
        const val MAX_KEYFRAMES = 64
    }
}
