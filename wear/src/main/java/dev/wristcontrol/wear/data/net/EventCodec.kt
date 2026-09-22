package dev.wristcontrol.wear.data.net

import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ApprovalRequest
import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.LogEntry
import dev.wristcontrol.wear.data.model.LogRole
import dev.wristcontrol.wear.data.model.RemoteSession
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.data.model.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Hand-written JSON mapping for the relay protocol (see docs/protocol.md).
 *
 * Deliberately tolerant: the relay is a moving target, so unknown frame types
 * and unknown enum values are dropped or downgraded instead of throwing. A
 * watch that goes blank because the server added a field is a worse outcome
 * than a watch that ignores that field.
 */
object EventCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decodeEvent(text: String, clock: () -> Long = System::currentTimeMillis): SessionEvent? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (root.string("type")) {
            "status", "status_changed" ->
                SessionEvent.StatusChanged(AgentStatus.fromWire(root.string("status")))

            "approval_required", "approval_request" ->
                decodeApproval(root, clock)?.let { SessionEvent.ApprovalRequested(it) }

            "approval_resolved" -> {
                val id = root.string("request_id") ?: return null
                SessionEvent.ApprovalResolved(id, root.bool("approved") ?: false)
            }

            "log", "log_entry" -> decodeLog(root, clock)?.let { SessionEvent.LogAppended(it) }

            "assistant_delta", "assistant_message" -> SessionEvent.AssistantDelta(
                messageId = root.string("message_id") ?: "msg-${clock()}",
                text = root.string("text").orEmpty(),
                final = root.bool("final") ?: (root.string("type") == "assistant_message"),
            )

            "session_ended" -> SessionEvent.SessionEnded(root.string("reason"))

            "error" -> SessionEvent.Failure(root.string("message") ?: "Unknown relay error")

            else -> null
        }
    }

    private fun decodeApproval(root: JsonObject, clock: () -> Long): ApprovalRequest? {
        // Accept both a nested object and a flattened frame.
        val node = root["request"] as? JsonObject ?: root
        val id = node.string("id") ?: node.string("request_id") ?: return null
        return ApprovalRequest(
            id = id,
            sessionId = node.string("session_id") ?: root.string("session_id").orEmpty(),
            toolName = node.string("tool") ?: node.string("tool_name") ?: "tool",
            command = node.string("command") ?: node.string("input") ?: "",
            explanation = node.string("explanation") ?: node.string("description"),
            risk = RiskLevel.fromWire(node.string("risk")),
            receivedAtEpochMillis = clock(),
        )
    }

    private fun decodeLog(root: JsonObject, clock: () -> Long): LogEntry? {
        val node = root["entry"] as? JsonObject ?: root
        val text = node.string("text") ?: node.string("message") ?: return null
        return LogEntry(
            id = node.string("id") ?: "log-${clock()}",
            role = when (node.string("role")?.lowercase()) {
                "user" -> LogRole.USER
                "assistant" -> LogRole.ASSISTANT
                "tool" -> LogRole.TOOL
                else -> LogRole.SYSTEM
            },
            text = text,
            timestampEpochMillis = node["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: clock(),
        )
    }

    fun encodeCommand(command: ClientCommand): String = when (command) {
        is ClientCommand.ResolveApproval -> buildJsonObject {
            put("type", "approval_response")
            put("request_id", command.requestId)
            put("approved", command.approved)
            put("remember", command.rememberForSession)
        }
        is ClientCommand.SendPrompt -> buildJsonObject {
            put("type", "user_prompt")
            put("text", command.text)
        }
        ClientCommand.Interrupt -> buildJsonObject { put("type", "interrupt") }
        ClientCommand.RequestSnapshot -> buildJsonObject { put("type", "snapshot_request") }
    }.toString()

    /** Parses `GET /v1/code/sessions`, accepting either a bare array or `{ "data": [...] }`. */
    fun decodeSessions(body: String, clock: () -> Long = System::currentTimeMillis): List<RemoteSession> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] ?: root["sessions"]) as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val id = node.string("id") ?: node.string("session_id") ?: return@mapNotNull null
            RemoteSession(
                id = id,
                title = node.string("title") ?: node.string("name") ?: "Session",
                hostName = node.string("host") ?: node.string("hostname").orEmpty(),
                workingDirectory = node.string("cwd") ?: node.string("working_directory"),
                status = AgentStatus.fromWire(node.string("status")),
                lastActivityEpochMillis =
                    node["last_activity_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: clock(),
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonElement)?.let { element ->
            runCatching { element.jsonPrimitive.content }.getOrNull()
        }?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()
}
