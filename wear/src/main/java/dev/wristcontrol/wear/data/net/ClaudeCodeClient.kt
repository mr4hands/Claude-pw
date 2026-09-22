package dev.wristcontrol.wear.data.net

import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.data.model.SessionEvent
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/** One item coming out of a live session stream. */
sealed interface StreamMessage {
    data class Connection(val state: ConnectionState) : StreamMessage
    data class Event(val event: SessionEvent) : StreamMessage
}

/**
 * A live attachment to one remote session.
 *
 * Implementations own their own reconnect loop, so [messages] stays valid
 * across network drops and only completes when [close] is called.
 */
interface SessionStream : Closeable {
    val messages: Flow<StreamMessage>

    /** Returns false when the command could not be queued onto a live socket. */
    suspend fun send(command: ClientCommand): Boolean
}

/**
 * Transport-facing contract for the Claude Code cloud relay.
 *
 * Split out from the repository so the entire UI — including the tile — can be
 * exercised against [FakeClaudeCodeClient] with no account and no network, and
 * so the REST/WSS paths stay swappable if the endpoint shape changes.
 */
interface ClaudeCodeClient {
    suspend fun listSessions(): List<RemoteSession>
    fun openStream(sessionId: String): SessionStream
}

/** Thrown when the relay rejects our credentials and a refresh did not help. */
class AuthorizationExpiredException(message: String) : Exception(message)
