package dev.wristcontrol.wear.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.wristcontrol.wear.ui.components.Waveform
import dev.wristcontrol.wear.ui.theme.WristColors
import dev.wristcontrol.wear.voice.VoiceState

/**
 * Screen 4: push-to-talk.
 *
 * Opens straight into listening — the user got here by pressing a button with
 * the intention of speaking, so making them press another one would be rude.
 * The recognised text is shown for confirmation before it is sent, because
 * "delete the database" and "delete the data, please" sound similar enough.
 */
@Composable
fun VoiceScreen(
    state: VoiceState,
    recognitionAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) onStart()
    }

    LaunchedEffect(hasPermission, recognitionAvailable) {
        if (!recognitionAvailable) return@LaunchedEffect
        if (hasPermission) onStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!recognitionAvailable) {
            Text(
                text = "Speech recognition isn't available on this watch.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.error,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        when (state) {
            is VoiceState.Listening -> {
                Text(
                    text = state.partial.ifBlank { "Listening..." },
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                Waveform(
                    amplitude = state.amplitude,
                    color = WristColors.Clay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(top = 10.dp),
                )
                Chip(
                    label = { Text("Done") },
                    onClick = onStop,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            VoiceState.Processing -> Text(
                text = "Transcribing...",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )

            is VoiceState.Result -> {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                )
                Chip(
                    label = { Text("Send") },
                    onClick = { onSend(state.text) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.padding(top = 10.dp),
                )
                Chip(
                    label = { Text("Redo") },
                    onClick = onReset,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            is VoiceState.Error -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
                if (state.recoverable) {
                    Chip(
                        label = { Text("Try again") },
                        onClick = onStart,
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            VoiceState.Idle -> Text(
                text = "Ready",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }
}
