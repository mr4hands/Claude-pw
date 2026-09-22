package dev.wristcontrol.wear

import app.cash.turbine.test
import dev.wristcontrol.wear.data.AppSettings
import dev.wristcontrol.wear.data.SessionRepository
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.data.model.Loadable
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.data.model.SessionEvent
import dev.wristcontrol.wear.data.net.ClaudeCodeClient
import dev.wristcontrol.wear.data.net.SessionStream
import dev.wristcontrol.wear.data.net.StreamMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private lateinit var settings: AppSettings
    private lateinit var client: RecordingClient

    private val session = RemoteSession(
        id = "s1",
        title = "frontend-app",
        hostName = "Desktop",
        workingDirectory = "~/src",
        status = AgentStatus.IDLE,
        lastActivityEpochMillis = 0L,
    )

    @Before
    fun setUp() {
        settings = AppSettings(RuntimeEnvironment.getApplication())
        client = RecordingClient()
    }

    @Test
    fun `attaching remembers the session for tile deep links`() = runTest {
        val repository = newRepository(this)
        repository.attach(session)
        advanceUntilIdle()

        assertEquals("s1", settings.lastSessionId)
        assertEquals(session, repository.state.value.session)
    }

    @Test
    fun `approval raises exactly one alert even when the snapshot repeats it`() = runTest {
        val repository = newRepository(this)
        repository.attach(session)
        advanceUntilIdle()

        val request = approvalRequest("req-1")

        repository.approvalAlerts.test {
            client.emit(StreamMessage.Event(SessionEvent.ApprovalRequested(request)))
            advanceUntilIdle()
            assertEquals("req-1", awaitItem().id)

            // A reconnect replays the same pending request; the user should not
            // be buzzed a second time for a prompt already on screen.
            client.emit(StreamMessage.Event(SessionEvent.ApprovalRequested(request)))
            advanceUntilIdle()
            expectNoEvents()
        }

        assertEquals(AgentStatus.AWAITING_APPROVAL, repository.state.value.status)
    }

    @Test
    fun `resolving clears the gate optimistically and sends the command`() = runTest {
        val repository = newRepository(this)
        repository.attach(session)
        advanceUntilIdle()

        client.emit(StreamMessage.Event(SessionEvent.ApprovalRequested(approvalRequest("req-2"))))
        advanceUntilIdle()

        assertTrue(repository.resolvePendingApproval(approved = true))
        advanceUntilIdle()

        assertNull(repository.state.value.pendingApproval)
        assertEquals(AgentStatus.EXECUTING, repository.state.value.status)
        val sent = client.stream.sent.last() as ClientCommand.ResolveApproval
        assertEquals("req-2", sent.requestId)
        assertTrue(sent.approved)
    }

    @Test
    fun `a status change from elsewhere drops a stale approval`() = runTest {
        val repository = newRepository(this)
        repository.attach(session)
        advanceUntilIdle()

        client.emit(StreamMessage.Event(SessionEvent.ApprovalRequested(approvalRequest("req-3"))))
        advanceUntilIdle()

        // Someone answered on the host terminal.
        client.emit(StreamMessage.Event(SessionEvent.StatusChanged(AgentStatus.EXECUTING)))
        advanceUntilIdle()

        assertNull(repository.state.value.pendingApproval)
    }

    @Test
    fun `assistant deltas are buffered until the final chunk`() = runTest {
        val repository = newRepository(this)
        repository.attach(session)
        advanceUntilIdle()

        client.emit(StreamMessage.Event(SessionEvent.AssistantDelta("m1", "Installed ", final = false)))
        client.emit(StreamMessage.Event(SessionEvent.AssistantDelta("m1", "the dep.", final = false)))
        advanceUntilIdle()
        assertNull(repository.state.value.lastAssistantMessage)

        client.emit(StreamMessage.Event(SessionEvent.AssistantDelta("m1", "", final = true)))
        advanceUntilIdle()
        assertEquals("Installed the dep.", repository.state.value.lastAssistantMessage)
    }

    @Test
    fun `sending a prompt without a stream fails rather than pretending`() = runTest {
        val repository = newRepository(this)
        // Never attached, so there is no socket to write to.
        assertFalse(repository.sendPrompt("fix the schema"))
    }

    @Test
    fun `detach tears the attachment down`() = runTest {
        val repository = newRepository(this)
        repository.attach(session)
        advanceUntilIdle()

        client.emit(StreamMessage.Connection(ConnectionState.Connected))
        advanceUntilIdle()

        repository.detach()
        assertNull(repository.state.value.session)
        assertTrue(client.stream.closed)
    }

    @Test
    fun `listing failures surface as a failed state`() = runTest {
        client.failListing = true
        val repository = newRepository(this)
        repository.refreshSessions()
        advanceUntilIdle()

        assertTrue(repository.sessions.value is Loadable.Failed)
    }

    /**
     * The repository launches long-lived collectors, so it gets the test's
     * background scope — those never need to complete for the test to finish.
     */
    private fun newRepository(scope: TestScope) = SessionRepository(
        clientProvider = { client },
        settings = settings,
        scope = scope.backgroundScope,
        clock = { 0L },
    )

    private fun approvalRequest(id: String) = ApprovalRequest(
        id = id,
        sessionId = "s1",
        toolName = "bash",
        command = "npm install",
        explanation = null,
        risk = RiskLevel.MEDIUM,
        receivedAtEpochMillis = 0L,
    )

    private class RecordingClient : ClaudeCodeClient {
        val stream = RecordingStream()
        var failListing = false

        override suspend fun listSessions(): List<RemoteSession> {
            if (failListing) throw IllegalStateException("relay unreachable")
            return emptyList()
        }

        override fun openStream(sessionId: String): SessionStream = stream

        suspend fun emit(message: StreamMessage) = stream.channel.emit(message)
    }

    private class RecordingStream : SessionStream {
        val channel = MutableSharedFlow<StreamMessage>(extraBufferCapacity = 32)
        val sent = mutableListOf<ClientCommand>()
        var closed = false

        override val messages: Flow<StreamMessage> = channel

        override suspend fun send(command: ClientCommand): Boolean {
            sent += command
            return true
        }

        override fun close() {
            closed = true
        }
    }
}
