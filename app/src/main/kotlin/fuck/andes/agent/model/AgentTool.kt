package fuck.andes.agent.model

import org.json.JSONObject

/**
 * Represents a tool available to the agent for performing actions.
 *
 * @property name The unique identifier for the tool.
 * @property description A human-readable description of what the tool does.
 * @property parameters A JSON schema describing the parameters the tool accepts.
 * @property category The category of the tool (e.g., "system", "browser", "media").
 * @property requiresConfirmation Whether the tool requires user confirmation before execution.
 * @property timeout The maximum time in milliseconds the tool can run before timing out.
 */
data class AgentTool(
    val name: String,
    val description: String,
    val parameters: JSONObject = JSONObject(),
    val category: String = "general",
    val requiresConfirmation: Boolean = false,
    val timeout: Long = 30000L,
) {
    /**
     * Converts the tool definition to a JSON object suitable for the API.
     */
    fun toApiJson(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                },
            )
        }
    }

    /**
     * Returns a summary of the tool for logging or display purposes.
     */
    fun summary(): String {
        return "$name: $description (category=$category, requiresConfirmation=$requiresConfirmation)"
    }

    companion object {
        /**
         * Creates a tool definition from a JSON object.
         */
        fun fromJson(json: JSONObject): AgentTool {
            return AgentTool(
                name = json.getString("name"),
                description = json.optString("description", ""),
                parameters = json.optJSONObject("parameters") ?: JSONObject(),
                category = json.optString("category", "general"),
                requiresConfirmation = json.optBoolean("requiresConfirmation", false),
                timeout = json.optLong("timeout", 30000L),
            )
        }
    }
}