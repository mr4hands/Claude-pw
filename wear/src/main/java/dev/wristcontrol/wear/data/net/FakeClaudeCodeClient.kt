package dev.wristcontrol.wear.data.net

import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.data.model.LogEntry
import dev.wristcontrol.wear.data.model.LogRole
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.data.model.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Scripted stand-in for the relay.
 *
 * Phase 2 of the design doc calls for building the whole UI against mock data;
 * this also makes the tile, the approval notification and the TTS path
 * demonstrable on an emulator with no Anthropic account. Debug builds only.
 */
class FakeClaudeCodeClient(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClaudeCodeClient {

    override suspend fun listSessions(): List<RemoteSession> {
        delay(400)
        return listOf(
            RemoteSession(
                id = "demo-frontend",
                title = "frontend-app",
                hostName = "Desktop",
                workingDirectory = "~/src/frontend-app",
                status = AgentStatus.EXECUTING,
                lastActivityEpochMillis = clock(),
            ),
            RemoteSession(
                id = "demo-api",
                title = "billing-api",
                hostName = "MacBook Pro",
                workingDirectory = "~/work/billing-api",
                status = AgentStatus.IDLE,
                lastActivityEpochMillis = clock() - 12 * 60 * 1000,
            ),
        )
    }

    override fun openStream(sessionId: String): SessionStream = FakeStream(sessionId)

    private inner class FakeStream(private val sessionId: String) : SessionStream {

        private val _messages = MutableSharedFlow<StreamMessage>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val messages: Flow<StreamMessage> = _messages.asSharedFlow()

        private var pendingApprovalId: String? = null

        private val job: Job = scope.launch {
            _messages.emit(StreamMessage.Connection(ConnectionState.Connecting))
            delay(600)
            _messages.emit(StreamMessage.Connection(ConnectionState.Connected))

            while (isActive) {
                step(AgentStatus.THINKING, "Reading src/api/client.ts", LogRole.SYSTEM, 2_500)
                step(AgentStatus.EXECUTING, "npm test --silent", LogRole.TOOL, 3_000)

                val request = ApprovalRequest(
                    id = "req-${clock()}",
                    sessionId = sessionId,
                    toolName = "bash",
                    command = "npm install @tanstack/react-query",
                    explanation = "Adds the query client the refactor needs.",
                    risk = RiskLevel.MEDIUM,
                    receivedAtEpochMillis = clock(),
                )
                pendingApprovalId = request.id
                _messages.emit(StreamMessage.Event(SessionEvent.StatusChanged(AgentStatus.AWAITING_APPROVAL)))
                _messages.emit(StreamMessage.Event(SessionEvent.ApprovalRequested(request)))

                // Wait for the user; if they ignore it, the host times out.
                var waited = 0L
                while (isActive && pendingApprovalId == request.id && waited < 90_000) {
                    delay(500)
                    waited += 500
                }
                if (pendingApprovalId == request.id) {
                    pendingApprovalId = null
                    _messages.emit(StreamMessage.Event(SessionEvent.ApprovalResolved(request.id, false)))
                }

                _messages.emit(
                    StreamMessage.Event(
                        SessionEvent.AssistantDelta(
                            messageId = "msg-${clock()}",
                            text = "Installed the dependency and re-ran the suite. 42 tests passing.",
                            final = true,
                        )
                    )
                )
                step(AgentStatus.IDLE, "Waiting for instructions", LogRole.SYSTEM, 6_000)
            }
        }

        private suspend fun step(status: AgentStatus, log: String, role: LogRole, holdMillis: Long) {
            _messages.emit(StreamMessage.Event(SessionEvent.StatusChanged(status)))
            _messages.emit(
                StreamMessage.Event(
                    SessionEvent.LogAppended(
                        LogEntry("log-${clock()}", role, log, clock())
                    )
                )
            )
            delay(holdMillis)
        }

        override suspend fun send(command: ClientCommand): Boolean {
            when (command) {
                is ClientCommand.ResolveApproval -> {
                    pendingApprovalId = null
                    _messages.emit(
                        StreamMessage.Event(
                            SessionEvent.ApprovalResolved(command.requestId, command.approved)
                        )
                    )
                    _messages.emit(
                        StreamMessage.Event(
                            SessionEvent.StatusChanged(
                                if (command.approved) AgentStatus.EXECUTING else AgentStatus.THINKING
                            )
                        )
                    )
                }

                is ClientCommand.SendPrompt -> {
                    _messages.emit(
                        StreamMessage.Event(
                            SessionEvent.LogAppended(
                                LogEntry("log-${clock()}", LogRole.USER, command.text, clock())
                            )
                        )
                    )
                    _messages.emit(StreamMessage.Event(SessionEvent.StatusChanged(AgentStatus.THINKING)))
                }

                ClientCommand.Interrupt ->
                    _messages.emit(StreamMessage.Event(SessionEvent.StatusChanged(AgentStatus.IDLE)))

                ClientCommand.RequestSnapshot -> Unit
            }
            return true
        }

        override fun close() {
            job.cancel()
        }
    }
}
