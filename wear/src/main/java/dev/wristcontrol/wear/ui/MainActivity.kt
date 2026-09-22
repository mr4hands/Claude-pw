package dev.wristcontrol.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.wristcontrol.wear.data.auth.AuthState
import dev.wristcontrol.wear.tile.TileLayouts
import dev.wristcontrol.wear.ui.screens.ApprovalScreen
import dev.wristcontrol.wear.ui.screens.AuthScreen
import dev.wristcontrol.wear.ui.screens.DashboardScreen
import dev.wristcontrol.wear.ui.screens.LogScreen
import dev.wristcontrol.wear.ui.screens.SessionListScreen
import dev.wristcontrol.wear.ui.screens.SettingsScreen
import dev.wristcontrol.wear.ui.screens.VoiceScreen
import dev.wristcontrol.wear.ui.theme.WristControlTheme
import dev.wristcontrol.wear.ui.vm.MainViewModel
import dev.wristcontrol.wear.ui.vm.VoiceViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WristControlTheme {
                WristApp(
                    initialDestination = intent?.getStringExtra(TileLayouts.EXTRA_DESTINATION),
                    initialSessionId = intent?.getStringExtra(TileLayouts.EXTRA_SESSION_ID),
                )
            }
        }
    }

    /**
     * The activity is singleTask, so a tile or notification tap while it is
     * already open arrives here rather than through onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

@Composable
private fun WristApp(initialDestination: String?, initialSessionId: String?) {
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val navController = rememberSwipeDismissableNavController()

    RequestNotificationPermissionOnce()

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val wristState by viewModel.wristState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    // Deep link from the tile or an approval notification.
    LaunchedEffect(initialDestination, initialSessionId, authState) {
        if (authState !is AuthState.SignedIn) return@LaunchedEffect
        viewModel.ensureAttached(initialSessionId)
        when (initialDestination) {
            TileLayouts.DESTINATION_VOICE -> navController.navigate(Destinations.VOICE)
            TileLayouts.DESTINATION_DASHBOARD -> navController.navigate(Destinations.DASHBOARD)
            // APPROVAL is handled by the watcher below, which also copes with the
            // request having been answered before the screen came up.
            else -> Unit
        }
    }

    LaunchedEffect(authState) {
        when (authState) {
            AuthState.SignedIn -> viewModel.refreshSessions()
            AuthState.SignedOut -> navController.navigate(Destinations.AUTH) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            else -> Unit
        }
    }

    // An approval outranks whatever the user was looking at.
    val pendingApprovalId = wristState.pendingApproval?.id
    LaunchedEffect(pendingApprovalId) {
        if (pendingApprovalId != null) {
            navController.navigate(Destinations.APPROVAL)
        }
    }

    val startDestination = when {
        authState is AuthState.SignedIn && wristState.isAttached -> Destinations.DASHBOARD
        authState is AuthState.SignedIn -> Destinations.SESSIONS
        else -> Destinations.AUTH
    }

    val sessionListState = rememberScalingLazyListState()
    val logListState = rememberScalingLazyListState()
    val settingsListState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = sessionListState) },
    ) {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable(Destinations.AUTH) {
                AuthScreen(state = authState, onSignIn = viewModel::signIn)
            }

            composable(Destinations.SESSIONS) {
                SessionListScreen(
                    sessions = sessions,
                    listState = sessionListState,
                    onSelect = { session ->
                        viewModel.selectSession(session)
                        navController.navigate(Destinations.DASHBOARD)
                    },
                    onRetry = viewModel::refreshSessions,
                    onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                )
            }

            composable(Destinations.DASHBOARD) {
                DashboardScreen(
                    state = wristState,
                    onTalk = { navController.navigate(Destinations.VOICE) },
                    onLogs = { navController.navigate(Destinations.LOGS) },
                    onInterrupt = viewModel::interrupt,
                    onSwitchSession = { navController.navigate(Destinations.SESSIONS) },
                )
            }

            composable(Destinations.APPROVAL) {
                val request = wristState.pendingApproval
                if (request == null) {
                    // Resolved elsewhere (phone, host terminal, or the tile)
                    // while this screen was coming up.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    ApprovalScreen(
                        request = request,
                        onApprove = {
                            viewModel.approve()
                            navController.popBackStack()
                        },
                        onDeny = {
                            viewModel.deny()
                            navController.popBackStack()
                        },
                    )
                }
            }

            composable(Destinations.VOICE) {
                VoiceRoute(
                    onSend = { text ->
                        viewModel.sendPrompt(text)
                        navController.popBackStack()
                    },
                )
            }

            composable(Destinations.LOGS) {
                LogScreen(entries = logs, listState = logListState)
            }

            composable(Destinations.SETTINGS) {
                SettingsRoute(viewModel = viewModel, listState = settingsListState, navController = navController)
            }
        }
    }
}

/**
 * Asks for POST_NOTIFICATIONS on first launch.
 *
 * Not optional decoration: from API 33 the permission is denied by default, and
 * without it the approval notification never posts — which on a watch means an
 * approval raised while the screen is off simply never reaches the user. The
 * app still runs if it is refused; approvals then only appear while the app or
 * the tile is on screen.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Either way the app keeps working; nothing to do here. */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun VoiceRoute(onSend: (String) -> Unit) {
    val voiceViewModel: VoiceViewModel = viewModel(factory = VoiceViewModel.Factory)
    val voiceState by voiceViewModel.state.collectAsState()
    val available = remember { voiceViewModel.isAvailable }

    VoiceScreen(
        state = voiceState,
        recognitionAvailable = available,
        onStart = voiceViewModel::startListening,
        onStop = voiceViewModel::stopListening,
        onSend = { text ->
            voiceViewModel.reset()
            onSend(text)
        },
        onReset = voiceViewModel::reset,
    )
}

@Composable
private fun SettingsRoute(
    viewModel: MainViewModel,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    navController: NavHostController,
) {
    val speak by viewModel.speakUpdates.collectAsStateWithLifecycle()
    val haptics by viewModel.hapticsOnApproval.collectAsStateWithLifecycle()
    val demo by viewModel.demoMode.collectAsStateWithLifecycle()

    SettingsScreen(
        speakUpdates = speak,
        hapticsOnApproval = haptics,
        demoMode = demo,
        listState = listState,
        onSpeakUpdatesChange = viewModel::setSpeakUpdates,
        onHapticsChange = viewModel::setHaptics,
        onDemoModeChange = viewModel::setDemoMode,
        onSignOut = {
            viewModel.signOut()
            navController.navigate(Destinations.AUTH) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        },
    )
}
