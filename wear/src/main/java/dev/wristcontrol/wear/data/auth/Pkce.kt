package dev.wristcontrol.wear.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RFC 7636 PKCE parameters.
 *
 * The watch never holds a client secret, so the verifier is the only thing
 * binding the authorization code to this device.
 */
data class PkcePair(val verifier: String, val challenge: String) {

    companion object {
        private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun generate(random: SecureRandom = SecureRandom()): PkcePair {
            val bytes = ByteArray(64)
            random.nextBytes(bytes)
            val verifier = Base64.encodeToString(bytes, FLAGS)
            return PkcePair(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, FLAGS)
        }
    }
}
