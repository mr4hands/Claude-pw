package dev.wristcontrol.wear.data.model

/**
 * Lifecycle of the remote agent, as reported by the cloud relay.
 *
 * The watch renders exactly one of these at a time on the status ring and on
 * the tile, so the set is deliberately small: anything the relay sends that we
 * do not recognise degrades to [UNKNOWN] rather than breaking the UI.
 */
enum class AgentStatus(val wireName: String) {
    IDLE("idle"),
    THINKING("thinking"),
    EXECUTING("executing"),
    AWAITING_APPROVAL("awaiting_approval"),
    ERROR("error"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): AgentStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** A `claude --rc` session discovered through the cloud session manager. */
data class RemoteSession(
    val id: String,
    val title: String,
    val hostName: String,
    val workingDirectory: String?,
    val status: AgentStatus,
    val lastActivityEpochMillis: Long,
) {
    /** "Desktop - frontend-app" in the session selector. */
    val displayName: String
        get() = if (hostName.isBlank()) title else "$hostName - $title"
}

/** How loudly the watch should shout about a pending approval. */
enum class RiskLevel(val wireName: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromWire(value: String?): RiskLevel =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: MEDIUM
    }
}

/**
 * A paused tool call waiting on the user. [command] is the literal shell line
 * or file path the agent wants to act on; it is shown verbatim because an
 * approximation of a command is worse than no command at all.
 */
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val command: String,
    val explanation: String?,
    val risk: RiskLevel,
    val receivedAtEpochMillis: Long,
) {
    /** Short enough to fit a tile line and a TTS announcement. */
    val shortCommand: String
        get() = command.lineSequence().firstOrNull()?.take(80).orEmpty()
}

enum class LogRole { USER, ASSISTANT, TOOL, SYSTEM }

data class LogEntry(
    val id: String,
    val role: LogRole,
    val text: String,
    val timestampEpochMillis: Long,
)

/** Connection state of the WSS relay, surfaced so the UI never lies about liveness. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int, val nextRetryInMillis: Long) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/** Generic loading wrapper for the one-shot REST calls. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}
