package dev.wristcontrol.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.wristcontrol.wear.data.auth.AuthState

/**
 * First run. The actual login happens in a browser on the paired phone; all the
 * watch can do is start it and explain where to look.
 */
@Composable
fun AuthScreen(
    state: AuthState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Claude Code",
            style = MaterialTheme.typography.title3,
            color = MaterialTheme.colors.primary,
        )

        when (state) {
            AuthState.SigningIn -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 12.dp).size(28.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    text = "Finish signing in on your phone",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            is AuthState.Error -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
                Chip(
                    label = { Text("Try again") },
                    onClick = onSignIn,
                    colors = ChipDefaults.primaryChipColors(),
                )
            }

            else -> {
                Text(
                    text = "Sign in to watch your remote sessions",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                Chip(
                    label = { Text("Sign in") },
                    onClick = onSignIn,
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
        }
    }
}
