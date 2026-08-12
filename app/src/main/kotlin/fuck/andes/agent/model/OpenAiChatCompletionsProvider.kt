package fuck.andes.agent.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Implementation of the AI model provider that interfaces with OpenAI's Chat Completions API.
 * Supports streaming responses and tool calls.
 */
class OpenAiChatCompletionsProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val model: String = "gpt-4o",
    private val providerType: String = "openai",
) : AiModelProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun chat(
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        systemPrompt: String?,
        temperature: Double?,
        maxTokens: Int?,
    ): AiModelResponse = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val requestBody = buildRequestBody(messages, tools, systemPrompt, temperature, maxTokens)
            val request = buildRequest(requestBody)

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("API request failed with status ${response.code}: ${response.body?.string()}")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            parseResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling OpenAI API", e)
            throw e
        }
    }

    override fun chatStream(
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        systemPrompt: String?,
        temperature: Double?,
        maxTokens: Int?,
    ): Flow<AiModelStreamEvent> = flow {
        val requestBody = buildRequestBody(messages, tools, systemPrompt, temperature, maxTokens, stream = true)
        val request = buildRequest(requestBody)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("API request failed with status ${response.code}: ${response.body?.string()}")
        }

        val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: throw IOException("Empty response body")))

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data.isNotEmpty() && data != "[DONE]") {
                        try {
                            val json = JSONObject(data)
                            parseStreamChunk(json)?.let { emit(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing stream chunk", e)
                        }
                    }
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        systemPrompt: String?,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean = false,
    ): String {
        return JSONObject().apply {
            put("model", model)
            if (stream) {
                put("stream", true)
            }
            if (temperature != null) {
                put("temperature", temperature)
            }
            if (maxTokens != null) {
                put("max_tokens", maxTokens)
            }

            // Build messages array
            put("messages", JSONArray().apply {
                // Add system prompt if provided
                if (systemPrompt != null) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                // Add conversation messages
                messages.forEach { message ->
                    put(message.toJson())
                }
            })

            // Add tools if provided
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { tool ->
                        put(tool.toApiJson())
                    }
                })
            }
        }.toString()
    }

    private fun buildRequest(body: String): Request {
        val url = buildUrl()
        return Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
    }

    private fun buildUrl(): String {
        return when (providerType.lowercase()) {
            "openrouter" -> "$baseUrl/api/v1/chat/completions"
            "together" -> "$baseUrl/v1/chat/completions"
            "groq" -> "$baseUrl/openai/v1/chat/completions"
            else -> "$baseUrl/v1/chat/completions"
        }
    }

    private fun parseResponse(responseBody: String): AiModelResponse {
        val json = JSONObject(responseBody)
        val choices = json.getJSONArray("choices")

        if (choices.length() == 0) {
            return AiModelResponse(text = "", toolCalls = emptyList())
        }

        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")
        val content = message.optString("content", "") ?: ""
        val toolCalls = mutableListOf<AgentToolCall>()

        // Parse tool calls if present
        if (message.has("tool_calls")) {
            val toolCallsArray = message.getJSONArray("tool_calls")
            for (i in 0 until toolCallsArray.length()) {
                val toolCall = toolCallsArray.getJSONObject(i)
                val function = toolCall.getJSONObject("function")
                toolCalls.add(
                    AgentToolCall(
                        id = toolCall.getString("id"),
                        name = function.getString("name"),
                        arguments = JSONObject(function.getString("arguments")),
                    ),
                )
            }
        }

        return AiModelResponse(
            text = content,
            toolCalls = toolCalls,
            usage = parseUsage(json.optJSONObject("usage")),
            finishReason = choice.optString("finish_reason", null),
        )
    }

    private fun parseStreamChunk(json: JSONObject): AiModelStreamEvent? {
        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) return null

        val choice = choices.getJSONObject(0)
        val delta = choice.optJSONObject("delta") ?: return null
        val finishReason = choice.optString("finish_reason", null)

        // Handle finish reason
        if (finishReason != null && finishReason != "null") {
            return AiModelStreamEvent.Finished(
                stopReason = finishReason,
                usage = parseUsage(json.optJSONObject("usage")),
            )
        }

        // Handle content delta
        if (delta.has("content")) {
            val content = delta.getString("content")
            if (content.isNotEmpty()) {
                return AiModelStreamEvent.TextDelta(content)
            }
        }

        // Handle tool calls
        if (delta.has("tool_calls")) {
            val toolCalls = delta.getJSONArray("tool_calls")
            if (toolCalls.length() > 0) {
                val toolCall = toolCalls.getJSONObject(0)
                val index = toolCall.getInt("index")

                // Check if this is a new tool call or a continuation
                if (toolCall.has("id")) {
                    val function = toolCall.getJSONObject("function")
                    return AiModelStreamEvent.ToolCallStart(
                        id = toolCall.getString("id"),
                        name = function.getString("name"),
                    )
                } else if (toolCall.has("function")) {
                    val function = toolCall.getJSONObject("function")
                    if (function.has("arguments")) {
                        return AiModelStreamEvent.ToolCallDelta(function.getString("arguments"))
                    }
                }
            }
        }

        return null
    }

    private fun parseUsage(json: JSONObject?): AiModelUsage? {
        if (json == null) return null
        return AiModelUsage(
            promptTokens = json.optInt("prompt_tokens", 0),
            completionTokens = json.optInt("completion_tokens", 0),
            totalTokens = json.optInt("total_tokens", 0),
        )
    }

    companion object {
        private const val TAG = "OpenAiChatCompletionsProvider"
    }
}