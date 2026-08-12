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
 * Manages ephemeral state for OpenAI Responses API interactions.
 * The Responses API maintains state across multiple requests using a response_id.
 */
object ResponsesEphemeralState {
    private const val TAG = "ResponsesEphemeralState"

    /**
     * Data class representing ephemeral state for a Responses API interaction.
     */
    data class EphemeralState(
        val responseId: String,
        val conversationId: String?,
        val previousResponseId: String?,
        val model: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * Extracts the response_id from a Responses API response for state tracking.
     */
    fun extractResponseId(response: JsonObject): String? {
        return try {
            response["id"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting response_id", e)
            null
        }
    }

    /**
     * Extracts the conversation_id from a Responses API response.
     */
    fun extractConversationId(response: JsonObject): String? {
        return try {
            response["conversation_id"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting conversation_id", e)
            null
        }
    }

    /**
     * Creates a new request body with state tracking for the next request.
     */
    fun addStateToRequest(
        request: JsonObject,
        state: EphemeralState
    ): JsonObject {
        return try {
            val mutableMap = request.toMutableMap()
            
            // Add previous_response_id for state continuity
            if (state.responseId.isNotBlank()) {
                mutableMap["previous_response_id"] = JsonPrimitive(state.responseId)
            }
            
            // Add conversation_id if available
            if (!state.conversationId.isNullOrBlank()) {
                mutableMap["conversation_id"] = JsonPrimitive(state.conversationId)
            }
            
            JsonObject(mutableMap)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding state to request", e)
            request
        }
    }

    /**
     * Creates a new state from a response and existing state.
     */
    fun createStateFromResponse(
        response: JsonObject,
        existingState: EphemeralState?,
        model: String
    ): EphemeralState {
        val responseId = extractResponseId(response) ?: ""
        val conversationId = extractConversationId(response)
        
        return EphemeralState(
            responseId = responseId,
            conversationId = conversationId,
            previousResponseId = existingState?.responseId,
            model = model
        )
    }

    /**
     * Checks if a state is still valid (not too old).
     * States older than 30 minutes are considered invalid.
     */
    fun isStateValid(state: EphemeralState, maxAgeMs: Long = 30 * 60 * 1000): Boolean {
        return (System.currentTimeMillis() - state.createdAt) < maxAgeMs
    }

    /**
     * Cleans up expired states from a list.
     */
    fun cleanupExpiredStates(states: List<EphemeralState>, maxAgeMs: Long = 30 * 60 * 1000): List<EphemeralState> {
        return states.filter { isStateValid(it, maxAgeMs) }
    }

    /**
     * Formats state for debugging.
     */
    fun formatStateForDebug(state: EphemeralState): String {
        return """
            State: {
                responseId: ${state.responseId.take(8)}...,
                conversationId: ${state.conversationId?.take(8) ?: "null"}...,
                previousResponseId: ${state.previousResponseId?.take(8) ?: "null"}...,
                model: ${state.model},
                createdAt: ${state.createdAt}
            }
        """.trimIndent()
    }
}