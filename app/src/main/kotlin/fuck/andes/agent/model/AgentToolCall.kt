package fuck.andes.agent.model

import org.json.JSONObject

/**
 * Represents a call to a tool made by the agent.
 *
 * @property id The unique identifier for this tool call.
 * @property name The name of the tool being called.
 * @property arguments The arguments passed to the tool as a JSON object.
 */
data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
) {
    /**
     * Converts the tool call to a JSON object representation.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", name)
                    put("arguments", arguments.toString())
                },
            )
        }
    }

    /**
     * Converts the tool call to a format suitable for the Anthropic Messages API.
     */
    fun toAnthropicJson(): JSONObject {
        return JSONObject().apply {
            put("type", "tool_use")
            put("id", id)
            put("name", name)
            put("input", arguments)
        }
    }

    companion object {
        /**
         * Creates a tool call from a JSON object.
         */
        fun fromJson(json: JSONObject): AgentToolCall {
            return AgentToolCall(
                id = json.getString("id"),
                name = json.getJSONObject("function").getString("name"),
                arguments = JSONObject(json.getJSONObject("function").getString("arguments")),
            )
        }

        /**
         * Creates a tool call from an Anthropic tool_use JSON object.
         */
        fun fromAnthropicJson(json: JSONObject): AgentToolCall {
            return AgentToolCall(
                id = json.getString("id"),
                name = json.getString("name"),
                arguments = json.getJSONObject("input"),
            )
        }
    }
}