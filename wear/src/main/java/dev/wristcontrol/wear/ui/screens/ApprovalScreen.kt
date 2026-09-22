package dev.wristcontrol.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.wristcontrol.wear.R
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.ui.theme.WristColors

/**
 * Screen 3: the Approval Gate.
 *
 * Deliberately plain. The command is shown verbatim and in full (scrollable) —
 * summarising a shell command the user is about to authorise would be actively
 * dangerous. The two targets are large, far apart, and colour-coded.
 */
@Composable
fun ApprovalScreen(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = request.toolName.uppercase(),
            style = MaterialTheme.typography.caption2,
            color = riskColor(request.risk),
        )

        Text(
            text = request.command,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )

        request.explanation?.let { explanation ->
            Text(
                text = explanation,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        ) {
            Button(
                onClick = onDeny,
                colors = ButtonDefaults.buttonColors(backgroundColor = WristColors.Deny),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_deny),
                    contentDescription = "Deny",
                    modifier = Modifier.size(24.dp),
                )
            }

            Button(
                onClick = onApprove,
                colors = ButtonDefaults.buttonColors(backgroundColor = WristColors.Approve),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_approve),
                    contentDescription = "Approve",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private fun riskColor(risk: RiskLevel) = when (risk) {
    RiskLevel.HIGH -> WristColors.Deny
    RiskLevel.MEDIUM -> WristColors.Clay
    RiskLevel.LOW -> WristColors.Dim
}
