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
import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.ExportSize
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeResampler
import ch.lkmc.goo.engine.gl.GlWarpRenderer
import ch.lkmc.goo.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    savedStateHandle: SavedStateHandle,
    private val imageLoader: ImageLoader,
    private val imageSaver: ImageSaver,
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
    private var exportJob: Job? = null

    private val log = StrokeLog()
    private var resampler: StrokeResampler? = null
    private var liveStamps = mutableListOf<Stamp>()

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
                // Previous sessions' copies are garbage now (REVIEW.md G-1).
                imageLoader.sweepSessions(keep = file)
                val bitmap = imageLoader.decodePreview(file)
                _uiState.update { it.copy(loading = false, bitmap = bitmap) }
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

    /** Engine-side failures (GL thread) surface as the screen's error state. */
    fun reportEngineError(message: String) {
        _uiState.update { it.copy(loading = false, error = message) }
    }

    // ---- Live stroke ---------------------------------------------------

    fun beginStroke(u: Float, v: Float) {
        val bitmap = _uiState.value.bitmap ?: return
        val aspect = bitmap.width.toFloat() / bitmap.height
        val radius = _uiState.value.brushRadius
        liveParams = Stroke(
            tool = BrushTool.SMEAR,
            radius = radius,
            strength = SMEAR_STRENGTH,
            stamps = emptyList(),
        )
        resampler = StrokeResampler(radius = radius, aspect = aspect).also { it.begin(u, v) }
        liveStamps = mutableListOf()
    }

    /** @return newly produced stamps for incremental GPU stamping. */
    fun extendStroke(u: Float, v: Float): List<Stamp> {
        val r = resampler ?: return emptyList()
        val fresh = r.extend(u, v, mutableListOf())
        liveStamps.addAll(fresh)
        return fresh
    }

    /** Commit the live stroke. @return it, if it produced any stamps. */
    fun endStroke(): Stroke? {
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
    fun liveStrokeParams(): Stroke = liveParams ?: Stroke(
        tool = BrushTool.SMEAR,
        radius = _uiState.value.brushRadius,
        strength = SMEAR_STRENGTH,
        stamps = emptyList(),
    )

    // ---- History -------------------------------------------------------
    // A history action while a second finger is mid-stroke first discards
    // the live stroke (its stamped head is wiped by the rebuild anyway);
    // the rest of that gesture then produces no stamps, so the screen and
    // the document can never diverge.

    fun undo(): List<Stroke>? {
        discardLiveStroke()
        return log.undo().also { refreshHistoryFlags() }
    }

    fun redo(): List<Stroke>? {
        discardLiveStroke()
        return log.redo().also { refreshHistoryFlags() }
    }

    /** @return the (empty) stroke list to rebuild with, or null if no-op. */
    fun reset(): List<Stroke>? {
        discardLiveStroke()
        if (log.isEmpty) return null
        log.reset()
        refreshHistoryFlags()
        return log.strokes
    }

    private fun discardLiveStroke() {
        resampler = null
        liveParams = null
        liveStamps = mutableListOf()
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
            var warped: Bitmap? = null
            try {
                val maxTex = suspendCancellableCoroutine { cont ->
                    bridge { if (cont.isActive) cont.resume(maxTextureSize) }
                }
                val cap = ExportSize.exportLongSideCap(maxTex)
                val decoded = imageLoader.decodePreview(session, cap)
                full = decoded
                val rendered = suspendCancellableCoroutine { cont ->
                    bridge {
                        exportBitmap(decoded, strokes) { out ->
                            // Cancelled mid-render: nobody will ever see
                            // this bitmap — free ~50 MB now, not at GC.
                            if (cont.isActive) cont.resume(out) else out?.recycle()
                        }
                    }
                }
                warped = rendered ?: throw IllegalStateException("engine could not render the export")
                full.recycle()
                full = null
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
        // with the finally's own reset).
        _uiState.update { it.copy(exporting = false) }
    }

    private fun refreshHistoryFlags() {
        _uiState.update {
            it.copy(canUndo = log.canUndo, canRedo = log.canRedo, canReset = !log.isEmpty)
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

        /** Smear tracks the finger 1:1; other tools will differ. */
        const val SMEAR_STRENGTH = 1f

        private const val KEY_SESSION_FILE = "sessionFile"
    }
}
