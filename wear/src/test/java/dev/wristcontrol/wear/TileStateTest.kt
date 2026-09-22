package dev.wristcontrol.wear

import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.tile.TileLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile's copy is the same string the ongoing notification and the
 * dashboard show, so it is worth pinning down on its own.
 */
class TileStateTest {

    @Test
    fun `connection trouble outranks the agent status`() {
        val state = WristState(
            status = AgentStatus.EXECUTING,
            connection = ConnectionState.Reconnecting(attempt = 2, nextRetryInMillis = 4_000),
        )
        // Claiming "Executing" while the socket is down would be a lie: we have
        // no idea what the agent is doing once we stop hearing from it.
        assertEquals("Reconnecting", TileLayouts.statusText(state))
    }

    @Test
    fun `failed connections read as offline`() {
        val state = WristState(
            status = AgentStatus.THINKING,
            connection = ConnectionState.Failed("Signed out"),
        )
        assertEquals("Offline", TileLayouts.statusText(state))
    }

    @Test
    fun `awaiting approval is phrased as a call to action`() {
        val state = WristState(
            status = AgentStatus.AWAITING_APPROVAL,
            connection = ConnectionState.Connected,
        )
        assertEquals("Waiting on you", TileLayouts.statusText(state))
    }

    @Test
    fun `needsAttention tracks the pending approval`() {
        val idle = WristState(status = AgentStatus.IDLE, connection = ConnectionState.Connected)
        assertFalse(idle.needsAttention)

        val blocked = idle.copy(
            pendingApproval = ApprovalRequest(
                id = "req-1",
                sessionId = "s1",
                toolName = "bash",
                command = "rm -rf build\nnpm run build",
                explanation = null,
                risk = RiskLevel.HIGH,
                receivedAtEpochMillis = 0L,
            )
        )
        assertTrue(blocked.needsAttention)
        // Only the first line reaches the tile and the TTS announcement.
        assertEquals("rm -rf build", blocked.pendingApproval!!.shortCommand)
    }
}
