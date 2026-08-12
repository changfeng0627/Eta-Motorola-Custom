package fuck.andes.agent.model

import android.util.Log

/**
 * Factory class for creating AI model provider instances based on configuration.
 * Supports multiple provider types and handles provider-specific setup.
 */
object ProviderClientFactory {

    private const val TAG = "ProviderClientFactory"

    /**
     * Creates an AI model provider instance based on the provided configuration.
     *
     * @param config The provider configuration containing API credentials and settings.
     * @return An AiModelProvider instance configured according to the provided settings.
     * @throws IllegalArgumentException if the provider type is not supported.
     */
    fun create(config: ProviderConfig): AiModelProvider {
        return when (config.providerType.lowercase()) {
            "anthropic" -> {
                AnthropicMessagesProvider(
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl ?: "https://api.anthropic.com",
                    model = config.model,
                    maxTokens = config.maxTokens,
                )
            }
            "openai", "openrouter", "together", "groq" -> {
                OpenAiChatCompletionsProvider(
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl ?: getDefaultBaseUrl(config.providerType),
                    model = config.model,
                    providerType = config.providerType,
                )
            }
            "responses" -> {
                OpenAiResponsesProvider(
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl ?: "https://api.openai.com",
                    model = config.model,
                )
            }
            "custom" -> {
                // Custom providers can use either Chat Completions or Responses API
                if (config.useResponsesApi) {
                    OpenAiResponsesProvider(
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl ?: throw IllegalArgumentException("Base URL required for custom provider"),
                        model = config.model,
                    )
                } else {
                    OpenAiChatCompletionsProvider(
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl ?: throw IllegalArgumentException("Base URL required for custom provider"),
                        model = config.model,
                        providerType = "custom",
                    )
                }
            }
            else -> {
                Log.w(TAG, "Unknown provider type: ${config.providerType}, falling back to OpenAI Chat Completions")
                OpenAiChatCompletionsProvider(
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl ?: "https://api.openai.com",
                    model = config.model,
                    providerType = config.providerType,
                )
            }
        }
    }

    /**
     * Gets the default base URL for a known provider type.
     *
     * @param providerType The type of provider.
     * @return The default base URL for the provider.
     */
    private fun getDefaultBaseUrl(providerType: String): String {
        return when (providerType.lowercase()) {
            "openai" -> "https://api.openai.com"
            "openrouter" -> "https://openrouter.ai"
            "together" -> "https://api.together.xyz"
            "groq" -> "https://api.groq.com"
            else -> "https://api.openai.com"
        }
    }

    /**
     * Checks if a provider type is supported.
     *
     * @param providerType The provider type to check.
     * @return True if the provider type is supported.
     */
    fun isSupportedProvider(providerType: String): Boolean {
        return providerType.lowercase() in setOf(
            "anthropic",
            "openai",
            "openrouter",
            "together",
            "groq",
            "responses",
            "custom",
        )
    }

    /**
     * Gets a list of all supported provider types.
     *
     * @return List of supported provider type strings.
     */
    fun getSupportedProviders(): List<String> {
        return listOf(
            "anthropic",
            "openai",
            "openrouter",
            "together",
            "groq",
            "responses",
            "custom",
        )
    }
}

/**
 * Configuration data class for AI model providers.
 *
 * @property providerType The type of provider (e.g., "openai", "anthropic", "custom").
 * @property apiKey The API key for authentication.
 * @property model The model to use for inference.
 * @property baseUrl Optional custom base URL for the API endpoint.
 * @property maxTokens Maximum tokens for Anthropic providers.
 * @property useResponsesApi Whether to use the Responses API for custom providers.
 * @property temperature Optional temperature for sampling.
 * @property customHeaders Optional custom HTTP headers.
 */
data class ProviderConfig(
    val providerType: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String? = null,
    val maxTokens: Int = 4096,
    val useResponsesApi: Boolean = false,
    val temperature: Double? = null,
    val customHeaders: Map<String, String> = emptyMap(),
)