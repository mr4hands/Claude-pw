package dev.wristcontrol.wear.data.net

import dev.wristcontrol.wear.data.auth.AuthRepository
import dev.wristcontrol.wear.data.model.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * REST + WSS client for the Claude Code cloud relay.
 *
 * Endpoint paths are configurable because the design doc lists
 * `GET /v1/code/sessions` as indicative ("or equivalent"); keeping them in one
 * place means adapting to the real shape is a one-line change.
 */
class HttpClaudeCodeClient(
    private val httpClient: OkHttpClient,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
    private val apiBaseUrl: String,
    private val wsBaseUrl: String,
    private val sessionsPath: String = DEFAULT_SESSIONS_PATH,
    private val streamPathTemplate: String = DEFAULT_STREAM_PATH,
) : ClaudeCodeClient {

    override suspend fun listSessions(): List<RemoteSession> = withContext(Dispatchers.IO) {
        val body = getWithRetry(apiBaseUrl.trimEnd('/') + "/" + sessionsPath.trimStart('/'))
        EventCodec.decodeSessions(body)
    }

    private suspend fun getWithRetry(url: String): String {
        val token = authRepository.validAccessToken()
            ?: throw AuthorizationExpiredException("Not signed in")

        execute(url, token)?.let { return it }

        // 401 on a token we thought was valid: refresh once, then give up.
        val refreshed = authRepository.invalidateAndRefresh()
            ?: throw AuthorizationExpiredException("Sign-in expired")
        return execute(url, refreshed)
            ?: throw AuthorizationExpiredException("Sign-in expired")
    }

    /** Returns null specifically on 401/403 so the caller can retry once. */
    private fun execute(url: String, token: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("${request.url.encodedPath} returned ${response.code}")
            }
            return body
        }
    }

    override fun openStream(sessionId: String): SessionStream {
        val url = wsBaseUrl.trimEnd('/') + "/" +
            streamPathTemplate.trimStart('/').replace(SESSION_ID_PLACEHOLDER, sessionId)

        return WebSocketSessionStream(
            httpClient = httpClient,
            sessionId = sessionId,
            requestFactory = { token ->
                Request.Builder()
                    .url(url.toHttpUrlCompat())
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .build()
            },
            tokenProvider = { forceRefresh ->
                if (forceRefresh) authRepository.invalidateAndRefresh()
                else authRepository.validAccessToken()
            },
            parentScope = scope,
        )
    }

    /** OkHttp wants http(s) URLs even for websockets; it upgrades internally. */
    private fun String.toHttpUrlCompat() =
        replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()

    companion object {
        const val SESSION_ID_PLACEHOLDER = "{sessionId}"
        const val DEFAULT_SESSIONS_PATH = "v1/code/sessions"
        const val DEFAULT_STREAM_PATH = "v1/code/sessions/$SESSION_ID_PLACEHOLDER/stream"
        private const val USER_AGENT = "ClaudeWristControl/1.0 (Wear OS)"
    }
}
