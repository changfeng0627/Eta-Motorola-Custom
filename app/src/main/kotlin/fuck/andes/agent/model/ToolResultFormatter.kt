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
 * Formatter for tool execution results.
 * Handles formatting, truncation, and display of tool results.
 */
object ToolResultFormatter {
    private const val TAG = "ToolResultFormatter"
    private const val MAX_RESULT_LENGTH = 10000
    private const val TRUNCATION_SUFFIX = "\n\n[Result truncated...]"

    /**
     * Formats a tool result for display in the chat interface.
     */
    fun formatForDisplay(result: AgentToolResult, toolName: String): String {
        return try {
            val sb = StringBuilder()
            
            if (result.isError) {
                sb.appendLine("**Error in $toolName:**")
                sb.appendLine(result.error ?: "Unknown error")
            } else {
                val output = result.output ?: "No output"
                val formattedOutput = truncateAndFormat(output, MAX_RESULT_LENGTH)
                
                sb.appendLine("**$toolName Result:**")
                sb.appendLine(formattedOutput)
            }
            
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting tool result", e)
            "Error formatting tool result for $toolName"
        }
    }

    /**
     * Formats a tool result for logging/debugging.
     */
    fun formatForLogging(result: AgentToolResult, toolName: String, durationMs: Long): String {
        return try {
            val status = if (result.isError) "❌ FAILED" else "✅ SUCCESS"
            val outputPreview = result.output?.take(200) ?: "No output"
            val errorPreview = result.error?.take(200) ?: ""
            
            """
                Tool: $toolName
                Status: $status
                Duration: ${durationMs}ms
                Output: $outputPreview${if (result.output?.length ?: 0 > 200) "..." else ""}
                Error: $errorPreview${if (result.error?.length ?: 0 > 200) "..." else ""}
            """.trimIndent()
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting tool result for logging", e)
            "Error formatting tool result for $toolName"
        }
    }

    /**
     * Formats multiple tool results for batch display.
     */
    fun formatBatchResults(results: List<Pair<String, AgentToolResult>>): String {
        if (results.isEmpty()) return "No tool results"
        
        val sb = StringBuilder()
        sb.appendLine("**Tool Results (${results.size}):**")
        sb.appendLine()
        
        results.forEachIndexed { index, (toolName, result) ->
            sb.appendLine("**${index + 1}. $toolName**")
            val formatted = formatForDisplay(result, toolName)
            // Indent the result
            formatted.lines().forEach { line ->
                sb.appendLine("   $line")
            }
            sb.appendLine()
        }
        
        return sb.toString().trim()
    }

    /**
     * Extracts a summary from a tool result for quick display.
     */
    fun extractSummary(result: AgentToolResult, maxLength: Int = 200): String {
        return try {
            if (result.isError) {
                val error = result.error ?: "Unknown error"
                return "Error: ${truncate(error, maxLength)}"
            }
            
            val output = result.output ?: "No output"
            truncate(output, maxLength)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting summary", e)
            "Error extracting summary"
        }
    }

    /**
     * Validates if a tool result is successful.
     */
    fun isSuccess(result: AgentToolResult): Boolean {
        return !result.isError && result.output != null
    }

    /**
     * Gets the size of a tool result in characters.
     */
    fun getResultSize(result: AgentToolResult): Int {
        val outputSize = result.output?.length ?: 0
        val errorSize = result.error?.length ?: 0
        return outputSize + errorSize
    }

    /**
     * Formats tool results as JSON for API responses.
     */
    fun formatAsJson(results: List<AgentToolResult>): JsonArray {
        return JsonArray(
            results.map { result ->
                JsonObject(
                    mapOf(
                        "isError" to JsonPrimitive(result.isError),
                        "output" to JsonPrimitive(result.output ?: ""),
                        "error" to JsonPrimitive(result.error ?: "")
                    )
                )
            }
        )
    }

    /**
     * Truncates text to a maximum length and adds suffix if truncated.
     */
    private fun truncateAndFormat(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength) + TRUNCATION_SUFFIX
        } else {
            text
        }
    }

    /**
     * Simple truncation without suffix.
     */
    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength) + "..."
        } else {
            text
        }
    }
}