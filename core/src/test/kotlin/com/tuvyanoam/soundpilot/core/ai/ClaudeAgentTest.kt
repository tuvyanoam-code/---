package com.tuvyanoam.soundpilot.core.ai

import com.tuvyanoam.soundpilot.core.FakeAudio
import com.tuvyanoam.soundpilot.core.FakeContext
import com.tuvyanoam.soundpilot.core.FakeNotifier
import com.tuvyanoam.soundpilot.core.FakePlatform
import com.tuvyanoam.soundpilot.core.FakeScheduler
import com.tuvyanoam.soundpilot.core.MutableClock
import com.tuvyanoam.soundpilot.core.automation.AutomationCoordinator
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.platform.EnglishPhrases
import com.tuvyanoam.soundpilot.core.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeAgentTest {
    private val server = MockWebServer().apply { start() }
    private val clock = MutableClock(LocalDateTime.of(2026, 10, 5, 12, 0))
    private val storage = Storage(Files.createTempDirectory("sp").toFile(), clock::now)
    private val audio = FakeAudio()

    @AfterTest
    fun stop() = server.shutdown()

    private fun agent(scope: CoroutineScope, key: String? = "sk-test"): ClaudeAgent {
        val coordinator = AutomationCoordinator(storage, audio, FakeContext(clock), FakeScheduler(), FakeNotifier(), clock, EnglishPhrases, scope)
        val toolbox = AutomationToolbox(storage, coordinator, FakePlatform(), clock, EnglishPhrases)
        val gateway = ClaudeGateway({ key }, baseUrl = server.url("/").toString().trimEnd('/'))
        return ClaudeAgent(gateway, toolbox, storage) { "Hebrew" }
    }

    private fun message(id: String, stop: String, content: String, extra: String = "") = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody(
            """{"id":"$id","type":"message","role":"assistant","model":"claude-opus-5-5","content":$content,""" +
                """"stop_reason":"$stop","stop_sequence":null$extra,"usage":{"input_tokens":120,"output_tokens":30}}""",
        )

    @Test
    fun `runs a tool and returns the final answer`() = runTest {
        server.enqueue(
            message(
                "msg_1", "tool_use",
                """[{"type":"text","text":"Setting vibrate."},""" +
                    """{"type":"tool_use","id":"toolu_1","name":"set_ringer_mode","input":{"mode":"VIBRATE","minutes":30}}]""",
            ),
        )
        server.enqueue(message("msg_2", "end_turn", """[{"type":"text","text":"הטלפון ברטט לחצי שעה."}]"""))

        val conversation = Conversation()
        val reply = agent(backgroundScope).run(AgentMode.CHAT, conversation, "רטט לחצי שעה")

        assertEquals(AgentReply.Outcome.DONE, reply.outcome)
        assertEquals("הטלפון ברטט לחצי שעה.", reply.text)
        assertEquals(listOf("set_ringer_mode"), reply.toolCalls.map { it.name })
        assertEquals("Ringer: Vibrate for 30 min", reply.toolCalls.single().summary)
        assertEquals(RingerMode.VIBRATE, audio.state.ringerMode)
        assertNotNull(storage.automation.get().temporary)
        assertEquals(240L, reply.inputTokens)
        // user, assistant(tool_use), user(tool_result), assistant(answer)
        assertEquals(4, conversation.messages.size)

        val first = server.takeRequest()
        assertEquals("/v1/messages", first.path?.substringBefore('?'))
        assertTrue(first.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val toolNames = body["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("set_ringer_mode" in toolNames && "create_rule" in toolNames)
        assertTrue(body["system"]!!.jsonArray[0].jsonObject.containsKey("cache_control"))

        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val lastContent = second["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", lastContent["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", lastContent["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid tool input goes back to Claude as an error`() = runTest {
        server.enqueue(
            message("msg_1", "tool_use", """[{"type":"tool_use","id":"toolu_1","name":"set_volume","input":{"stream":"RING","percent":500}}]"""),
        )
        server.enqueue(message("msg_2", "end_turn", """[{"type":"text","text":"Sorry."}]"""))
        val reply = agent(backgroundScope).run(AgentMode.CHAT, Conversation(), "max volume")
        assertTrue(reply.toolCalls.single().isError)
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("\"is_error\":true" in second)
    }

    @Test
    fun `a refusal is reported and leaves no trace in the conversation`() = runTest {
        server.enqueue(
            message("msg_1", "refusal", "[]", extra = ""","stop_details":{"type":"refusal","category":null,"explanation":"Declined."}"""),
        )
        val conversation = Conversation()
        val reply = agent(backgroundScope).run(AgentMode.CHAT, conversation, "...")
        assertEquals(AgentReply.Outcome.REFUSED, reply.outcome)
        assertEquals("Declined.", reply.text)
        assertTrue(conversation.isEmpty)
    }

    @Test
    fun `an invalid key is classified`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        val reply = agent(backgroundScope).run(AgentMode.CHAT, Conversation(), "hi")
        assertEquals(AgentReply.Outcome.FAILED, reply.outcome)
        assertEquals(AiFailure.INVALID_KEY, reply.failure)
    }

    @Test
    fun `no key fails without a request`() = runTest {
        val reply = agent(backgroundScope, key = null).run(AgentMode.CHAT, Conversation(), "hi")
        assertEquals(AiFailure.NO_KEY, reply.failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `haiku requests carry no fallback`() = runTest {
        storage.settings.update { it.copy(ai = it.ai.copy(model = "claude-haiku-5-5")) }
        server.enqueue(message("msg_1", "end_turn", """[{"type":"text","text":"ok"}]"""))
        agent(backgroundScope).run(AgentMode.RULE_DRAFT, Conversation(), "x")
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertTrue("fallbacks" !in body)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
}
