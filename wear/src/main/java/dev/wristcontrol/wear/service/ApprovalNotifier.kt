package dev.wristcontrol.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.wristcontrol.wear.R
import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.tile.TileLayouts
import dev.wristcontrol.wear.ui.MainActivity

/**
 * Notifications and haptics for the approval flow.
 *
 * On a watch, the notification *is* the interaction for anything that arrives
 * while the screen is off: the full-screen intent wakes straight to the
 * Approval Gate, and the two actions let the user answer without ever opening
 * the app.
 */
object ApprovalNotifier {

    const val CHANNEL_ONGOING = "wristcontrol_session"
    const val CHANNEL_APPROVAL = "wristcontrol_approval"

    const val NOTIFICATION_ONGOING = 1001
    const val NOTIFICATION_APPROVAL = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.channel_session),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_session_description)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVAL,
                context.getString(R.string.channel_approval),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_approval_description)
                enableVibration(true)
                vibrationPattern = APPROVAL_VIBRATION
            }
        )
    }

    /** The quiet, permanent notification that keeps the socket alive. */
    fun buildOngoing(context: Context, state: WristState): Notification {
        val title = state.session?.displayName ?: context.getString(R.string.app_name)
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title)
            .setContentText(TileLayouts.statusText(state))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, TileLayouts.DESTINATION_DASHBOARD))
            .build()
    }

    fun showApproval(context: Context, request: ApprovalRequest, vibrate: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(context.getString(R.string.approval_title, request.toolName))
            .setContentText(request.shortCommand)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.command))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setOngoing(request.risk == RiskLevel.HIGH)
            .setContentIntent(openApp(context, TileLayouts.DESTINATION_APPROVAL))
            // Wakes the screen straight onto the Approval Gate.
            .setFullScreenIntent(openApp(context, TileLayouts.DESTINATION_APPROVAL), true)
            .addAction(
                R.drawable.ic_deny,
                context.getString(R.string.deny),
                resolveIntent(context, request.id, approved = false),
            )
            .addAction(
                R.drawable.ic_approve,
                context.getString(R.string.approve),
                resolveIntent(context, request.id, approved = true),
            )
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_APPROVAL, notification)
        }

        if (vibrate) vibrate(context, request.risk)
    }

    fun cancelApproval(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_APPROVAL)
    }

    /** "Vibrates vigorously", per the design doc — scaled by how risky the call is. */
    private fun vibrate(context: Context, risk: RiskLevel) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return

        val pattern = when (risk) {
            RiskLevel.HIGH -> longArrayOf(0, 260, 120, 260, 120, 420)
            RiskLevel.MEDIUM -> APPROVAL_VIBRATION
            RiskLevel.LOW -> longArrayOf(0, 140, 90, 140)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun openApp(context: Context, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(TileLayouts.EXTRA_DESTINATION, destination)
        return PendingIntent.getActivity(
            context,
            destination.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun resolveIntent(context: Context, requestId: String, approved: Boolean): PendingIntent {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = if (approved) ApprovalActionReceiver.ACTION_APPROVE else ApprovalActionReceiver.ACTION_DENY
            putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, requestId)
        }
        return PendingIntent.getBroadcast(
            context,
            (requestId + approved).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val APPROVAL_VIBRATION = longArrayOf(0, 200, 100, 200, 100, 300)
}
