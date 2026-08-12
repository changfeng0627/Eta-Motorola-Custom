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
 * Handler for processing tool calls from OpenAI Responses API.
 * Manages the execution of tool calls and formatting of results.
 */
object ResponsesToolCallHandler {
    private const val TAG = "ResponsesToolCallHandler"

    /**
     * Data class representing a tool call from Responses API.
     */
    data class ResponsesToolCall(
        val callId: String,
        val name: String,
        val arguments: String,
        val status: String = "in_progress" // "in_progress", "completed", "failed"
    )

    /**
     * Extracts tool calls from a Responses API response.
     */
    fun extractToolCalls(response: JsonObject): List<ResponsesToolCall> {
        val toolCalls = mutableListOf<ResponsesToolCall>()
        
        try {
            val output = response["output"] as? JsonArray ?: return toolCalls
            
            for (item in output) {
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                
                if (type == "function_call") {
                    val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    
                    toolCalls.add(
                        ResponsesToolCall(
                            callId = callId,
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

    /**
     * Converts Responses API tool calls to AgentToolCall format.
     */
    fun toAgentToolCalls(responsesToolCalls: List<ResponsesToolCall>): List<AgentToolCall> {
        return responsesToolCalls.map { toolCall ->
            AgentToolCall(
                id = toolCall.callId,
                name = toolCall.name,
                arguments = toolCall.arguments
            )
        }
    }

    /**
     * Creates a tool result response for the Responses API.
     */
    fun createToolResultResponse(
        callId: String,
        result: String,
        isError: Boolean = false
    ): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("function_call_output"),
                "call_id" to JsonPrimitive(callId),
                "output" to JsonPrimitive(result),
                "is_error" to JsonPrimitive(isError)
            )
        )
    }

    /**
     * Formats tool execution results for display.
     */
    fun formatToolResultsForDisplay(toolCalls: List<ResponsesToolCall>, results: List<AgentToolResult>): String {
        val sb = StringBuilder()
        sb.appendLine("**Tool Calls:**")
        sb.appendLine()
        
        toolCalls.forEachIndexed { index, toolCall ->
            val result = results.getOrNull(index)
            val status = when {
                result == null -> "⏳ Pending"
                result.isError -> "❌ Error"
                else -> "✅ Success"
            }
            
            sb.appendLine("**${index + 1}. ${toolCall.name}** $status")
            sb.appendLine("   Arguments: ${toolCall.arguments.take(100)}...")
            
            if (result != null) {
                val output = if (result.isError) {
                    result.error ?: "Unknown error"
                } else {
                    result.output?.take(200) ?: "No output"
                }
                sb.appendLine("   Result: $output")
            }
            sb.appendLine()
        }
        
        return sb.toString().trim()
    }

    /**
     * Validates tool call arguments against a schema.
     */
    fun validateToolCallArguments(toolCall: ResponsesToolCall, schema: JsonObject?): Boolean {
        return try {
            if (schema == null) return true
            
            val arguments = kotlinx.serialization.json.Json.parseToJsonElement(toolCall.arguments) as? JsonObject
                ?: return false
            
            val required = schema["required"] as? JsonArray
            required?.forEach { field ->
                val fieldName = field.jsonPrimitive.contentOrNull
                if (fieldName != null && !arguments.containsKey(fieldName)) {
                    Log.w(TAG, "Missing required field: $fieldName for tool: ${toolCall.name}")
                    return false
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating tool call arguments", e)
            false
        }
    }

    /**
     * Creates a summary of tool calls for logging.
     */
    fun createToolCallsSummary(toolCalls: List<ResponsesToolCall>): String {
        if (toolCalls.isEmpty()) return "No tool calls"
        
        return toolCalls.joinToString(", ") { toolCall ->
            "${toolCall.name}(${toolCall.arguments.take(50)}...)"
        }
    }
}