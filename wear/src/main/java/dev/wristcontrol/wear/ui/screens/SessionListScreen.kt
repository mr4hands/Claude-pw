package dev.wristcontrol.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.Loadable
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.ui.components.colorFor

/** Screen 1 of the design doc: pick which `claude --rc` session to follow. */
@Composable
fun SessionListScreen(
    sessions: Loadable<List<RemoteSession>>,
    listState: ScalingLazyListState,
    onSelect: (RemoteSession) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (sessions) {
        Loadable.Loading -> CenteredMessage(modifier) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }

        is Loadable.Failed -> CenteredMessage(modifier) {
            Text(
                text = sessions.message,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Chip(
                label = { Text("Retry") },
                onClick = onRetry,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }

        is Loadable.Ready -> ScalingLazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Sessions") } }

            if (sessions.value.isEmpty()) {
                item {
                    Text(
                        text = "No active sessions.\nStart one with claude --rc",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            items(sessions.value, key = { it.id }) { session ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(session) },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text(
                            text = session.displayName,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = statusLabel(session.status),
                            color = colorFor(session.status),
                        )
                    },
                )
            }

            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRetry,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text("Refresh") },
                )
            }

            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenSettings,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text("Settings") },
                )
            }
        }
    }
}

private fun statusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.IDLE -> "Idle"
    AgentStatus.THINKING -> "Thinking"
    AgentStatus.EXECUTING -> "Executing"
    AgentStatus.AWAITING_APPROVAL -> "Needs you"
    AgentStatus.ERROR -> "Error"
    AgentStatus.UNKNOWN -> "Unknown"
}

@Composable
private fun CenteredMessage(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
