package fuck.andes.agent.model

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Utility for handling reasoning/thinking content from various AI providers.
 */
object ProviderReasoning {
    private const val TAG = "ProviderReasoning"

    /**
     * Extracts reasoning content from an OpenAI Responses API response delta.
     * OpenAI Responses API puts reasoning in a "summary" array within the delta.
     */
    fun extractReasoningFromOpenAiResponsesDelta(delta: JsonObject): String? {
        return try {
            val summary = delta["summary"] as? JsonArray
            if (summary.isNullOrEmpty()) return null
            
            val reasoningBuilder = StringBuilder()
            for (item in summary) {
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                
                if (type == "summary_text" && text != null) {
                    reasoningBuilder.append(text)
                }
            }
            
            val reasoning = reasoningBuilder.toString().trim()
            if (reasoning.isNotEmpty()) reasoning else null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting reasoning from OpenAI Responses delta", e)
            null
        }
    }

    /**
     * Extracts reasoning content from an Anthropic Messages API response delta.
     * Anthropic API puts thinking in a "content_block_delta" with type "thinking_delta".
     */
    fun extractReasoningFromAnthropicDelta(delta: JsonObject): String? {
        return try {
            val type = delta["type"]?.jsonPrimitive?.contentOrNull
            if (type == "thinking_delta") {
                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull
                return thinking?.trim()?.ifEmpty { null }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting reasoning from Anthropic delta", e)
            null
        }
    }

    /**
     * Creates a reasoning content block for OpenAI Responses API format.
     */
    fun createReasoningBlockForOpenAi(reasoning: String): JsonElement {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("reasoning"),
                "summary" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("summary_text"),
                                "text" to JsonPrimitive(reasoning)
                            )
                        )
                    )
                )
            )
        )
    }

    /**
     * Creates a thinking content block for Anthropic Messages API format.
     */
    fun createThinkingBlockForAnthropic(thinking: String): JsonElement {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("thinking"),
                "thinking" to JsonPrimitive(thinking)
            )
        )
    }

    /**
     * Formats reasoning content for display.
     * Removes excessive whitespace and formats for better readability.
     */
    fun formatReasoningForDisplay(reasoning: String): String {
        return reasoning.trim()
            .replace(Regex("\\s+"), " ")
            .takeIf { it.isNotEmpty() }
            ?: "[No reasoning content]"
    }
}