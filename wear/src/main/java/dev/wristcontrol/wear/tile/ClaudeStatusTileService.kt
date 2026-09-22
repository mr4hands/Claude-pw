package dev.wristcontrol.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.wristcontrol.wear.R
import dev.wristcontrol.wear.ServiceLocator
import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.ConnectionState

/**
 * The Claude Code status tile.
 *
 * Renders synchronously from [dev.wristcontrol.wear.data.SessionRepository]'s
 * current snapshot — a tile request is not the place to open a socket, and the
 * repository is already kept live by the foreground service. When nothing is
 * attached the tile still draws (a "choose session" chip) rather than showing
 * the system's blank-tile placeholder.
 */
class ClaudeStatusTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = future("tile-request") {
        val locator = ServiceLocator.from(this)
        val state = locator.sessionRepository.state.value

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(
                LayoutElementBuilders.Box.Builder()
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(TileLayouts.backgroundColor()))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        TileLayouts.build(this, requestParams.deviceConfiguration, state)
                    )
                    .build()
            )
            .build()

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(freshnessFor(state))
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
                    )
                    .build()
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = future("tile-resources") {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(TileLayouts.ID_ICON_APPROVE, drawable(R.drawable.ic_approve))
            .addIdToImageMapping(TileLayouts.ID_ICON_DENY, drawable(R.drawable.ic_deny))
            .addIdToImageMapping(TileLayouts.ID_ICON_MIC, drawable(R.drawable.ic_mic))
            .build()
    }

    /**
     * The user just swiped onto the tile. Re-list sessions and reattach to the
     * last one so a tile that has been sitting cold for hours shows live state
     * instead of whatever was true when the app was last open.
     */
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        val locator = ServiceLocator.from(this)
        val repository = locator.sessionRepository
        if (!repository.state.value.isAttached) {
            locator.settings.lastSessionId?.let(repository::attachById)
        }
        repository.refreshSessions()
    }

    private fun drawable(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(resId)
                    .build()
            )
            .build()

    /**
     * How long the system may keep this render before asking again. We push
     * updates ourselves via TileRefresher, so this is only a safety net — short
     * while something is moving, long when idle to protect the battery.
     */
    private fun freshnessFor(state: WristState): Long = when {
        state.pendingApproval != null -> 60_000L
        state.connection is ConnectionState.Connected -> 5 * 60_000L
        else -> 15 * 60_000L
    }

    private fun <T> future(tag: String, block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            try {
                completer.set(block())
            } catch (e: Exception) {
                completer.setException(e)
            }
            tag
        }

    private companion object {
        /** Bump whenever the drawables referenced above change. */
        const val RESOURCES_VERSION = "1"
    }
}
