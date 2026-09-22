package dev.wristcontrol.wear.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import dev.wristcontrol.wear.ServiceLocator
import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.Loadable
import dev.wristcontrol.wear.data.model.LogEntry
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.data.auth.AuthState
import dev.wristcontrol.wear.service.SessionForegroundService
import dev.wristcontrol.wear.work.ConnectionWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Single view model behind every screen.
 *
 * The real state lives in [dev.wristcontrol.wear.data.SessionRepository] (which
 * outlives the UI, because the foreground service and the tile read it too);
 * this class only forwards it and turns taps into commands.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = ServiceLocator.from(application)
    private val repository = locator.sessionRepository

    val authState: StateFlow<AuthState> = locator.authRepository.state
    val sessions: StateFlow<Loadable<List<RemoteSession>>> = repository.sessions
    val wristState: StateFlow<WristState> = repository.state
    val logs: StateFlow<List<LogEntry>> = repository.logs
    val speakUpdates: StateFlow<Boolean> = locator.settings.speakUpdates
    val hapticsOnApproval: StateFlow<Boolean> = locator.settings.hapticsOnApproval
    val demoMode: StateFlow<Boolean> = locator.settings.demoMode

    /** One-shot user feedback ("Couldn't reach Claude"), consumed by a snackbar-ish chip. */
    private val _transient = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transient: SharedFlow<String> = _transient.asSharedFlow()

    fun signIn() {
        viewModelScope.launch {
            val result = locator.authRepository.signIn()
            if (result is AuthState.SignedIn) refreshSessions()
        }
    }

    fun signOut() {
        SessionForegroundService.stop(getApplication())
        ConnectionWorker.cancel(getApplication())
        repository.detach()
        locator.authRepository.signOut()
    }

    fun refreshSessions() = repository.refreshSessions()

    fun selectSession(session: RemoteSession) {
        repository.attach(session)
        // The service owns keeping this attachment alive once the screen sleeps.
        SessionForegroundService.attach(getApplication(), session.id)
        ConnectionWorker.schedule(getApplication())
    }

    /** Used when the app is opened from a tile or notification with a session in hand. */
    fun ensureAttached(sessionId: String?) {
        val target = sessionId ?: locator.settings.lastSessionId ?: return
        if (repository.state.value.session?.id == target) return
        repository.attachById(target)
        SessionForegroundService.attach(getApplication(), target)
    }

    fun approve() = resolve(approved = true)

    fun deny() = resolve(approved = false)

    private fun resolve(approved: Boolean) {
        viewModelScope.launch {
            if (!repository.resolvePendingApproval(approved)) {
                _transient.emit("Not connected - try again")
            }
        }
    }

    fun sendPrompt(text: String) {
        viewModelScope.launch {
            if (!repository.sendPrompt(text)) {
                _transient.emit("Couldn't send - not connected")
            }
        }
    }

    fun interrupt() {
        viewModelScope.launch { repository.interrupt() }
    }

    fun setSpeakUpdates(enabled: Boolean) = locator.settings.setSpeakUpdates(enabled)

    fun setHaptics(enabled: Boolean) = locator.settings.setHapticsOnApproval(enabled)

    fun setDemoMode(enabled: Boolean) {
        locator.settings.setDemoMode(enabled)
        repository.detach()
        repository.refreshSessions()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(requireApplication(this)) }
        }

        private fun requireApplication(extras: CreationExtras): Application =
            checkNotNull(extras[APPLICATION_KEY]) { "Missing application in CreationExtras" }
    }
}
