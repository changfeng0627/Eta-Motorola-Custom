package fuck.andes.agent.model

/**
 * Represents an event emitted by the agent during execution.
 * These events are used to communicate the agent's state and actions to the UI or other components.
 */
sealed interface AgentEvent {
    /**
     * The agent has started processing a request.
     */
    data object Started : AgentEvent

    /**
     * The agent is currently thinking or planning its next action.
     */
    data object Thinking : AgentEvent

    /**
     * The agent is actively running/executing a tool or action.
     */
    data object Running : AgentEvent

    /**
     * The agent has finished processing and produced a final text response.
     * @param text The final text response from the agent.
     */
    data class Finished(val text: String) : AgentEvent

    /**
     * An error occurred during the agent's execution.
     * @param error The error message or exception.
     */
    data class Error(val error: Throwable) : AgentEvent
}