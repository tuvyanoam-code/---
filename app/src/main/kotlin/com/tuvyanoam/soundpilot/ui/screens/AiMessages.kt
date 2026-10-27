package com.tuvyanoam.soundpilot.ui.screens

import android.content.Context
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.core.ai.AgentReply
import com.tuvyanoam.soundpilot.core.ai.AiFailure

/** A user-facing explanation of why Claude did not answer normally, or null when it did. */
fun describeProblem(context: Context, reply: AgentReply): String? = when (reply.outcome) {
    AgentReply.Outcome.DONE -> null
    AgentReply.Outcome.REFUSED -> context.getString(R.string.ai_refused) +
        reply.text.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    AgentReply.Outcome.STEP_LIMIT -> context.getString(R.string.ai_step_limit)
    AgentReply.Outcome.TRUNCATED -> context.getString(R.string.ai_truncated)
    AgentReply.Outcome.FAILED -> describeFailure(context, reply.failure ?: AiFailure.UNKNOWN, reply.text)
}

fun describeFailure(context: Context, failure: AiFailure, detail: String? = null): String = when (failure) {
    AiFailure.NO_KEY -> context.getString(R.string.ai_no_key)
    AiFailure.INVALID_KEY -> context.getString(R.string.ai_invalid_key)
    AiFailure.PERMISSION -> context.getString(R.string.ai_permission)
    AiFailure.RATE_LIMITED -> context.getString(R.string.ai_rate_limited)
    AiFailure.OVERLOADED -> context.getString(R.string.ai_overloaded)
    AiFailure.MODEL_NOT_FOUND -> context.getString(R.string.ai_model_not_found)
    AiFailure.NETWORK -> context.getString(R.string.ai_network)
    AiFailure.BAD_REQUEST, AiFailure.UNKNOWN ->
        context.getString(R.string.ai_unknown) + detail?.takeIf { it.isNotBlank() }?.let { "\n${it.take(300)}" }.orEmpty()
}
