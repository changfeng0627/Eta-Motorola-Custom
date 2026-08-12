package fuck.andes.agent.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Bitmap.CompressFormat
import fuck.andes.agent.model.ModelImage
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object AgentModelImageEncoder {
    private val screenProfile = EncodingProfile(
        format = CompressFormat.WEBP_LOSSY,
        quality = 75,
        maxDimension = null,
        maintainAspectRatio = true,
    )

    private val previewProfile = EncodingProfile(
        format = CompressFormat.JPEG,
        quality = 80,
        maxDimension = 512,
        maintainAspectRatio = true,
    )

    private val toolVisionProfile = EncodingProfile(
        format = CompressFormat.JPEG,
        quality = 82,
        maxDimension = 1600,
        maintainAspectRatio = true,
    )

    fun screen(
        source: ModelImage,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
    ): ModelImage {
        return encodeWithProfile(source, screenProfile, targetWidth, targetHeight)
    }

    fun toolVision(
        source: ModelImage,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
    ): ModelImage {
        return encodeWithProfile(source, toolVisionProfile, targetWidth, targetHeight)
    }

    fun preview(
        source: ModelImage,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
    ): ModelImage {
        return encodeWithProfile(source, previewProfile, targetWidth, targetHeight)
    }

    private fun encodeWithProfile(
        source: ModelImage,
        profile: EncodingProfile,
        targetWidth: Int?,
        targetHeight: Int?,
    ): ModelImage {
        val bitmap = decodeModelImage(source) ?: return source
        val bounds = ImageBounds(bitmap.width, bitmap.height)
        val targetSize = calculateTargetSize(bounds, profile, targetWidth, targetHeight)
        val scaledBitmap = if (targetSize.width != bounds.width || targetSize.height != bounds.height) {
            scaleBitmap(bitmap, targetSize.width, targetSize.height)
        } else {
            bitmap
        }
        val outputStream = ByteArrayOutputStream()
        val compressed = scaledBitmap.compress(profile.format, profile.quality, outputStream)
        if (!compressed) {
            return source
        }
        val compressedData = outputStream.toByteArray()
        val mediaType = when (profile.format) {
            CompressFormat.JPEG -> "image/jpeg"
            CompressFormat.PNG -> "image/png"
            CompressFormat.WEBP_LOSSY, CompressFormat.WEBP_LOSSLESS -> "image/webp"
            else -> "image/jpeg"
        }
        val filename = generateFilename(profile.format)
        val result = ModelImage(
            mediaType = mediaType.toMediaTypeOrNull(),
            data = compressedData.toByteString(),
            filename = filename,
        )
        bitmap.recycle()
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        return result
    }

    private fun decodeModelImage(
        source: ModelImage,
    ): Bitmap? {
        val data = source.data.toByteArray()
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    private fun calculateTargetSize(
        bounds: ImageBounds,
        profile: EncodingProfile,
        targetWidth: Int?,
        targetHeight: Int?,
    ): TargetSize {
        val maxWidth = targetWidth ?: profile.maxDimension
        val maxHeight = targetHeight ?: profile.maxDimension
        if (maxWidth == null && maxHeight == null) {
            return TargetSize(bounds.width, bounds.height)
        }
        if (profile.maintainAspectRatio) {
            val scaleFactorX = if (maxWidth != null) maxWidth.toFloat() / bounds.width else 1f
            val scaleFactorY = if (maxHeight != null) maxHeight.toFloat() / bounds.height else 1f
            val scaleFactor = minOf(scaleFactorX, scaleFactorY, 1f)
            val newWidth = (bounds.width * scaleFactor).toInt()
            val newHeight = (bounds.height * scaleFactor).toInt()
            return TargetSize(newWidth, newHeight)
        } else {
            val newWidth = maxWidth ?: bounds.width
            val newHeight = maxHeight ?: bounds.height
            return TargetSize(newWidth, newHeight)
        }
    }

    private fun scaleBitmap(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        val matrix = Matrix()
        matrix.postScale(
            targetWidth.toFloat() / bitmap.width,
            targetHeight.toFloat() / bitmap.height,
        )
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun generateFilename(
        format: CompressFormat,
    ): String {
        val extension = when (format) {
            CompressFormat.JPEG -> "jpg"
            CompressFormat.PNG -> "png"
            CompressFormat.WEBP_LOSSY, CompressFormat.WEBP_LOSSLESS -> "webp"
            else -> "jpg"
        }
        return "image_${System.currentTimeMillis()}.$extension"
    }

    data class EncodingProfile(
        val format: CompressFormat,
        val quality: Int,
        val maxDimension: Int?,
        val maintainAspectRatio: Boolean,
    )

    data class ImageBounds(
        val width: Int,
        val height: Int,
    )

    data class TargetSize(
        val width: Int,
        val height: Int,
    )

    fun saveToFile(
        image: ModelImage,
        file: File,
    ): Boolean {
        return try {
            val bitmap = decodeModelImage(image) ?: return false
            FileOutputStream(file).use { outputStream ->
                val format = when {
                    image.mediaType?.toString()?.contains("png") == true -> CompressFormat.PNG
                    image.mediaType?.toString()?.contains("webp") == true -> CompressFormat.WEBP_LOSSY
                    else -> CompressFormat.JPEG
                }
                bitmap.compress(format, 100, outputStream)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun calculateSize(
        image: ModelImage,
    ): Long {
        return image.data.size.toLong()
    }

    fun isSupportedFormat(
        mediaType: String?,
    ): Boolean {
        if (mediaType == null) return false
        val supportedTypes = listOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/bmp",
            "image/heic",
            "image/heif",
        )
        return supportedTypes.any { mediaType.lowercase().contains(it) }
    }

    fun getSupportedExtensions(): List<String> {
        return listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    }

    fun isSupportedExtension(
        filename: String?,
    ): Boolean {
        if (filename == null) return false
        val extension = filename.substringAfterLast('.', "")
            .lowercase()
        return getSupportedExtensions().contains(extension)
    }
}