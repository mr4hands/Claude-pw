package dev.wristcontrol.wear.data

import android.util.Log
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.data.model.Loadable
import dev.wristcontrol.wear.data.model.LogEntry
import dev.wristcontrol.wear.data.model.LogRole
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.data.model.SessionEvent
import dev.wristcontrol.wear.data.net.ClaudeCodeClient
import dev.wristcontrol.wear.data.net.SessionStream
import dev.wristcontrol.wear.data.net.StreamMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Snapshot of everything a glanceable surface needs.
 *
 * The tile, the ongoing notification and the dashboard all render from this one
 * value, which is the only way to keep three surfaces from disagreeing about
 * whether Claude is waiting on you.
 */
data class WristState(
    val session: RemoteSession? = null,
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val pendingApproval: ApprovalRequest? = null,
    val lastAssistantMessage: String? = null,
) {
    val isAttached: Boolean get() = session != null
    val needsAttention: Boolean get() = pendingApproval != null
}

/**
 * Owns the live attachment to one remote session and fans its state out to the
 * UI, the foreground service and the tile.
 *
 * Process-wide singleton (see ServiceLocator): a tile update and an open
 * activity must never open two sockets to the same session.
 */
class SessionRepository(
    private val clientProvider: () -> ClaudeCodeClient,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _sessions = MutableStateFlow<Loadable<List<RemoteSession>>>(Loadable.Loading)
    val sessions: StateFlow<Loadable<List<RemoteSession>>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<RemoteSession?>(null)
    private val _status = MutableStateFlow(AgentStatus.UNKNOWN)
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    private val _lastAssistantMessage = MutableStateFlow<String?>(null)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    /** Fires once per newly raised approval: drives haptics, TTS and the notification. */
    private val _approvalAlerts = MutableSharedFlow<ApprovalRequest>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val approvalAlerts: SharedFlow<ApprovalRequest> = _approvalAlerts.asSharedFlow()

    /** Completed assistant messages worth reading out loud. */
    private val _spokenMessages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val spokenMessages: SharedFlow<String> = _spokenMessages.asSharedFlow()

    val state: StateFlow<WristState> = combine(
        _activeSession,
        _status,
        _connection,
        _pendingApproval,
        _lastAssistantMessage,
    ) { session, status, connection, approval, message ->
        WristState(session, status, connection, approval, message)
    }.stateInEagerly(scope, WristState())

    private var stream: SessionStream? = null
    private var streamJob: Job? = null
    private val deltaBuffers = mutableMapOf<String, StringBuilder>()

    fun refreshSessions() {
        scope.launch {
            _sessions.value = Loadable.Loading
            _sessions.value = try {
                Loadable.Ready(clientProvider().listSessions())
            } catch (e: Exception) {
                Log.w(TAG, "Session list failed", e)
                Loadable.Failed(e.message ?: "Could not reach Claude Code.")
            }
        }
    }

    /** Idempotent: re-attaching to the session we are already on is a no-op. */
    fun attach(session: RemoteSession) {
        if (_activeSession.value?.id == session.id && stream != null) return
        detach()

        _activeSession.value = session
        _status.value = session.status
        settings.lastSessionId = session.id

        val opened = clientProvider().openStream(session.id)
        stream = opened
        streamJob = opened.messages
            .onEach(::handle)
            .launchIn(scope)
    }

    /** Used by the tile and the notification, which only know an id. */
    fun attachById(sessionId: String) {
        val known = (_sessions.value as? Loadable.Ready)?.value?.firstOrNull { it.id == sessionId }
        if (known != null) {
            attach(known)
            return
        }
        scope.launch {
            val fetched = runCatching { clientProvider().listSessions() }.getOrNull().orEmpty()
            _sessions.value = Loadable.Ready(fetched)
            fetched.firstOrNull { it.id == sessionId }?.let(::attach)
        }
    }

    fun detach() {
        streamJob?.cancel()
        streamJob = null
        stream?.close()
        stream = null
        deltaBuffers.clear()
        _connection.value = ConnectionState.Disconnected
        _pendingApproval.value = null
        _activeSession.value = null
        _status.value = AgentStatus.UNKNOWN
        _logs.value = emptyList()
    }

    private suspend fun handle(message: StreamMessage) {
        when (message) {
            is StreamMessage.Connection -> _connection.value = message.state
            is StreamMessage.Event -> handleEvent(message.event)
        }
    }

    private suspend fun handleEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.StatusChanged -> {
                _status.value = event.status
                // A status that moves off AWAITING_APPROVAL means somebody else
                // answered; drop our stale prompt rather than showing a dead one.
                if (event.status != AgentStatus.AWAITING_APPROVAL) {
                    _pendingApproval.value = null
                }
            }

            is SessionEvent.ApprovalRequested -> {
                val alreadyShowing = _pendingApproval.value?.id == event.request.id
                _pendingApproval.value = event.request
                _status.value = AgentStatus.AWAITING_APPROVAL
                // Re-sent on every reconnect snapshot; only alert on the first sight.
                if (!alreadyShowing) _approvalAlerts.emit(event.request)
            }

            is SessionEvent.ApprovalResolved -> {
                if (_pendingApproval.value?.id == event.requestId) {
                    _pendingApproval.value = null
                }
                appendLog(
                    LogEntry(
                        id = "resolved-${event.requestId}",
                        role = LogRole.SYSTEM,
                        text = if (event.approved) "Approved" else "Denied",
                        timestampEpochMillis = clock(),
                    )
                )
            }

            is SessionEvent.LogAppended -> appendLog(event.entry)

            is SessionEvent.AssistantDelta -> {
                val buffer = deltaBuffers.getOrPut(event.messageId) { StringBuilder() }
                buffer.append(event.text)
                if (event.final) {
                    val complete = buffer.toString().trim()
                    deltaBuffers.remove(event.messageId)
                    if (complete.isNotEmpty()) {
                        _lastAssistantMessage.value = complete
                        appendLog(
                            LogEntry(event.messageId, LogRole.ASSISTANT, complete, clock())
                        )
                        if (settings.speakUpdates.value) _spokenMessages.emit(complete)
                    }
                }
            }

            is SessionEvent.SessionEnded -> {
                appendLog(
                    LogEntry(
                        "ended-${clock()}",
                        LogRole.SYSTEM,
                        event.reason ?: "Session ended on the host.",
                        clock(),
                    )
                )
                _status.value = AgentStatus.IDLE
                _connection.value = ConnectionState.Disconnected
            }

            is SessionEvent.Failure ->
                appendLog(LogEntry("err-${clock()}", LogRole.SYSTEM, event.message, clock()))
        }
    }

    private fun appendLog(entry: LogEntry) {
        // Bounded: a watch has no business holding an unbounded transcript.
        _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
    }

    suspend fun resolveApproval(requestId: String, approved: Boolean): Boolean {
        val sent = stream?.send(ClientCommand.ResolveApproval(requestId, approved)) ?: false
        if (sent && _pendingApproval.value?.id == requestId) {
            // Optimistic: the relay echoes approval_resolved, but the user
            // should see the gate close on their tap, not a round trip later.
            _pendingApproval.value = null
            _status.value = if (approved) AgentStatus.EXECUTING else AgentStatus.THINKING
        }
        return sent
    }

    /** Convenience for the tile and notification, which answer "the current one". */
    suspend fun resolvePendingApproval(approved: Boolean): Boolean {
        val pending = _pendingApproval.value ?: return false
        return resolveApproval(pending.id, approved)
    }

    suspend fun sendPrompt(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val sent = stream?.send(ClientCommand.SendPrompt(trimmed)) ?: false
        if (sent) {
            appendLog(LogEntry("user-${clock()}", LogRole.USER, trimmed, clock()))
            _status.value = AgentStatus.THINKING
        }
        return sent
    }

    suspend fun interrupt(): Boolean = stream?.send(ClientCommand.Interrupt) ?: false

    private companion object {
        const val TAG = "SessionRepository"
        const val MAX_LOG_ENTRIES = 100
    }
}

/**
 * `stateIn` with eager sharing, spelled out so the repository's state is hot
 * before any UI collects it — the tile reads `state.value` synchronously.
 */
private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInEagerly(
    scope: CoroutineScope,
    initial: T,
): StateFlow<T> {
    val flow = MutableStateFlow(initial)
    distinctUntilChanged().onEach { flow.value = it }.launchIn(scope)
    return flow.asStateFlow()
}
