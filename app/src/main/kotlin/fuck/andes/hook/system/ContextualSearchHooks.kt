package fuck.andes.hook.system

import android.os.IBinder
import android.util.Log
import fuck.andes.core.HookInstaller
import fuck.andes.core.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ContextualSearch 的 Hook 实现。
 * 这个文件负责 ContextualSearch 服务的启动引导、包名替换和权限检查。
 */
object ContextualSearchHooks {
    private const val TAG = "ContextualSearchHooks"
    private val isInstalled = AtomicBoolean(false)

    fun install(module: HookInstaller, logger: Logger, classLoader: ClassLoader) {
        if (isInstalled.getAndSet(true)) {
            logger.d(TAG, "Already installed")
            return
        }

        logger.d(TAG, "Installing ContextualSearchHooks")

        // Hook ContextualSearch 服务的启动
        try {
            val contextualSearchServiceClass = Class.forName(
                "com.android.internal.view.ContextualSearchService",
                false,
                classLoader
            )

            // Hook 服务绑定
            module.hookMethod(
                contextualSearchServiceClass,
                "onBind",
                listOf(),
                object : fuck.andes.core.HookMethodCallback() {
                    override fun before(param: fuck.andes.core.HookMethodParam) {
                        logger.d(TAG, "ContextualSearchService.onBind() hooked")
                    }
                }
            )

            // Hook 服务启动
            module.hookMethod(
                contextualSearchServiceClass,
                "onStartCommand",
                listOf(
                    android.content.Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ),
                object : fuck.andes.core.HookMethodCallback() {
                    override fun before(param: fuck.andes.core.HookMethodParam) {
                        logger.d(TAG, "ContextualSearchService.onStartCommand() hooked")
                    }
                }
            )

            logger.d(TAG, "ContextualSearch service hooks installed")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to hook ContextualSearch service", e)
        }

        // Hook 包名替换逻辑
        try {
            val contextualSearchPackage = "com.android.internal.view.contextualsearch"
            
            // 替换包名检查
            module.hookMethod(
                Class.forName("android.app.Context", false, classLoader),
                "getPackageName",
                listOf(),
                object : fuck.andes.core.HookMethodCallback() {
                    override fun after(param: fuck.andes.core.HookMethodParam) {
                        val originalResult = param.result as? String
                        if (originalResult == "android") {
                            // 对于系统上下文，检查是否需要替换
                            val context = param.instance as android.content.Context
                            if (context.packageName == "android") {
                                logger.d(TAG, "Replacing package name for system context")
                                param.result = contextualSearchPackage
                            }
                        }
                    }
                }
            )

            logger.d(TAG, "Package name replacement hook installed")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to hook package name replacement", e)
        }

        // Hook 权限检查
        try {
            val permissionManagerClass = Class.forName(
                "com.android.server.permission.PermissionManagerService",
                false,
                classLoader
            )

            module.hookMethod(
                permissionManagerClass,
                "checkPermission",
                listOf(
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType
                ),
                object : fuck.andes.core.HookMethodCallback() {
                    override fun after(param: fuck.andes.core.HookMethodParam) {
                        val permission = param.args[0] as? String
                        val packageName = param.args[1] as? String
                        
                        if (permission == "android.permission.BIND_CONTEXTUAL_SEARCH_SERVICE" 
                            && packageName == "com.android.internal.view.contextualsearch") {
                            logger.d(TAG, "Granting ContextualSearch permission")
                            param.result = android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                    }
                }
            )

            logger.d(TAG, "Permission check hook installed")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to hook permission check", e)
        }

        logger.d(TAG, "ContextualSearchHooks installation completed")
    }
}