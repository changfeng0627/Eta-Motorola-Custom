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
 * Utility for formatting citations from OpenAI Responses API.
 * Responses API can include web search results as citations.
 */
object ResponsesCitationFormatter {
    private const val TAG = "ResponsesCitationFormatter"

    /**
     * Extracts and formats citations from a Responses API response.
     */
    fun extractCitationsFromResponse(response: JsonObject): List<Citation> {
        val citations = mutableListOf<Citation>()
        
        try {
            // Check for web search results in the response
            val output = response["output"] as? JsonArray ?: return citations
            
            for (item in output) {
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                
                when (type) {
                    "web_search_call" -> {
                        // Extract web search results as citations
                        val results = obj["results"] as? JsonArray
                        results?.forEach { result ->
                            val resultObj = result.jsonObject
                            citations.add(
                                Citation(
                                    url = resultObj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                                    title = resultObj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                                    snippet = resultObj["snippet"]?.jsonPrimitive?.contentOrNull ?: "",
                                    source = "web_search"
                                )
                            )
                        }
                    }
                    "file_search_call" -> {
                        // Extract file search results as citations
                        val results = obj["results"] as? JsonArray
                        results?.forEach { result ->
                            val resultObj = result.jsonObject
                            citations.add(
                                Citation(
                                    url = resultObj["file_id"]?.jsonPrimitive?.contentOrNull ?: "",
                                    title = resultObj["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                                    snippet = resultObj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                                    source = "file_search"
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting citations from response", e)
        }
        
        return citations
    }

    /**
     * Formats citations for display in the UI.
     */
    fun formatCitationsForDisplay(citations: List<Citation>): String {
        if (citations.isEmpty()) return ""
        
        val sb = StringBuilder()
        sb.appendLine("**Sources:**")
        sb.appendLine()
        
        citations.forEachIndexed { index, citation ->
            sb.appendLine("${index + 1}. [${citation.title}](${citation.url})")
            if (citation.snippet.isNotBlank()) {
                sb.appendLine("   ${citation.snippet.take(200)}...")
            }
            sb.appendLine()
        }
        
        return sb.toString().trim()
    }

    /**
     * Formats citations as markdown links.
     */
    fun formatCitationsAsMarkdownLinks(citations: List<Citation>): String {
        if (citations.isEmpty()) return ""
        
        return citations.map { citation ->
            "[${citation.title}](${citation.url})"
        }.joinToString(", ")
    }

    /**
     * Deduplicates citations based on URL.
     */
    fun deduplicateCitations(citations: List<Citation>): List<Citation> {
        return citations.distinctBy { it.url }
    }

    /**
     * Filters citations by source type.
     */
    fun filterBySource(citations: List<Citation>, source: String): List<Citation> {
        return citations.filter { it.source == source }
    }

    /**
     * Data class representing a citation.
     */
    data class Citation(
        val url: String,
        val title: String,
        val snippet: String,
        val source: String
    )
}