package dev.wristcontrol.wear.tile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.wristcontrol.wear.ServiceLocator
import dev.wristcontrol.wear.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible trampoline for tile button taps.
 *
 * A tile clickable can only fire a `LoadAction` (which just re-renders) or a
 * `LaunchAction`. Approving a command has to reach the relay, so the buttons
 * launch this activity, which does the work on the application scope and
 * finishes immediately — from the user's point of view the tile simply
 * responds, with no screen transition.
 */
class TileActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val action = TileAction.fromId(intent?.getStringExtra(TileLayouts.EXTRA_TILE_ACTION))
        val locator = ServiceLocator.from(this)

        when (action) {
            TileAction.APPROVE, TileAction.DENY -> {
                val approved = action == TileAction.APPROVE
                val repository = locator.sessionRepository
                // Application scope, not lifecycleScope: this activity is gone
                // before the socket write completes.
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    val sent = repository.resolvePendingApproval(approved)
                    withContext(Dispatchers.Main) {
                        toast(
                            when {
                                !sent -> getString(dev.wristcontrol.wear.R.string.tile_action_failed)
                                approved -> getString(dev.wristcontrol.wear.R.string.approved)
                                else -> getString(dev.wristcontrol.wear.R.string.denied)
                            }
                        )
                    }
                    TileRefresher.requestUpdate(applicationContext)
                }
            }

            TileAction.VOICE -> startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(TileLayouts.EXTRA_DESTINATION, TileLayouts.DESTINATION_VOICE)
            )

            null -> Unit
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
