package dev.wristcontrol.wear

import android.app.Application
import dev.wristcontrol.wear.service.ApprovalNotifier
import dev.wristcontrol.wear.tile.TileRefresher

class ClaudeWristApp : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator(this)

        ApprovalNotifier.createChannels(this)

        // The tile renders straight off SessionRepository.state, so every change
        // to that state has to poke the tile — otherwise the watch face shows a
        // stale "Idle" while Claude is blocked on an approval.
        TileRefresher.observe(this, locator.sessionRepository, locator.appScope)
    }
}
