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
 * Parser for streaming responses from OpenAI Responses API.
 * Handles Server-Sent Events (SSE) format and parses delta chunks.
 */
object ResponsesStreamParser {
    private const val TAG = "ResponsesStreamParser"

    /**
     * Data class representing a parsed streaming event.
     */
    data class StreamEvent(
        val type: String,
        val data: JsonObject,
        val delta: String? = null,
        val toolCalls: List<ToolCallDelta>? = null
    )

    /**
     * Data class representing a tool call delta in streaming.
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val type: String? = null,
        val function: FunctionDelta? = null
    )

    /**
     * Data class representing a function call delta.
     */
    data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null
    )

    /**
     * Parses a single SSE event line from the stream.
     */
    fun parseSSEEvent(eventLine: String): StreamEvent? {
        return try {
            if (!eventLine.startsWith("data: ")) return null
            
            val jsonData = eventLine.removePrefix("data: ").trim()
            if (jsonData.isEmpty() || jsonData == "[DONE]") return null
            
            val jsonObject = kotlinx.serialization.json.Json.parseToJsonElement(jsonData) as? JsonObject
                ?: return null
            
            val type = jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: return null
            
            when (type) {
                "response.output_text.delta" -> {
                    val delta = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                    StreamEvent(type = type, data = jsonObject, delta = delta)
                }
                "response.function_call_arguments.delta" -> {
                    val delta = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                    val itemIndex = jsonObject["item_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val callIndex = jsonObject["call_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    
                    val toolCallDelta = ToolCallDelta(
                        index = callIndex,
                        function = FunctionDelta(arguments = delta)
                    )
                    
                    StreamEvent(
                        type = type,
                        data = jsonObject,
                        toolCalls = listOf(toolCallDelta)
                    )
                }
                "response.completed" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.in_progress" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.output_item.added" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.output_item.done" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.content_part.added" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.content_part.done" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.output_text.done" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                "response.function_call_arguments.done" -> {
                    StreamEvent(type = type, data = jsonObject)
                }
                else -> {
                    StreamEvent(type = type, data = jsonObject)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SSE event: $eventLine", e)
            null
        }
    }

    /**
     * Parses a complete streaming response line (could be multi-line).
     */
    fun parseStreamLine(line: String): StreamEvent? {
        return try {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return null
            
            // Handle different SSE formats
            when {
                trimmedLine.startsWith("data: ") -> parseSSEEvent(trimmedLine)
                trimmedLine.startsWith("{") -> {
                    // Direct JSON (some APIs return JSON lines)
                    val jsonObject = kotlinx.serialization.json.Json.parseToJsonElement(trimmedLine) as? JsonObject
                    val type = jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
                    if (type != null) {
                        StreamEvent(type = type, data = jsonObject)
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing stream line: $line", e)
            null
        }
    }

    /**
     * Extracts the final text content from a completed response.
     */
    fun extractFinalText(response: JsonObject): String? {
        return try {
            val output = response["output"] as? JsonArray ?: return null
            
            for (item in output) {
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                
                if (type == "message") {
                    val content = obj["content"] as? JsonArray
                    content?.forEach { contentItem ->
                        val contentObj = contentItem.jsonObject
                        val contentType = contentObj["type"]?.jsonPrimitive?.contentOrNull
                        if (contentType == "output_text") {
                            return contentObj["text"]?.jsonPrimitive?.contentOrNull
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting final text", e)
            null
        }
    }

    /**
     * Extracts tool calls from a completed response.
     */
    fun extractToolCalls(response: JsonObject): List<AgentToolCall> {
        val toolCalls = mutableListOf<AgentToolCall>()
        
        try {
            val output = response["output"] as? JsonArray ?: return toolCalls
            
            for (item in output) {
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                
                if (type == "function_call") {
                    val id = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    
                    toolCalls.add(
                        AgentToolCall(
                            id = id,
                            name = name,
                            arguments = arguments
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting tool calls", e)
        }
        
        return toolCalls
    }
}