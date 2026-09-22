package dev.wristcontrol.wear.data.auth

import android.content.Context
import android.net.Uri
import androidx.wear.phone.interactions.authentication.CodeChallenge
import androidx.wear.phone.interactions.authentication.CodeVerifier
import androidx.wear.phone.interactions.authentication.OAuthRequest
import androidx.wear.phone.interactions.authentication.OAuthResponse
import androidx.wear.phone.interactions.authentication.RemoteAuthClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Result of the "open a browser on the phone" half of the login. */
sealed interface RemoteAuthResult {
    data class Success(val code: String, val verifier: String, val redirectUri: String) : RemoteAuthResult
    data class Error(val code: Int, val message: String) : RemoteAuthResult
}

/**
 * Drives [RemoteAuthClient]: the watch hands an authorization URL to the paired
 * phone, the phone opens it in a real browser, and the resulting redirect comes
 * back over the Wearable Data Layer. Nothing but the authorization code crosses
 * the link; the code-for-token exchange happens on the watch so the phone never
 * sees an access token.
 */
class RemoteAuthManager(
    private val context: Context,
    private val authorizeUrl: String,
    private val clientId: String,
    private val scopes: List<String>,
) {

    suspend fun authorize(): RemoteAuthResult {
        val verifier = CodeVerifier()
        val state = PkcePair.generate().verifier.take(32)

        val authUri = Uri.parse(authorizeUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", scopes.joinToString(" "))
            .appendQueryParameter("state", state)
            .build()

        val request = OAuthRequest.Builder(context)
            .setAuthProviderUrl(authUri)
            .setClientId(clientId)
            .setCodeChallenge(CodeChallenge(verifier))
            .build()

        val response = sendRequest(request) ?: return RemoteAuthResult.Error(
            code = RemoteAuthClient.ERROR_PHONE_UNAVAILABLE,
            message = "Could not reach the paired phone.",
        )

        if (response.errorCode != RemoteAuthClient.NO_ERROR) {
            return RemoteAuthResult.Error(response.errorCode, describe(response.errorCode))
        }

        val responseUrl = response.responseUrl
            ?: return RemoteAuthResult.Error(RemoteAuthClient.ERROR_UNSUPPORTED, "Empty redirect.")

        val returnedState = responseUrl.getQueryParameter("state")
        if (returnedState != null && returnedState != state) {
            return RemoteAuthResult.Error(RemoteAuthClient.ERROR_UNSUPPORTED, "State mismatch.")
        }

        responseUrl.getQueryParameter("error")?.let { error ->
            return RemoteAuthResult.Error(RemoteAuthClient.ERROR_UNSUPPORTED, error)
        }

        val code = responseUrl.getQueryParameter("code")
            ?: return RemoteAuthResult.Error(RemoteAuthClient.ERROR_UNSUPPORTED, "No authorization code.")

        return RemoteAuthResult.Success(
            code = code,
            verifier = verifier.value,
            redirectUri = request.redirectUrl,
        )
    }

    private suspend fun sendRequest(request: OAuthRequest): OAuthResponse? =
        suspendCancellableCoroutine { continuation ->
            val client = RemoteAuthClient.create(context)
            val executor = Executor { it.run() }
            val callback = object : RemoteAuthClient.Callback() {
                override fun onAuthorizationResponse(request: OAuthRequest, response: OAuthResponse) {
                    client.close()
                    if (continuation.isActive) continuation.resume(response)
                }

                override fun onAuthorizationError(request: OAuthRequest, errorCode: Int) {
                    client.close()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation { client.close() }
            client.sendAuthorizationRequest(request, executor, callback)
        }

    private fun describe(errorCode: Int): String = when (errorCode) {
        RemoteAuthClient.ERROR_PHONE_UNAVAILABLE ->
            "Phone unavailable. Keep it nearby and unlocked."
        RemoteAuthClient.ERROR_UNSUPPORTED ->
            "This watch cannot open a login browser on the phone."
        else -> "Sign-in failed (code $errorCode)."
    }
}
