package fuck.andes.agent.model

import android.util.Log

/**
 * Utility object for safely managing custom HTTP headers in API requests.
 * Filters out prohibited headers and sanitizes sensitive headers for logging.
 */
object CustomHeaderFilter {

    private const val TAG = "CustomHeaderFilter"

    /**
     * Headers that are prohibited from being set by users for security reasons.
     * These headers are either critical for API functionality or pose security risks.
     */
    private val PROHIBITED_HEADERS = setOf(
        "authorization",
        "x-api-key",
        "api-key",
        "x-auth-token",
        "cookie",
        "set-cookie",
        "host",
        "origin",
        "referer",
        "x-forwarded-for",
        "x-real-ip",
        "content-length",
        "transfer-encoding",
        "connection",
    )

    /**
     * Headers that contain sensitive values and should be redacted in logs.
     */
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "x-api-key",
        "api-key",
        "x-auth-token",
        "cookie",
        "x-csrf-token",
        "x-xsrf-token",
    )

    /**
     * Maximum length for header values in logs before truncation.
     */
    private const val MAX_LOG_VALUE_LENGTH = 50

    /**
     * Filters a map of custom headers, removing prohibited headers.
     *
     * @param headers The original map of headers.
     * @return A new map with only allowed headers.
     */
    fun filterHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.filter { (name, _) ->
            val lowerName = name.lowercase()
            if (PROHIBITED_HEADERS.contains(lowerName)) {
                Log.w(TAG, "Filtering out prohibited header: $name")
                false
            } else {
                true
            }
        }
    }

    /**
     * Sanitizes a header value for safe logging by redacting sensitive information.
     *
     * @param headerName The name of the header.
     * @param headerValue The value of the header.
     * @return A sanitized version of the header value.
     */
    fun sanitizeHeaderValue(headerName: String, headerValue: String): String {
        val lowerName = headerName.lowercase()
        return when {
            SENSITIVE_HEADERS.contains(lowerName) -> {
                if (headerValue.length > 8) {
                    headerValue.take(4) + "****" + headerValue.takeLast(4)
                } else {
                    "****"
                }
            }
            headerValue.length > MAX_LOG_VALUE_LENGTH -> {
                headerValue.take(MAX_LOG_VALUE_LENGTH) + "..."
            }
            else -> headerValue
        }
    }

    /**
     * Creates a sanitized string representation of headers for logging.
     *
     * @param headers The map of headers to sanitize.
     * @return A sanitized string representation.
     */
    fun sanitizeHeadersForLog(headers: Map<String, String>): String {
        return headers.entries.joinToString(", ") { (name, value) ->
            "$name: ${sanitizeHeaderValue(name, value)}"
        }
    }

    /**
     * Validates that a header name follows HTTP header naming conventions.
     *
     * @param headerName The header name to validate.
     * @return True if the header name is valid.
     */
    fun isValidHeaderName(headerName: String): Boolean {
        if (headerName.isEmpty()) return false
        return headerName.all { char ->
            char.isLetterOrDigit() || char == '-' || char == '_'
        }
    }

    /**
     * Validates that a header value doesn't contain forbidden characters.
     *
     * @param headerValue The header value to validate.
     * @return True if the header value is valid.
     */
    fun isValidHeaderValue(headerValue: String): Boolean {
        // Header values should not contain control characters (except space and tab)
        return headerValue.none { char ->
            char.code < 32 || char.code == 127
        }
    }

    /**
     * Merges custom headers with default headers, with custom headers taking precedence.
     * Filters out prohibited headers from the result.
     *
     * @param defaultHeaders The default headers.
     * @param customHeaders The custom headers to merge.
     * @return A merged map of headers with prohibited headers removed.
     */
    fun mergeHeaders(
        defaultHeaders: Map<String, String>,
        customHeaders: Map<String, String>,
    ): Map<String, String> {
        val merged = defaultHeaders.toMutableMap()
        merged.putAll(customHeaders)
        return filterHeaders(merged)
    }

    /**
     * Common user agent strings for different platforms.
     */
    object UserAgents {
        const val DESKTOP_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val MOBILE_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val MOBILE_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}