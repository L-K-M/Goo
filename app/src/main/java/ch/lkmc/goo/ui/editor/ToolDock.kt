package ch.lkmc.goo.ui.editor

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.ui.components.CandyIconButton
import ch.lkmc.goo.ui.theme.CandyCyan
import ch.lkmc.goo.ui.theme.CandyGrape
import ch.lkmc.goo.ui.theme.CandyLemon
import ch.lkmc.goo.ui.theme.CandyLime
import ch.lkmc.goo.ui.theme.CandyOrange
import ch.lkmc.goo.ui.theme.CandyPink
import ch.lkmc.goo.ui.theme.GooOnDarkDim
import ch.lkmc.goo.ui.theme.GooTableRaised
import ch.lkmc.goo.ui.theme.GooTableShadow

/**
 * The editor's bottom tray: three mode tabs (Brush / Levers / GOOvies)
 * hosting the palette, the global rig, or the strip. On the brush tab the
 * palette is a family grid and everything below it is contextual — only
 * what the active tool can actually use. Collapses into [ToolPuck] so the
 * canvas gets the whole screen back.
 */
@Composable
fun ToolDock(
    panel: EditorPanel,
    leversHot: Boolean,
    onTabSelect: (EditorPanel) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (EditorPanel) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = GooTableRaised,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DockTab(
                    label = stringResource(R.string.dock_tab_brush),
                    color = CandyPink,
                    selected = panel == EditorPanel.BRUSH,
                    dot = false,
                    onClick = { onTabSelect(EditorPanel.BRUSH) },
                )
                DockTab(
                    label = stringResource(R.string.editor_levers),
                    color = CandyCyan,
                    selected = panel == EditorPanel.LEVERS,
                    // Levers are document state, not a mode: the dot says
                    // "something is off-center" even on another tab.
                    dot = leversHot,
                    onClick = { onTabSelect(EditorPanel.LEVERS) },
                )
                DockTab(
                    label = stringResource(R.string.editor_goovies),
                    color = CandyLemon,
                    selected = panel == EditorPanel.GOOVIE,
                    dot = false,
                    onClick = { onTabSelect(EditorPanel.GOOVIE) },
                )
                Spacer(modifier = Modifier.weight(1f))
                val haptics = LocalHapticFeedback.current
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.dock_collapse),
                    tint = GooOnDarkDim,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCollapse()
                            },
                        )
                        .padding(8.dp),
                )
            }
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    val springIn = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    )
                    // `using null` kills the default SizeTransform: the tray
                    // floats over the canvas, but animating its height would
                    // still jitter every panel's content mid-spring.
                    (slideInVertically(springIn) { it / 3 } + fadeIn()) togetherWith
                        (slideOutVertically { it / 3 } + fadeOut()) using null
                },
                label = "dockPanelSwap",
            ) { which ->
                content(which)
            }
        }
    }
}

/** The collapsed dock: one juicy bead wearing the active mode's candy. */
@Composable
fun ToolPuck(
    panel: EditorPanel,
    tool: BrushTool,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, color) = when (panel) {
        EditorPanel.BRUSH -> tool.icon() to tool.candyColor()
        EditorPanel.LEVERS -> Icons.Filled.Tune to CandyCyan
        EditorPanel.GOOVIE -> Icons.Filled.Movie to CandyLemon
    }
    CandyIconButton(
        icon = icon,
        contentDescription = stringResource(R.string.dock_expand),
        color = color,
        selected = true,
        size = 56.dp,
        onClick = onExpand,
        modifier = modifier,
    )
}

@Composable
private fun DockTab(
    label: String,
    color: Color,
    selected: Boolean,
    dot: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) color else Color.Transparent,
        label = "dockTabBackground",
    )
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .background(background)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) GooTableShadow else GooOnDarkDim,
        )
    }
}

/**
 * The brush tab: the palette as a family grid (names live on the strip —
 * nine lookalike labels under the beads are noise), then the contextual
 * strip with everything the ACTIVE tool can use: its name, size/strength,
 * Mirror, Punch, and Fusion's photo actions. Nothing scrolls.
 */
@Composable
fun BrushDockContent(
    tool: BrushTool,
    mirrored: Boolean,
    radius: Float,
    strength: Float,
    showFusionActions: Boolean,
    keyframeCount: Int,
    onToolChange: (BrushTool) -> Unit,
    onMirrorToggle: () -> Unit,
    onRadiusChange: (Float) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onAdjustingChange: (Boolean) -> Unit,
    onPunch: () -> Unit,
    onFusionPick: () -> Unit,
    onFusionRemove: () -> Unit,
) {
    // A slider dragged off-screen (tab swap, tray collapse) never delivers
    // its onValueChangeFinished — clear the adjusting flag on the way out.
    DisposableEffect(Unit) {
        onDispose { onAdjustingChange(false) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            toolDockRows().forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { entry ->
                        CandyIconButton(
                            icon = entry.icon(),
                            contentDescription = stringResource(entry.labelRes()),
                            color = entry.candyColor(),
                            selected = tool == entry,
                            selectable = true,
                            size = 38.dp,
                            onClick = { onToolChange(entry) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(tool.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                color = tool.candyColor(),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (showFusionActions) {
                CandyIconButton(
                    icon = Icons.Filled.Collections,
                    contentDescription = stringResource(R.string.fusion_change_photo),
                    color = CandyGrape,
                    selected = false,
                    size = 38.dp,
                    onClick = onFusionPick,
                )
                CandyIconButton(
                    icon = Icons.Filled.HideImage,
                    contentDescription = stringResource(R.string.fusion_remove_photo),
                    color = CandyOrange,
                    selected = false,
                    size = 38.dp,
                    onClick = onFusionRemove,
                )
            }
            CandyIconButton(
                icon = Icons.Filled.Flip,
                contentDescription = stringResource(R.string.tool_mirror),
                color = CandyGrape,
                selected = mirrored,
                selectable = true,
                size = 38.dp,
                onClick = onMirrorToggle,
            )
            // The KPT loop is goo → punch → goo → punch; punching never
            // needed the strip open, so the bead stays on the brush tab.
            // The badge is the punch confirmation (was the label count).
            Box {
                CandyIconButton(
                    icon = Icons.Filled.AddAPhoto,
                    contentDescription = stringResource(R.string.goovie_capture),
                    color = CandyLemon,
                    selected = false,
                    enabled = keyframeCount < EditorViewModel.MAX_KEYFRAMES,
                    size = 38.dp,
                    onClick = onPunch,
                )
                if (keyframeCount > 0) {
                    Text(
                        text = keyframeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = GooTableShadow,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(CandyLemon, CircleShape)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                            .clearAndSetSemantics { },
                    )
                }
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
        Spacer(modifier = Modifier.size(4.dp))
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

@StringRes
internal fun BrushTool.labelRes(): Int = when (this) {
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

internal fun BrushTool.icon(): ImageVector = when (this) {
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
internal fun BrushTool.candyColor(): Color = when (this) {
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
