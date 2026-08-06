package ch.lkmc.goo.ui.home

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.goo.BuildConfig
import ch.lkmc.goo.R
import ch.lkmc.goo.data.ProjectStore
import ch.lkmc.goo.ui.components.ChromeButton
import ch.lkmc.goo.ui.components.GridBackdrop
import ch.lkmc.goo.ui.components.Wordmark
import ch.lkmc.goo.ui.theme.MeltPanel
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonMagenta
import ch.lkmc.goo.ui.theme.NeonTangerine
import ch.lkmc.goo.ui.theme.NeonViolet
import ch.lkmc.goo.ui.theme.chromeSweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The In room: a console standing on a neon horizon, with one giant
 * button into the goo. The system Photo Picker needs no permission and no
 * gallery UI of our own — Meltorama is an editor, not a browser
 * (PLAN.md §1). Below the door: saved projects to pick back up, then
 * bundled samples for instant play (PLAN.md §6.1), procedurally generated
 * by scripts/generate_samples.py.
 */
@Composable
fun HomeScreen(
    onOpenImage: (Uri) -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onOpenImage) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The editor writes projects behind this screen's back, so the shelf
    // is re-read on every return rather than once at composition.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title, BuildConfig.VERSION_NAME)) },
            text = { Text(stringResource(R.string.about_body)) },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.about_close))
                }
            },
        )
    }

    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.project_delete_all_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.project_delete_all_body,
                        state.projects.size,
                        state.projects.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        viewModel.deleteAll()
                    },
                ) { Text(stringResource(R.string.project_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.project_delete_cancel))
                }
            },
        )
    }

    var pendingDelete by remember { mutableStateOf<String?>(null) }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.project_delete_title)) },
            text = { Text(stringResource(R.string.project_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(id)
                    },
                ) { Text(stringResource(R.string.project_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.project_delete_cancel))
                }
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            GridBackdrop()
            TextButton(
                onClick = { showAbout = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Edge-to-edge (MainActivity): without the inset the
                    // 3-button nav bar would cover most of this button.
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_about),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    // The console centers on a tall screen and scrolls on
                    // a short one — the shelf of saved goo grows, and it
                    // must never push the door off the bottom edge.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Wordmark(
                    name = stringResource(R.string.app_name_full),
                    model = stringResource(R.string.app_model),
                )
                Spacer(modifier = Modifier.height(10.dp))
                // The claim, set like a slogan stamped on the bezel.
                Text(
                    text = stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.titleMedium,
                    color = NeonCyan,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.home_subtagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(36.dp))
                ChromeButton(
                    label = stringResource(R.string.home_enter_goo),
                    color = NeonMagenta,
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    size = 128.dp,
                    breathe = true,
                )
                if (state.projects.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = stringResource(R.string.home_projects_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(
                            space = 16.dp,
                            alignment = Alignment.CenterHorizontally,
                        ),
                    ) {
                        for (project in state.projects) {
                            // Keyed by id: deleting a tile from the middle
                            // must not hand its decoded preview to the
                            // project that slides into its slot.
                            key(project.id) {
                                ProjectTile(
                                    project = project,
                                    onOpen = { onOpenProject(project.id) },
                                    onDelete = { pendingDelete = project.id },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    // The instrument readout: what the shelf holds, what it
                    // costs, and what is left. Nothing evicts on its own,
                    // so this is the number the decision is made on — and
                    // labelSmall is the console's monospace readout face,
                    // which is exactly what these are.
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_projects_usage,
                            state.projects.size,
                            state.projects.size,
                            formatBytes(state.usedBytes),
                            formatBytes(state.freeBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { confirmDeleteAll = true }) {
                        Text(
                            text = stringResource(R.string.project_delete_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonTangerine,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.home_samples_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SampleThumb(
                        assetPath = "samples/goo-guy.png",
                        labelRes = R.string.sample_goo_guy,
                        glow = NeonMagenta,
                        onOpenImage = onOpenImage,
                    )
                    SampleThumb(
                        assetPath = "samples/candy-blobs.png",
                        labelRes = R.string.sample_candy_blobs,
                        glow = NeonCyan,
                        onOpenImage = onOpenImage,
                    )
                }
                // Clearance for the About button, which floats over this
                // column at the bottom of the room.
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

/**
 * One saved project as a screen in a chrome bezel — a rectangle, not the
 * samples' port-hole, because this is a document you were working on
 * rather than a picture on the shelf.
 *
 * Tap resumes it; a long press (or the TalkBack action) offers to throw
 * it away. The preview is the goo as it was saved, not the source photo:
 * ProjectStore renders it through the same replay the export uses.
 */
@Composable
private fun ProjectTile(
    project: ProjectStore.Summary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    // Keyed on the save time as well as the path: a re-saved project keeps
    // the same preview file, and only the timestamp says it holds a
    // different picture now.
    val thumb by produceState<ImageBitmap?>(
        initialValue = null,
        project.thumbnail,
        project.updatedAtMillis,
    ) {
        val file = project.thumbnail
        value = if (file == null) null else withContext(Dispatchers.IO) {
            // A save that could not render a preview (no GL surface) still
            // produced a project: the tile shows the empty screen instead.
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val context = LocalContext.current
    val label = stringResource(R.string.project_open)
    val deleteLabel = stringResource(R.string.project_delete_confirm)
    // The Context-taking variant on purpose: it formats in the CONTEXT's
    // locale, so a per-app language override (locales_config) reaches this
    // label too — the static overloads read the system locale instead.
    val age = remember(project.updatedAtMillis, context) {
        DateUtils.getRelativeDateTimeString(
            context,
            project.updatedAtMillis,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.WEEK_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_ALL,
        ).toString()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 84.dp)
                .semantics {
                    contentDescription = "$label, $age"
                    // Long-press is invisible to TalkBack; the same verb
                    // has to exist as an action.
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
                .combinedClickable(role = Role.Button, onLongClick = onDelete, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(TILE_SHAPE)
                    .background(MeltPanel),
            ) {
                thumb?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // The bezel, drawn over the glass so the screen sits inside it.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rim = 3.dp.toPx()
                drawRoundRect(
                    brush = chromeSweep(),
                    topLeft = Offset(rim / 2f, rim / 2f),
                    size = Size(size.width - rim, size.height - rim),
                    cornerRadius = CornerRadius(TILE_CORNER.toPx()),
                    style = Stroke(width = rim),
                )
                // A violet pilot light along the bottom lip: this screen is
                // powered up, unlike the samples' cold port-holes.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, NeonViolet.copy(alpha = 0.35f)),
                    ),
                    topLeft = Offset(rim, size.height * 0.6f),
                    size = Size(size.width - rim * 2f, size.height * 0.4f - rim),
                    cornerRadius = CornerRadius(TILE_CORNER.toPx()),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = age,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TILE_CORNER = 12.dp
private val TILE_SHAPE = RoundedCornerShape(12.dp)

/**
 * Bytes as the platform would say them ("148 MB"), in the app's locale —
 * [Formatter] takes a Context precisely so the units and the decimal
 * separator follow it, which a hand-rolled divide-by-1024 would not.
 */
@Composable
private fun formatBytes(bytes: Long): String {
    val context = LocalContext.current
    return remember(bytes, context) { Formatter.formatFileSize(context, bytes) }
}

/**
 * One bundled sample as a tappable port-hole: the image behind a milled
 * chrome ring, lit by its own neon. Decoded off the main thread at
 * quarter resolution — the full image only loads if picked (the editor
 * re-reads the asset through the session-file pipeline).
 */
@Composable
private fun SampleThumb(
    assetPath: String,
    labelRes: Int,
    glow: Color,
    onOpenImage: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, assetPath) {
        value = withContext(Dispatchers.IO) {
            // A missing/unreadable asset degrades to the placeholder disc,
            // never a crash (the paths are hardcoded next to the assets,
            // but a rename shouldn't take the In room down).
            try {
                context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(
                        stream,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = 4 },
                    )
                }?.asImageBitmap()
            } catch (e: java.io.IOException) {
                null
            }
        }
    }
    val label = stringResource(labelRes)
    Box(
        modifier = Modifier
            .size(84.dp)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button) {
                onOpenImage("file:///android_asset/$assetPath".toUri())
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.35f), Color.Transparent),
                    center = c,
                    radius = outer,
                ),
                radius = outer,
                center = c,
            )
            val ringWidth = outer * 0.14f
            drawCircle(
                brush = chromeSweep(),
                radius = outer * 0.90f - ringWidth / 2f,
                center = c,
                style = Stroke(width = ringWidth),
            )
        }
        // The glass itself, inside the ring. An undecoded sample shows the
        // empty port-hole in panel gunmetal rather than a hole in the UI.
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MeltPanel),
        ) {
            thumb?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            }
        }
    }
}
