package dev.wristcontrol.wear.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Credentials as persisted on the watch. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
) {
    fun isExpired(nowMillis: Long, skewMillis: Long = 60_000L): Boolean =
        expiresAtEpochMillis > 0L && nowMillis >= expiresAtEpochMillis - skewMillis
}

interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

/**
 * Keystore-backed storage, per section 6.3 of the design doc.
 *
 * Wear devices occasionally corrupt the encrypted prefs file across a factory
 * reset of the Keystore; when that happens the only recovery is to drop the
 * file and force a fresh login, which is what the catch below does.
 */
class EncryptedTokenStore(private val context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        Log.w(TAG, "Encrypted store unreadable, recreating", e)
        context.deleteSharedPreferences(FILE_NAME)
        create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): AuthTokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return AuthTokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            expiresAtEpochMillis = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    override fun save(tokens: AuthTokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochMillis)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "EncryptedTokenStore"
        const val FILE_NAME = "wristcontrol_auth"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}

/** Used by unit tests and by demo mode. */
class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private var tokens: AuthTokens? = initial
    override fun load(): AuthTokens? = tokens
    override fun save(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}
