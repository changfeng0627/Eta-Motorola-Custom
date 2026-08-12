package fuck.andes.core

object ModuleConfig {
    /**
     * 原始 AOSP / Google 助手的静态作用域，保持不变
     */
    val ANDROID_ASSISTANT_PACKAGES = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant"
    )

    /**
     * 小爱同学的静态作用域，保持不变
     */
    val XIAOMI_PACKAGES = listOf(
        "com.miui.voiceassist",
        "com.xiaomi.aiasst.service",
        "com.xiaomi.aiasst.vision",
        "com.xiaomi.aiasst.eval",
        "com.xiaomi.aiasst.msgcenter"
    )

    /**
     * 三星 Bixby 的静态作用域，保持不变
     */
    val BREENO_PACKAGES = listOf(
        "com.coloros.gamespace",
        "com.heytap.speechassist",
        "com.heytap.usercenter"
    )

    /**
     * 三星 Bixby 的静态作用域，保持不变
     */
    val SAMSUNG_PACKAGES = listOf(
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.service",
        "com.samsung.android.bixby.vision",
        "com.samsung.android.bixby.voiceinput"
    )

    /**
     * 小布助手的静态作用域，保持不变
     */
    val OPLUS_PACKAGES = listOf(
        "com.coloros.deepthinker",
        "com.heytap.speechassist",
        "com.heytap.usercenter"
    )

    /**
     * 【新增】联想天禧AI（想帮帮）的静态作用域
     */
    val LENOVO_TIANXI_PACKAGES = listOf(
        "com.lenovo.xbb",
        "com.lenovo.xbb.service",
        "com.lenovo.xbb.vision",
        "com.lenovo.xbb.voiceinput"
    )

    /**
     * 需要 Hook 的目标应用包名列表
     * 包含所有支持的AI助手包名
     */
    val AGENT_RUNTIME_ENTRY_PACKAGES = listOf(
        *ANDROID_ASSISTANT_PACKAGES.toTypedArray(),
        *XIAOMI_PACKAGES.toTypedArray(),
        *BREENO_PACKAGES.toTypedArray(),
        *SAMSUNG_PACKAGES.toTypedArray(),
        *OPLUS_PACKAGES.toTypedArray(),
        *LENOVO_TIANXI_PACKAGES.toTypedArray() // 添加天禧AI包名
    )

    /**
     * Xposed 模块作用域
     */
    val XPOSED_SCOPE = listOf(
        "com.android.systemui",
        "com.android.settings",
        *ANDROID_ASSISTANT_PACKAGES.toTypedArray(),
        *XIAOMI_PACKAGES.toTypedArray(),
        *BREENO_PACKAGES.toTypedArray(),
        *SAMSUNG_PACKAGES.toTypedArray(),
        *OPLUS_PACKAGES.toTypedArray(),
        *LENOVO_TIANXI_PACKAGES.toTypedArray() // 添加天禧AI包名
    )

    /**
     * 包名判断工具函数
     */
    fun isAndroidAssistantPackage(packageName: String): Boolean {
        return ANDROID_ASSISTANT_PACKAGES.any { it == packageName || packageName.startsWith("$it:") }
    }

    fun isXiaomiPackage(packageName: String): Boolean {
        return XIAOMI_PACKAGES.any { it == packageName || packageName.startsWith("$it:") }
    }

    fun isBreenoPackage(packageName: String): Boolean {
        return BREENO_PACKAGES.any { it == packageName || packageName.startsWith("$it:") }
    }

    fun isSamsungPackage(packageName: String): Boolean {
        return SAMSUNG_PACKAGES.any { it == packageName || packageName.startsWith("$it:") }
    }

    fun isOplusPackage(packageName: String): Boolean {
        return OPLUS_PACKAGES.any { it == packageName || packageName.startsWith("$it:") }
    }

    /**
     * 【新增】天禧AI包名判断函数
     */
    fun isLenovoTianxiPackage(packageName: String): Boolean {
        return LENOVO_TIANXI_PACKAGES.any { it == packageName || packageName.startsWith("$it:") }
    }

    /**
     * 综合判断是否为Hook目标包名
     */
    fun isTargetPackage(packageName: String): Boolean {
        return isAndroidAssistantPackage(packageName) ||
               isXiaomiPackage(packageName) ||
               isBreenoPackage(packageName) ||
               isSamsungPackage(packageName) ||
               isOplusPackage(packageName) ||
               isLenovoTianxiPackage(packageName) // 添加天禧AI判断
    }

    /**
     * 配置项：是否启用天禧AI Hook
     * 默认启用，用户可以在设置界面中关闭
     */
    var enableLenovoTianxiHook = true
}