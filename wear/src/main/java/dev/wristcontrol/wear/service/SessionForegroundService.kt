package dev.wristcontrol.wear.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.wristcontrol.wear.R
import dev.wristcontrol.wear.ServiceLocator
import dev.wristcontrol.wear.data.SessionRepository
import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.voice.TtsController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the relay attachment alive while the screen is off.
 *
 * Without this the socket dies with the last visible activity and an approval
 * raised thirty seconds later never reaches the wrist — which would make the
 * whole app pointless. The ongoing notification is the price Android charges
 * for that, and it doubles as a glanceable status line.
 */
class SessionForegroundService : LifecycleService() {

    private lateinit var repository: SessionRepository
    private var tts: TtsController? = null

    override fun onCreate() {
        super.onCreate()
        val locator = ServiceLocator.from(this)
        repository = locator.sessionRepository
        tts = TtsController(this)

        startForegroundCompat(ApprovalNotifier.buildOngoing(this, repository.state.value))

        observeState()
        observeApprovals(locator)
        observeSpokenMessages(locator)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_ATTACH -> intent.getStringExtra(EXTRA_SESSION_ID)?.let(repository::attachById)
            ACTION_STOP -> {
                repository.detach()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Restart with a null intent after a kill: the last session id is in
        // settings, so we can silently reattach.
        if (intent == null) {
            ServiceLocator.from(this).settings.lastSessionId?.let(repository::attachById)
        }
        return START_STICKY
    }

    private fun observeState() {
        lifecycleScope.launch {
            repository.state
                .map { it to ApprovalNotifier.buildOngoing(this@SessionForegroundService, it) }
                .distinctUntilChanged { (previous, _), (next, _) -> sameNotificationContent(previous, next) }
                .collectLatest { (_, notification) -> updateOngoing(notification) }
        }
    }

    private fun observeApprovals(locator: ServiceLocator) {
        lifecycleScope.launch {
            locator.sessionRepository.approvalAlerts.collectLatest { request ->
                ApprovalNotifier.showApproval(
                    context = this@SessionForegroundService,
                    request = request,
                    vibrate = locator.settings.hapticsOnApproval.value,
                )
                if (locator.settings.speakUpdates.value) {
                    tts?.speak(announcementFor(request), interrupt = true)
                }
            }
        }
        // Clear the notification when the approval is answered anywhere —
        // the watch, the phone, or the host terminal itself.
        lifecycleScope.launch {
            repository.state
                .map { it.pendingApproval?.id }
                .distinctUntilChanged()
                .collectLatest { pendingId ->
                    if (pendingId == null) {
                        ApprovalNotifier.cancelApproval(this@SessionForegroundService)
                    }
                }
        }
    }

    private fun observeSpokenMessages(locator: ServiceLocator) {
        lifecycleScope.launch {
            locator.sessionRepository.spokenMessages.collectLatest { message ->
                tts?.speak(message)
            }
        }
    }

    private fun announcementFor(request: ApprovalRequest): String =
        getString(R.string.approval_announcement, request.toolName, request.shortCommand)

    private fun sameNotificationContent(previous: WristState, next: WristState): Boolean =
        previous.session?.displayName == next.session?.displayName &&
            previous.status == next.status &&
            previous.connection::class == next.connection::class

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            ApprovalNotifier.NOTIFICATION_ONGOING,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateOngoing(notification: Notification) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(this).notify(ApprovalNotifier.NOTIFICATION_ONGOING, notification)
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessionService"

        const val ACTION_ATTACH = "dev.wristcontrol.wear.action.ATTACH"
        const val ACTION_STOP = "dev.wristcontrol.wear.action.STOP"
        const val EXTRA_SESSION_ID = "session_id"

        /**
         * Returns false when the platform refused the start. Since Android 12
         * a background process cannot launch a foreground service, which is
         * exactly the situation the connection watchdog runs in — so callers
         * have to cope rather than crash.
         */
        fun attach(context: Context, sessionId: String): Boolean {
            val intent = Intent(context, SessionForegroundService::class.java)
                .setAction(ACTION_ATTACH)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Foreground start refused", e)
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
