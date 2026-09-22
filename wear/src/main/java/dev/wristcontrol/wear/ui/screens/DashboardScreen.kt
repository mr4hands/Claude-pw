package dev.wristcontrol.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.ui.res.painterResource
import dev.wristcontrol.wear.R
import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.tile.TileLayouts
import dev.wristcontrol.wear.ui.components.StatusRing
import dev.wristcontrol.wear.ui.components.colorFor

/**
 * Screen 2: the main dashboard.
 *
 * Status ring in the middle, quick actions underneath. Everything here is
 * reachable one-handed; the log view is a swipe away rather than a button so
 * the primary actions keep the biggest targets.
 */
@Composable
fun DashboardScreen(
    state: WristState,
    onTalk: () -> Unit,
    onLogs: () -> Unit,
    onInterrupt: () -> Unit,
    onSwitchSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StatusRing(
            status = state.status,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        ) {
            Text(
                text = TileLayouts.statusText(state),
                style = MaterialTheme.typography.title3,
                color = colorFor(state.status),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            state.session?.let { session ->
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (state.connection is ConnectionState.Reconnecting) {
                Text(
                    text = "Reconnecting...",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Button(
                    onClick = onTalk,
                    colors = ButtonDefaults.primaryButtonColors(),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "Speak to Claude",
                        modifier = Modifier.size(20.dp),
                    )
                }

                Button(
                    onClick = onLogs,
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_logs),
                        contentDescription = "Recent activity",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                CompactChip(
                    onClick = onSwitchSession,
                    label = { Text("Switch") },
                )
                if (state.status.isBusy()) {
                    CompactChip(
                        onClick = onInterrupt,
                        label = { Text("Stop") },
                    )
                }
            }
        }
    }
}

/** "Stop" only makes sense while there is something to stop. */
private fun AgentStatus.isBusy(): Boolean =
    this == AgentStatus.THINKING || this == AgentStatus.EXECUTING
