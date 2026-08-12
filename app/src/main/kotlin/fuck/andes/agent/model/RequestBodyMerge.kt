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
 * Utility for merging request bodies when updating or retrying API requests.
 */
object RequestBodyMerge {
    private const val TAG = "RequestBodyMerge"

    /**
     * Merges tool results into a request body for OpenAI Chat Completions format.
     */
    fun mergeToolResultsForOpenAiChat(
        existingBody: JsonObject,
        toolResults: List<JsonObject>
    ): JsonObject {
        return try {
            val messages = existingBody["messages"] as? JsonArray ?: return existingBody
            val mergedMessages = messages.toMutableList()
            
            for (toolResult in toolResults) {
                mergedMessages.add(toolResult)
            }
            
            existingBody.toMutableMap().apply {
                put("messages", JsonArray(mergedMessages))
            }.let { JsonObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error merging tool results for OpenAI Chat", e)
            existingBody
        }
    }

    /**
     * Merges tool results into a request body for Anthropic Messages format.
     */
    fun mergeToolResultsForAnthropic(
        existingBody: JsonObject,
        toolResults: List<JsonObject>
    ): JsonObject {
        return try {
            val messages = existingBody["messages"] as? JsonArray ?: return existingBody
            val mergedMessages = messages.toMutableList()
            
            // Convert tool results to Anthropic format
            for (toolResult in toolResults) {
                val anthropicResult = JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("user"),
                        "content" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("tool_result"),
                                        "tool_use_id" to (toolResult["tool_call_id"] ?: JsonPrimitive("")),
                                        "content" to (toolResult["content"] ?: JsonPrimitive(""))
                                    )
                                )
                            )
                        )
                    )
                )
                mergedMessages.add(anthropicResult)
            }
            
            existingBody.toMutableMap().apply {
                put("messages", JsonArray(mergedMessages))
            }.let { JsonObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error merging tool results for Anthropic", e)
            existingBody
        }
    }

    /**
     * Updates the model in a request body.
     */
    fun updateModel(
        body: JsonObject,
        newModel: String
    ): JsonObject {
        return body.toMutableMap().apply {
            put("model", JsonPrimitive(newModel))
        }.let { JsonObject(it) }
    }

    /**
     * Updates the max tokens in a request body.
     */
    fun updateMaxTokens(
        body: JsonObject,
        maxTokens: Int
    ): JsonObject {
        return body.toMutableMap().apply {
            put("max_tokens", JsonPrimitive(maxTokens))
        }.let { JsonObject(it) }
    }

    /**
     * Removes stream parameter from a request body (for non-streaming requests).
     */
    fun removeStreamParameter(body: JsonObject): JsonObject {
        return body.toMutableMap().apply {
            remove("stream")
            remove("stream_options")
        }.let { JsonObject(it) }
    }

    /**
     * Adds stream parameter to a request body (for streaming requests).
     */
    fun addStreamParameter(
        body: JsonObject,
        includeUsage: Boolean = true
    ): JsonObject {
        return body.toMutableMap().apply {
            put("stream", JsonPrimitive(true))
            if (includeUsage) {
                put(
                    "stream_options",
                    JsonObject(mapOf("include_usage" to JsonPrimitive(true)))
                )
            }
        }.let { JsonObject(it) }
    }

    /**
     * Deep merges two JSON objects, with the second object's values taking precedence.
     */
    fun deepMerge(base: JsonObject, override: JsonObject): JsonObject {
        val result = base.toMutableMap()
        
        for ((key, value) in override) {
            if (value is JsonObject && result[key] is JsonObject) {
                // Recursively merge nested objects
                result[key] = deepMerge(
                    result[key] as JsonObject,
                    value
                )
            } else {
                // Override with new value
                result[key] = value
            }
        }
        
        return JsonObject(result)
    }
}