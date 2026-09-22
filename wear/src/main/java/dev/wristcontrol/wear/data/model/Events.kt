package dev.wristcontrol.wear.data.model

/** Everything the relay can push down the socket for a session. */
sealed interface SessionEvent {
    data class StatusChanged(val status: AgentStatus) : SessionEvent

    data class ApprovalRequested(val request: ApprovalRequest) : SessionEvent

    /** The host resolved the request itself, or another client answered first. */
    data class ApprovalResolved(val requestId: String, val approved: Boolean) : SessionEvent

    data class LogAppended(val entry: LogEntry) : SessionEvent

    /**
     * A chunk of assistant text. [final] marks the end of a message so TTS can
     * speak a whole sentence rather than stuttering through deltas.
     */
    data class AssistantDelta(val messageId: String, val text: String, val final: Boolean) : SessionEvent

    data class SessionEnded(val reason: String?) : SessionEvent

    data class Failure(val message: String) : SessionEvent
}

/** Everything the watch can push up the socket. */
sealed interface ClientCommand {
    data class ResolveApproval(
        val requestId: String,
        val approved: Boolean,
        val rememberForSession: Boolean = false,
    ) : ClientCommand

    data class SendPrompt(val text: String) : ClientCommand

    data object Interrupt : ClientCommand

    data object RequestSnapshot : ClientCommand
}
