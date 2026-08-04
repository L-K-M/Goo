package ch.lkmc.goo.ui.editor

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ch.lkmc.goo.data.ImageLoader
import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeResampler
import ch.lkmc.goo.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val bitmap: Bitmap? = null,
        val error: String? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val canReset: Boolean = false,
        /** Aspect-space brush radius (fraction of image height). */
        val brushRadius: Float = DEFAULT_RADIUS,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
