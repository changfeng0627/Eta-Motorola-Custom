package fuck.andes.agent.model

import fuck.andes.agent.model.AgentMessage.Companion.MAX_TEXT_LENGTH
import fuck.andes.agent.model.AgentMessage.Companion.TRUNCATION_SUFFIX
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a message exchanged between the user and the agent.
 *
 * @property role The role of the message sender (user, assistant, system).
 * @property content The content of the message, which can be text or a list of content parts (e.g., text and images).
 */
data class AgentMessage(
    val role: Role,
    val content: Any,
) {
    enum class Role {
        User,
        Assistant,
        System,
    }

    companion object {
        const val MAX_TEXT_LENGTH = 100000
        const val TRUNCATION_SUFFIX = "...[truncated]"

        /**
         * Creates a user message with the given text content.
         */
        fun user(text: String) = AgentMessage(Role.User, text)

        /**
         * Creates an assistant message with the given text content.
         */
        fun assistant(text: String) = AgentMessage(Role.Assistant, text)

        /**
         * Creates a system message with the given text content.
         */
        fun system(text: String) = AgentMessage(Role.System, text)

        /**
         * Creates a user message with both text and an image.
         */
        fun user(text: String, image: AgentImage) = AgentMessage(
            Role.User,
            listOf(
                ContentPart.Text(text),
                ContentPart.Image(image),
            ),
        )
    }

    sealed interface ContentPart {
        data class Text(val text: String) : ContentPart
        data class Image(val image: AgentImage) : ContentPart
    }

    /**
     * Returns the text content of the message, concatenating all text parts if the content is a list.
     */
    fun text(): String {
        return when (content) {
            is String -> content
            is List<*> -> content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
            else -> ""
        }
    }

    /**
     * Returns all image parts contained in the message content, if any.
     */
    fun images(): List<AgentImage> {
        return when (content) {
            is List<*> -> content.filterIsInstance<ContentPart.Image>().map { it.image }
            else -> emptyList()
        }
    }

    /**
     * Converts the message to a JSON object representation.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("role", role.name.lowercase())
            put("content", contentToJson())
        }
    }

    private fun contentToJson(): Any {
        return when (content) {
            is String -> truncateText(content as String)
            is List<*> -> {
                val parts = content as List<ContentPart>
                JSONArray().apply {
                    parts.forEach { part ->
                        when (part) {
                            is ContentPart.Text -> {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", truncateText(part.text))
                                    },
                                )
                            }
                            is ContentPart.Image -> {
                                put(
                                    JSONObject().apply {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            JSONObject().apply {
                                                put("url", "data:image/jpeg;base64,${part.image.bitmap.toBase64()}")
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            else -> ""
        }
    }

    private fun truncateText(text: String): String {
        return if (text.length > MAX_TEXT_LENGTH) {
            text.take(MAX_TEXT_LENGTH) + TRUNCATION_SUFFIX
        } else {
            text
        }
    }

    /**
     * Converts the message to a format suitable for the Anthropic Messages API.
     */
    fun toAnthropicJson(): JSONObject {
        return JSONObject().apply {
            put("role", role.name.lowercase())
            put("content", anthropicContentToJson())
        }
    }

    private fun anthropicContentToJson(): Any {
        return when (content) {
            is String -> content as String
            is List<*> -> {
                val parts = content as List<ContentPart>
                JSONArray().apply {
                    parts.forEach { part ->
                        when (part) {
                            is ContentPart.Text -> {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", part.text)
                                    },
                                )
                            }
                            is ContentPart.Image -> {
                                put(
                                    JSONObject().apply {
                                        put("type", "image")
                                        put(
                                            "source",
                                            JSONObject().apply {
                                                put("type", "base64")
                                                put("media_type", "image/jpeg")
                                                put("data", part.image.bitmap.toBase64())
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            else -> ""
        }
    }

    private fun android.graphics.Bitmap.toBase64(): String {
        val stream = java.io.ByteArrayOutputStream()
        compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }
}