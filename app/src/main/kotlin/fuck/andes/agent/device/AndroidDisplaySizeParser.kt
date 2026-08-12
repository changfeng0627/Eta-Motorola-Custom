package fuck.andes.agent.device

/**
 * 解析 `wm size` 命令输出，提取物理分辨率与 Override 分辨率。
 *
 * 输入格式示例：
 * ```
 * Physical size: 1080x2340
 * Override size: 1080x2160
 * ```
 */
internal object AndroidDisplaySizeParser {
    data class DisplaySize(
        /** 物理分辨率宽，如 1080 */
        val physicalWidth: Int,
        /** 物理分辨率高，如 2340 */
        val physicalHeight: Int,
        /** Override 宽度（可能与物理宽度相同） */
        val overrideWidth: Int,
        /** Override 高度（可能与物理高度相同） */
        val overrideHeight: Int,
    )

    fun parse(output: String): DisplaySize? {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        var physicalWidth = 0
        var physicalHeight = 0
        var overrideWidth = 0
        var overrideHeight = 0
        for (line in lines) {
            val resolution = extractResolution(line) ?: continue
            if (line.startsWith("Physical", ignoreCase = true)) {
                physicalWidth = resolution.first
                physicalHeight = resolution.second
            } else if (line.startsWith("Override", ignoreCase = true)) {
                overrideWidth = resolution.first
                overrideHeight = resolution.second
            }
        }
        // 如果只找到一个，视为物理尺寸
        if (overrideWidth == 0 && overrideHeight == 0) {
            overrideWidth = physicalWidth
            overrideHeight = physicalHeight
        }
        return DisplaySize(
            physicalWidth = physicalWidth,
            physicalHeight = physicalHeight,
            overrideWidth = overrideWidth,
            overrideHeight = overrideHeight,
        ).takeIf { physicalWidth > 0 && physicalHeight > 0 }
    }

    private val SIZE_REGEX = Regex("(\\d{1,5})x(\\d{1,5})")
    private fun extractResolution(line: String): Pair<Int, Int>? {
        val match = SIZE_REGEX.find(line) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return width to height
    }
}