package dev.wristcontrol.wear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wristcontrol.wear.ServiceLocator
import dev.wristcontrol.wear.tile.TileRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles Approve/Deny tapped straight from the notification shade. */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val approved = intent.action == ACTION_APPROVE

        val appContext = context.applicationContext
        val repository = ServiceLocator.from(appContext).sessionRepository

        // goAsync keeps the process alive for the socket write; a broadcast
        // receiver is otherwise killable the moment onReceive returns.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                repository.resolveApproval(requestId, approved)
                ApprovalNotifier.cancelApproval(appContext)
                TileRefresher.requestUpdate(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "dev.wristcontrol.wear.action.APPROVE"
        const val ACTION_DENY = "dev.wristcontrol.wear.action.DENY"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
