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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import ch.lkmc.goo.ui.theme.ChromeHi
import ch.lkmc.goo.ui.theme.ChromeLo
import ch.lkmc.goo.ui.theme.MeltVoid
import ch.lkmc.goo.ui.theme.chromeSweep
import kotlinx.coroutines.launch

/**
 * A bipolar console lever: a neon thumb in a milled ring, riding a slot
 * machined into the panel, with a magnetic center detent. Center =
 * identity; releasing inside the detent springs the thumb to exact 0 (so
 * "off" is honest), and crossing the center ticks the haptics like a
 * physical switch.
 *
 * The drag calls [onChange] continuously — the levers drive live warp
 * uniforms, so per-frame updates are the point (see PLAN.md §4.1).
 */
@Composable
fun ChromeLever(
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

            // Groove: a slot milled into the panel — dark inside, with a
            // bright hairline along the top lip where the light catches
            // the machined edge (the whole console is lit from above).
            drawRoundRect(
                color = MeltVoid,
                topLeft = Offset(trackLeft, cy - trackH / 2f),
                size = Size(trackRight - trackLeft, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
            drawRoundRect(
                color = ChromeLo.copy(alpha = 0.9f),
                topLeft = Offset(trackLeft, cy - trackH / 2f),
                size = Size(trackRight - trackLeft, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
                style = Stroke(width = 1.dp.toPx()),
            )
            // Center detent notch: a scored index mark on the slot.
            val cx = (trackLeft + trackRight) / 2f
            drawRoundRect(
                color = ChromeHi.copy(alpha = 0.55f),
                topLeft = Offset(cx - 1.5.dp.toPx(), cy - trackH),
                size = Size(3.dp.toPx(), trackH * 2f),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            // Fill from center to the ball: the lever's neon tube lighting
            // up the length of travel.
            val ballX = cx + (ball.value * (trackRight - trackLeft) / 2f)
            if (ball.value != 0f) {
                drawRoundRect(
                    color = color.copy(alpha = 0.75f),
                    topLeft = Offset(minOf(cx, ballX), cy - trackH / 2f),
                    size = Size(kotlin.math.abs(ballX - cx), trackH),
                    cornerRadius = CornerRadius(trackH / 2f),
                )
            }
            // The thumb: a neon core in a milled ring, swelling in hand.
            val r = thumbRadiusPx * if (dragging) 1.15f else 1f
            val ringWidth = r * 0.22f
            drawCircle(
                brush = chromeSweep(),
                radius = r - ringWidth / 2f,
                center = Offset(ballX, cy),
                style = Stroke(width = ringWidth),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.lighten(0.40f), color, color.darken(0.45f)),
                    center = Offset(ballX - r * 0.3f, cy - r * 0.35f),
                    radius = r * 1.6f,
                ),
                radius = r - ringWidth,
                center = Offset(ballX, cy),
            )
            // Gloss sized against the CORE, not the outer radius: at the
            // outer radius the haze would spill onto the milled ring.
            val core = r - ringWidth
            val gloss = Offset(ballX - core * 0.30f, cy - core * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.60f), Color.Transparent),
                    center = gloss,
                    radius = core * 0.5f,
                ),
                radius = core * 0.5f,
                center = gloss,
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
