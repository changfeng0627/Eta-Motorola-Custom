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
 * Implementation of the AI model provider that interfaces with Anthropic's Messages API.
 * Supports streaming responses and tool calls.
 */
class AnthropicMessagesProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val model: String = "claude-3-5-sonnet-20241022",
    private val maxTokens: Int = 4096,
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
            Log.e(TAG, "Error calling Anthropic API", e)
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
        val requestBody = buildRequestBody(messages, tools, systemPrompt, temperature, maxTokens)
        val request = buildRequest(requestBody, stream = true)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("API request failed with status ${response.code}: ${response.body?.string()}")
        }

        val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: throw IOException("Empty response body")))
        var currentEvent = ""

        try {
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> {
                        currentEvent = line.removePrefix("event:").trim()
                    }
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotEmpty() && data != "[DONE]") {
                            try {
                                val json = JSONObject(data)
                                parseStreamEvent(currentEvent, json)?.let { emit(it) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing stream event", e)
                            }
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
    ): String {
        return JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens ?: this@AnthropicMessagesProvider.maxTokens)
            if (temperature != null) {
                put("temperature", temperature)
            }
            if (systemPrompt != null) {
                put("system", systemPrompt)
            }
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(message.toAnthropicJson())
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { tool ->
                        put(tool.toApiJson())
                    }
                })
            }
        }.toString()
    }

    private fun buildRequest(body: String, stream: Boolean = false): Request {
        val url = if (stream) {
            "$baseUrl/v1/messages?stream=true"
        } else {
            "$baseUrl/v1/messages"
        }

        return Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .build()
    }

    private fun parseResponse(responseBody: String): AiModelResponse {
        val json = JSONObject(responseBody)
        val content = json.getJSONArray("content")
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<AgentToolCall>()

        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            when (part.getString("type")) {
                "text" -> textParts.add(part.getString("text"))
                "tool_use" -> {
                    toolCalls.add(
                        AgentToolCall(
                            id = part.getString("id"),
                            name = part.getString("name"),
                            arguments = part.getJSONObject("input"),
                        ),
                    )
                }
            }
        }

        return AiModelResponse(
            text = textParts.joinToString("\n"),
            toolCalls = toolCalls,
            usage = parseUsage(json.optJSONObject("usage")),
        )
    }

    private fun parseStreamEvent(eventType: String, json: JSONObject): AiModelStreamEvent? {
        return when (eventType) {
            "message_start" -> {
                val message = json.getJSONObject("message")
                AiModelStreamEvent.Started(
                    id = message.getString("id"),
                    model = message.getString("model"),
                )
            }
            "content_block_start" -> {
                val contentBlock = json.getJSONObject("content_block")
                when (contentBlock.getString("type")) {
                    "text" -> AiModelStreamEvent.TextDelta("")
                    "tool_use" -> AiModelStreamEvent.ToolCallStart(
                        id = contentBlock.getString("id"),
                        name = contentBlock.getString("name"),
                    )
                    else -> null
                }
            }
            "content_block_delta" -> {
                val delta = json.getJSONObject("delta")
                when (delta.getString("type")) {
                    "text_delta" -> AiModelStreamEvent.TextDelta(delta.getString("text"))
                    "input_json_delta" -> AiModelStreamEvent.ToolCallDelta(delta.getString("partial_json"))
                    else -> null
                }
            }
            "content_block_stop" -> AiModelStreamEvent.ContentBlockStop
            "message_delta" -> {
                val delta = json.getJSONObject("delta")
                val stopReason = delta.optString("stop_reason", null)
                val usage = json.optJSONObject("usage")
                AiModelStreamEvent.Finished(
                    stopReason = stopReason,
                    usage = parseUsage(usage),
                )
            }
            "message_stop" -> AiModelStreamEvent.MessageStop
            "error" -> {
                val error = json.getJSONObject("error")
                AiModelStreamEvent.Error(
                    message = error.getString("message"),
                    type = error.optString("type", "unknown"),
                )
            }
            else -> null
        }
    }

    private fun parseUsage(json: JSONObject?): AiModelUsage? {
        if (json == null) return null
        return AiModelUsage(
            promptTokens = json.optInt("input_tokens", 0),
            completionTokens = json.optInt("output_tokens", 0),
            totalTokens = json.optInt("input_tokens", 0) + json.optInt("output_tokens", 0),
        )
    }

    companion object {
        private const val TAG = "AnthropicMessagesProvider"
    }
}