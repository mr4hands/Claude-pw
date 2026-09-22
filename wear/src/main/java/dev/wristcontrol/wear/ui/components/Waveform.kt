package dev.wristcontrol.wear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Scrolling microphone level meter for the push-to-talk screen.
 *
 * Purely feedback: it exists so the user knows the watch is hearing them
 * before the transcript catches up. The history advances on its own clock
 * rather than on RMS callbacks, so the bars keep scrolling smoothly through
 * a pause instead of freezing.
 */
@Composable
fun Waveform(
    amplitude: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    frameMillis: Long = 70,
) {
    var history by remember(barCount) { mutableStateOf(List(barCount) { MIN_LEVEL }) }
    // The effect below outlives any single amplitude value, so read it through
    // an updated state holder rather than capturing it.
    val latestAmplitude by rememberUpdatedState(amplitude)

    LaunchedEffect(barCount, frameMillis) {
        while (true) {
            delay(frameMillis)
            // Read the latest amplitude each frame; smoothing keeps a single
            // loud syllable from spiking the whole meter.
            val previous = history.last()
            val target = latestAmplitude.coerceIn(0f, 1f)
            val next = max(MIN_LEVEL, previous + (target - previous) * SMOOTHING)
            history = history.drop(1) + next
        }
    }

    Canvas(modifier = modifier) {
        val slot = size.width / barCount
        val barWidth = slot * 0.45f
        history.forEachIndexed { index, level ->
            val height = size.height * level
            val centerX = slot * index + slot / 2f
            drawLine(
                color = color,
                start = Offset(centerX, size.height / 2f - height / 2f),
                end = Offset(centerX, size.height / 2f + height / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val MIN_LEVEL = 0.06f
private const val SMOOTHING = 0.55f
