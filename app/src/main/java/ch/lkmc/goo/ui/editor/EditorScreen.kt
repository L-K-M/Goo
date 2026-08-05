package ch.lkmc.goo.ui.editor

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.sqrt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.ViewTransform
import ch.lkmc.goo.engine.gl.GlWarpRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ch.lkmc.goo.engine.gl.WarpSurfaceView
import ch.lkmc.goo.ui.components.CandyIconButton
import ch.lkmc.goo.ui.components.CandyToolChip
import ch.lkmc.goo.ui.export.ExportSheet
import ch.lkmc.goo.ui.theme.CandyCyan
import ch.lkmc.goo.ui.theme.CandyGrape
import ch.lkmc.goo.ui.theme.CandyLemon
import ch.lkmc.goo.ui.theme.CandyLime
import ch.lkmc.goo.ui.theme.CandyOrange
import ch.lkmc.goo.ui.theme.CandyPink
import ch.lkmc.goo.ui.theme.GooTableShadow

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

            state.bitmap != null -> WarpEditor(viewModel = viewModel, state = state, onBack = onBack)
        }
    }
}

@Composable
private fun WarpEditor(
    viewModel: EditorViewModel,
    // Collected once in EditorScreen and passed down — a second
    // collectAsStateWithLifecycle here would spin up a redundant collector.
    state: EditorViewModel.UiState,
    onBack: () -> Unit,
) {
    val bitmap = state.bitmap ?: return
    var surface by remember { mutableStateOf<WarpSurfaceView?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showLevers by remember { mutableStateOf(false) }
    var adjustingBrush by remember { mutableStateOf(false) }
    // Pan/zoom/rotate of the preview. Ephemeral by design: not saved
    // across rotation (the viewport changes under it anyway) — the photo
    // reappears fitted, which is also what Reset View promises.
    var view by remember { mutableStateOf(ViewTransform()) }
    // One reset spring at a time: re-clicks restart it, and new two-finger
    // input cancels it instead of fighting it frame-by-frame.
    val viewResetScope = rememberCoroutineScope()
    var viewResetJob by remember { mutableStateOf<Job?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Hand the ViewModel a way to reach the GL thread while (and only
    // while) the surface exists — export orchestration needs it.
    DisposableEffect(surface) {
        val s = surface
        viewModel.engineBridge = s?.let { sv -> { command -> sv.engine(command) } }
        onDispose {
            viewModel.engineBridge = null
            // A dead surface drops queued events, so a bridged export
            // continuation would never resume — cancel instead of wedging.
            viewModel.cancelExport()
        }
    }

    // Levers are live uniforms: sync the renderer on every change and on
    // surface (re)creation — a fresh GL context starts at identity.
    LaunchedEffect(surface, state.globals) {
        val globals = state.globals
        surface?.engine { setGlobalParams(globals) }
    }

    // View transform: same uniform-sync pattern as the levers.
    LaunchedEffect(surface, view) {
        val v = view
        surface?.engine { setViewTransform(v) }
    }

    // Fusion's photo B follows state like A does — including re-upload
    // after a GL context recreation (the surface key).
    LaunchedEffect(surface, state.bitmapB) {
        val b = state.bitmapB
        surface?.engine { setImageB(b) }
    }

    // Fusion needs a photo B: selecting the tool without one opens the
    // picker; canceling with still-no-B falls back to Smear so the brush
    // never paints an invisible mask.
    val pickImageB = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.importSecondImage(uri)
        } else if (viewModel.uiState.value.bitmapB == null) {
            viewModel.setTool(BrushTool.SMEAR)
        }
    }
    LaunchedEffect(state.tool) {
        if (state.tool == BrushTool.FUSE && viewModel.uiState.value.bitmapB == null) {
            pickImageB.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }

    // GOOvie scrub sync: every scrub/strip change re-derives the tween
    // payload; leaving the strip (or having < 2 keyframes) shows live.
    LaunchedEffect(surface, state.goovieMode, state.scrubPos, state.keyframes) {
        val req = viewModel.tweenRequest()
        if (req != null) {
            surface?.engine { tweenTo(req.strokes, req.countA, req.countB, req.t, req.lerpedGlobals) }
        } else {
            surface?.engine { clearTween() }
        }
    }

    // Playback: frame-locked advance while playing; loops via the timeline.
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) viewModel.advancePlayback((now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    // One-shot export outcomes: snackbars and the share chooser.
    val savedGallery = stringResource(R.string.export_saved_gallery)
    val savedAppStorage = stringResource(R.string.export_saved_app_storage)
    val failedPrefix = stringResource(R.string.export_failed)
    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is EditorViewModel.ExportEvent.Saved -> {
                    showExportSheet = false
                    snackbarHostState.showSnackbar(
                        if (event.toGallery) savedGallery else savedAppStorage,
                    )
                }

                is EditorViewModel.ExportEvent.ShareReady -> {
                    showExportSheet = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }

                is EditorViewModel.ExportEvent.Failed ->
                    snackbarHostState.showSnackbar("$failedPrefix ${event.message}")
            }
        }
    }

    // GLSurfaceView must see activity pause/resume or its GL thread leaks.
    DisposableEffect(surface, lifecycleOwner) {
        val surfaceView = surface
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> surfaceView?.onPause()
                Lifecycle.Event.ON_RESUME -> surfaceView?.onResume()
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

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopRail(
            // History is edit-mode territory; the strip pauses it so
            // keyframe pins can't shift under a scrub.
            canUndo = state.canUndo && !state.goovieMode,
            canRedo = state.canRedo && !state.goovieMode,
            canReset = state.canReset && !state.goovieMode,
            onBack = onBack,
            onUndo = {
                viewModel.undo()?.let { strokes -> surface?.engine { rebuild(strokes) } }
            },
            onRedo = {
                viewModel.redo()?.let { strokes -> surface?.engine { rebuild(strokes) } }
            },
            onReset = { confirmReset = true },
            onLevers = {
                // From the strip, the levers bead OPENS levers (not a blind
                // toggle — showLevers may already be true underneath).
                showLevers = if (state.goovieMode) true else !showLevers
                if (state.goovieMode) viewModel.toggleGoovie()
            },
            leversActive = !state.goovieMode && (showLevers || !state.globals.isIdentity),
            onGoovie = { viewModel.toggleGoovie() },
            goovieActive = state.goovieMode,
            onExport = { showExportSheet = true },
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
                        // Touches map through the INVERSE view transform
                        // first — painting happens in canvas space however
                        // the view is panned/zoomed/rotated.
                        fun toUv(px: Float, py: Float): Pair<Float, Float> {
                            val (cx, cy) = view.invert(px, py)
                            return fit.viewToUv(cx, cy)
                        }

                        val (u0, v0) = toUv(down.position.x, down.position.y)
                        // Ignore paint starts farther than a brush radius
                        // from the image (deep letterbox): they can never
                        // move a pixel, and committing them would light up
                        // Undo for a no-op. Starting within one radius
                        // stays allowed — outside-in edge smears are
                        // legitimate. The gesture itself continues: a
                        // second finger can still turn it into navigation.
                        val aspect = bitmap.width.toFloat() / bitmap.height
                        val du = (u0.coerceIn(0f, 1f) - u0) * aspect
                        val dv = v0.coerceIn(0f, 1f) - v0
                        val outside = sqrt(du * du + dv * dv)
                        var stroking = outside <= viewModel.uiState.value.brushRadius
                        if (stroking) viewModel.beginStroke(u0, v0)
                        down.consume()
                        val params = if (stroking) viewModel.liveStrokeParams() else null
                        var navigating = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }

                            if (!navigating && pressedCount >= 2) {
                                // Second finger: this gesture is navigation
                                // now. Commit the in-flight stroke (same
                                // policy as setTool / gesture CANCEL — its
                                // stamps are already on the field), and
                                // take over from any running reset spring.
                                navigating = true
                                viewResetJob?.cancel()
                                if (stroking) {
                                    stroking = false
                                    viewModel.endStroke()?.let { stroke ->
                                        surface?.engine { commit(stroke) }
                                    }
                                }
                            }

                            if (navigating) {
                                if (pressedCount == 0) break
                                val zoom = event.calculateZoom()
                                val rotationRad =
                                    event.calculateRotation() * DEGREES_TO_RADIANS
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f || rotationRad != 0f || pan != Offset.Zero) {
                                    view = view.gesture(
                                        centroidX = centroid.x,
                                        centroidY = centroid.y,
                                        panX = pan.x,
                                        panY = pan.y,
                                        zoom = zoom,
                                        rotationDelta = rotationRad,
                                    )
                                }
                                // All changes, pressed or not: nothing
                                // upstream competes today, but a future
                                // scrollable ancestor must never see these.
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // Deliberate: gesture CANCEL commits like
                                // an ordinary lift. The stroke's stamps
                                // are already visible on the field, so
                                // committing keeps screen ≡ document;
                                // discarding would snap pixels back and
                                // need a rebuild. Undo covers regrets.
                                if (stroking) {
                                    viewModel.endStroke()?.let { stroke ->
                                        // Feed the committed stroke to the
                                        // renderer's recovery snapshot (its
                                        // stamps are already on the GPU).
                                        surface?.engine { commit(stroke) }
                                    }
                                }
                                break
                            }
                            if (stroking && params != null) {
                                // Historical samples first: fast flicks
                                // batch several path points into one event,
                                // and skipping them would leave gaps.
                                val stamps = buildList {
                                    change.historical.forEach { h ->
                                        val (u, v) = toUv(h.position.x, h.position.y)
                                        addAll(viewModel.extendStroke(u, v))
                                    }
                                    val (u, v) = toUv(change.position.x, change.position.y)
                                    addAll(viewModel.extendStroke(u, v))
                                }
                                if (stamps.isNotEmpty()) {
                                    surface?.engine { stampBatch(params, stamps) }
                                }
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

            // Live brush preview while a Size/Strength slider is in hand.
            // Gated on the brush panel being the active one: a mid-drag
            // panel swap strands the flag true (onValueChangeFinished
            // never fires for a slider that left composition).
            state.bitmap?.let { bmp ->
                BrushPreviewOverlay(
                    visible = adjustingBrush && !state.goovieMode && !showLevers,
                    imageWidth = bmp.width,
                    imageHeight = bmp.height,
                    radius = state.brushRadius,
                    strength = state.brushStrength,
                    profile = state.tool.profile,
                    view = view,
                )
            }

            // Reset view: appears whenever the photo is off its fitted
            // pose; springs everything back to 100% and straight.
            androidx.compose.animation.AnimatedVisibility(
                visible = !view.isIdentity,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                CandyIconButton(
                    icon = Icons.Filled.CenterFocusStrong,
                    contentDescription = stringResource(R.string.editor_reset_view),
                    color = CandyCyan,
                    selected = false,
                    onClick = {
                        viewResetJob?.cancel()
                        val start = view
                        viewResetJob = viewResetScope.launch {
                            Animatable(0f).animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) {
                                view = start.lerp(ViewTransform(), value)
                            }
                        }
                    },
                )
            }

            // First-run hint: floats until the first stroke lands, ever.
            // Fully qualified on purpose: this Box nests inside the screen
            // Column, whose ColumnScope member extension AnimatedVisibility
            // captures the unqualified name (no `visible` overload here —
            // it doesn't compile). The import would not help.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.showHint && state.bitmap != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                enter = fadeIn() + slideInVertically(
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                ) { it / 2 },
                exit = fadeOut(),
            ) {
                Text(
                    text = stringResource(R.string.editor_hint_smear),
                    style = MaterialTheme.typography.labelLarge,
                    color = GooTableShadow,
                    modifier = Modifier
                        .background(CandyLemon, CircleShape)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        val panel = when {
            state.goovieMode -> EditorPanel.GOOVIE
            showLevers -> EditorPanel.LEVERS
            else -> EditorPanel.BRUSH
        }
        AnimatedContent(
            targetState = panel,
            transitionSpec = {
                val springIn = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = IntOffset.VisibilityThreshold,
                )
                // `using null` kills the default SizeTransform: animating the
                // panel height would re-measure the weight(1f) canvas — and
                // resize the GLSurfaceView — every frame of the spring. The
                // height snaps (as the old if/else did); content slides.
                (slideInVertically(springIn) { it / 3 } + fadeIn()) togetherWith
                    (slideOutVertically { it / 3 } + fadeOut()) using null
            },
            label = "panelSwap",
        ) { which ->
            when (which) {
                EditorPanel.LEVERS -> LeversPanel(
                    globals = state.globals,
                    onChange = viewModel::setGlobals,
                )
                EditorPanel.GOOVIE -> GooviePanel(
                    keyframes = state.keyframes,
                    scrubPos = state.scrubPos,
                    playing = state.playing,
                    selected = state.selectedKeyframe,
                    canCapture = state.keyframes.size < EditorViewModel.MAX_KEYFRAMES,
                    exporting = state.exportingMovie,
                    exportProgress = state.movieProgress,
                    onCapture = viewModel::captureKeyframe,
                    onSelect = viewModel::selectKeyframe,
                    onDelete = viewModel::deleteSelectedKeyframe,
                    onMove = viewModel::moveSelectedKeyframe,
                    onScrub = viewModel::scrubTo,
                    onPlayToggle = { viewModel.setPlaying(!state.playing) },
                    onExport = viewModel::exportGoovie,
                )
                EditorPanel.BRUSH -> BrushRail(
                    tool = state.tool,
                    mirrored = state.mirrored,
                    radius = state.brushRadius,
                    strength = state.brushStrength,
                    showFusionPick = state.tool == BrushTool.FUSE && state.bitmapB != null,
                    onToolChange = viewModel::setTool,
                    onMirrorToggle = viewModel::toggleMirror,
                    onRadiusChange = viewModel::setBrushRadius,
                    onStrengthChange = viewModel::setBrushStrength,
                    onAdjustingChange = { adjustingBrush = it },
                    onFusionPick = {
                        pickImageB.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onFusionRemove = viewModel::clearSecondImage,
                )
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }

    if (showExportSheet) {
        ExportSheet(
            exporting = state.exporting,
            onSave = viewModel::export,
            onShare = viewModel::share,
            onDismiss = { if (!state.exporting) showExportSheet = false },
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
    onLevers: () -> Unit,
    leversActive: Boolean,
    onGoovie: () -> Unit,
    goovieActive: Boolean,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CandyIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.editor_back),
            color = CandyCyan,
            selected = false,
            haptic = false,
            onClick = onBack,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CandyIconButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.editor_undo),
                color = CandyLime,
                selected = false,
                enabled = canUndo,
                onClick = onUndo,
            )
            CandyIconButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.editor_redo),
                color = CandyLime,
                selected = false,
                enabled = canRedo,
                onClick = onRedo,
            )
            CandyIconButton(
                icon = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.editor_reset),
                color = CandyOrange,
                selected = false,
                enabled = canReset,
                onClick = onReset,
            )
            CandyIconButton(
                icon = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.editor_levers),
                color = CandyCyan,
                selected = leversActive,
                selectable = true,
                onClick = onLevers,
            )
            CandyIconButton(
                icon = Icons.Filled.Movie,
                contentDescription = stringResource(R.string.editor_goovies),
                color = CandyLemon,
                selected = goovieActive,
                selectable = true,
                onClick = onGoovie,
            )
            CandyIconButton(
                icon = Icons.Filled.IosShare,
                contentDescription = stringResource(R.string.editor_export),
                color = CandyPink,
                selected = false,
                haptic = false,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun BrushRail(
    tool: BrushTool,
    mirrored: Boolean,
    radius: Float,
    strength: Float,
    showFusionPick: Boolean,
    onToolChange: (BrushTool) -> Unit,
    onMirrorToggle: () -> Unit,
    onRadiusChange: (Float) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onFusionPick: () -> Unit,
    onAdjustingChange: (Boolean) -> Unit,
    onFusionRemove: () -> Unit,
) {
    // A slider dragged off-screen (panel swap) never delivers its
    // onValueChangeFinished — clear the adjusting flag on the way out.
    DisposableEffect(Unit) {
        onDispose { onAdjustingChange(false) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BrushTool.entries.forEach { entry ->
                CandyToolChip(
                    icon = entry.icon(),
                    label = stringResource(entry.labelRes()),
                    color = entry.candyColor(),
                    selected = tool == entry,
                    onClick = { onToolChange(entry) },
                )
            }
            CandyToolChip(
                icon = Icons.Filled.Flip,
                label = stringResource(R.string.tool_mirror),
                color = CandyGrape,
                selected = mirrored,
                onClick = onMirrorToggle,
            )
            if (showFusionPick) {
                CandyToolChip(
                    icon = Icons.Filled.Collections,
                    label = stringResource(R.string.fusion_change_photo),
                    color = CandyGrape,
                    selected = false,
                    onClick = onFusionPick,
                )
                CandyToolChip(
                    icon = Icons.Filled.HideImage,
                    label = stringResource(R.string.fusion_remove_photo),
                    color = CandyOrange,
                    selected = false,
                    onClick = onFusionRemove,
                )
            }
        }
        LabeledSlider(
            label = stringResource(R.string.editor_brush_size),
            value = radius,
            onValueChange = onRadiusChange,
            valueRange = EditorViewModel.MIN_RADIUS..EditorViewModel.MAX_RADIUS,
            onAdjustingChange = onAdjustingChange,
        )
        LabeledSlider(
            label = stringResource(R.string.editor_brush_strength),
            value = strength,
            onValueChange = onStrengthChange,
            valueRange = 0.05f..1f,
            onAdjustingChange = onAdjustingChange,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onAdjustingChange: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            modifier = Modifier.weight(1f),
            value = value,
            onValueChange = {
                onAdjustingChange(true)
                onValueChange(it)
            },
            onValueChangeFinished = { onAdjustingChange(false) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = CandyPink,
                activeTrackColor = CandyPink.copy(alpha = 0.6f),
                inactiveTrackColor = GooTableShadow,
            ),
        )
    }
}

/** Which bottom panel the editor shows; GOOVIE follows the ViewModel. */
private enum class EditorPanel { BRUSH, LEVERS, GOOVIE }

private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

@StringRes
private fun BrushTool.labelRes(): Int = when (this) {
    BrushTool.SMEAR -> R.string.tool_smear
    BrushTool.MOVE -> R.string.tool_move
    BrushTool.SMUDGE -> R.string.tool_smudge
    BrushTool.NUDGE -> R.string.tool_nudge
    BrushTool.GROW -> R.string.tool_grow
    BrushTool.SHRINK -> R.string.tool_shrink
    BrushTool.SMOOTH -> R.string.tool_smooth
    BrushTool.UNGOO -> R.string.tool_ungoo
    BrushTool.FUSE -> R.string.tool_fuse
}

private fun BrushTool.icon(): ImageVector = when (this) {
    BrushTool.SMEAR -> Icons.Filled.Gesture
    BrushTool.MOVE -> Icons.Filled.OpenWith
    BrushTool.SMUDGE -> Icons.Filled.BlurOn
    BrushTool.NUDGE -> Icons.Filled.TouchApp
    BrushTool.GROW -> Icons.Filled.ZoomIn
    BrushTool.SHRINK -> Icons.Filled.ZoomOut
    BrushTool.SMOOTH -> Icons.Filled.Waves
    BrushTool.UNGOO -> Icons.Filled.AutoFixHigh
    BrushTool.FUSE -> Icons.Filled.PhotoLibrary
}

/** Each tool wears its own candy — families share a flavor. */
private fun BrushTool.candyColor(): Color = when (this) {
    BrushTool.SMEAR -> CandyPink
    BrushTool.MOVE -> CandyCyan
    BrushTool.SMUDGE -> CandyPink
    BrushTool.NUDGE -> CandyCyan
    BrushTool.GROW -> CandyOrange
    BrushTool.SHRINK -> CandyLemon
    BrushTool.SMOOTH -> CandyLime
    BrushTool.UNGOO -> CandyLime
    BrushTool.FUSE -> CandyGrape
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
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.editor_back), color = CandyCyan)
        }
    }
}
