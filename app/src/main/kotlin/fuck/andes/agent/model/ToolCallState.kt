package fuck.andes.agent.model

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Manages the state of tool calls during agent execution.
 * Tracks the lifecycle of tool calls from creation to completion.
 */
object ToolCallState {
    private const val TAG = "ToolCallState"

    /**
     * Data class representing the state of a tool call.
     */
    data class State(
        val callId: String,
        val toolName: String,
        val arguments: String,
        val status: Status,
        val result: AgentToolResult? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
        val error: String? = null
    )

    /**
     * Enum representing the possible states of a tool call.
     */
    enum class Status {
        PENDING,      // Tool call created but not yet started
        RUNNING,      // Tool call is currently executing
        COMPLETED,    // Tool call completed successfully
        FAILED,       // Tool call failed with an error
        CANCELLED     // Tool call was cancelled
    }

    /**
     * Creates a new tool call state.
     */
    fun create(
        callId: String,
        toolName: String,
        arguments: String
    ): State {
        return State(
            callId = callId,
            toolName = toolName,
            arguments = arguments,
            status = Status.PENDING
        )
    }

    /**
     * Updates the state to RUNNING.
     */
    fun startExecution(state: State): State {
        return state.copy(status = Status.RUNNING)
    }

    /**
     * Updates the state to COMPLETED with a result.
     */
    fun completeExecution(state: State, result: AgentToolResult): State {
        return state.copy(
            status = Status.COMPLETED,
            result = result,
            completedAt = System.currentTimeMillis()
        )
    }

    /**
     * Updates the state to FAILED with an error message.
     */
    fun failExecution(state: State, error: String): State {
        return state.copy(
            status = Status.FAILED,
            error = error,
            completedAt = System.currentTimeMillis()
        )
    }

    /**
     * Updates the state to CANCELLED.
     */
    fun cancelExecution(state: State): State {
        return state.copy(
            status = Status.CANCELLED,
            completedAt = System.currentTimeMillis()
        )
    }

    /**
     * Creates a tool call state from a JSON object (e.g., from agent message).
     */
    fun fromJson(json: JsonObject): State? {
        return try {
            val callId = json["callId"]?.jsonPrimitive?.contentOrNull ?: return null
            val toolName = json["toolName"]?.jsonPrimitive?.contentOrNull ?: return null
            val arguments = json["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val statusStr = json["status"]?.jsonPrimitive?.contentOrNull ?: "PENDING"
            
            val status = try {
                Status.valueOf(statusStr)
            } catch (e: IllegalArgumentException) {
                Status.PENDING
            }
            
            State(
                callId = callId,
                toolName = toolName,
                arguments = arguments,
                status = status
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating state from JSON", e)
            null
        }
    }

    /**
     * Converts the state to a JSON object for serialization.
     */
    fun toJson(state: State): JsonObject {
        return JsonObject(
            mapOf(
                "callId" to JsonPrimitive(state.callId),
                "toolName" to JsonPrimitive(state.toolName),
                "arguments" to JsonPrimitive(state.arguments),
                "status" to JsonPrimitive(state.status.name),
                "createdAt" to JsonPrimitive(state.createdAt),
                "completedAt" to JsonPrimitive(state.completedAt ?: 0L),
                "error" to JsonPrimitive(state.error ?: "")
            )
        )
    }

    /**
     * Formats the state for debugging.
     */
    fun formatForDebug(state: State): String {
        val duration = state.completedAt?.let { it - state.createdAt } ?: 0L
        return """
            Tool Call State: {
                callId: ${state.callId.take(8)}...,
                toolName: ${state.toolName},
                status: ${state.status},
                duration: ${duration}ms,
                error: ${state.error ?: "none"}
            }
        """.trimIndent()
    }

    /**
     * Checks if the state is in a terminal state (completed, failed, or cancelled).
     */
    fun isTerminalState(state: State): Boolean {
        return state.status in listOf(Status.COMPLETED, Status.FAILED, Status.CANCELLED)
    }

    /**
     * Calculates the execution duration in milliseconds.
     */
    fun getExecutionDuration(state: State): Long {
        return state.completedAt?.let { it - state.createdAt } ?: (System.currentTimeMillis() - state.createdAt)
    }
}