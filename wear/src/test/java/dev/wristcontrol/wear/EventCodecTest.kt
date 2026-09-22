package dev.wristcontrol.wear

import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.RiskLevel
import dev.wristcontrol.wear.data.model.SessionEvent
import dev.wristcontrol.wear.data.net.EventCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCodecTest {

    private val fixedClock = { 1_700_000_000_000L }

    @Test
    fun `decodes status frames`() {
        val event = EventCodec.decodeEvent("""{"type":"status","status":"executing"}""", fixedClock)
        assertEquals(SessionEvent.StatusChanged(AgentStatus.EXECUTING), event)
    }

    @Test
    fun `unknown status degrades instead of throwing`() {
        val event = EventCodec.decodeEvent("""{"type":"status","status":"vibing"}""", fixedClock)
        assertEquals(SessionEvent.StatusChanged(AgentStatus.UNKNOWN), event)
    }

    @Test
    fun `unknown frame types are ignored`() {
        assertNull(EventCodec.decodeEvent("""{"type":"telemetry","v":1}""", fixedClock))
        assertNull(EventCodec.decodeEvent("not json at all", fixedClock))
    }

    @Test
    fun `decodes nested approval requests`() {
        val json = """
            {"type":"approval_required","session_id":"s1","request":{
              "id":"req-7","tool":"bash","command":"npm install",
              "explanation":"needs the dep","risk":"high"}}
        """.trimIndent()

        val event = EventCodec.decodeEvent(json, fixedClock) as SessionEvent.ApprovalRequested
        assertEquals("req-7", event.request.id)
        assertEquals("s1", event.request.sessionId)
        assertEquals("bash", event.request.toolName)
        assertEquals("npm install", event.request.command)
        assertEquals(RiskLevel.HIGH, event.request.risk)
    }

    @Test
    fun `decodes flattened approval requests`() {
        val json = """{"type":"approval_request","request_id":"req-9","tool_name":"edit","input":"src/a.ts"}"""
        val event = EventCodec.decodeEvent(json, fixedClock) as SessionEvent.ApprovalRequested
        assertEquals("req-9", event.request.id)
        assertEquals("edit", event.request.toolName)
        // Unspecified risk is treated as medium rather than assumed safe.
        assertEquals(RiskLevel.MEDIUM, event.request.risk)
    }

    @Test
    fun `assistant_message frames are final by default`() {
        val event = EventCodec.decodeEvent(
            """{"type":"assistant_message","message_id":"m1","text":"done"}""",
            fixedClock,
        ) as SessionEvent.AssistantDelta
        assertTrue(event.final)
    }

    @Test
    fun `assistant_delta frames are partial by default`() {
        val event = EventCodec.decodeEvent(
            """{"type":"assistant_delta","message_id":"m1","text":"do"}""",
            fixedClock,
        ) as SessionEvent.AssistantDelta
        assertTrue(!event.final)
    }

    @Test
    fun `decodes sessions from a wrapped payload`() {
        val body = """
            {"data":[
              {"id":"s1","title":"frontend-app","host":"Desktop","cwd":"~/src","status":"idle"},
              {"session_id":"s2","name":"api","hostname":"MBP","status":"thinking"}
            ]}
        """.trimIndent()

        val sessions = EventCodec.decodeSessions(body, fixedClock)
        assertEquals(2, sessions.size)
        assertEquals("Desktop - frontend-app", sessions[0].displayName)
        assertEquals(AgentStatus.THINKING, sessions[1].status)
    }

    @Test
    fun `decodes sessions from a bare array`() {
        val sessions = EventCodec.decodeSessions("""[{"id":"s1","title":"x","status":"idle"}]""", fixedClock)
        assertEquals(1, sessions.size)
        // No host reported: fall back to the bare title rather than a stray dash.
        assertEquals("x", sessions[0].displayName)
    }

    @Test
    fun `encodes approval responses`() {
        val json = EventCodec.encodeCommand(ClientCommand.ResolveApproval("req-1", approved = true))
        assertTrue(json.contains(""""type":"approval_response""""))
        assertTrue(json.contains(""""request_id":"req-1""""))
        assertTrue(json.contains(""""approved":true"""))
    }

    @Test
    fun `encodes prompts with escaping intact`() {
        val text = """use "quotes" & \slashes"""
        val json = EventCodec.encodeCommand(ClientCommand.SendPrompt(text))
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("user_prompt", parsed.getValue("type").jsonPrimitive.content)
        assertEquals(text, parsed.getValue("text").jsonPrimitive.content)
    }
}
