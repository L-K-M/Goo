package ch.lkmc.goo.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ch.lkmc.goo.R
import ch.lkmc.goo.data.CropRecord
import ch.lkmc.goo.data.ExportFormat
import ch.lkmc.goo.data.ImageLoader
import ch.lkmc.goo.data.ImageSaver
import ch.lkmc.goo.data.KeyframeRecord
import ch.lkmc.goo.data.MovieFormat
import ch.lkmc.goo.data.MovieSaver
import ch.lkmc.goo.data.OnboardingPrefs
import ch.lkmc.goo.data.ProjectDocument
import ch.lkmc.goo.data.ProjectStore
import ch.lkmc.goo.engine.core.BrushDynamics
import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.CropRect
import ch.lkmc.goo.engine.core.DealLevers
import ch.lkmc.goo.engine.core.Easing
import ch.lkmc.goo.engine.core.EchoOffset
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.GlobalWobble
import ch.lkmc.goo.engine.core.LeverWobble
import ch.lkmc.goo.engine.core.GooMe
import ch.lkmc.goo.engine.core.GooWhip
import ch.lkmc.goo.engine.core.GoovieTimeline
import ch.lkmc.goo.engine.core.leverProgress
import ch.lkmc.goo.engine.core.tweenProgress
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.lerp
import ch.lkmc.goo.engine.core.ExportSize
import ch.lkmc.goo.engine.core.MovieSpeed
import ch.lkmc.goo.engine.core.PumpStamps
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeResampler
import ch.lkmc.goo.engine.core.Symmetry
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
    private val projectStore: ProjectStore,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val bitmap: Bitmap? = null,
        /**
         * The room could not open: a string RESOURCE, not a sentence.
         * Wording that reaches a user is a resource everywhere in this
         * app (the ViewModel has no Context and no locale), so exception
         * text goes to Logcat and the user gets a translated line.
         */
        @StringRes val error: Int? = null,
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
        /**
         * Kaleidoscope dial: every stamp is fanned into this many copies
         * rotated about the image center. 1 = off. Composes with
         * [mirrored] rather than replacing it (see [Symmetry]).
         */
        val sectors: Int = 1,
        /**
         * Echo's source anchor in image UV, or null when none is
         * planted. With Echo selected and no anchor, the next touch
         * plants one instead of painting.
         */
        val echoAnchor: Pair<Float, Float>? = null,
        /** Global-effect levers — live document state, not history. */
        val globals: GlobalParams = GlobalParams(),
        /**
         * The modulation rig (proposal 0009). Document state beside the
         * levers it modulates, and still by default.
         */
        val wobble: GlobalWobble = GlobalWobble(),
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
        /**
         * Counts the document changes no other field here can tell apart:
         * a re-crop (both rects just read as [cropped]) and a Fusion photo
         * swap (both just read as "has a B"). Only [signature] uses it —
         * without it, re-cropping a saved project would look identical to
         * the state that was saved, and Back would leave without asking.
         */
        val documentEpoch: Int = 0,
        /** A project write is in flight; Back waits for it. */
        val savingProject: Boolean = false,
        /**
         * [signature] as of the last successful save (or of the project
         * this session opened); null = never saved.
         */
        val savedSignature: DocumentSignature? = null,
    ) {
        /**
         * Everything a save would write, compared by value — the test for
         * "is what's on screen already on disk".
         */
        val signature: DocumentSignature
            get() = DocumentSignature(revisionId, globals, keyframes, documentEpoch)
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
         * There is something in this session a save would capture.
         * Everything a user would call "my work" counts:
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
         * behind it.
         */
        val hasWork: Boolean
            get() = canReset || keyframes.isNotEmpty() || bitmapB != null || cropped

        /**
         * The document on screen is not the document on disk.
         *
         * The one question the editor asks about saving, and it drives
         * everything: the checkpoint loop, the write on the way out, and
         * whether the rail's Save bead is lit. There is deliberately no
         * "unsaved work" flag beside it any more — leaving saves, so the
         * only thing that can be unwritten is the last few seconds.
         */
        val hasUnwrittenChanges: Boolean
            get() = hasWork && signature != savedSignature
    }

    /**
     * Value-identity for the document: what a save writes, in the terms
     * this state can compare. Revision ids are never reused, so
     * [revisionId] equality is stroke-log equality (see [UiState.revisionId]).
     */
    data class DocumentSignature(
        val revisionId: StrokeRevisionId?,
        val globals: GlobalParams,
        val keyframes: List<Keyframe>,
        val documentEpoch: Int,
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
        /**
         * Saved; [toGallery] false means the API 26–28 app-storage path.
         * [video] picks the folder the message names: MP4s land in
         * Movies/, everything else (stills, GIFs) in Pictures/.
         */
        data class Saved(val toGallery: Boolean, val video: Boolean = false) : ExportEvent
        data class ShareReady(val uri: Uri, val mimeType: String) : ExportEvent

        /** [reason] is a string resource; see [UiState.error]. */
        data class Failed(@StringRes val reason: Int) : ExportEvent
    }

    /** Why a project write is happening — it decides what the user sees. */
    enum class SaveReason {
        /** The rail's Save bead: stay in the room, say it worked. */
        EXPLICIT,

        /** On the way out: the room closes once the write lands. */
        LEAVE,

        /**
         * The editor went to the background. Silent on success — the user
         * is somewhere else — but a FAILURE is still announced when they
         * come back, because it means the work they walked away from is
         * not on disk.
         */
        AUTOSAVE,
    }

    /** One-shot outcomes of a project write. */
    sealed interface ProjectEvent {
        /** The document is on disk. [leave] closes the room. */
        data class Saved(val leave: Boolean) : ProjectEvent

        data class Failed(@StringRes val reason: Int) : ProjectEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _exportEvents = Channel<ExportEvent>(Channel.BUFFERED)
    val exportEvents: Flow<ExportEvent> = _exportEvents.receiveAsFlow()

    private val _projectEvents = Channel<ProjectEvent>(Channel.BUFFERED)
    val projectEvents: Flow<ProjectEvent> = _projectEvents.receiveAsFlow()

    /**
     * Bridge to the screen-owned GL renderer: runs a block on the GL
     * thread and requests a redraw. Set while the surface exists; the
     * ViewModel deliberately never owns the renderer (it outlives the
     * surface).
     */
    var engineBridge: ((GlWarpRenderer.() -> Unit) -> Unit)? = null

    private var sessionFile: File? = null
    private var sessionFileB: File? = null

    /**
     * The project this session writes to, once it has one. Null until the
     * first save of a freshly opened photo; set for the whole life of a
     * resumed project, so saving again overwrites rather than piling up
     * near-identical copies of the same picture.
     */
    private var projectId: String? = null

    /**
     * A failing autosave has already told the user once. Cleared by any
     * successful write, so a disk that frees up and fills again speaks
     * again.
     */
    private var autosaveFailureReported = false

    /**
     * Back was pressed while a write was already running (a checkpoint
     * landing under the finger). That write is the one that saves this
     * session, so the room closes when IT lands — see
     * [leaveWhenCurrentSaveLands].
     */
    private var leaveAfterSave = false
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

    /** Symmetry dial and image aspect, frozen for the live stroke. */
    private var sectorsLive = 1
    private var aspectLive = 1f

    /** Echo's constant per-stroke delta; null for every other tool. */
    private var echoDelta: Pair<Float, Float>? = null

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
        savedStateHandle.get<FloatArray>(KEY_WOBBLE)
            ?.let(GlobalWobble::unpack)
            ?.let { w -> _uiState.update { it.copy(wobble = w) } }
        savedStateHandle.get<FloatArray>(KEY_GLOBALS)?.let { a ->
            GlobalParams.fromArray(a)?.let { g ->
                _uiState.update { it.copy(globals = g) }
                refreshHistoryFlags()
            }
        }
        val route = savedStateHandle.toRoute<EditorRoute>()
        // The saved-state copy wins over the route: a session that saved a
        // project once reopens THAT project after process death, strokes
        // and all, rather than the bare photo the route names.
        val project = savedStateHandle.get<String>(KEY_PROJECT_ID) ?: route.projectId
        startCheckpointing()
        viewModelScope.launch {
            try {
                if (project != null) openProject(project) else openPhoto(route.imageUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not open the editor", e)
                _uiState.update { it.copy(loading = false, error = R.string.error_open_image) }
            }
        }
    }

    /** Fresh photo (or the same one again after process death). */
    private suspend fun openPhoto(imageUri: String?) {
        // After process death the picker grant on the original URI is
        // gone; the session copy made on first import is the stable
        // source (that's why it exists).
        val restored = savedStateHandle.get<String>(KEY_SESSION_FILE)
            ?.let(::File)
            ?.takeIf(File::exists)
        val file = restored ?: imageLoader.importImage(
            requireNotNull(imageUri) { "the editor route names neither a photo nor a project" }
                .toUri(),
        ).also {
            savedStateHandle[KEY_SESSION_FILE] = it.path
        }
        sessionFile = file
        // Restore the crop before the decode that uses it. A malformed
        // rect restores as null (full frame) — the strokes it framed died
        // with the process anyway.
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
        // Restore B after A: the cover-crop needs A's dimensions. The
        // screen syncs bitmapB to the engine like it does A. A failed B
        // decode degrades to no-B (key cleared so the next restore
        // doesn't retry a bad file) — it must never take photo A's whole
        // editor down with it.
        if (restoredB != null) {
            try {
                val bitmapB = imageLoader.decodeCover(restoredB, bitmap.width, bitmap.height)
                sessionFileB = restoredB
                _uiState.update { it.copy(bitmapB = bitmapB) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                savedStateHandle.remove<String>(KEY_SESSION_B)
            }
        }
    }

    /**
     * Reopen a saved project (ANALYSIS SOL-34): its bytes are copied back
     * into fresh session files, then the document is restored on top.
     *
     * The copy is the point. A session is mutable in ways a saved project
     * must not be — swapping Fusion's photo B deletes the file it
     * replaces, a sweep collects what no session claims — so the editor
     * never holds the project's own files. The folder on disk changes
     * only when the user saves.
     */
    private suspend fun openProject(id: String) {
        val loaded = projectStore.load(id)
        if (loaded == null) {
            // Deleted from the In room in another session, or never
            // finished being written.
            _uiState.update { it.copy(loading = false, error = R.string.error_project_missing) }
            return
        }
        projectId = id
        savedStateHandle[KEY_PROJECT_ID] = id
        val document = loaded.document
        val file = imageLoader.importFile(loaded.source)
        sessionFile = file
        savedStateHandle[KEY_SESSION_FILE] = file.path
        val fileB = loaded.fusion?.let { imageLoader.importFile(it) }
        savedStateHandle[KEY_SESSION_B] = fileB?.path
        imageLoader.sweepSessions(keep = setOfNotNull(file, fileB))

        cropRect = document.cropRect()
        savedStateHandle[KEY_CROP] = cropRect?.toArray()
        val bitmap = imageLoader.decodePreview(file, crop = cropRect)

        // The document last, and all at once: a log that won't restore is
        // a damaged project, not a reason to hand back a blank canvas over
        // the right photo (which is what "lost all my goo" looks like).
        val table = log.restore(document.log)
        if (table == null) {
            Log.w(TAG, "project $id has an unreadable stroke log")
            _uiState.update { it.copy(loading = false, error = R.string.error_project_damaged) }
            return
        }
        val keyframes = document.keyframes
            // A pin whose revision is missing cannot be replayed. Dropping
            // it keeps the rest of the strip (and the strokes) openable;
            // only a corrupt file can get here, since snapshot() writes
            // every pinned revision.
            .mapNotNull { record ->
                table[StrokeRevisionId(record.revision)]?.let {
                    Keyframe(it, record.globals, record.easing)
                }
            }
            .take(MAX_KEYFRAMES)
        savedStateHandle[KEY_GLOBALS] = document.globals.toArray()
        savedStateHandle[KEY_WOBBLE] = document.wobble.sanitized().pack()
        _uiState.update {
            it.copy(
                loading = false,
                bitmap = bitmap,
                cropped = cropRect != null,
                globals = document.globals,
                wobble = document.wobble.sanitized(),
                keyframes = keyframes,
                selectedKeyframe = -1,
                scrubPos = 0f,
            )
        }
        refreshHistoryFlags()
        // What is on screen IS what is on disk: Back leaves without a
        // prompt until the first edit.
        markSaved(_uiState.value.signature)
        if (fileB != null) {
            try {
                val bitmapB = imageLoader.decodeCover(fileB, bitmap.width, bitmap.height)
                sessionFileB = fileB
                _uiState.update { it.copy(bitmapB = bitmapB) }
                // bitmapB is not part of the signature (documentEpoch is,
                // and this restore does not bump it), so a resumed Fusion
                // project still counts as saved.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "project $id has an unreadable Fusion photo", e)
                savedStateHandle.remove<String>(KEY_SESSION_B)
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
                // A swap leaves "has a B" unchanged, so the epoch is the
                // only thing that tells the exit guard a photo moved.
                _uiState.update { it.copy(bitmapB = bitmapB, documentEpoch = it.documentEpoch + 1) }
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
                    Log.w(TAG, "could not import the Fusion photo", e)
                    _exportEvents.trySend(ExportEvent.Failed(R.string.error_open_second_image))
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
            it.copy(
                bitmapB = null,
                importingPhotoB = false,
                tool = BrushTool.SMEAR,
                documentEpoch = it.documentEpoch + 1,
            )
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
                        // Two different rects both read as "cropped"; only
                        // the epoch tells a re-crop from the saved frame.
                        documentEpoch = it.documentEpoch + 1,
                        globals = GlobalParams(),
                        wobble = GlobalWobble(),
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
                _exportEvents.send(ExportEvent.Failed(R.string.error_crop_memory))
            } catch (e: Exception) {
                Log.w(TAG, "crop failed", e)
                _exportEvents.send(ExportEvent.Failed(R.string.error_crop))
            }
        }
    }

    /** Engine-side failures (GL thread) surface as the screen's error state. */
    fun reportEngineError(@StringRes reason: Int) {
        _uiState.update { it.copy(loading = false, error = reason) }
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
        // Echo paints from a planted source. With none planted, the
        // touch plants one rather than starting a stroke — the smallest
        // gesture that keeps one-finger painting sacrosanct, in place of
        // the proposal's long-press (which needs gesture plumbing this
        // build cannot exercise).
        if (state.tool == BrushTool.ECHO) {
            val anchor = state.echoAnchor
            if (anchor == null || !EchoOffset.isUseful(u, v, anchor.first, anchor.second)) {
                // Clear the previous stroke's offset on the way out.
                // Nothing reads it after a false return today, but a
                // stale delta surviving an aborted begin is the kind of
                // thing that only bites once the calling convention
                // changes, and by then it corrupts stamps silently.
                echoDelta = null
                _uiState.update { it.copy(echoAnchor = Pair(u, v)) }
                return false
            }
            echoDelta = EchoOffset.delta(u, v, anchor.first, anchor.second)
        } else {
            echoDelta = null
        }
        goLive()
        val aspect = bitmap.width.toFloat() / bitmap.height
        val radius = state.brushRadius
        val tool = state.tool
        mirrorLive = state.mirrored
        sectorsLive = state.sectors
        aspectLive = aspect
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
            val first = emit(listOf(PumpStamps.at(tool, u, v, tick = 1)))
            if (first.isNotEmpty()) {
                liveParams?.let { params ->
                    engineBridge?.invoke { stampBatch(params, first) }
                }
            }
        }
        if (tool.pumped) {
            // Continue the tick count rather than restarting it: a tool
            // that stamped on touch-down has already spent tick 1, and
            // repeating it would apply Melt's first run twice and shift
            // the whole acceleration ramp by one.
            startPump(firstTick = PumpStamps.firstPumpTick(tool))
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
        // Echo overrides the resampler's drag deltas with one constant
        // offset, which is what makes the graft a rigid translation of
        // the source region instead of a smear (see EchoOffset). It runs
        // BEFORE symmetry on purpose: the fan rotates whatever delta a
        // stamp carries, so each sector's copy clones from the rotated
        // offset — a kaleidoscope OF the graft, rather than one graft
        // repeated at rotated positions.
        val stamps = echoDelta?.let { (dx, dy) ->
            fresh.map { it.copy(dx = dx, dy = dy) }
        } ?: fresh
        // Symmetry copies are produced HERE, so everything downstream
        // sees an ordinary stroke: the log, undo, export replay and
        // GOOvie caches need no symmetry logic and no format change.
        val batch = if (sectorsLive <= 1 && !mirrorLive) {
            stamps
        } else {
            stamps.flatMap { Symmetry.family(tool, it, aspectLive, sectorsLive, mirrorLive) }
        }
        liveStamps.addAll(batch)
        return batch
    }

    private fun startPump(firstTick: Int) {
        pumpJob?.cancel()
        pumpJob = viewModelScope.launch {
            var tick = firstTick
            while (true) {
                // beginStroke already applied the one-shot click. Wait before
                // repeating so a quick tap is exactly one application.
                delay(BrushDynamics.PUMP_INTERVAL_MS)
                val (u, v) = pumpPoint ?: break
                val params = liveParams ?: break
                val batch = emit(listOf(PumpStamps.at(params.tool, u, v, tick)))
                engineBridge?.invoke { stampBatch(params, batch) }
                tick++
            }
        }
    }

    private fun stopPump() {
        pumpJob?.cancel()
        pumpJob = null
        pumpPoint = null
    }

    /**
     * Finish a Whip with a flick (proposal 0015): extend the live stroke
     * along the ballistic tail that ([velU], [velV]) throws, in UV units
     * per second, then end it as usual.
     *
     * The tail is generated HERE, once, and appended as ordinary stamps.
     * Replay never sees a velocity — a tail recomputed at export time
     * would depend on input timing that no longer exists, and would be a
     * different mark every render.
     *
     * @return the newly produced stamps, for the caller to stamp into
     * the live field before it commits the stroke.
     */
    fun whipTail(velU: Float, velV: Float): List<Stamp> {
        val params = liveParams ?: return emptyList()
        if (params.tool != BrushTool.WHIP) return emptyList()
        val resampler = resampler ?: return emptyList()
        val last = liveStamps.lastOrNull() ?: return emptyList()
        val points = GooWhip.tail(
            u = last.cx,
            v = last.cy,
            velU = velU,
            velV = velV,
            aspect = aspectLive,
        )
        if (points.isEmpty()) return emptyList()
        val fresh = mutableListOf<Stamp>()
        // The same resampler instance the drag was using, so the tail
        // continues the stroke's spacing rather than restarting it —
        // there is no seam at the release point.
        for ((u, v) in points) resampler.extend(u, v, fresh)
        return emit(fresh)
    }

    /** Commit the live stroke. @return it, if it produced any stamps. */
    fun endStroke(): Stroke? {
        stopPump()
        val params = liveParams ?: return null
        resampler = null
        // Every other per-stroke field is torn down here; the echo offset
        // belongs with them. Nothing reads it between strokes today, but
        // "beginStroke always reassigns it first" is a caller contract,
        // not an invariant of this class.
        echoDelta = null
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
        // Leaving Echo forgets its source. An anchor the user cannot see
        // and did not plant this session is a clone that grafts from
        // somewhere surprising; re-arming is one tap.
        _uiState.update {
            it.copy(tool = tool, echoAnchor = if (tool == BrushTool.ECHO) it.echoAnchor else null)
        }
    }

    fun setBrushStrength(value: Float) {
        _uiState.update { it.copy(brushStrength = value.coerceIn(0.05f, 1f)) }
    }

    fun toggleMirror() {
        _uiState.update { it.copy(mirrored = !it.mirrored) }
    }

    /** Step the kaleidoscope dial to the next sector count, wrapping. */
    fun cycleSectors() {
        _uiState.update {
            val next = Symmetry.SECTORS.indexOf(it.sectors).let { i ->
                Symmetry.SECTORS[(i + 1) % Symmetry.SECTORS.size]
            }
            it.copy(sectors = next)
        }
    }

    // ---- History -------------------------------------------------------
    // A history action while a second finger is mid-stroke first discards
    // the live stroke. Its stamps are already on the GPU field, so the
    // caller MUST rebuild even when the log op itself was a no-op (null) —
    // otherwise the discarded head stays visible while the document says
    // it never happened, and undo/export silently disagree with the
    // screen. Hence: null only when nothing was discarded AND the log
    // didn't move.

    fun undo(): List<Stroke>? = withDealLevers { withDiscardGuard { log.undo() } }

    fun redo(): List<Stroke>? = withDealLevers { withDiscardGuard { log.redo() } }

    /**
     * Restore the levers a Goo Me deal moved, when history walks past it.
     *
     * Levers are document state and not history (PLAN.md §4.1), which is
     * right for a slider — pulling it back is the undo. It is wrong for a
     * deal: the user taps Undo expecting the joke gone, and a deal that
     * left a twirl pulled would leave the photo visibly warped by an
     * "undone" edit. So a deal pins the lever table to the revisions on
     * either side of it, and any history move that lands on a pinned
     * revision restores that table. Ordinary lever moves pin nothing and
     * are untouched.
     *
     * Not persisted: a reloaded project's undo walks the strokes back but
     * leaves the levers where they were saved. Same as before this
     * existed, and a saved lever position is a deliberate document state
     * by then rather than an accident of a deal.
     */
    private inline fun withDealLevers(op: () -> List<Stroke>?): List<Stroke>? {
        val from = log.currentRevision.id
        val result = op()
        dealLevers.crossing(from, log.currentRevision.id)?.let { pinned ->
            if (pinned != _uiState.value.globals) setGlobals(pinned)
        }
        return result
    }

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
        // Reset means "back to the photo": levers zero and the rig
        // stills, or the picture would keep breathing after a reset.
        setGlobals(GlobalParams())
        setWobble(GlobalWobble())
        if (log.isEmpty) return log.strokes.takeIf { discarded }
        log.reset()
        refreshHistoryFlags()
        return log.strokes
    }

    // ---- Global levers -------------------------------------------------
    // Levers are live document state, not history entries (PLAN.md §4.1):
    // pulling one back to center undoes it exactly, so undo/redo stay
    // stroke-only. The screen syncs the renderer whenever they change.

    /**
     * Set the modulation rig. Same rules as the levers it rides on:
     * document state, not history, and Reset stills it.
     */
    /**
     * Set one lever's modulation, against the rig as it stands NOW.
     *
     * Exists because the obvious `setWobble(state.wobble.with(i, w))` at
     * the call site reads a composition snapshot: Zero-all writes six
     * levers in a synchronous loop with no recomposition between them, so
     * every call would apply its change to the SAME original rig and only
     * the last would survive. The photo would keep breathing after the
     * user asked it to stop.
     */
    fun setLeverWobble(index: Int, lever: LeverWobble) {
        setWobble(_uiState.value.wobble.with(index, lever))
    }

    fun setWobble(wobble: GlobalWobble) {
        goLive()
        val safe = wobble.sanitized()
        _uiState.update { it.copy(wobble = safe) }
        savedStateHandle[KEY_WOBBLE] = safe.pack()
        engineBridge?.invoke { setWobble(safe) }
        refreshHistoryFlags()
    }

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

    // ---- Goo Me ----------------------------------------------------------

    /**
     * Deal a recipe onto the photo (proposal 0007): a couple of curated
     * strokes and maybe a lever, applied for real.
     *
     * @return the dealt strokes to stamp into the field, or null when
     * there is nothing to deal onto.
     */
    fun dealGoo(): List<Stroke>? {
        val bitmap = _uiState.value.bitmap ?: return null
        if (_uiState.value.exportingMovie) return null
        // Deal first, then commit to it. GooMe.deal reads only the
        // levers, which neither call below touches — and a deal that
        // came back empty must not have cost the user an in-flight
        // stroke on its way to doing nothing.
        val deal = GooMe.deal(
            seed = nextDealSeed(),
            aspect = bitmap.width.toFloat() / bitmap.height,
            from = _uiState.value.globals,
        )
        if (deal.strokes.isEmpty()) return null
        goLive()
        discardLiveStroke()
        // Pin the transition, so undo across it finds the levers as they
        // were and redo across it finds them as dealt.
        val before = log.currentRevision.id
        val was = _uiState.value.globals
        log.pushBatch(deal.strokes)
        dealLevers.pin(before, log.currentRevision.id, was = was, dealt = deal.globals)
        setGlobals(deal.globals)
        if (_uiState.value.showHint) {
            onboardingPrefs.smearHintSeen = true
            _uiState.update { it.copy(showHint = false) }
        }
        refreshHistoryFlags()
        return log.strokes
    }

    /** See [withDealLevers]. */
    private val dealLevers = DealLevers()

    private var dealSeed: Long = System.nanoTime()

    /** A fresh seed per tap — tap again, different accident. */
    private fun nextDealSeed(): Long {
        dealSeed = dealSeed * 6364136223846793005L + 1442695040888963407L
        return dealSeed
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
                // Keep the segment's curve: Update re-pins the POSE, and
                // the easing is a property of the timing that leaves this
                // keyframe, not of what it holds.
                this[i] = Keyframe(
                    revision = log.currentRevision,
                    globals = s.globals,
                    easing = this[i].easing,
                )
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

    /**
     * Step the selected keyframe's outgoing segment to the next curve.
     * A no-op on the last keyframe, where nothing leaves.
     */
    fun cycleSelectedEasing() {
        _uiState.update { s ->
            val i = s.selectedKeyframe
            if (i !in s.keyframes.indices || i == s.keyframes.lastIndex) return@update s
            val list = s.keyframes.toMutableList()
            val next = Easing.entries[(list[i].easing.ordinal + 1) % Easing.entries.size]
            list[i] = list[i].copy(easing = next)
            s.copy(keyframes = list)
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
     * Export the GOOvie: the GL thread renders every tweened frame into
     * the H.264 encoder surface (renderMovie) or into a palettised GIF
     * (renderGif), then the result lands in the gallery [share] false, or
     * the share sheet [share] true — through the same ExportEvent flow the
     * image path uses.
     *
     * [speed] scales the frame count, not the frame rate; [loop] only
     * reaches the GIF writer (an MP4 has no loop flag to set — players
     * decide).
     */
    fun exportGoovie(
        share: Boolean,
        format: MovieFormat,
        speed: MovieSpeed,
        loop: Boolean,
    ) {
        val s = _uiState.value
        if (s.exportingMovie || s.keyframes.size < 2) return
        val bridge = engineBridge
        if (bridge == null) {
            _exportEvents.trySend(ExportEvent.Failed(R.string.error_editor_not_ready))
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
                movieSaver.createWorkFile(format)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not create the movie work file", e)
                _uiState.update { it.copy(exportingMovie = false, movieProgress = 0f) }
                _exportEvents.send(ExportEvent.Failed(R.string.error_movie_export))
                return@launch
            }
            // MutableStateFlow.update is thread-safe; GL thread is fine.
            val onProgress: (Float) -> Unit = { p ->
                _uiState.update { it.copy(movieProgress = p) }
            }
            val onResult: (Boolean) -> Unit = { ok ->
                onMovieRendered(ok, workFile, format, share)
            }
            bridge.invoke {
                when (format) {
                    MovieFormat.MP4 -> renderMovie(
                        keyframes = keyframes,
                        speed = speed.multiplier,
                        outputFile = workFile,
                        onProgress = onProgress,
                        onResult = onResult,
                    )

                    MovieFormat.GIF -> renderGif(
                        keyframes = keyframes,
                        speed = speed.multiplier,
                        loop = loop,
                        outputFile = workFile,
                        onProgress = onProgress,
                        onResult = onResult,
                    )
                }
            }
        }
    }

    /** GL-thread completion → IO save → main-thread events. */
    private fun onMovieRendered(
        ok: Boolean,
        workFile: File,
        format: MovieFormat,
        share: Boolean,
    ) {
        viewModelScope.launch {
            try {
                if (!ok) {
                    _exportEvents.send(ExportEvent.Failed(R.string.error_movie_export))
                    return@launch
                }
                if (share) {
                    val uri = movieSaver.writeShareCache(workFile)
                    _exportEvents.send(ExportEvent.ShareReady(uri, format.mimeType))
                } else {
                    val result = movieSaver.save(workFile, format)
                    _exportEvents.send(
                        ExportEvent.Saved(
                            toGallery = result is MovieSaver.SaveResult.Gallery,
                            video = format.isVideo,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not save the movie", e)
                _exportEvents.send(ExportEvent.Failed(R.string.error_movie_save))
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
        // The segment's curve applies to the SCRUB as well as to
        // playback, deliberately. Proposal 0011 wanted the scrub left
        // linear so the handle "goes where the finger goes" — it still
        // does, since the handle sets the segment's PROGRESS. What the
        // curve changes is which frame that progress shows, and a scrub
        // that disagreed with the export would be a preview that lies
        // about the movie, which is the class of bug SOL-2 is about.
        val curve = a.easing
        val tween = tweenProgress(t, curve)
        // Levers clamp: running their lerp past 1 would push a lever
        // outside [-1, 1], where the analytic warps were never
        // characterized. The field's overshoot is the effect; a lever's
        // is undefined behaviour.
        return TweenRequest(
            revisionA = a.revision,
            revisionB = b.revision,
            t = tween,
            lerpedGlobals = a.globals.lerp(b.globals, leverProgress(t, curve)),
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

    // ---- Projects ------------------------------------------------------
    // Saving writes the DOCUMENT, not a picture: the source bytes, the
    // stroke log as a normalized revision table, the levers, the crop
    // rect and the GOOvie pins (ProjectDocument). Out saves pictures;
    // this saves the thing you can go on gooing tomorrow.

    /**
     * Save when the editor goes to the background.
     *
     * This is the answer to "the OS killed my app and my goo was gone":
     * `onStop` is the last callback guaranteed before a process can be
     * reclaimed, so it is where the document has to reach disk. Only when
     * there is something new to write — backgrounding an untouched photo,
     * or one whose state is already saved, does nothing.
     */
    /**
     * Back was pressed while a write was already in flight. Don't start a
     * second one — it would copy the same photo again — just close the
     * room when the running write lands.
     */
    fun leaveWhenCurrentSaveLands() {
        if (_uiState.value.savingProject) leaveAfterSave = true
    }

    fun autosaveProject() {
        val state = _uiState.value
        if (!state.hasUnwrittenChanges || state.savingProject) return
        // A stroke in flight is not in the log yet, and a pumped tool can
        // hold one down for a minute. Writing now would checkpoint the
        // state before it; the commit that ends it moves the signature and
        // brings the loop straight back.
        if (liveParams != null) return
        saveProject(SaveReason.AUTOSAVE)
    }

    /**
     * Checkpoint the document while editing continues — the foreground
     * half of "an OS kill must not cost the session" ([AutosavePolicy]
     * holds the two clocks and why there are two).
     *
     * Runs for the ViewModel's whole life; it costs nothing while the
     * document matches disk. Deliberately paused while an export owns the
     * GL thread: the checkpoint's own preview render would queue behind a
     * movie and time out, and a movie is not a moment to add GL work to.
     */
    private fun startCheckpointing() {
        viewModelScope.launch {
            var dirtySinceMs: Long? = null
            uiState
                .map { state ->
                    Checkpoint(
                        signature = state.signature,
                        due = state.hasUnwrittenChanges,
                        busy = state.savingProject || state.exporting || state.exportingMovie,
                    )
                }
                .distinctUntilChanged()
                // collectLatest IS the debounce: every change to the
                // document cancels the pending wait and starts a new one.
                .collectLatest { checkpoint ->
                    if (!checkpoint.due) {
                        dirtySinceMs = null
                        return@collectLatest
                    }
                    if (checkpoint.busy) return@collectLatest
                    val now = System.nanoTime() / 1_000_000L
                    val since = dirtySinceMs ?: now.also { dirtySinceMs = it }
                    delay(AutosavePolicy.delayMs(now - since))
                    // Restart the clock at the ATTEMPT, not at the write.
                    // A write that succeeds clears `due` and this is moot;
                    // one that fails (no space, say) leaves the document
                    // dirty and already past the ceiling, and without this
                    // the loop would retry as fast as the disk can say no.
                    dirtySinceMs = System.nanoTime() / 1_000_000L
                    autosaveProject()
                }
        }
    }

    /** What the checkpoint loop watches; distinct values restart its wait. */
    private data class Checkpoint(
        val signature: DocumentSignature,
        val due: Boolean,
        val busy: Boolean,
    )

    /**
     * Write this session to disk, overwriting the project it came from
     * (or minting one on the first save). Emits [ProjectEvent.Saved] when
     * the document is safely on disk — the leave dialog waits for it.
     */
    fun saveProject(reason: SaveReason) {
        if (_uiState.value.savingProject) return
        // A second finger can be mid-stroke when the dialog's button is
        // tapped. Commit rather than discard, same policy as setTool: the
        // stamps are on the field, and the save should include them.
        endStroke()?.let { stroke -> engineBridge?.invoke { commit(stroke) } }
        val session = sessionFile
        if (session == null) {
            _projectEvents.trySend(ProjectEvent.Failed(R.string.error_editor_not_ready))
            return
        }
        val state = _uiState.value
        val keyframes = state.keyframes
        val document = ProjectDocument(
            crop = cropRect?.let(CropRecord::of),
            globals = state.globals,
            wobble = state.wobble,
            // The pins ride along as extra roots: a keyframe can hold a
            // revision the history has truncated, and it has to reach disk.
            log = log.snapshot(pins = keyframes.map { it.revision }),
            keyframes = keyframes.map { KeyframeRecord(it.revisionId.value, it.globals, it.easing) },
        )
        // Snapshotted here, not read back at completion: this is the state
        // being written, and nothing later may claim more than that was.
        val signature = state.signature
        val strokes = log.strokes
        val fusion = sessionFileB
        // A project with no preview at all is worse than one showing the
        // photo under the goo, so the very first write of a project may
        // fall back to the plain decode. Later writes never do: they
        // would replace a real preview of the goo with a bare photo.
        val firstWrite = projectId == null
        // Checkpoints keep the preview they have. Rendering one replays
        // the whole log on the GL thread, and a checkpoint fires ten
        // seconds into a pause — precisely when the user is about to put
        // a finger back down, and would wait behind it. A save they asked
        // for is a moment they are already waiting on; this is not.
        val renderPreview = reason != SaveReason.AUTOSAVE || firstWrite
        _uiState.update { it.copy(savingProject = true) }
        viewModelScope.launch {
            var thumbnail: Bitmap? = null
            try {
                if (renderPreview) {
                    thumbnail = renderThumbnail(strokes, sourceIsAcceptable = firstWrite)
                }
                projectId = projectStore.save(projectId, document, session, fusion, thumbnail)
                savedStateHandle[KEY_PROJECT_ID] = projectId
                markSaved(signature)
                autosaveFailureReported = false
                val leave = reason == SaveReason.LEAVE || leaveAfterSave
                leaveAfterSave = false
                // A checkpoint is silent unless someone is waiting on it:
                // announcing every one would put a snackbar on screen
                // every ten seconds, and the screen's collector suspends
                // for as long as a snackbar is up.
                if (reason != SaveReason.AUTOSAVE || leave) {
                    _projectEvents.send(ProjectEvent.Saved(leave = leave))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not save the project", e)
                // Nothing reached disk, so nobody leaves on the strength
                // of it; the screen drops its scrim on the same event.
                leaveAfterSave = false
                // A broken autosave says so ONCE. It keeps retrying (the
                // work is still unwritten, and the disk may free up), but
                // a snackbar every ten seconds would bury the editor
                // without telling the user anything new.
                val announce = reason != SaveReason.AUTOSAVE || !autosaveFailureReported
                if (reason == SaveReason.AUTOSAVE) autosaveFailureReported = true
                if (announce) _projectEvents.send(ProjectEvent.Failed(R.string.error_project_save))
            } finally {
                thumbnail?.recycle()
                _uiState.update { it.copy(savingProject = false) }
            }
        }
    }

    /** Record that [signature] is what the project on disk now holds. */
    private fun markSaved(signature: DocumentSignature) {
        _uiState.update { it.copy(savedSignature = signature) }
    }

    /**
     * The In room's tile: the document rendered small, through the same
     * replay the export uses, so the tile shows the goo rather than the
     * photo it started from.
     *
     * Best-effort by design — a project with no preview still opens, so
     * nothing here may fail a save. The timeout is the one that matters:
     * the GL bridge answers on the surface's queue, and a surface torn
     * down mid-save (a rotation) would otherwise leave this suspended
     * forever, with the user stuck in a dialog that never returns.
     */
    private suspend fun renderThumbnail(
        strokes: List<Stroke>,
        sourceIsAcceptable: Boolean,
    ): Bitmap? {
        val session = sessionFile ?: return null
        var source: Bitmap? = null
        var sourceB: Bitmap? = null
        var rendered = false
        try {
            val decoded =
                imageLoader.decodePreview(session, ProjectStore.THUMBNAIL_MAX_DIM, cropRect)
            source = decoded
            val bridge = engineBridge
            val warped = if (bridge == null) null else withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                val decodedB = sessionFileB?.let {
                    imageLoader.decodeCover(it, decoded.width, decoded.height)
                }
                sourceB = decodedB
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    bridge {
                        exportBitmap(decoded, decodedB, strokes) { out ->
                            if (cont.isActive) cont.resume(out) else out?.recycle()
                        }
                    }
                }
            }
            if (warped != null) {
                rendered = true
                return warped
            }
            // No replay: no surface, or a paused one (the autosave case —
            // the GL thread stops as the app goes to the background). The
            // caller decides whether a bare photo beats no preview.
            if (!sourceIsAcceptable) return null
            source = null
            return decoded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not render the project preview", e)
            return null
        } finally {
            // Recycled only when the GL side actually answered. After a
            // timeout the queued event may still be holding these, and
            // freeing a bitmap under the GL thread is worse than letting
            // GC take a megabyte late (same policy as runExport).
            if (rendered && coroutineContext.isActive) {
                source?.recycle()
                sourceB?.recycle()
            }
        }
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
            _exportEvents.trySend(ExportEvent.Failed(R.string.error_editor_not_ready))
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
                Log.w(TAG, "export failed", e)
                _exportEvents.send(ExportEvent.Failed(R.string.error_export))
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
                canReset = !log.isEmpty || !it.globals.isIdentity || !it.wobble.isStill,
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

        private const val TAG = "EditorViewModel"

        private const val KEY_SESSION_FILE = "sessionFile"
        private const val KEY_SESSION_B = "sessionFileB"
        private const val KEY_GLOBALS = "globals"
        private const val KEY_WOBBLE = "wobble"
        private const val KEY_CROP = "crop"
        private const val KEY_PROJECT_ID = "projectId"

        /**
         * Ceiling on the project preview render. Generous for a 512 px
         * replay of even a pump-heavy log, short enough that a wedged GL
         * queue costs a preview rather than the save.
         */
        private const val THUMBNAIL_TIMEOUT_MS = 5_000L

        /** KPT Goo's strip held 64; so does ours. */
        const val MAX_KEYFRAMES = 64
    }
}
