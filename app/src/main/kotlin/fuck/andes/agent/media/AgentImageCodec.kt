package fuck.andes.agent.media

import fuck.andes.agent.model.ModelImage
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.Base64

object AgentImageCodec {
    fun fromBytes(
        data: ByteArray,
        mediaType: String? = null,
        filename: String? = null,
    ): ModelImage {
        val mediaTypeParsed = mediaType?.let { it.toMediaTypeOrNull() }
            ?: guessMediaType(data, filename)
            ?: "application/octet-stream".toMediaTypeOrNull()!!
        return ModelImage(
            mediaType = mediaTypeParsed,
            data = data.toByteString(),
            filename = filename,
        )
    }

    fun fromAttachmentBytes(
        base64Data: String,
        mediaType: String,
        filename: String? = null,
    ): ModelImage {
        val decoded = Base64.getDecoder().decode(base64Data.trim())
        return fromBytes(decoded, mediaType, filename)
    }

    fun fromScreenBytes(
        pngData: ByteArray,
    ): ModelImage {
        return fromBytes(pngData, "image/png")
    }

    fun fromScreenBitmap(
        bitmap: android.graphics.Bitmap,
    ): ModelImage {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        return fromScreenBytes(stream.toByteArray())
    }

    fun previewFromReference(
        reference: ModelImage.Reference,
    ): ModelImage {
        val raw = readReferenceBytes(reference) ?: return ModelImage.empty()
        return fromBytes(raw, mediaType = null, filename = null)
    }

    fun fromToolFile(
        file: File,
        mediaType: String? = null,
    ): ModelImage {
        val data = file.readBytes()
        return fromBytes(data, mediaType, file.name)
    }

    fun fromReference(
        reference: ModelImage.Reference,
    ): ModelImage {
        val data = readReferenceBytes(reference) ?: return ModelImage.empty()
        val mediaType = reference.mediaType
        val filename = reference.filename
        return fromBytes(data, mediaType, filename)
    }

    fun fromTransferReference(
        reference: ModelImage.Reference,
    ): ModelImage {
        val data = readReferenceBytes(reference) ?: return ModelImage.empty()
        return fromBytes(data, reference.mediaType, reference.filename)
    }

    private fun readReferenceBytes(
        reference: ModelImage.Reference,
    ): ByteArray? {
        return when (reference) {
            is ModelImage.Reference.Url -> {
                try {
                    URL(reference.url).openStream().use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
            }
            is ModelImage.Reference.DataUri -> {
                val base64Data = reference.data
                    .substringAfter(",", "")
                val decoded = Base64.getDecoder().decode(base64Data.trim())
                decoded
            }
            is ModelImage.Reference.File -> {
                val file = File(reference.path)
                if (file.exists() && file.isFile) {
                    file.readBytes()
                } else {
                    null
                }
            }
            is ModelImage.Reference.Base64 -> {
                val decoded = Base64.getDecoder().decode(reference.base64.trim())
                decoded
            }
        }
    }

    private fun guessMediaType(
        data: ByteArray,
        filename: String?,
    ): okhttp3.MediaType? {
        if (filename != null) {
            val extension = filename.substringAfterLast('.', "")
                .lowercase()
            return when (extension) {
                "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                "png" -> "image/png".toMediaTypeOrNull()
                "gif" -> "image/gif".toMediaTypeOrNull()
                "webp" -> "image/webp".toMediaTypeOrNull()
                else -> null
            }
        }
        if (data.size >= 4) {
            val magic = data.take(4).toByteArray()
            if (magic.size >= 4) {
                if (magic[0] == 0x89.toByte() && magic[1] == 0x50.toByte() && magic[2] == 0x4E.toByte() && magic[3] == 0x47.toByte()) {
                    return "image/png".toMediaTypeOrNull()
                }
                if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte() && magic[2] == 0xFF.toByte()) {
                    return "image/jpeg".toMediaTypeOrNull()
                }
                if (magic[0] == 0x47.toByte() && magic[1] == 0x49.toByte() && magic[2] == 0x46.toByte() && magic[3] == 0x38.toByte()) {
                    return "image/gif".toMediaTypeOrNull()
                }
            }
        }
        return null
    }

    fun isValidBase64(
        base64: String,
    ): Boolean {
        return try {
            Base64.getDecoder().decode(base64.trim())
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun stripBase64Padding(
        base64: String,
    ): String {
        return base64.replace(Regex("[=\\n\\r]"), "")
    }
}