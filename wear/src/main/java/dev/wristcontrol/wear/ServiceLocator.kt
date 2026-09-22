package dev.wristcontrol.wear

import android.content.Context
import dev.wristcontrol.wear.data.AppSettings
import dev.wristcontrol.wear.data.SessionRepository
import dev.wristcontrol.wear.data.auth.AuthRepository
import dev.wristcontrol.wear.data.auth.EncryptedTokenStore
import dev.wristcontrol.wear.data.auth.RemoteAuthManager
import dev.wristcontrol.wear.data.auth.TokenExchanger
import dev.wristcontrol.wear.data.auth.TokenStore
import dev.wristcontrol.wear.data.net.ClaudeCodeClient
import dev.wristcontrol.wear.data.net.FakeClaudeCodeClient
import dev.wristcontrol.wear.data.net.HttpClaudeCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency graph.
 *
 * Hilt would pull ~1 MB of dex onto a device where that matters and buys little
 * for a graph this size; everything here is a process singleton created lazily
 * on first touch.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings by lazy { AppSettings(appContext) }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // websocket reads block indefinitely
            .writeTimeout(15, TimeUnit.SECONDS)
            // Server pings are not guaranteed; ours keep the socket alive through
            // carrier NAT timeouts while the screen is off.
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val tokenStore: TokenStore by lazy { EncryptedTokenStore(appContext) }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            tokenStore = tokenStore,
            remoteAuth = RemoteAuthManager(
                context = appContext,
                authorizeUrl = BuildConfig.OAUTH_AUTHORIZE_URL,
                clientId = BuildConfig.OAUTH_CLIENT_ID,
                scopes = OAUTH_SCOPES,
            ),
            tokenExchanger = TokenExchanger(
                httpClient = httpClient,
                tokenUrl = BuildConfig.OAUTH_TOKEN_URL,
                clientId = BuildConfig.OAUTH_CLIENT_ID,
            ),
        ).also { it.restore() }
    }

    private val httpApiClient: ClaudeCodeClient by lazy {
        HttpClaudeCodeClient(
            httpClient = httpClient,
            authRepository = authRepository,
            scope = appScope,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            wsBaseUrl = BuildConfig.WS_BASE_URL,
        )
    }

    private val fakeApiClient: ClaudeCodeClient by lazy { FakeClaudeCodeClient(appScope) }

    /**
     * Resolved per call rather than injected, so flipping demo mode in settings
     * takes effect on the next attach without recreating the graph.
     */
    fun currentClient(): ClaudeCodeClient =
        if (BuildConfig.ALLOW_DEMO_MODE && settings.demoMode.value) fakeApiClient else httpApiClient

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            clientProvider = ::currentClient,
            settings = settings,
            scope = appScope,
        )
    }

    companion object {
        private val OAUTH_SCOPES = listOf("code:sessions.read", "code:sessions.control")

        fun from(context: Context): ServiceLocator =
            (context.applicationContext as ClaudeWristApp).locator
    }
}
