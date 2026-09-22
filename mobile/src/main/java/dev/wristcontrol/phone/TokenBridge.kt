package dev.wristcontrol.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Ships credentials to the watch over the Wearable Data Layer.
 *
 * The Data Layer only delivers to an app with the same package name and
 * signing certificate, and the link itself is encrypted — which is what makes
 * this an acceptable place to put a bearer token.
 */
object TokenBridge {

    const val PATH_AUTH_TOKEN = "/wristcontrol/auth-token"
    const val PATH_AUTH_REQUEST = "/wristcontrol/auth-request"

    suspend fun sendTokens(context: Context, payloadJson: String): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            val messageClient = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    PATH_AUTH_TOKEN,
                    payloadJson.toByteArray(Charsets.UTF_8),
                ).await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach the watch", e)
            false
        }
    }

    private const val TAG = "TokenBridge"
}
