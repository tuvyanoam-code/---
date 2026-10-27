package com.tuvyanoam.soundpilot.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Why a call to Claude failed, in terms the app can explain to the user. */
enum class AiFailure { NO_KEY, INVALID_KEY, PERMISSION, RATE_LIMITED, OVERLOADED, BAD_REQUEST, MODEL_NOT_FOUND, NETWORK, UNKNOWN }

class AiException(val failure: AiFailure, message: String?, cause: Throwable? = null) : Exception(message, cause)

/** Owns the Anthropic client and rebuilds it when the API key changes. */
class ClaudeGateway(
    private val apiKeyProvider: suspend () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Overrides the API host; used by tests. */
    private val baseUrl: String? = null,
) {
    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    suspend fun hasKey(): Boolean = !apiKeyProvider().isNullOrBlank()

    private suspend fun client(): AnthropicClient {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrEmpty()) throw AiException(AiFailure.NO_KEY, "No Claude API key configured")
        synchronized(this) {
            val existing = cachedClient
            if (existing != null && cachedKey == key) return existing
            existing?.close()
            return buildClient(key).also {
                cachedClient = it
                cachedKey = key
            }
        }
    }

    /** Sends one Messages API request on the beta endpoint (needed for server-side refusal fallbacks). */
    suspend fun send(params: MessageCreateParams): BetaMessage {
        val client = client()
        return withContext(io) {
            try {
                client.beta().messages().create(params)
            } catch (e: Exception) {
                throw classify(e)
            }
        }
    }

    /** Checks that [apiKey] works with [model] without spending tokens. Returns null when everything is fine. */
    suspend fun verify(apiKey: String, model: String): AiFailure? = withContext(io) {
        val client = buildClient(apiKey.trim())
        try {
            client.models().retrieve(model)
            null
        } catch (e: Exception) {
            classify(e).failure
        } finally {
            client.close()
        }
    }

    private fun buildClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(key)
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    companion object {
        /** Most specific first: the type decides whether retrying later can help. */
        fun classify(e: Throwable): AiException = when (e) {
            is AiException -> e
            is UnauthorizedException -> AiException(AiFailure.INVALID_KEY, e.message, e)
            is PermissionDeniedException -> AiException(AiFailure.PERMISSION, e.message, e)
            is NotFoundException -> AiException(AiFailure.MODEL_NOT_FOUND, e.message, e)
            is RateLimitException -> AiException(AiFailure.RATE_LIMITED, e.message, e)
            is BadRequestException -> AiException(AiFailure.BAD_REQUEST, e.message, e)
            is InternalServerException -> AiException(AiFailure.OVERLOADED, e.message, e)
            is AnthropicServiceException ->
                if (e.statusCode() >= 500) AiException(AiFailure.OVERLOADED, e.message, e)
                else AiException(AiFailure.UNKNOWN, e.message, e)
            is AnthropicIoException -> AiException(AiFailure.NETWORK, e.message, e)
            is java.io.IOException -> AiException(AiFailure.NETWORK, e.message, e)
            else -> AiException(AiFailure.UNKNOWN, e.message, e)
        }
    }
}
