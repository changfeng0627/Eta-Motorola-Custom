package fuck.andes.agent.device

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 安全执行 Root 命令，限制输出大小，避免 OOM。
 *
 * 仅在已确认 Root 可用后调用。
 */
internal object BoundedRootCommandExecutor {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
    )

    /**
     * 执行命令并返回合并结果。
     * @param commands 要执行的命令列表
     * @param timeoutMs 超时时间（毫秒）
     * @param maxOutputBytes 最大输出字节数
     */
    fun exec(
        commands: List<String>,
        timeoutMs: Long = 10_000L,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
    ): Result {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = process.outputStream.bufferedWriter()
            for (cmd in commands) {
                os.write(cmd)
                os.newLine()
            }
            os.write("exit")
            os.newLine()
            os.flush()

            val stdout = readBounded(process.inputStream, maxOutputBytes)
            val stderr = readBounded(process.errorStream, maxOutputBytes)

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val exitCode = if (finished) process.exitValue() else -1

            return Result(
                exitCode = exitCode,
                stdout = stdout.text,
                stderr = stderr.text,
                truncated = stdout.truncated || stderr.truncated,
            )
        } catch (e: Exception) {
            return Result(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Unknown error",
                truncated = false,
            )
        } finally {
            process?.destroy()
        }
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean)

    private fun readBounded(inputStream: ParcelFileDescriptor.AutoCloseInputStream, maxBytes: Int): BoundedOutput {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val sb = StringBuilder()
        var totalBytes = 0
        var truncated = false
        var line = reader.readLine()
        while (line != null) {
            val lineBytes = line.toByteArray().size + 1 // +1 for newline
            if (totalBytes + lineBytes > maxBytes) {
                truncated = true
                break
            }
            sb.append(line).append('\n')
            totalBytes += lineBytes
            line = reader.readLine()
        }
        return BoundedOutput(sb.toString().trimEnd(), truncated)
    }

    private const val MAX_OUTPUT_BYTES = 256 * 1024 // 256 KB
    const val IO_JOIN_TIMEOUT_MS = 2_000L
}