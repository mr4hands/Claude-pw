package dev.wristcontrol.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Runs the OAuth authorization-code + PKCE flow in a Custom Tab and forwards
 * the resulting tokens to the watch.
 *
 * The verifier never leaves the phone and the redirect lands back in this
 * activity through the app link declared in the manifest.
 */
class OAuthActivity : AppCompatActivity() {

    private val httpClient by lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data == null) startAuthorization() else handleRedirect(intent.data!!)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(::handleRedirect)
    }

    private fun startAuthorization() {
        val pkce = PkcePair.generate()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_VERIFIER, pkce.verifier)
            .apply()

        val uri = Uri.parse(BuildConfig.OAUTH_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", BuildConfig.OAUTH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("scope", "code:sessions.read code:sessions.control")
            .build()

        CustomTabsIntent.Builder().build().launchUrl(this, uri)
    }

    private fun handleRedirect(data: Uri) {
        val code = data.getQueryParameter("code")
        if (code == null) {
            finishWith(getString(R.string.sign_in_cancelled))
            return
        }
        val verifier = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_VERIFIER, null)
        if (verifier == null) {
            finishWith(getString(R.string.sign_in_failed))
            return
        }

        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) { exchange(code, verifier) }
            if (payload == null) {
                finishWith(getString(R.string.sign_in_failed))
                return@launch
            }
            val delivered = TokenBridge.sendTokens(this@OAuthActivity, payload)
            finishWith(
                if (delivered) getString(R.string.sent_to_watch) else getString(R.string.watch_not_reachable)
            )
        }
    }

    private fun exchange(code: String, verifier: String): String? = try {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", BuildConfig.OAUTH_CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(BuildConfig.OAUTH_TOKEN_URL)
            .post(form)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun finishWith(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_VERIFIER).apply()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val PREFS = "wristcontrol_phone"
        const val KEY_VERIFIER = "pkce_verifier"
        const val REDIRECT_URI = "wristcontrol://oauth/callback"
    }
}
