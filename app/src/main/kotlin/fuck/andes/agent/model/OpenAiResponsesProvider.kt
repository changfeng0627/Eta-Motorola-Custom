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
 * Implementation of the AI model provider that interfaces with OpenAI's Responses API.
 * This API supports more advanced features like parallel tool calls and web search.
 */
class OpenAiResponsesProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val model: String = "gpt-4o",
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
            Log.e(TAG, "Error calling OpenAI Responses API", e)
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
                            parseStreamEvent(json)?.let { emit(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing stream event", e)
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
                put("max_output_tokens", maxTokens)
            }

            // Build input array (Responses API uses "input" instead of "messages")
            put("input", JSONArray().apply {
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
                        put(convertToolToResponsesFormat(tool))
                    }
                })
            }
        }.toString()
    }

    private fun convertToolToResponsesFormat(tool: AgentTool): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        }
    }

    private fun buildRequest(body: String): Request {
        return Request.Builder()
            .url("$baseUrl/v1/responses")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
    }

    private fun parseResponse(responseBody: String): AiModelResponse {
        val json = JSONObject(responseBody)
        val output = json.getJSONArray("output")
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<AgentToolCall>()

        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            when (item.getString("type")) {
                "message" -> {
                    val content = item.getJSONArray("content")
                    for (j in 0 until content.length()) {
                        val contentItem = content.getJSONObject(j)
                        if (contentItem.getString("type") == "output_text") {
                            textParts.add(contentItem.getString("text"))
                        }
                    }
                }
                "function_call" -> {
                    toolCalls.add(
                        AgentToolCall(
                            id = item.getString("call_id"),
                            name = item.getString("name"),
                            arguments = JSONObject(item.getString("arguments")),
                        ),
                    )
                }
                "web_search_call" -> {
                    // Handle web search results
                    val results = item.optJSONArray("results")
                    if (results != null) {
                        for (j in 0 until results.length()) {
                            val searchResult = results.getJSONObject(j)
                            textParts.add("[Web Search: ${searchResult.optString("title", "")}]")
                            textParts.add(searchResult.optString("snippet", ""))
                        }
                    }
                }
            }
        }

        return AiModelResponse(
            text = textParts.joinToString("\n"),
            toolCalls = toolCalls,
            usage = parseUsage(json.optJSONObject("usage")),
        )
    }

    private fun parseStreamEvent(json: JSONObject): AiModelStreamEvent? {
        val type = json.getString("type")

        return when (type) {
            "response.created" -> {
                val response = json.getJSONObject("response")
                AiModelStreamEvent.Started(
                    id = response.getString("id"),
                    model = response.getString("model"),
                )
            }
            "response.output_item.added" -> {
                val item = json.getJSONObject("item")
                when (item.getString("type")) {
                    "message" -> AiModelStreamEvent.TextDelta("")
                    "function_call" -> AiModelStreamEvent.ToolCallStart(
                        id = item.getString("call_id"),
                        name = item.getString("name"),
                    )
                    else -> null
                }
            }
            "response.content_part.added" -> {
                val part = json.getJSONObject("part")
                if (part.getString("type") == "output_text") {
                    AiModelStreamEvent.TextDelta("")
                } else {
                    null
                }
            }
            "response.output_text.delta" -> {
                val delta = json.getString("delta")
                AiModelStreamEvent.TextDelta(delta)
            }
            "response.function_call_arguments.delta" -> {
                val delta = json.getString("delta")
                AiModelStreamEvent.ToolCallDelta(delta)
            }
            "response.completed" -> {
                val response = json.getJSONObject("response")
                val usage = response.optJSONObject("usage")
                AiModelStreamEvent.Finished(
                    stopReason = response.optString("status", "completed"),
                    usage = parseUsage(usage),
                )
            }
            "response.failed" -> {
                val response = json.getJSONObject("response")
                val error = response.optJSONObject("error")
                AiModelStreamEvent.Error(
                    message = error?.optString("message", "Unknown error") ?: "Unknown error",
                    type = error?.optString("type", "unknown") ?: "unknown",
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
        private const val TAG = "OpenAiResponsesProvider"
    }
}