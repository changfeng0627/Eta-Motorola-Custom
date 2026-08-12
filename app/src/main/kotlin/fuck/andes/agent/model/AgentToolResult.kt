package fuck.andes.agent.model

import org.json.JSONObject

/**
 * Represents the result of a tool execution.
 *
 * @property toolCallId The ID of the tool call that produced this result.
 * @property output The output of the tool execution, which can be a string or a JSON object.
 * @property error An optional error message if the tool execution failed.
 * @property durationMs The time taken to execute the tool in milliseconds.
 * @property screenshots Optional list of screenshots captured during execution.
 */
data class AgentToolResult(
    val toolCallId: String,
    val output: Any,
    val error: String? = null,
    val durationMs: Long = 0L,
    val screenshots: List<AgentImage> = emptyList(),
) {
    /**
     * Whether the tool execution was successful (no error).
     */
    val isSuccess: Boolean get() = error == null

    /**
     * Returns the output as a string, converting from JSON if necessary.
     */
    fun outputAsString(): String {
        return when (output) {
            is String -> output as String
            is JSONObject -> (output as JSONObject).toString(2)
            else -> output.toString()
        }
    }

    /**
     * Converts the tool result to a JSON object representation.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("tool_call_id", toolCallId)
            put("output", output)
            if (error != null) {
                put("error", error)
            }
            put("duration_ms", durationMs)
            if (screenshots.isNotEmpty()) {
                put(
                    "screenshots",
                    org.json.JSONArray().apply {
                        screenshots.forEach { image ->
                            put(image.path ?: "in-memory")
                        }
                    },
                )
            }
        }
    }

    /**
     * Converts the tool result to a format suitable for the Anthropic Messages API.
     */
    fun toAnthropicJson(): JSONObject {
        return JSONObject().apply {
            put("type", "tool_result")
            put("tool_use_id", toolCallId)
            put("content", if (isSuccess) outputAsString() else "Error: $error")
            if (!isSuccess) {
                put("is_error", true)
            }
        }
    }

    companion object {
        /**
         * Creates a successful tool result.
         */
        fun success(toolCallId: String, output: Any, durationMs: Long = 0L) = AgentToolResult(
            toolCallId = toolCallId,
            output = output,
            durationMs = durationMs,
        )

        /**
         * Creates a failed tool result.
         */
        fun error(toolCallId: String, error: String, durationMs: Long = 0L) = AgentToolResult(
            toolCallId = toolCallId,
            output = "",
            error = error,
            durationMs = durationMs,
        )

        /**
         * Creates a tool result from a JSON object.
         */
        fun fromJson(json: JSONObject): AgentToolResult {
            return AgentToolResult(
                toolCallId = json.getString("tool_call_id"),
                output = json.opt("output") ?: "",
                error = json.optString("error", null),
                durationMs = json.optLong("duration_ms", 0L),
            )
        }
    }
}