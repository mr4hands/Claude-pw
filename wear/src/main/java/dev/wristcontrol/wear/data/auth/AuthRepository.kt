package dev.wristcontrol.wear.data.auth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data object SignedIn : AuthState
    data class Error(val message: String) : AuthState
}

/**
 * Single source of truth for "are we allowed to talk to the relay".
 *
 * Everything that needs a bearer token goes through [validAccessToken], which
 * refreshes at most once concurrently — on a watch, two parallel refreshes are
 * a good way to invalidate your own rotating refresh token.
 */
class AuthRepository(
    private val tokenStore: TokenStore,
    private val remoteAuth: RemoteAuthManager,
    private val tokenExchanger: TokenExchanger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun restore() {
        _state.value = if (tokenStore.load() != null) AuthState.SignedIn else AuthState.SignedOut
    }

    /** Called by the Data Layer listener when the phone pushes a token directly. */
    fun acceptExternalTokens(tokens: AuthTokens) {
        tokenStore.save(tokens)
        _state.value = AuthState.SignedIn
    }

    suspend fun signIn(): AuthState {
        _state.value = AuthState.SigningIn
        val next = when (val result = remoteAuth.authorize()) {
            is RemoteAuthResult.Error -> AuthState.Error(result.message)
            is RemoteAuthResult.Success -> try {
                val tokens = tokenExchanger.exchangeCode(
                    code = result.code,
                    verifier = result.verifier,
                    redirectUri = result.redirectUri,
                )
                tokenStore.save(tokens)
                AuthState.SignedIn
            } catch (e: Exception) {
                Log.w(TAG, "Token exchange failed", e)
                AuthState.Error(e.message ?: "Could not complete sign-in.")
            }
        }
        _state.value = next
        return next
    }

    fun signOut() {
        tokenStore.clear()
        _state.value = AuthState.SignedOut
    }

    /**
     * Returns a usable access token, refreshing first if it is about to expire.
     * Returns null (and moves to [AuthState.SignedOut]) when the user has to
     * log in again.
     */
    suspend fun validAccessToken(): String? = refreshMutex.withLock {
        val current = tokenStore.load() ?: run {
            _state.value = AuthState.SignedOut
            return@withLock null
        }
        if (!current.isExpired(clock())) return@withLock current.accessToken

        val refreshToken = current.refreshToken ?: run {
            signOutLocked()
            return@withLock null
        }
        try {
            val refreshed = tokenExchanger.refresh(refreshToken)
            tokenStore.save(refreshed)
            _state.value = AuthState.SignedIn
            refreshed.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed", e)
            signOutLocked()
            null
        }
    }

    /** Invoked when the relay answers 401 with a token we believed was good. */
    suspend fun invalidateAndRefresh(): String? = refreshMutex.withLock {
        val refreshToken = tokenStore.load()?.refreshToken ?: run {
            signOutLocked()
            return@withLock null
        }
        try {
            val refreshed = tokenExchanger.refresh(refreshToken)
            tokenStore.save(refreshed)
            refreshed.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Forced refresh failed", e)
            signOutLocked()
            null
        }
    }

    private fun signOutLocked() {
        tokenStore.clear()
        _state.value = AuthState.SignedOut
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
