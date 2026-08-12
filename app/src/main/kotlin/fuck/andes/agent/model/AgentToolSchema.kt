package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Utility object for building tool schemas (JSON definitions) used by the agent.
 */
object AgentToolSchema {

    /**
     * Enum representing the coordinate space for tool parameters.
     */
    enum class CoordinateSpace(val value: String) {
        SCREENSHOT("screenshot"),
        SCREEN("screen"),
    }

    /**
     * Creates a function tool schema with the specified properties.
     *
     * @param name The name of the function.
     * @param description A description of what the function does.
     * @param parameters A JSON object defining the function's parameters.
     * @return A JSON object representing the function tool schema.
     */
    fun function(
        name: String,
        description: String,
        parameters: JSONObject = JSONObject(),
    ): JSONObject {
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
     * Creates a parameter schema for a string property.
     *
     * @param name The parameter name.
     * @param description The parameter description.
     * @param required Whether the parameter is required.
     * @param enumValues Optional list of allowed values.
     * @return A JSON object representing the string parameter.
     */
    fun stringParameter(
        name: String,
        description: String,
        required: Boolean = true,
        enumValues: List<String>? = null,
    ): JSONObject {
        return JSONObject().apply {
            put("type", "string")
            put("description", description)
            if (enumValues != null) {
                put("enum", JSONArray(enumValues))
            }
            if (required) {
                put("required", true)
            }
        }
    }

    /**
     * Creates a parameter schema for a number property.
     *
     * @param name The parameter name.
     * @param description The parameter description.
     * @param required Whether the parameter is required.
     * @param minimum Optional minimum value.
     * @param maximum Optional maximum value.
     * @return A JSON object representing the number parameter.
     */
    fun numberParameter(
        name: String,
        description: String,
        required: Boolean = true,
        minimum: Number? = null,
        maximum: Number? = null,
    ): JSONObject {
        return JSONObject().apply {
            put("type", "number")
            put("description", description)
            if (required) {
                put("required", true)
            }
            if (minimum != null) {
                put("minimum", minimum)
            }
            if (maximum != null) {
                put("maximum", maximum)
            }
        }
    }

    /**
     * Creates a parameter schema for a boolean property.
     *
     * @param name The parameter name.
     * @param description The parameter description.
     * @param required Whether the parameter is required.
     * @return A JSON object representing the boolean parameter.
     */
    fun booleanParameter(
        name: String,
        description: String,
        required: Boolean = true,
    ): JSONObject {
        return JSONObject().apply {
            put("type", "boolean")
            put("description", description)
            if (required) {
                put("required", true)
            }
        }
    }

    /**
     * Creates a parameter schema for an array property.
     *
     * @param name The parameter name.
     * @param description The parameter description.
     * @param items The schema for the array items.
     * @param required Whether the parameter is required.
     * @return A JSON object representing the array parameter.
     */
    fun arrayParameter(
        name: String,
        description: String,
        items: JSONObject,
        required: Boolean = true,
    ): JSONObject {
        return JSONObject().apply {
            put("type", "array")
            put("description", description)
            put("items", items)
            if (required) {
                put("required", true)
            }
        }
    }

    /**
     * Creates a parameter schema for an object property.
     *
     * @param name The parameter name.
     * @param description The parameter description.
     * @param properties The schema for the object's properties.
     * @param required Whether the parameter is required.
     * @return A JSON object representing the object parameter.
     */
    fun objectParameter(
        name: String,
        description: String,
        properties: JSONObject,
        required: Boolean = true,
    ): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("description", description)
            put("properties", properties)
            if (required) {
                put("required", true)
            }
        }
    }

    /**
     * Creates a complete parameter schema object with the given parameters.
     *
     * @param params Variable number of parameter JSON objects.
     * @return A JSON object containing the parameters schema.
     */
    fun parametersSchema(vararg params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    params.forEach { param ->
                        val name = param.keys().next()
                        put(name, param.getJSONObject(name))
                    }
                },
            )
            put(
                "required",
                JSONArray().apply {
                    params.forEach { param ->
                        val name = param.keys().next()
                        if (param.getJSONObject(name).optBoolean("required", false)) {
                            put(name)
                        }
                    }
                },
            )
        }
    }
}