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
 * Builder for constructing OpenAI Responses API requests.
 * Provides a fluent API for building complex requests with proper structure.
 */
object ResponsesRequestBuilder {
    private const val TAG = "ResponsesRequestBuilder"

    /**
     * Creates a base request with common parameters.
     */
    fun createBaseRequest(
        model: String,
        input: String,
        instructions: String? = null,
        tools: List<AgentTool>? = null,
        temperature: Double? = null,
        maxOutputTokens: Int? = null
    ): JsonObject {
        return try {
            val requestMap = mutableMapOf<String, JsonElement>(
                "model" to JsonPrimitive(model),
                "input" to JsonPrimitive(input)
            )

            // Add optional parameters
            instructions?.let { requestMap["instructions"] = JsonPrimitive(it) }
            
            tools?.let { toolList ->
                if (toolList.isNotEmpty()) {
                    requestMap["tools"] = JsonArray(
                        toolList.map { tool ->
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("function"),
                                    "function" to JsonObject(
                                        mapOf(
                                            "name" to JsonPrimitive(tool.name),
                                            "description" to JsonPrimitive(tool.description),
                                            "parameters" to (tool.parameters ?: JsonObject(emptyMap()))
                                        )
                                    )
                                )
                            )
                        }
                    )
                }
            }

            temperature?.let { requestMap["temperature"] = JsonPrimitive(it) }
            maxOutputTokens?.let { requestMap["max_output_tokens"] = JsonPrimitive(it) }

            JsonObject(requestMap)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating base request", e)
            JsonObject(emptyMap())
        }
    }

    /**
     * Adds web search tool to the request.
     */
    fun addWebSearchTool(
        request: JsonObject,
        userLocation: String? = null
    ): JsonObject {
        return try {
            val tools = (request["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            
            val webSearchTool = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("web_search_preview")
                ).apply {
                    userLocation?.let { loc ->
                        // Add user location if provided
                    }
                }
            )
            
            tools.add(webSearchTool)
            
            request.toMutableMap().apply {
                put("tools", JsonArray(tools))
            }.let { JsonObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding web search tool", e)
            request
        }
    }

    /**
     * Adds file search tool to the request.
     */
    fun addFileSearchTool(
        request: JsonObject,
        vectorStoreIds: List<String>
    ): JsonObject {
        return try {
            val tools = (request["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            
            val fileSearchTool = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("file_search"),
                    "vector_store_ids" to JsonArray(
                        vectorStoreIds.map { JsonPrimitive(it) }
                    )
                )
            )
            
            tools.add(fileSearchTool)
            
            request.toMutableMap().apply {
                put("tools", JsonArray(tools))
            }.let { JsonObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding file search tool", e)
            request
        }
    }

    /**
     * Adds code interpreter tool to the request.
     */
    fun addCodeInterpreterTool(request: JsonObject): JsonObject {
        return try {
            val tools = (request["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            
            val codeInterpreterTool = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("code_interpreter")
                )
            )
            
            tools.add(codeInterpreterTool)
            
            request.toMutableMap().apply {
                put("tools", JsonArray(tools))
            }.let { JsonObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding code interpreter tool", e)
            request
        }
    }

    /**
     * Configures response format for the request.
     */
    fun setResponseFormat(
        request: JsonObject,
        format: String = "text" // "text", "json", "json_schema"
    ): JsonObject {
        return try {
            request.toMutableMap().apply {
                put(
                    "text",
                    JsonObject(
                        mapOf(
                            "format" to JsonPrimitive(format)
                        )
                    )
                )
            }.let { JsonObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting response format", e)
            request
        }
    }

    /**
     * Adds state tracking to the request for multi-turn conversations.
     */
    fun addStateTracking(
        request: JsonObject,
        previousResponseId: String?,
        conversationId: String? = null
    ): JsonObject {
        return try {
            val mutableMap = request.toMutableMap()
            
            previousResponseId?.let {
                if (it.isNotBlank()) {
                    mutableMap["previous_response_id"] = JsonPrimitive(it)
                }
            }
            
            conversationId?.let {
                if (it.isNotBlank()) {
                    mutableMap["conversation_id"] = JsonPrimitive(it)
                }
            }
            
            JsonObject(mutableMap)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding state tracking", e)
            request
        }
    }

    /**
     * Converts the request to a JSON string for debugging.
     */
    fun requestToJsonString(request: JsonObject): String {
        return try {
            request.toString()
                .replace(Regex("\\s+"), " ")
                .take(500) + if (request.toString().length > 500) "..." else ""
        } catch (e: Exception) {
            Log.e(TAG, "Error converting request to string", e)
            "Error converting request to string"
        }
    }
}