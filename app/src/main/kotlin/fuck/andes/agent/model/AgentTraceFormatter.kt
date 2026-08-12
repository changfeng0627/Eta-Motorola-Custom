package fuck.andes.agent.model

/**
 * Utility object for formatting agent trace events into human-readable strings.
 * This is useful for logging, debugging, and creating summaries of agent activity.
 */
object AgentTraceFormatter {

    /**
     * Maximum length for truncated values in summaries.
     */
    private const val MAX_VALUE_LENGTH = 100

    /**
     * Format a tool call into a concise summary string.
     *
     * @param toolCall The tool call to format.
     * @return A formatted string representing the tool call.
     */
    fun formatToolCall(toolCall: AgentToolCall): String {
        val args = toolCall.arguments.keys().asSequence().joinToString(", ") { key ->
            val value = toolCall.arguments.get(key)
            val valueStr = value.toString()
            val truncated = if (valueStr.length > MAX_VALUE_LENGTH) {
                valueStr.take(MAX_VALUE_LENGTH) + "..."
            } else {
                valueStr
            }
            "$key=$truncated"
        }
        return "${toolCall.name}($args)"
    }

    /**
     * Format a tool result into a concise summary string.
     *
     * @param result The tool result to format.
     * @return A formatted string representing the tool result.
     */
    fun formatToolResult(result: AgentToolResult): String {
        return if (result.isSuccess) {
            val output = result.outputAsString()
            val truncated = if (output.length > MAX_VALUE_LENGTH) {
                output.take(MAX_VALUE_LENGTH) + "..."
            } else {
                output
            }
            "Success($truncated)"
        } else {
            "Error(${result.error})"
        }
    }

    /**
     * Format an agent message into a concise summary string.
     *
     * @param message The message to format.
     * @return A formatted string representing the message.
     */
    fun formatMessage(message: AgentMessage): String {
        return when (message.role) {
            AgentMessage.Role.User -> {
                val text = message.text()
                val truncated = if (text.length > MAX_VALUE_LENGTH) {
                    text.take(MAX_VALUE_LENGTH) + "..."
                } else {
                    text
                }
                "User: $truncated"
            }
            AgentMessage.Role.Assistant -> {
                val text = message.text()
                val truncated = if (text.length > MAX_VALUE_LENGTH) {
                    text.take(MAX_VALUE_LENGTH) + "..."
                } else {
                    text
                }
                "Assistant: $truncated"
            }
            AgentMessage.Role.System -> "System: [${message.text().length} chars]"
        }
    }

    /**
     * Format an agent event into a concise summary string.
     *
     * @param event The event to format.
     * @return A formatted string representing the event.
     */
    fun formatEvent(event: AgentEvent): String {
        return when (event) {
            is AgentEvent.Started -> "Agent started"
            is AgentEvent.Thinking -> "Agent thinking..."
            is AgentEvent.Running -> "Agent running..."
            is AgentEvent.Finished -> "Agent finished: ${event.text.take(MAX_VALUE_LENGTH)}${if (event.text.length > MAX_VALUE_LENGTH) "..." else ""}"
            is AgentEvent.Error -> "Agent error: ${event.error.message ?: "Unknown error"}"
        }
    }

    /**
     * Format a duration in milliseconds into a human-readable string.
     *
     * @param durationMs The duration in milliseconds.
     * @return A formatted string like "123ms", "1.2s", "2m 30s".
     */
    fun formatDuration(durationMs: Long): String {
        return when {
            durationMs < 1000 -> "${durationMs}ms"
            durationMs < 60_000 -> "${durationMs / 1000.0}s"
            else -> {
                val minutes = durationMs / 60_000
                val seconds = (durationMs % 60_000) / 1000
                "${minutes}m ${seconds}s"
            }
        }
    }

    /**
     * Sensitive parameter names that should be redacted in logs.
     */
    private val SENSITIVE_PARAM_NAMES = setOf(
        "api_key",
        "apikey",
        "api-key",
        "secret",
        "password",
        "token",
        "authorization",
        "auth",
    )

    /**
     * Redact sensitive information from a tool call for safe logging.
     *
     * @param toolCall The tool call to redact.
     * @return A new tool call with sensitive parameters redacted.
     */
    fun redactSensitiveInfo(toolCall: AgentToolCall): AgentToolCall {
        val redactedArgs = org.json.JSONObject()
        toolCall.arguments.keys().forEach { key ->
            val value = toolCall.arguments.get(key)
            if (SENSITIVE_PARAM_NAMES.any { sensitive -> key.lowercase().contains(sensitive) }) {
                redactedArgs.put(key, "[REDACTED]")
            } else {
                redactedArgs.put(key, value)
            }
        }
        return toolCall.copy(arguments = redactedArgs)
    }

    /**
     * Common tool names that should be formatted with special handling.
     */
    private val SPECIAL_FORMAT_TOOLS = setOf(
        "click",
        "tap",
        "scroll",
        "swipe",
        "type",
        "input",
        "screenshot",
        "screen_capture",
        "launch_app",
        "open_app",
        "wait",
        "wait_for_selector",
    )

    /**
     * Check if a tool name requires special formatting.
     *
     * @param toolName The name of the tool.
     * @return True if the tool requires special formatting.
     */
    fun requiresSpecialFormatting(toolName: String): Boolean {
        return toolName.lowercase() in SPECIAL_FORMAT_TOOLS
    }
}