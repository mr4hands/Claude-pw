package dev.wristcontrol.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.wristcontrol.wear.data.model.LogEntry
import dev.wristcontrol.wear.data.model.LogRole
import dev.wristcontrol.wear.ui.theme.WristColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Swipe-up transcript. Read-only, newest at the bottom, auto-scrolled. */
@Composable
fun LogScreen(
    entries: List<LogEntry>,
    listState: ScalingLazyListState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size)
    }

    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ListHeader { Text("Activity") } }

        if (entries.isEmpty()) {
            item {
                Text(
                    text = "Nothing yet.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(
                    text = "${timeFormat.format(Date(entry.timestampEpochMillis))}  ${labelFor(entry.role)}",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.caption1,
                    color = when (entry.role) {
                        LogRole.USER -> WristColors.Clay
                        LogRole.ASSISTANT -> MaterialTheme.colors.onBackground
                        LogRole.TOOL -> WristColors.Approve
                        LogRole.SYSTEM -> MaterialTheme.colors.onSurfaceVariant
                    },
                    maxLines = 6,
                )
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun labelFor(role: LogRole): String = when (role) {
    LogRole.USER -> "you"
    LogRole.ASSISTANT -> "claude"
    LogRole.TOOL -> "tool"
    LogRole.SYSTEM -> "system"
}
