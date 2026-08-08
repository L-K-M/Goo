package ch.lkmc.goo.ui.editor

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SouthEast
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import ch.lkmc.goo.engine.core.CropRect
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.ViewTransform
import ch.lkmc.goo.engine.gl.GlWarpRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ch.lkmc.goo.engine.gl.WarpSurfaceView
import ch.lkmc.goo.ui.components.ChromeIconButton
import ch.lkmc.goo.ui.components.chromePanel
import ch.lkmc.goo.ui.components.ChromeToolChip
import ch.lkmc.goo.ui.export.ExportSheet
import ch.lkmc.goo.ui.export.MovieExportSheet
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonViolet
import ch.lkmc.goo.ui.theme.NeonAmber
import ch.lkmc.goo.ui.theme.NeonLime
import ch.lkmc.goo.ui.theme.NeonTangerine
import ch.lkmc.goo.ui.theme.NeonMagenta
import ch.lkmc.goo.ui.theme.MeltVoid

/**
 * The Goo room: the GL canvas with the brush pipeline wired through the
 * ViewModel (touch → resampler → stamps → GPU), a minimal control rail
 * (undo/redo/reset/brush size), mounted on milled console plates.
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

            state.error != null -> ErrorPane(message = stringResource(state.error!!), onBack = onBack)

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
    // A write is running with the room about to close behind it. Distinct
    // from state.savingProject, which is also true for the rail's Save
    // bead — that one must not put a scrim over the canvas.
    var leaving by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showMovieSheet by remember { mutableStateOf(false) }
    var showLevers by remember { mutableStateOf(false) }
    var adjustingBrush by remember { mutableStateOf(false) }
    // Crop mode: the overlay owns the canvas; painting is gated off.
    // pendingCrop holds an Apply awaiting the "start fresh" confirm
    // (only asked when there is goo to lose).
    var showCrop by remember { mutableStateOf(false) }
    var showFunhouse by remember { mutableStateOf(false) }
    // Which lens is in hand. Pure UI: the rack itself lives in
    // globals, and a selection is not document state.
    var selectedLens by remember { mutableIntStateOf(-1) }
    var pendingCrop by remember { mutableStateOf<CropAction?>(null) }
    // Leaving writes the document, then goes. There is no "are you sure":
    // nothing is lost by leaving, so there is nothing to ask about (the
    // old guard existed only because the session lived in memory alone —
    // ANALYSIS SOL-7 called it an interim measure, and SOL-34 retired it).
    // A goo you did not want is thrown away from the In room, where the
    // rest of the shelf lives.
    //
    // Remembered rather than rebuilt each pass: WarpEditor recomposes on
    // every pointer move (the cursor ring's strokePos), and handing the
    // rail a fresh lambda would drag all nine of its beads into every one
    // of those frames. rememberUpdatedState keeps the lambda reading
    // current state without making the lambda itself unstable.
    val latestState by rememberUpdatedState(state)
    val leaveEditor: () -> Unit = remember(onBack, viewModel) {
        {
            if (latestState.savingProject) {
                // A write is already in flight (a checkpoint landed as the
                // finger came down on Back). Ride it out: it closes the
                // room when it lands, and starting a second write would
                // only copy the same photo twice.
                leaving = true
                viewModel.leaveWhenCurrentSaveLands()
            } else if (latestState.hasUnwrittenChanges) {
                leaving = true
                viewModel.saveProject(EditorViewModel.SaveReason.LEAVE)
            } else {
                onBack()
            }
        }
    }
    // Crop mode owns the whole canvas: back leaves the overlay, the way it
    // would dismiss any other modal, instead of leaving the room. Enabled
    // only when there is something to intercept — a disabled BackHandler
    // lets the system run its own (predictive) animation for the pop, and
    // with nothing to write that pop is exactly right.
    //
    // Deliberately still enabled during a movie export: leaving tears the
    // surface down, which cancels the export (the engineBridge
    // DisposableEffect above), and the write on the way out is worth more
    // there than anywhere else.
    BackHandler(enabled = showCrop || showFunhouse || state.hasUnwrittenChanges || leaving) {
        when {
            showCrop -> showCrop = false
            showFunhouse -> {
                showFunhouse = false
                selectedLens = -1
            }
            else -> leaveEditor()
        }
    }
    // Pan/zoom/rotate of the preview. Ephemeral across process recreation;
    // viewport changes rebase it around the same source point below.
    var view by remember { mutableStateOf(ViewTransform()) }
    // One reset spring at a time: re-clicks restart it, and new two-finger
    // input cancels it instead of fighting it frame-by-frame.
    val viewResetScope = rememberCoroutineScope()
    var viewResetJob by remember { mutableStateOf<Job?>(null) }
    fun animateViewReset() {
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
    }
    // Brush cursor: the touch point in view px while a stroke is down
    // (null = finger up / navigating), and the stroke's radius, frozen at
    // beginStroke — captured once at gesture start, so the overlay reads
    // no ViewModel state during a drag.
    var strokePos by remember { mutableStateOf<Offset?>(null) }
    var strokeRadius by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Failure wording arrives as a string RESOURCE on a one-shot event, so
    // it can't be a stringResource call at composition time. LocalResources
    // (not LocalContext.getString) is what re-reads on a locale change.
    val resources = LocalResources.current
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

    // The frost sheen shows only while the varnish is in hand, so the
    // mask is something to see rather than something to remember. Same
    // uniform-sync pattern as the levers, including on surface recreate.
    LaunchedEffect(surface, state.tool) {
        val armed = state.tool == BrushTool.FREEZE
        surface?.engine { setShowFreezeMask(armed) }
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
    // payload; leaving the strip, gooing inside it, or having < 2
    // keyframes shows live. (The ViewModel also clears the tween eagerly
    // when a stroke starts — this effect is the steady-state sync.)
    LaunchedEffect(surface, state.goovieMode, state.goovieLive, state.scrubPos, state.keyframes) {
        val req = viewModel.tweenRequest()
        if (req != null) {
            surface?.engine { tweenTo(req.revisionA, req.revisionB, req.t, req.lerpedGlobals) }
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
    val savedMovieGallery = stringResource(R.string.export_saved_movies)
    val savedAppStorage = stringResource(R.string.export_saved_app_storage)
    val projectSaved = stringResource(R.string.project_saved)
    val failedPrefix = stringResource(R.string.export_failed)
    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is EditorViewModel.ExportEvent.Saved -> {
                    showExportSheet = false
                    showMovieSheet = false
                    snackbarHostState.showSnackbar(
                        when {
                            !event.toGallery -> savedAppStorage
                            event.video -> savedMovieGallery
                            else -> savedGallery
                        },
                    )
                }

                is EditorViewModel.ExportEvent.ShareReady -> {
                    showExportSheet = false
                    showMovieSheet = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }

                is EditorViewModel.ExportEvent.Failed ->
                    snackbarHostState.showSnackbar(
                        "$failedPrefix ${resources.getString(event.reason)}",
                    )
            }
        }
    }

    // Project writes. The document is on disk, so the room can close —
    // and a write that was already running when Back was pressed closes
    // it too ([leaving]), which is why this reads the flag rather than
    // only the event's own reason.
    LaunchedEffect(viewModel) {
        viewModel.projectEvents.collect { event ->
            when (event) {
                is EditorViewModel.ProjectEvent.Saved ->
                    if (event.leave || leaving) {
                        onBack()
                    } else {
                        snackbarHostState.showSnackbar(projectSaved)
                    }

                is EditorViewModel.ProjectEvent.Failed -> {
                    // Never strand the user in a room they asked to leave
                    // with no way out and no explanation: drop the scrim,
                    // say what happened, and let them try again or leave
                    // (Back with a failed write still offers to retry).
                    leaving = false
                    snackbarHostState.showSnackbar(
                        "$failedPrefix ${resources.getString(event.reason)}",
                    )
                }
            }
        }
    }

    // GLSurfaceView must see activity pause/resume or its GL thread leaks.
    //
    // ON_STOP is also where the document reaches disk. It is the last
    // callback the platform guarantees before a backgrounded process can
    // be reclaimed, so an app the OS kills while it is away has already
    // been saved. (The ViewModel's scope outlives ON_STOP — it is cleared
    // when the room closes, not when it is hidden — so the write finishes
    // in the background.)
    DisposableEffect(surface, lifecycleOwner, viewModel) {
        val surfaceView = surface
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> surfaceView?.onPause()
                Lifecycle.Event.ON_RESUME -> surfaceView?.onResume()
                Lifecycle.Event.ON_STOP -> viewModel.autosaveProject()
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
            // History works inside the strip too — it drops the preview to
            // live like a stroke does. Only a movie render locks it out:
            // that owns the GL thread and snapshots the log up front.
            canUndo = state.canUndo && !state.exportingMovie,
            canRedo = state.canRedo && !state.exportingMovie,
            canReset = state.canReset && !state.exportingMovie,
            onBack = leaveEditor,
            onUndo = {
                viewModel.undo()?.let { strokes -> surface?.engine { rebuild(strokes) } }
            },
            onRedo = {
                viewModel.redo()?.let { strokes -> surface?.engine { rebuild(strokes) } }
            },
            onReset = { confirmReset = true },
            // A deal is a batch of ordinary strokes: stamp them and
            // record them exactly as a finger's would be.
            canDeal = state.bitmap != null && !state.exportingMovie,
            onDeal = {
                viewModel.dealGoo()?.let { strokes ->
                    surface?.engine { rebuild(strokes) }
                }
            },
            onCrop = {
                if (showCrop) {
                    showCrop = false
                } else {
                    // Same mid-gesture policy as setTool/toggleGoovie: a
                    // second finger can tap the bead — commit, don't drop.
                    viewModel.endStroke()?.let { s -> surface?.engine { commit(s) } }
                    strokePos = null
                    showLevers = false
                    if (state.goovieMode) viewModel.toggleGoovie()
                    // The overlay works in the fitted pose: snap the view
                    // home (no spring — the crop UI needs it NOW).
                    viewResetJob?.cancel()
                    view = ViewTransform()
                    showCrop = true
                }
            },
            cropActive = showCrop,
            cropEnabled = !state.exporting && !state.exportingMovie,
            funhouseActive = showFunhouse,
            onFunhouse = {
                showCrop = false
                showLevers = false
                if (state.goovieMode) viewModel.toggleGoovie()
                // Commit any in-flight stroke, same policy as setTool:
                // its stamps are already on the field.
                viewModel.endStroke()?.let { st -> surface?.engine { commit(st) } }
                strokePos = null
                showFunhouse = !showFunhouse
                if (!showFunhouse) selectedLens = -1
            },
            onLevers = {
                // From the strip, the levers bead OPENS levers (not a blind
                // toggle — showLevers may already be true underneath).
                showCrop = false
                showLevers = if (state.goovieMode) true else !showLevers
                if (state.goovieMode) viewModel.toggleGoovie()
            },
            leversActive = !state.goovieMode && (showLevers || !state.globals.isIdentity),
            onGoovie = {
                showCrop = false
                viewModel.toggleGoovie()
            },
            goovieActive = state.goovieMode,
            onSave = { viewModel.saveProject(EditorViewModel.SaveReason.EXPLICIT) },
            // Dark once everything on screen is on disk — including
            // straight after a save, which is how the bead answers
            // "did that work?" without a second dialog.
            canSave = state.hasUnwrittenChanges && !state.savingProject,
            onExport = { showExportSheet = true },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { newSize ->
                    val oldSize = canvasSize
                    if (oldSize.width > 0 && oldSize.height > 0 &&
                        newSize.width > 0 && newSize.height > 0 &&
                        oldSize != newSize
                    ) {
                        val resumeReset = viewResetJob?.isActive == true
                        viewResetJob?.cancel()
                        view = view.rebase(
                            oldFit = FitTransform(
                                oldSize.width.toFloat(), oldSize.height.toFloat(),
                                bitmap.width.toFloat(), bitmap.height.toFloat(),
                            ),
                            newFit = FitTransform(
                                newSize.width.toFloat(), newSize.height.toFloat(),
                                bitmap.width.toFloat(), bitmap.height.toFloat(),
                            ),
                        )
                        if (resumeReset) animateViewReset()
                    }
                    canvasSize = newSize
                }
                .pointerInput(bitmap, showCrop, showFunhouse) {
                    // Crop and Funhouse each own all input while open.
                    // Their own pointerInput consumes first anyway
                    // (topmost child); keying on the flags makes the
                    // hand-off explicit and cancels any in-flight gesture
                    // at the flip. Painting and moving apparatus are
                    // different verbs; sharing the canvas would make
                    // every tap a guess.
                    if (showCrop || showFunhouse) return@pointerInput
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
                        // beginStroke has the final say: it refuses while a
                        // movie render owns the GL thread, and a ring drawn
                        // over a canvas that can't paint is a lie.
                        var stroking = outside <= viewModel.uiState.value.brushRadius &&
                            viewModel.beginStroke(u0, v0)
                        down.consume()
                        val params = if (stroking) viewModel.liveStrokeParams() else null
                        var navigating = false
                        if (stroking && params != null) {
                            strokePos = down.position
                            strokeRadius = params.radius
                        }

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
                                strokePos = null
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
                                strokePos = change.position
                            }
                            change.consume()
                        }
                        strokePos = null
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
                EchoAnchorOverlay(
                    anchor = state.echoAnchor.takeIf { state.tool == BrushTool.ECHO },
                    imageWidth = bmp.width,
                    imageHeight = bmp.height,
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
                ChromeIconButton(
                    icon = Icons.Filled.CenterFocusStrong,
                    contentDescription = stringResource(R.string.editor_reset_view),
                    color = NeonCyan,
                    selected = false,
                    onClick = ::animateViewReset,
                )
            }

            // Brush cursor while painting: KPT drew a ring for the brush;
            // on a phone the finger covers the work, so position feedback
            // matters even more. Same ring style as BrushPreviewOverlay,
            // plus a center dot for aim. The center is the touch (already
            // in view space); the radius is aspect-space (fraction of
            // image height) → canvas px → scaled by the view zoom.
            // Remembered unconditionally (not inside the conditional
            // branch): positional memoization in branches is a footgun.
            val cursorFit = remember(canvasSize, bitmap.width, bitmap.height) {
                FitTransform(
                    viewWidth = canvasSize.width.toFloat(),
                    viewHeight = canvasSize.height.toFloat(),
                    imageWidth = bitmap.width.toFloat(),
                    imageHeight = bitmap.height.toFloat(),
                )
            }
            val cursor = strokePos
            if (cursor != null && canvasSize.width > 0) {
                val ringRadius = strokeRadius * cursorFit.fittedHeight * view.scale
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Dark backing under the white ring: readable on any photo.
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.35f),
                        radius = ringRadius,
                        center = cursor,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = ringRadius,
                        center = cursor,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 2.5.dp.toPx(),
                        center = cursor,
                    )
                }
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
                    color = MeltVoid,
                    modifier = Modifier
                        .background(NeonAmber, CircleShape)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            // Funhouse mode: the apparatus is drawn on the photo and
            // owns canvas input while it is open.
            if (showFunhouse && !showCrop) {
                FunhouseOverlay(
                    lenses = state.globals.lenses,
                    selected = selectedLens,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    view = view,
                    onPlace = { u, v ->
                        viewModel.placeLens(u, v)?.let { selectedLens = it }
                    },
                    onSelect = { selectedLens = it },
                    onCycle = viewModel::cycleLensType,
                    onMove = viewModel::moveLens,
                    onRemove = { index ->
                        viewModel.removeLens(index)
                        // Indices shift down past the hole; holding the
                        // old one would leave a different lens selected
                        // than the ring the user is looking at.
                        selectedLens = -1
                    },
                )
            }

            // Crop mode, topmost: dims the photo, owns all canvas input.
            if (showCrop) {
                // Applying restarts the goo — confirm only when there is
                // goo to lose (strokes, levers, or keyframes).
                val commitCrop: (CropRect?) -> Unit = { r ->
                    viewModel.applyCrop(r)
                    showCrop = false
                }
                val requestCrop: (CropRect?) -> Unit = { r ->
                    if (state.canReset || state.keyframes.isNotEmpty()) {
                        pendingCrop = CropAction(r)
                    } else {
                        commitCrop(r)
                    }
                }
                CropOverlay(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    showFullFrame = state.cropped,
                    onApply = { rect ->
                        // A full rect applies nothing: ✓ just closes.
                        // Uncropping belongs to the dedicated full-frame
                        // bead — review: ✓ silently destroying an active
                        // crop (not undoable, rect stored nowhere after)
                        // was too easy to hit as "OK, done looking".
                        if (rect.isFullFrame) showCrop = false else requestCrop(rect)
                    },
                    onFullFrame = { requestCrop(null) },
                    onCancel = { showCrop = false },
                )
            }
        }

        val panel = when {
            state.goovieMode -> EditorPanel.GOOVIE
            showFunhouse -> EditorPanel.FUNHOUSE
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
                EditorPanel.FUNHOUSE -> FunhousePanel(
                    lens = state.globals.lenses.getOrNull(selectedLens),
                    placed = state.globals.lenses.size,
                    onRadius = { viewModel.resizeLens(selectedLens, it) },
                    onStrength = { viewModel.setLensStrength(selectedLens, it) },
                    onCycleType = { viewModel.cycleLensType(selectedLens) },
                    onRemove = {
                        viewModel.removeLens(selectedLens)
                        selectedLens = -1
                    },
                )
                EditorPanel.GOOVIE -> GooviePanel(
                    keyframes = state.keyframes,
                    scrubPos = state.scrubPos,
                    playing = state.playing,
                    selected = state.selectedKeyframe,
                    live = state.goovieLive,
                    stale = state.selectedKeyframeStale,
                    canCapture = state.keyframes.size < EditorViewModel.MAX_KEYFRAMES,
                    exporting = state.exportingMovie,
                    exportProgress = state.movieProgress,
                    onCapture = viewModel::captureKeyframe,
                    onRepunch = viewModel::repunchSelectedKeyframe,
                    onCycleEasing = viewModel::cycleSelectedEasing,
                    onSelect = viewModel::selectKeyframe,
                    onDelete = viewModel::deleteSelectedKeyframe,
                    onMove = viewModel::moveSelectedKeyframe,
                    onScrub = viewModel::scrubTo,
                    onPlayToggle = { viewModel.setPlaying(!state.playing) },
                    onExport = { showMovieSheet = true },
                )
                EditorPanel.BRUSH -> BrushRail(
                    tool = state.tool,
                    mirrored = state.mirrored,
                    radius = state.brushRadius,
                    strength = state.brushStrength,
                    showFusionPick = state.tool == BrushTool.FUSE && state.bitmapB != null,
                    fusionLoading = state.importingPhotoB,
                    keyframeCount = state.keyframes.size,
                    // 1-based, or 0 to hide: the chip appears exactly while
                    // the selected pin lags the goo on screen, which is the
                    // moment "why didn't my keyframe change?" gets asked.
                    updateKeyframe = if (state.selectedKeyframeStale) {
                        state.selectedKeyframe + 1
                    } else {
                        0
                    },
                    onToolChange = viewModel::setTool,
                    sectors = state.sectors,
                    onMirrorToggle = viewModel::toggleMirror,
                    onCycleSectors = viewModel::cycleSectors,
                    onRadiusChange = viewModel::setBrushRadius,
                    onStrengthChange = viewModel::setBrushStrength,
                    onAdjustingChange = { adjustingBrush = it },
                    onPunch = viewModel::captureKeyframe,
                    onRepunch = viewModel::repunchSelectedKeyframe,
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
    // The write on the way out. Usually a blink — but the first save of a
    // session copies the source photo, and on a 50 MP picture that is long
    // enough that a room which simply stopped responding would read as a
    // hang. It also swallows taps, which is the point: the document being
    // written must not change underneath the writer.
    if (leaving) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MeltVoid.copy(alpha = 0.72f))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = NeonLime)
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

    if (showMovieSheet) {
        MovieExportSheet(
            keyframeCount = state.keyframes.size,
            exporting = state.exportingMovie,
            progress = state.movieProgress,
            onSave = { format, speed, loop ->
                viewModel.exportGoovie(share = false, format = format, speed = speed, loop = loop)
            },
            onShare = { format, speed, loop ->
                viewModel.exportGoovie(share = true, format = format, speed = speed, loop = loop)
            },
            onDismiss = { if (!state.exportingMovie) showMovieSheet = false },
        )
    }

    pendingCrop?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingCrop = null },
            title = { Text(stringResource(R.string.crop_confirm_title)) },
            text = { Text(stringResource(R.string.crop_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCrop = null
                        viewModel.applyCrop(action.rect)
                        showCrop = false
                    },
                ) { Text(stringResource(R.string.crop_confirm_go)) }
            },
            dismissButton = {
                // Back to the overlay, rect intact — not a full cancel.
                TextButton(onClick = { pendingCrop = null }) {
                    Text(stringResource(R.string.editor_reset_cancel))
                }
            },
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
    canDeal: Boolean,
    onDeal: () -> Unit,
    onCrop: () -> Unit,
    cropActive: Boolean,
    cropEnabled: Boolean,
    funhouseActive: Boolean,
    onFunhouse: () -> Unit,
    onLevers: () -> Unit,
    leversActive: Boolean,
    onGoovie: () -> Unit,
    goovieActive: Boolean,
    onSave: () -> Unit,
    canSave: Boolean,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Plate first, insets after: the milled panel runs up behind
            // the status bar instead of floating below a bare strip.
            .chromePanel(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.editor_back),
            color = NeonCyan,
            selected = false,
            haptic = false,
            onClick = onBack,
        )
        // Scrollable: back + seven 48dp beads + spacing is ~428dp — wider
        // than a 360dp screen, so a plain Row would push the export bead
        // off the right edge on exactly the phones Goo targets.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChromeIconButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.editor_undo),
                color = NeonLime,
                selected = false,
                enabled = canUndo,
                onClick = onUndo,
            )
            ChromeIconButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.editor_redo),
                color = NeonLime,
                selected = false,
                enabled = canRedo,
                onClick = onRedo,
            )
            ChromeIconButton(
                icon = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.editor_reset),
                color = NeonTangerine,
                selected = false,
                enabled = canReset,
                onClick = onReset,
            )
            // Next to Undo on purpose: the loop is tap → laugh → undo →
            // tap, and the two beads that drive it should be a thumb
            // apart.
            ChromeIconButton(
                icon = Icons.Filled.Casino,
                contentDescription = stringResource(R.string.editor_goo_me),
                color = NeonMagenta,
                selected = false,
                enabled = canDeal,
                onClick = onDeal,
            )
            ChromeIconButton(
                icon = Icons.Filled.Crop,
                contentDescription = stringResource(R.string.editor_crop),
                color = NeonViolet,
                selected = cropActive,
                selectable = true,
                enabled = cropEnabled,
                onClick = onCrop,
            )
            // Beside the levers on purpose: a lens IS a lever with a
            // position, and the two rooms are the same idea at different
            // scales — one glued to the frame, one you put where you want.
            ChromeIconButton(
                icon = Icons.Filled.FilterTiltShift,
                contentDescription = stringResource(R.string.funhouse),
                color = NeonViolet,
                selected = funhouseActive,
                selectable = true,
                onClick = onFunhouse,
            )
            ChromeIconButton(
                icon = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.editor_levers),
                color = NeonCyan,
                selected = leversActive,
                selectable = true,
                onClick = onLevers,
            )
            ChromeIconButton(
                icon = Icons.Filled.Movie,
                contentDescription = stringResource(R.string.editor_goovies),
                color = NeonAmber,
                selected = goovieActive,
                selectable = true,
                onClick = onGoovie,
            )
            // Save the DOCUMENT, next to (not inside) the Out tray that
            // saves pictures — two different verbs, so two beads. Lit only
            // when there is something new to write, which also makes it
            // the rail's honest answer to "did that get saved?".
            ChromeIconButton(
                icon = Icons.Filled.Save,
                contentDescription = stringResource(R.string.editor_save),
                color = NeonLime,
                selected = false,
                enabled = canSave,
                onClick = onSave,
            )
            ChromeIconButton(
                icon = Icons.Filled.IosShare,
                contentDescription = stringResource(R.string.editor_export),
                color = NeonMagenta,
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
    sectors: Int,
    radius: Float,
    strength: Float,
    showFusionPick: Boolean,
    fusionLoading: Boolean,
    keyframeCount: Int,
    /** 1-based keyframe the Update chip would re-pin; 0 hides the chip. */
    updateKeyframe: Int,
    onToolChange: (BrushTool) -> Unit,
    onMirrorToggle: () -> Unit,
    onCycleSectors: () -> Unit,
    onRadiusChange: (Float) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onPunch: () -> Unit,
    onRepunch: () -> Unit,
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
            .chromePanel(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                ChromeToolChip(
                    icon = entry.icon(),
                    label = stringResource(entry.labelRes()),
                    color = entry.neonColor(),
                    selected = tool == entry,
                    enabled = entry != BrushTool.FUSE || !fusionLoading,
                    onClick = { onToolChange(entry) },
                )
            }
            ChromeToolChip(
                icon = Icons.Filled.Flip,
                label = stringResource(R.string.tool_mirror),
                color = NeonViolet,
                selected = mirrored,
                onClick = onMirrorToggle,
            )
            // The kaleidoscope dial sits beside Mirror because the two
            // compose: sectors alone give a pinwheel, sectors with Mirror
            // give the mirror lines a real kaleidoscope has.
            ChromeToolChip(
                icon = Icons.Filled.Details,
                label = if (sectors > 1) {
                    stringResource(R.string.tool_sectors_count, sectors)
                } else {
                    stringResource(R.string.tool_sectors)
                },
                color = NeonTangerine,
                selected = sectors > 1,
                onClick = onCycleSectors,
            )
            // The KPT loop is goo → punch → goo → punch; punching never
            // needed the strip open (pins are stroke counts, safe to grab
            // mid-edit), so the bead lives on the rail. The count in the
            // label is the punch confirmation.
            ChromeToolChip(
                icon = Icons.Filled.AddAPhoto,
                label = if (keyframeCount > 0) {
                    stringResource(R.string.goovie_punch_count, keyframeCount)
                } else {
                    stringResource(R.string.goovie_punch)
                },
                color = NeonAmber,
                selected = false,
                enabled = keyframeCount < EditorViewModel.MAX_KEYFRAMES,
                onClick = onPunch,
            )
            // Re-punch, on the rail for the same reason Punch is: taking a
            // pin is just snapshotting the log, safe mid-edit, and the
            // strip shouldn't have to be open. This is the only way goo
            // made AFTER a punch reaches an existing keyframe.
            if (updateKeyframe > 0) {
                ChromeToolChip(
                    icon = Icons.Filled.Cached,
                    label = stringResource(R.string.goovie_update_count, updateKeyframe),
                    color = NeonAmber,
                    selected = false,
                    onClick = onRepunch,
                )
            }
            if (showFusionPick) {
                ChromeToolChip(
                    icon = Icons.Filled.Collections,
                    label = stringResource(R.string.fusion_change_photo),
                    color = NeonViolet,
                    selected = false,
                    enabled = !fusionLoading,
                    onClick = onFusionPick,
                )
                ChromeToolChip(
                    icon = Icons.Filled.HideImage,
                    label = stringResource(R.string.fusion_remove_photo),
                    color = NeonTangerine,
                    selected = false,
                    enabled = !fusionLoading,
                    onClick = onFusionRemove,
                )
            }
        }
        if (fusionLoading) {
            Text(
                text = stringResource(R.string.fusion_loading_photo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val sizeLabel = stringResource(R.string.editor_brush_size)
        val strengthLabel = stringResource(R.string.editor_brush_strength)
        // One label column, measured from the labels themselves rather
        // than pinned at a width that happened to fit "Size": at 64dp,
        // "Strength" wrapped to "Strengt" + "h". Measuring keeps the two
        // sliders aligned in every language and at every font scale —
        // German and Chinese set these words at very different widths.
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val labelWidth = remember(sizeLabel, strengthLabel, labelStyle, density) {
            with(density) {
                listOf(sizeLabel, strengthLabel)
                    .maxOf { measurer.measure(it, labelStyle).size.width }
                    .toDp()
            }
        }
        LabeledSlider(
            label = sizeLabel,
            labelWidth = labelWidth,
            value = radius,
            onValueChange = onRadiusChange,
            valueRange = EditorViewModel.MIN_RADIUS..EditorViewModel.MAX_RADIUS,
            onAdjustingChange = onAdjustingChange,
        )
        LabeledSlider(
            label = strengthLabel,
            labelWidth = labelWidth,
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
    labelWidth: Dp,
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
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The column is measured to fit, so wrapping can only mean
            // the measurement disagreed with the layout — clip a hair
            // rather than break a word across two lines again.
            maxLines = 1,
            softWrap = false,
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
                thumbColor = NeonMagenta,
                activeTrackColor = NeonMagenta.copy(alpha = 0.6f),
                inactiveTrackColor = MeltVoid,
            ),
        )
    }
}

/** Which bottom panel the editor shows; GOOVIE follows the ViewModel. */
private enum class EditorPanel { BRUSH, LEVERS, FUNHOUSE, GOOVIE }

/**
 * A crop request awaiting the "start fresh" confirm. rect is relative to
 * the on-screen frame; null asks for the full original picture back.
 */
private data class CropAction(val rect: CropRect?)

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
    BrushTool.VORTEX -> R.string.tool_vortex
    BrushTool.UNWIND -> R.string.tool_unwind
    BrushTool.MELT -> R.string.tool_melt
    BrushTool.COMB -> R.string.tool_comb
    BrushTool.POND -> R.string.tool_pond
    BrushTool.FAULT -> R.string.tool_fault
    BrushTool.ECHO -> R.string.tool_echo
    BrushTool.FREEZE -> R.string.tool_freeze
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
    BrushTool.VORTEX -> Icons.Filled.RotateRight
    BrushTool.UNWIND -> Icons.Filled.RotateLeft
    BrushTool.MELT -> Icons.Filled.SouthEast
    BrushTool.COMB -> Icons.Filled.Dehaze
    BrushTool.POND -> Icons.Filled.RadioButtonChecked
    BrushTool.FAULT -> Icons.Filled.CompareArrows
    BrushTool.ECHO -> Icons.Filled.ContentCopy
    BrushTool.FREEZE -> Icons.Filled.AcUnit
}

/** Each tool wears its own tube of neon — families share a color. */
private fun BrushTool.neonColor(): Color = when (this) {
    BrushTool.SMEAR -> NeonMagenta
    BrushTool.MOVE -> NeonCyan
    BrushTool.SMUDGE -> NeonMagenta
    BrushTool.NUDGE -> NeonCyan
    BrushTool.GROW -> NeonTangerine
    BrushTool.SHRINK -> NeonAmber
    BrushTool.SMOOTH -> NeonLime
    BrushTool.UNGOO -> NeonLime
    BrushTool.FUSE -> NeonViolet
    BrushTool.VORTEX -> NeonTangerine
    BrushTool.UNWIND -> NeonAmber
    BrushTool.MELT -> NeonMagenta
    BrushTool.COMB -> NeonLime
    BrushTool.POND -> NeonCyan
    BrushTool.FAULT -> NeonViolet
    BrushTool.ECHO -> NeonCyan
    // Its own tube: the varnish is the palette's only brake, and a
    // brake that looks like the accelerators is a brake nobody reaches
    // for.
    BrushTool.FREEZE -> NeonViolet
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
            Text(stringResource(R.string.editor_back), color = NeonCyan)
        }
    }
}
