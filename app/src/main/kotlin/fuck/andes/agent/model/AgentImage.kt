package fuck.andes.agent.model

import android.graphics.Bitmap

/**
 * Represents an image captured by the agent, including its bitmap data and associated metadata.
 *
 * @property bitmap The actual image data as a Bitmap.
 * @property path Optional file path where the image is stored locally.
 * @property timestamp The time at which the image was captured (in milliseconds since epoch).
 */
data class AgentImage(
    val bitmap: Bitmap,
    val path: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /**
     * Returns a string representation of the image, including its dimensions, path, and timestamp.
     */
    override fun toString(): String {
        return "AgentImage(width=${bitmap.width}, height=${bitmap.height}, path=$path, timestamp=$timestamp)"
    }
}