package dev.wristcontrol.phone

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wakes the phone's login flow when the watch asks for one.
 *
 * Only used on watches where RemoteAuthClient is unavailable; on everything
 * else the Wear OS companion app drives the browser and this never fires.
 */
class WatchAuthRequestService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != TokenBridge.PATH_AUTH_REQUEST) {
            super.onMessageReceived(messageEvent)
            return
        }
        startActivity(
            Intent(this, OAuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
