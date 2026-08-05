package ch.lkmc.goo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.ui.theme.GooTableShadow
import kotlinx.coroutines.launch

/**
 * A bipolar candy lever: glossy ball thumb on a grooved track with a
 * magnetic center detent. Center = identity; releasing inside the detent
 * springs the ball to exact 0 (so "off" is honest), and crossing the
 * center ticks the haptics like a physical switch.
 *
 * The drag calls [onChange] continuously — the levers drive live warp
 * uniforms, so per-frame updates are the point (see PLAN.md §4.1).
 */
@Composable
fun CandyLever(
    value: Float,
    color: Color,
    contentDescription: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val thumbRadiusPx = with(LocalDensity.current) { THUMB_RADIUS.toPx() }
    // pointerInput(Unit)'s handler runs once, from the first touch, forever:
    // it would freeze the first composition's onChange (and its captured
    // globals snapshot) — re-dragging this lever would then resurrect stale
    // values for every OTHER lever. rememberUpdatedState keeps it live.
    val currentOnChange by rememberUpdatedState(onChange)
    // The ball's on-screen position: snaps under the finger during a drag,
    // springs on settle. External writes (Zero all, Reset) arrive as a
    // [value] change with no drag in flight — the effect springs to them.
    val ball = remember { Animatable(value) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!dragging && ball.targetValue != value) {
            ball.animateTo(value, springSpec())
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 48dp: the Material minimum interactive height, matching the
            // beads' minimumInteractiveComponentSize treatment.
            .height(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, -1f..1f)
                // TalkBack adjustability — the stock Slider had it, so must
                // we. Detent-snapped, so a11y "off" is exact identity too.
                setProgress { target ->
                    currentOnChange(LeverDetent.settle(target.coerceIn(-1f, 1f)))
                    true
                }
            }
            .pointerInput(Unit) {
                // The gesture's own authoritative value — reading back
                // ball.value would race the async snapTo below.
                var gestureValue = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        gestureValue = ball.value
                    },
                    onDragCancel = {
                        dragging = false
                        settle(gestureValue, currentOnChange, scope, ball)
                    },
                    onDragEnd = {
                        dragging = false
                        if (LeverDetent.settle(gestureValue) == 0f && gestureValue != 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        settle(gestureValue, currentOnChange, scope, ball)
                    },
                ) { change, dragAmount ->
                    change.consume()
                    val track = size.width - thumbRadiusPx * 2f
                    val previous = gestureValue
                    gestureValue = (previous + LeverDetent.dragToDelta(dragAmount, track))
                        .coerceIn(-1f, 1f)
                    if (LeverDetent.crossedCenter(previous, gestureValue)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    currentOnChange(gestureValue)
                    val target = gestureValue
                    scope.launch { ball.snapTo(target) }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            val cy = size.height / 2f
            val trackLeft = thumbRadiusPx
            val trackRight = size.width - thumbRadiusPx
            val trackH = 8.dp.toPx()

            // Groove: a recessed slot in the table.
            drawRoundRect(
                color = GooTableShadow,
                topLeft = Offset(trackLeft, cy - trackH / 2f),
                size = Size(trackRight - trackLeft, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
            // Center detent notch.
            val cx = (trackLeft + trackRight) / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(cx - 1.5.dp.toPx(), cy - trackH),
                size = Size(3.dp.toPx(), trackH * 2f),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            // Fill from center to the ball, in the lever's candy color.
            val ballX = cx + (ball.value * (trackRight - trackLeft) / 2f)
            if (ball.value != 0f) {
                drawRoundRect(
                    color = color.copy(alpha = 0.55f),
                    topLeft = Offset(minOf(cx, ballX), cy - trackH / 2f),
                    size = Size(kotlin.math.abs(ballX - cx), trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
            }
            // The ball: a mini candy sphere, swelling slightly in hand.
            val r = thumbRadiusPx * if (dragging) 1.15f else 1f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.lighten(0.35f), color, color.darken(0.35f)),
                    center = Offset(ballX - r * 0.3f, cy - r * 0.35f),
                    radius = r * 1.6f,
                ),
                radius = r,
                center = Offset(ballX, cy),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(ballX - r * 0.30f, cy - r * 0.35f),
                    radius = r * 0.5f,
                ),
                radius = r * 0.5f,
                center = Offset(ballX - r * 0.30f, cy - r * 0.35f),
            )
        }
    }
}

private fun settle(
    gestureValue: Float,
    onChange: (Float) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    ball: Animatable<Float, *>,
) {
    val settled = LeverDetent.settle(gestureValue)
    onChange(settled)
    scope.launch { ball.animateTo(settled, springSpec()) }
}

private fun springSpec() = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val THUMB_RADIUS = 12.dp
