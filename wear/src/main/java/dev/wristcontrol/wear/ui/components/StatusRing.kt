package dev.wristcontrol.wear.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.ui.theme.WristColors

/**
 * The dashboard's animated status ring.
 *
 * Each agent state gets its own motion so the watch is readable from the corner
 * of an eye without reading any text: thinking breathes, executing sweeps,
 * waiting pulses hard, idle sits still.
 */
@Composable
fun StatusRing(
    status: AgentStatus,
    modifier: Modifier = Modifier,
    strokeWidthDp: Float = 8f,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "status-ring")

    val sweepFraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationFor(status)),
            repeatMode = if (status == AgentStatus.THINKING) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "sweep",
    )

    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val ringColor = colorFor(status)
    val alpha = if (status == AgentStatus.AWAITING_APPROVAL) pulse else 1f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
            val stroke = Stroke(width = strokeWidthDp * density, cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = WristColors.Track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            when (status) {
                AgentStatus.IDLE, AgentStatus.UNKNOWN -> drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 12f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )

                // A short arc chasing the rim: work is happening, duration unknown.
                AgentStatus.EXECUTING -> drawArc(
                    color = ringColor,
                    startAngle = -90f + sweepFraction * 360f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )

                AgentStatus.THINKING -> drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 40f + sweepFraction * 260f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )

                AgentStatus.AWAITING_APPROVAL, AgentStatus.ERROR -> drawArc(
                    color = ringColor.copy(alpha = alpha),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            content()
        }
    }
}

private fun durationFor(status: AgentStatus): Int = when (status) {
    AgentStatus.EXECUTING -> 1_600
    AgentStatus.THINKING -> 1_800
    AgentStatus.AWAITING_APPROVAL -> 900
    else -> 4_000
}

internal fun colorFor(status: AgentStatus): Color = when (status) {
    AgentStatus.IDLE -> WristColors.Dim
    AgentStatus.THINKING -> WristColors.Clay
    AgentStatus.EXECUTING -> WristColors.Approve
    AgentStatus.AWAITING_APPROVAL -> WristColors.Clay
    AgentStatus.ERROR -> WristColors.Deny
    AgentStatus.UNKNOWN -> WristColors.Track
}
