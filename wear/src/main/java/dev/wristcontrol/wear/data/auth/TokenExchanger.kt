package dev.wristcontrol.wear.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Exchanges an authorization code (or a refresh token) for access tokens.
 *
 * Kept deliberately hand-rolled over OkHttp: it is two form posts, and pulling
 * an OAuth library onto a watch costs more dex than it saves.
 */
class TokenExchanger(
    private val httpClient: OkHttpClient,
    private val tokenUrl: String,
    private val clientId: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun exchangeCode(code: String, verifier: String, redirectUri: String): AuthTokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .build()
        return post(form)
    }

    suspend fun refresh(refreshToken: String): AuthTokens {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val refreshed = post(form)
        // Some providers omit the refresh token on rotation; keep the old one
        // so the next silent refresh still has something to present.
        return if (refreshed.refreshToken == null) {
            refreshed.copy(refreshToken = refreshToken)
        } else {
            refreshed
        }
    }

    private suspend fun post(form: FormBody): AuthTokens = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(tokenUrl)
            .post(form)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Token endpoint returned ${response.code}: ${body.take(200)}")
            }
            parse(body)
        }
    }

    private fun parse(body: String): AuthTokens {
        val root = json.parseToJsonElement(body).jsonObject
        val access = root["access_token"]?.jsonPrimitive?.content
            ?: throw IOException("Token response had no access_token")
        val expiresIn = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return AuthTokens(
            accessToken = access,
            refreshToken = root["refresh_token"]?.jsonPrimitive?.content,
            expiresAtEpochMillis = if (expiresIn > 0) clock() + expiresIn * 1000 else 0L,
        )
    }
}
