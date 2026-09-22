package dev.wristcontrol.wear.tile

import android.content.Context
import androidx.wear.tiles.TileService
import dev.wristcontrol.wear.data.SessionRepository
import dev.wristcontrol.wear.data.WristState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Pushes tile refreshes when — and only when — something the tile actually
 * draws has changed.
 *
 * Tiles are rendered by the system, not by us, so the only way to keep one
 * honest is to call `requestUpdate` on every meaningful state transition. The
 * signature below deliberately ignores fields the tile does not show (log
 * entries, reconnect attempt counts), because each spurious update costs a
 * cross-process round trip and, on some launchers, a visible flicker.
 */
object TileRefresher {

    @OptIn(FlowPreview::class)
    fun observe(context: Context, repository: SessionRepository, scope: CoroutineScope) {
        val appContext = context.applicationContext
        repository.state
            .map(::signatureOf)
            .distinctUntilChanged()
            // Status often moves thinking -> executing -> thinking in bursts.
            .debounce(250)
            .onEach { requestUpdate(appContext) }
            .launchIn(scope)
    }

    fun requestUpdate(context: Context) {
        TileService.getUpdater(context.applicationContext)
            .requestUpdate(ClaudeStatusTileService::class.java)
    }

    private fun signatureOf(state: WristState): String = listOf(
        state.session?.id.orEmpty(),
        state.session?.displayName.orEmpty(),
        state.status.name,
        state.connection::class.simpleName.orEmpty(),
        state.pendingApproval?.id.orEmpty(),
        state.pendingApproval?.shortCommand.orEmpty(),
    ).joinToString("|")
}
