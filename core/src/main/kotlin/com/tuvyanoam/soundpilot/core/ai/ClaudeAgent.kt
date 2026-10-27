package com.tuvyanoam.soundpilot.core.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.JsonNode
import com.tuvyanoam.soundpilot.core.storage.Storage
import kotlinx.serialization.json.JsonObject

/** The running message history of one conversation with Claude. Only ever appended to, or cut back at the end. */
class Conversation {
    internal val messages = mutableListOf<BetaMessageParam>()

    val isEmpty: Boolean get() = messages.isEmpty()

    fun clear() = messages.clear()

    internal fun truncate(size: Int) {
        while (messages.size > size) messages.removeAt(messages.lastIndex)
    }
}

data class ToolCallRecord(val name: String, val summary: String?, val isError: Boolean)

data class AgentReply(
    val text: String,
    val toolCalls: List<ToolCallRecord>,
    val outcome: Outcome,
    val failure: AiFailure? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    enum class Outcome { DONE, REFUSED, STEP_LIMIT, TRUNCATED, FAILED }

    val succeeded: Boolean get() = outcome == Outcome.DONE
}

/**
 * Runs Claude with the automation tools: sends the conversation, executes requested tools on the phone, and loops
 * until Claude answers. One instance serves every mode; the mode picks the prompt, tools and effort.
 */
class ClaudeAgent(
    private val gateway: ClaudeGateway,
    private val toolbox: AutomationToolbox,
    private val storage: Storage,
    private val languageName: () -> String,
) {
    suspend fun hasKey(): Boolean = gateway.hasKey()

    suspend fun run(
        mode: AgentMode,
        conversation: Conversation,
        userText: String,
        collector: RunCollector = RunCollector(),
        onToolCall: suspend (ToolCallRecord) -> Unit = {},
    ): AgentReply {
        val settings = storage.settings.get().ai
        val tools = toolbox.toolsFor(mode, collector)
        val toolsByName = tools.associateBy { it.name }
        val betaTools = tools.map { it.toBetaTool() }
        val system = listOf(
            BetaTextBlockParam.builder()
                .text(Prompts.system(mode, languageName()))
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .build(),
            BetaTextBlockParam.builder()
                .text(Prompts.memory(toolbox.memoryText(storage.memory.all())))
                .build(),
        )

        val start = conversation.messages.size
        conversation.messages += BetaMessageParam.ofUser("[Now: ${toolbox.nowText()}]\n$userText")
        val calls = mutableListOf<ToolCallRecord>()
        var inputTokens = 0L
        var outputTokens = 0L

        repeat(MAX_STEPS) {
            val params = MessageCreateParams.builder()
                .model(settings.model)
                .maxTokens(MAX_TOKENS)
                .systemOfBetaTextBlockParams(system)
                .messages(conversation.messages.toList())
                .outputConfig(BetaOutputConfig.builder().effort(effortFor(mode)).build())
                .apply {
                    betaTools.forEach { addTool(it) }
                    if (supportsServerFallback(settings.model)) {
                        addBeta(SERVER_FALLBACK_BETA)
                        fallbacksDefault()
                    }
                }
                .build()

            val response: BetaMessage = try {
                gateway.send(params)
            } catch (e: Exception) {
                conversation.truncate(start)
                val failure = ClaudeGateway.classify(e)
                return AgentReply(failure.message.orEmpty(), calls, AgentReply.Outcome.FAILED, failure.failure, inputTokens, outputTokens)
            }
            inputTokens += response.usage().inputTokens()
            outputTokens += response.usage().outputTokens()

            val stop = response.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) {
                // Drop the refused turn so the conversation can continue normally.
                conversation.truncate(start)
                val explanation = response.stopDetails().flatMap { it.explanation() }.orElse("")
                return AgentReply(explanation, calls, AgentReply.Outcome.REFUSED, null, inputTokens, outputTokens)
            }
            if (stop == BetaStopReason.MAX_TOKENS) {
                conversation.truncate(start)
                return AgentReply(textOf(response), calls, AgentReply.Outcome.TRUNCATED, null, inputTokens, outputTokens)
            }

            conversation.messages += response.toParam()
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (stop != BetaStopReason.TOOL_USE || toolUses.isEmpty()) {
                return AgentReply(textOf(response), calls, AgentReply.Outcome.DONE, null, inputTokens, outputTokens)
            }

            // All results go back in a single user message.
            val results = toolUses.map { use ->
                val result = execute(use, toolsByName)
                val record = ToolCallRecord(use.name(), result.summary, result.isError)
                calls += record
                onToolCall(record)
                BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder()
                        .toolUseId(use.id())
                        .content(result.content)
                        .isError(result.isError)
                        .build(),
                )
            }
            conversation.messages += BetaMessageParam.ofUserOfContentBlockParams(results)
        }

        // Close the turn so the next user message follows an assistant message.
        conversation.messages += BetaMessageParam.ofAssistant("(Stopped after too many steps.)")
        return AgentReply("", calls, AgentReply.Outcome.STEP_LIMIT, null, inputTokens, outputTokens)
    }

    private suspend fun execute(use: BetaToolUseBlock, tools: Map<String, AgentTool>): ToolResult {
        val tool = tools[use.name()] ?: return ToolResult("Unknown tool ${use.name()}", isError = true)
        val input = try {
            val node = use._input().convert(JsonNode::class.java)
            ToolJson.parseToJsonElement(node?.toString() ?: "{}") as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return ToolResult("Invalid JSON input for ${use.name()}", isError = true)
        return try {
            tool.run(input)
        } catch (e: Exception) {
            ToolResult("Tool failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun textOf(message: BetaMessage): String =
        message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("\n").trim()

    private fun effortFor(mode: AgentMode): BetaOutputConfig.Effort = when (mode) {
        AgentMode.CHAT -> BetaOutputConfig.Effort.MEDIUM
        AgentMode.RULE_DRAFT, AgentMode.SMART -> BetaOutputConfig.Effort.LOW
        AgentMode.ANALYZE -> BetaOutputConfig.Effort.HIGH
    }

    private fun AgentTool.toBetaTool(): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        properties.forEach { (key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema.toJava())) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(props.build())
                    .required(required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }

    companion object {
        private const val MAX_STEPS = 10
        private const val MAX_TOKENS = 16_000L
        private const val SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Models that accept the server-side refusal fallback ("default" form) on the Claude API. */
        fun supportsServerFallback(model: String): Boolean =
            model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5-5") || model.startsWith("claude-fable-5")
    }
}
