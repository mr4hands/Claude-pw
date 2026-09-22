package dev.wristcontrol.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Plain (unencrypted) user preferences — nothing here is a secret. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("wristcontrol_settings", Context.MODE_PRIVATE)

    private val _speakUpdates = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, true))
    val speakUpdates: StateFlow<Boolean> = _speakUpdates.asStateFlow()

    private val _demoMode = MutableStateFlow(prefs.getBoolean(KEY_DEMO, false))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _hapticsOnApproval = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, true))
    val hapticsOnApproval: StateFlow<Boolean> = _hapticsOnApproval.asStateFlow()

    /** Survives process death so a tile tap can reattach without a picker. */
    var lastSessionId: String?
        get() = prefs.getString(KEY_LAST_SESSION, null)
        set(value) = prefs.edit().putString(KEY_LAST_SESSION, value).apply()

    fun setSpeakUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK, enabled).apply()
        _speakUpdates.value = enabled
    }

    fun setDemoMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, enabled).apply()
        _demoMode.value = enabled
    }

    fun setHapticsOnApproval(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
        _hapticsOnApproval.value = enabled
    }

    private companion object {
        const val KEY_SPEAK = "speak_updates"
        const val KEY_DEMO = "demo_mode"
        const val KEY_HAPTICS = "haptics_on_approval"
        const val KEY_LAST_SESSION = "last_session_id"
    }
}
