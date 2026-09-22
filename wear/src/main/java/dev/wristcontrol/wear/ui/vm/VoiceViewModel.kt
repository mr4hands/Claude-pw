package dev.wristcontrol.wear.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import dev.wristcontrol.wear.voice.VoiceInputController
import dev.wristcontrol.wear.voice.VoiceState
import kotlinx.coroutines.flow.StateFlow

/** Owns the [VoiceInputController] so a recomposition never restarts the mic. */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = VoiceInputController(application)

    val state: StateFlow<VoiceState> = controller.state
    val isAvailable: Boolean get() = controller.isAvailable

    fun startListening() = controller.start()

    fun stopListening() = controller.stop()

    fun reset() = controller.reset()

    override fun onCleared() {
        controller.stop()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { VoiceViewModel(requireApplication(this)) }
        }

        private fun requireApplication(extras: CreationExtras): Application =
            checkNotNull(extras[APPLICATION_KEY]) { "Missing application in CreationExtras" }
    }
}
