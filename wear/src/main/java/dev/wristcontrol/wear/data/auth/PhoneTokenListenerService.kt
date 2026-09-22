package dev.wristcontrol.wear.data.auth

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.wristcontrol.wear.ServiceLocator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fallback login path for watches where [RemoteAuthClient] is unavailable
 * (some LTE-only or China-market devices): the companion phone app runs the
 * OAuth flow itself and pushes the resulting tokens down the encrypted Data
 * Layer channel.
 *
 * The payload is only trusted because the Data Layer restricts delivery to
 * apps sharing our package name and signing certificate.
 */
class PhoneTokenListenerService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PATH_AUTH_TOKEN) {
            super.onMessageReceived(messageEvent)
            return
        }
        try {
            val root = json.parseToJsonElement(String(messageEvent.data, Charsets.UTF_8)).jsonObject
            val access = root["access_token"]?.jsonPrimitive?.content ?: return
            val expiresIn = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            ServiceLocator.from(this).authRepository.acceptExternalTokens(
                AuthTokens(
                    accessToken = access,
                    refreshToken = root["refresh_token"]?.jsonPrimitive?.content,
                    expiresAtEpochMillis =
                        if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Malformed token payload from phone", e)
        }
    }

    companion object {
        const val PATH_AUTH_TOKEN = "/wristcontrol/auth-token"
        private const val TAG = "PhoneTokenListener"
    }
}
