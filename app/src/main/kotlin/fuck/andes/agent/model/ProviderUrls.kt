package fuck.andes.agent.model

import android.util.Log

/**
 * Utility for managing provider URLs and endpoints.
 */
object ProviderUrls {
    private const val TAG = "ProviderUrls"

    // OpenAI endpoints
    const val OPENAI_CHAT_COMPLETIONS = "https://api.openai.com/v1/chat/completions"
    const val OPENAI_RESPONSES = "https://api.openai.com/v1/responses"
    const val OPENAI_MODELS = "https://api.openai.com/v1/models"

    // Anthropic endpoints
    const val ANTHROPIC_MESSAGES = "https://api.anthropic.com/v1/messages"
    const val ANTHROPIC_MODELS = "https://api.anthropic.com/v1/models"

    // Google AI endpoints
    const val GOOGLE_AI_CHAT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    const val GOOGLE_AI_STREAM_CHAT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent"

    // Common base URLs for third-party providers
    private val THIRD_PARTY_BASE_URLS = mapOf(
        "openrouter" to "https://openrouter.ai/api/v1",
        "together" to "https://api.together.xyz/v1",
        "groq" to "https://api.groq.com/openai/v1",
        "fireworks" to "https://api.fireworks.ai/inference/v1",
        "deepseek" to "https://api.deepseek.com/v1",
        "mistral" to "https://api.mistral.ai/v1",
        "cohere" to "https://api.cohere.ai/v1",
        "perplexity" to "https://api.perplexity.ai"
    )

    /**
     * Builds the appropriate endpoint URL based on provider configuration.
     */
    fun buildEndpointUrl(config: ProviderConfig): String {
        return when {
            config.baseUrl.isNotBlank() -> {
                // Custom base URL provided
                val baseUrl = config.baseUrl.trimEnd('/')
                when {
                    config.useOpenAiChatCompletionsApi -> "$baseUrl/chat/completions"
                    config.useOpenAiResponsesApi -> "$baseUrl/responses"
                    else -> baseUrl
                }
            }
            config.isAnthropic -> ANTHROPIC_MESSAGES
            config.isGoogleAi -> {
                // Google AI needs model ID in URL
                val model = config.modelId.ifBlank { "gemini-pro" }
                GOOGLE_AI_CHAT.replace("{model}", model)
            }
            config.isOpenAiCompatible -> {
                // OpenAI compatible providers
                if (config.useOpenAiResponsesApi) OPENAI_RESPONSES
                else OPENAI_CHAT_COMPLETIONS
            }
            else -> OPENAI_CHAT_COMPLETIONS
        }
    }

    /**
     * Determines if the provider uses streaming based on configuration.
     */
    fun shouldUseStreaming(config: ProviderConfig): Boolean {
        // Anthropic and OpenAI Responses API always use streaming for better UX
        return when {
            config.isAnthropic -> true
            config.useOpenAiResponsesApi -> true
            else -> true // Default to streaming for all providers
        }
    }

    /**
     * Gets the appropriate model ID for the provider.
     */
    fun getModelId(config: ProviderConfig): String {
        return config.modelId.ifBlank {
            when {
                config.isAnthropic -> "claude-3-5-sonnet-20241022"
                config.isGoogleAi -> "gemini-pro"
                config.isOpenAiCompatible -> "gpt-4o"
                else -> "gpt-4o"
            }
        }
    }

    /**
     * Validates if a URL is a valid HTTPS URL.
     */
    fun isValidHttpsUrl(url: String): Boolean {
        return try {
            val trimmed = url.trim()
            trimmed.startsWith("https://") && trimmed.length > 8
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sanitizes a URL by removing trailing slashes and normalizing.
     */
    fun sanitizeUrl(url: String): String {
        return url.trim()
            .trimEnd('/')
            .lowercase()
    }
}