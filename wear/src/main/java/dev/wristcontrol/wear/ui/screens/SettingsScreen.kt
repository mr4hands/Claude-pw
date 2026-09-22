package dev.wristcontrol.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import dev.wristcontrol.wear.BuildConfig

@Composable
fun SettingsScreen(
    speakUpdates: Boolean,
    hapticsOnApproval: Boolean,
    demoMode: Boolean,
    listState: ScalingLazyListState,
    onSpeakUpdatesChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    onDemoModeChange: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ListHeader { Text("Settings") } }

        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = speakUpdates,
                onCheckedChange = onSpeakUpdatesChange,
                label = { Text("Speak updates") },
                toggleControl = {
                    Icon(
                        imageVector = ToggleChipDefaults.switchIcon(speakUpdates),
                        contentDescription = if (speakUpdates) "On" else "Off",
                    )
                },
            )
        }

        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = hapticsOnApproval,
                onCheckedChange = onHapticsChange,
                label = { Text("Buzz on approval") },
                toggleControl = {
                    Icon(
                        imageVector = ToggleChipDefaults.switchIcon(hapticsOnApproval),
                        contentDescription = if (hapticsOnApproval) "On" else "Off",
                    )
                },
            )
        }

        if (BuildConfig.ALLOW_DEMO_MODE) {
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = demoMode,
                    onCheckedChange = onDemoModeChange,
                    label = { Text("Demo mode") },
                    secondaryLabel = { Text("Scripted session, no network") },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(demoMode),
                            contentDescription = if (demoMode) "On" else "Off",
                        )
                    },
                )
            }
        }

        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSignOut,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Sign out") },
            )
        }

        item {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = androidx.wear.compose.material.MaterialTheme.typography.caption3,
                color = androidx.wear.compose.material.MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }
}
