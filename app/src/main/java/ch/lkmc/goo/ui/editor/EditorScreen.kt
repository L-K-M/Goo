package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.sqrt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.gl.GlWarpRenderer
import ch.lkmc.goo.engine.gl.WarpSurfaceView
import ch.lkmc.goo.ui.theme.CandyCyan

/**
 * The Goo room: the GL canvas with the brush pipeline wired through the
 * ViewModel (touch → resampler → stamps → GPU), a minimal control rail
 * (undo/redo/reset/brush size). Candy-fication of these controls comes
 * with the UI-polish roadmap step; the goal here is a correct, fast MVP.
 */
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            state.error != null -> ErrorPane(message = state.error!!, onBack = onBack)

            state.bitmap != null -> WarpEditor(viewModel = viewModel, onBack = onBack)
        }
    }
}

@Composable
private fun WarpEditor(viewModel: EditorViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bitmap = state.bitmap ?: return
    var surface by remember { mutableStateOf<WarpSurfaceView?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // GLSurfaceView must see activity pause/resume or its GL thread leaks.
    DisposableEffect(surface, lifecycleOwner) {
        val view = surface
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // (Re)install the image whenever the bitmap or the surface changes.
    // The snapshot is taken HERE, on the main thread — StrokeLog is
    // main-thread-confined and must never be read inside engine{} (GL
    // thread). The snapshot list itself is immutable, so handing it over
    // is safe.
    LaunchedEffect(bitmap, surface) {
        val snapshot = viewModel.strokesSnapshot
        surface?.engine { setImage(bitmap, snapshot) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopRail(
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            canReset = state.canReset,
            onBack = onBack,
            onUndo = {
                viewModel.undo()?.let { strokes -> surface?.engine { rebuild(strokes) } }
            },
            onRedo = {
                viewModel.redo()?.let { strokes -> surface?.engine { rebuild(strokes) } }
            },
            onReset = { confirmReset = true },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(bitmap) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val fit = FitTransform(
                            viewWidth = size.width.toFloat(),
                            viewHeight = size.height.toFloat(),
                            imageWidth = bitmap.width.toFloat(),
                            imageHeight = bitmap.height.toFloat(),
                        )
                        val (u0, v0) = fit.viewToUv(down.position.x, down.position.y)
                        // Ignore touches farther than a brush radius from
                        // the image (deep letterbox): they can never move a
                        // pixel, and committing them would light up Undo
                        // for a no-op. Starting within one radius stays
                        // allowed — outside-in edge smears are legitimate.
                        val aspect = bitmap.width.toFloat() / bitmap.height
                        val du = (u0.coerceIn(0f, 1f) - u0) * aspect
                        val dv = v0.coerceIn(0f, 1f) - v0
                        val outside = sqrt(du * du + dv * dv)
                        if (outside > viewModel.uiState.value.brushRadius) return@awaitEachGesture
                        viewModel.beginStroke(u0, v0)
                        down.consume()
                        val params = viewModel.liveStrokeParams()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                viewModel.endStroke()?.let { stroke ->
                                    // Feed the committed stroke to the
                                    // renderer's recovery snapshot (its
                                    // stamps are already on the GPU).
                                    surface?.engine { commit(stroke) }
                                }
                                break
                            }
                            // Historical samples first: fast flicks batch
                            // several path points into one event, and
                            // skipping them would leave gaps in the goo.
                            val stamps = buildList {
                                change.historical.forEach { h ->
                                    val (u, v) = fit.viewToUv(h.position.x, h.position.y)
                                    addAll(viewModel.extendStroke(u, v))
                                }
                                val (u, v) = fit.viewToUv(change.position.x, change.position.y)
                                addAll(viewModel.extendStroke(u, v))
                            }
                            if (stamps.isNotEmpty()) {
                                surface?.engine { stampBatch(params, stamps) }
                            }
                            change.consume()
                        }
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val renderer = GlWarpRenderer(onUnsupported = viewModel::reportEngineError)
                    WarpSurfaceView(context, renderer).also { surface = it }
                },
            )
        }

        BrushRail(
            radius = state.brushRadius,
            onRadiusChange = viewModel::setBrushRadius,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.editor_reset_title)) },
            text = { Text(stringResource(R.string.editor_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.reset()?.let { strokes -> surface?.engine { rebuild(strokes) } }
                    },
                ) { Text(stringResource(R.string.editor_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.editor_reset_cancel))
                }
            },
        )
    }
}

@Composable
private fun TopRail(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.editor_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Row {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.editor_undo),
                    tint = railTint(canUndo),
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.editor_redo),
                    tint = railTint(canRedo),
                )
            }
            IconButton(onClick = onReset, enabled = canReset) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.editor_reset),
                    tint = railTint(canReset),
                )
            }
        }
    }
}

@Composable
private fun railTint(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.onBackground
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)

@Composable
private fun BrushRail(radius: Float, onRadiusChange: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.editor_brush_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            modifier = Modifier.weight(1f),
            value = radius,
            onValueChange = onRadiusChange,
            valueRange = EditorViewModel.MIN_RADIUS..EditorViewModel.MAX_RADIUS,
        )
    }
}

@Composable
private fun ErrorPane(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.editor_error_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.editor_back), color = CandyCyan)
        }
    }
}
