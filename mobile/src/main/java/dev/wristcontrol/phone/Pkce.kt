package dev.wristcontrol.phone

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** RFC 7636 PKCE parameters for the phone-side fallback login. */
data class PkcePair(val verifier: String, val challenge: String) {

    companion object {
        private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun generate(): PkcePair {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            val verifier = Base64.encodeToString(bytes, FLAGS)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return PkcePair(verifier, Base64.encodeToString(digest, FLAGS))
        }
    }
}
