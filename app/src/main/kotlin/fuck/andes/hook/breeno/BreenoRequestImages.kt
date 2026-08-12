package fuck.andes.hook.breeno

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Breeno 请求图片处理工具
 * 处理图片引用解析、缓存管理与多图支持
 */
object BreenoRequestImages {
    private const val TAG = "BreenoRequestImages"
    private const val CACHE_DIR = "breeno_image_cache"
    private const val MAX_IMAGE_SIZE = 1024 * 1024 // 1MB
    private const val MAX_IMAGE_DIMENSION = 1024

    /**
     * 图片引用数据类
     */
    data class ImageReference(
        val uri: String,
        val base64: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val mimeType: String = "image/jpeg"
    )

    /**
     * 解析图片引用
     * 支持格式：
     * - content:// URI
     * - file:// URI
     * - 直接 base64 编码
     * - http/https URL
     */
    fun parseImageReferences(text: String): List<ImageReference> {
        val references = mutableListOf<ImageReference>()
        
        // 正则匹配图片引用
        val patterns = listOf(
            Regex("""\[(?:image|img|picture|pic)[:\s]*([^\]]+)\]"""),
            Regex("""\{(?:image|img|picture|pic)[:\s]*([^}]+)\}"""),
            Regex("""(?:image|img|picture|pic)\s*[:：]\s*(\S+)"""),
            Regex("""(content://\S+|file://\S+|https?://\S+\.(?:jpg|jpeg|png|gif|webp))""")
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val reference = match.groupValues[1].trim()
                val imageRef = parseSingleReference(reference)
                if (imageRef != null) {
                    references.add(imageRef)
                }
            }
        }
        
        // 去重
        return references.distinctBy { it.uri }
    }

    /**
     * 解析单个图片引用
     */
    private fun parseSingleReference(reference: String): ImageReference? {
        return when {
            reference.startsWith("content://") || reference.startsWith("file://") -> {
                try {
                    val bitmap = loadBitmapFromUri(reference)
                    if (bitmap != null) {
                        val base64 = bitmapToBase64(bitmap)
                        ImageReference(
                            uri = reference,
                            base64 = base64,
                            width = bitmap.width,
                            height = bitmap.height
                        )
                    } else null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load image from URI: $reference", e)
                    null
                }
            }
            
            reference.startsWith("data:image/") -> {
                // 直接 base64 编码
                val base64 = reference.substringAfter("base64,")
                if (base64.isNotEmpty()) {
                    ImageReference(
                        uri = "base64_inline",
                        base64 = base64,
                        mimeType = reference.substringAfter("data:", ";")
                    )
                } else null
            }
            
            reference.matches(Regex("""^[A-Za-z0-9+/]*={0,2}$""")) -> {
                // 可能是 base64 编码
                try {
                    val bytes = Base64.decode(reference, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        ImageReference(
                            uri = "base64_direct",
                            base64 = reference,
                            width = bitmap.width,
                            height = bitmap.height
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            
            reference.startsWith("http://") || reference.startsWith("https://") -> {
                ImageReference(uri = reference)
            }
            
            else -> null
        }
    }

    /**
     * 从 URI 加载 Bitmap
     */
    private fun loadBitmapFromUri(uri: String): Bitmap? {
        return try {
            val context = fuck.andes.core.ContextHolder.appContext ?: return null
            val inputStream = when {
                uri.startsWith("content://") -> {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                }
                uri.startsWith("file://") -> {
                    val path = uri.removePrefix("file://")
                    java.io.FileInputStream(File(path))
                }
                else -> null
            }
            
            inputStream?.use { stream ->
                // 解码选项
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                
                // 先读取尺寸
                BitmapFactory.decodeStream(stream, null, options)
                
                // 计算采样率
                options.inSampleSize = calculateInSampleSize(options, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
                options.inJustDecodeBounds = false
                
                // 重新读取并解码
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
        }
    }

    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }

    /**
     * Bitmap 转 Base64
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        
        // 调整尺寸
        val scaledBitmap = if (bitmap.width > MAX_IMAGE_DIMENSION || bitmap.height > MAX_IMAGE_DIMENSION) {
            val ratio = minOf(
                MAX_IMAGE_DIMENSION.toFloat() / bitmap.width,
                MAX_IMAGE_DIMENSION.toFloat() / bitmap.height
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }
        
        // 压缩
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        
        // 限制大小
        return if (bytes.size > MAX_IMAGE_SIZE) {
            // 降低质量
            outputStream.reset()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } else {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /**
     * 从网络 URL 获取图片
     */
    suspend fun fetchImageFromUrl(url: String): ImageReference? {
        return try {
            val context = fuck.andes.core.ContextHolder.appContext ?: return null
            val cacheFile = File(context.cacheDir, CACHE_DIR)
            if (!cacheFile.exists()) {
                cacheFile.mkdirs()
            }
            
            // 生成缓存文件名
            val md5 = MessageDigest.getInstance("MD5")
            val hash = md5.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val cachedFile = File(cacheFile, "$hash.jpg")
            
            if (cachedFile.exists()) {
                // 使用缓存
                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    return ImageReference(
                        uri = url,
                        base64 = bitmapToBase64(bitmap),
                        width = bitmap.width,
                        height = bitmap.height
                    )
                }
            }
            
            // 下载图片
            val urlConnection = java.net.URL(url).openConnection()
            urlConnection.connectTimeout = 10000
            urlConnection.readTimeout = 10000
            
            urlConnection.inputStream.use { inputStream ->
                // 保存到缓存
                java.io.FileOutputStream(cachedFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                
                // 重新加载
                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    ImageReference(
                        uri = url,
                        base64 = bitmapToBase64(bitmap),
                        width = bitmap.width,
                        height = bitmap.height
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch image from URL: $url", e)
            null
        }
    }

    /**
     * 验证图片格式
     */
    fun validateImageFormat(data: ByteArray): Boolean {
        if (data.size < 4) return false
        
        // 检查文件签名
        val signatures = mapOf(
            0x89.toByte() to 0x50.toByte() to "PNG",  // PNG
            0xFF.toByte() to 0xD8.toByte() to "JPEG", // JPEG
            0x47.toByte() to 0x49.toByte() to "GIF",  // GIF
            0x52.toByte() to 0x49.toByte() to "WEBP"  // WEBP (RIFF)
        )
        
        return signatures.any { (pair, _) ->
            data[0] == pair.first && data[1] == pair.second
        }
    }

    /**
     * 清理缓存
     */
    fun clearCache() {
        try {
            val context = fuck.andes.core.ContextHolder.appContext ?: return
            val cacheFile = File(context.cacheDir, CACHE_DIR)
            if (cacheFile.exists()) {
                cacheFile.deleteRecursively()
                Log.d(TAG, "Cache cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Long {
        return try {
            val context = fuck.andes.core.ContextHolder.appContext ?: return 0
            val cacheFile = File(context.cacheDir, CACHE_DIR)
            if (cacheFile.exists()) {
                cacheFile.walkTopDown().sumOf { it.length() }
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 替换文本中的图片引用为 base64
     */
    fun replaceImageReferences(text: String, references: List<ImageReference>): String {
        var result = text
        for (reference in references) {
            if (reference.base64 != null) {
                val placeholder = "[image:${reference.uri}]"
                val replacement = "[image:base64,${reference.mimeType};${reference.base64}]"
                result = result.replace(placeholder, replacement)
            }
        }
        return result
    }
}