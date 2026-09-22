package dev.wristcontrol.wear.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.wristcontrol.wear.ServiceLocator
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.service.SessionForegroundService
import java.util.concurrent.TimeUnit

/**
 * Safety net for the foreground service.
 *
 * Wear aggressively reaps processes in deep doze; this periodic check notices
 * that we are meant to be attached to a session but are not, and restarts the
 * service. It deliberately does no network work of its own — reattaching is
 * the service's job.
 */
class ConnectionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.from(applicationContext)
        val sessionId = locator.settings.lastSessionId ?: return Result.success()

        val state = locator.sessionRepository.state.value
        val healthy = state.session?.id == sessionId &&
            state.connection is ConnectionState.Connected

        if (!healthy && !SessionForegroundService.attach(applicationContext, sessionId)) {
            // Background foreground-service starts are blocked from Android 12;
            // retry on WorkManager's schedule rather than dropping the session.
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "wristcontrol-connection-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConnectionWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
