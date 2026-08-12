package fuck.andes.hook.system

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.UserHandle
import android.util.ArrayMap
import android.util.Log
import fuck.andes.hook.core.CoreHook
import fuck.andes.hook.core.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AssistantManager - 语音交互会话管理器
 * 
 * 通过 Hook VoiceInteractionManagerService 来管理语音交互会话、
 * 默认助理校正、用户切换恢复等核心功能。
 */
object AssistantManager {
    
    private const val TAG = "AssistantManager"
    private const val DEFAULT_ASSISTANT_PACKAGE = "fuck.andes.eta"
    private const val DEFAULT_ASSISTANT_SERVICE = "$DEFAULT_ASSISTANT_PACKAGE/.assistant.VoiceInteractionService"
    private const val ACTION_VOICE_INTERACTION = "android.service.voice.VoiceInteractionService"
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val boundServices = ArrayMap<String, IBinder>()
    
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        Log.i(TAG, "Initializing AssistantManager for system_server")
        hookVoiceInteractionManagerService(lpparam)
    }
    
    private fun hookVoiceInteractionManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.server.voiceinteraction.VoiceInteractionManagerService",
                lpparam.classLoader
            )
            
            XposedHelpers.findAndHookMethod(
                clazz,
                "onBootPhase",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(method: MethodHookParam) {
                        val phase = method.args[0] as Int
                        if (phase == 500) {
                            Log.i(TAG, "System services ready, scheduling default assistant correction")
                            scheduleDefaultAssistantCorrection()
                        }
                        if (phase == 550) {
                            Log.i(TAG, "Activity manager ready, scheduling voice service binding check")
                            scheduleVoiceServiceBindingCheck()
                        }
                    }
                }
            )
            
            XposedHelpers.findAndHookMethod(
                clazz,
                "onUserUnlocking",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(method: MethodHookParam) {
                        val userId = method.args[0] as Int
                        Log.i(TAG, "User $userId unlocked, scheduling default assistant correction")
                        executor.schedule({
                            correctDefaultAssistantForUser(userId)
                        }, 2, TimeUnit.SECONDS)
                    }
                }
            )
            
            XposedHelpers.findAndHookMethod(
                clazz,
                "onUserSwitching",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(method: MethodHookParam) {
                        val newUserId = method.args[0] as Int
                        val oldUserId = method.args[1] as Int
                        Log.i(TAG, "User switching from $oldUserId to $newUserId")
                        executor.schedule({
                            correctDefaultAssistantForUser(newUserId)
                        }, 3, TimeUnit.SECONDS)
                    }
                }
            )
            
            Log.i(TAG, "VoiceInteractionManagerService hooks installed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook VoiceInteractionManagerService", e)
        }
    }
    
    private fun scheduleDefaultAssistantCorrection() {
        executor.schedule({ correctDefaultAssistant() }, 5, TimeUnit.SECONDS)
    }
    
    private fun scheduleVoiceServiceBindingCheck() {
        executor.schedule({ checkVoiceServiceBinding() }, 10, TimeUnit.SECONDS)
    }
    
    private fun correctDefaultAssistant() {
        try {
            val userId = Utils.getCurrentUserId()
            correctDefaultAssistantForUser(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to correct default assistant", e)
        }
    }
    
    private fun correctDefaultAssistantForUser(userId: Int) {
        try {
            Log.i(TAG, "Correcting default assistant for user $userId")
            val roleControllerManager = Utils.getRoleControllerManager()
            if (roleControllerManager == null) {
                Log.e(TAG, "RoleControllerManager is null")
                return
            }
            val assistantRoleName = "android.app.role.ASSISTANT"
            val componentName = ComponentName(DEFAULT_ASSISTANT_PACKAGE, 
                "fuck.andes.assistant.VoiceInteractionService")
            setDefaultAssistantWithRetry(roleControllerManager, assistantRoleName, componentName, userId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to correct default assistant for user $userId", e)
        }
    }
    
    private fun setDefaultAssistantWithRetry(
        roleControllerManager: Any,
        roleName: String,
        componentName: ComponentName,
        userId: Int,
        retryCount: Int = 0,
        maxRetries: Int = 3
    ) {
        if (retryCount >= maxRetries) {
            Log.w(TAG, "Max retries reached for setting default assistant")
            return
        }
        try {
            val userHandle = UserHandle.getUserHandleForId(userId)
            val iRoleControllerManager = getIRoleControllerManager(roleControllerManager, userId)
            if (iRoleControllerManager == null) {
                Log.e(TAG, "Failed to get IRoleControllerManager for user $userId")
                return
            }
            XposedHelpers.callMethod(
                iRoleControllerManager,
                "setRoleHolder",
                roleName,
                componentName.flattenToString(),
                true,
                userHandle,
                null
            )
            Log.i(TAG, "Default assistant set to $componentName for user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set default assistant (attempt ${retryCount + 1})", e)
            executor.schedule({
                setDefaultAssistantWithRetry(roleControllerManager, roleName, componentName, userId, retryCount + 1, maxRetries)
            }, 2 * (retryCount + 1), TimeUnit.SECONDS)
        }
    }
    
    private fun getIRoleControllerManager(roleControllerManager: Any, userId: Int): Any? {
        try {
            val method = XposedHelpers.findMethodExact(
                roleControllerManager.javaClass,
                "getIRoleControllerManager",
                UserHandle::class.java
            )
            val userHandle = UserHandle.getUserHandleForId(userId)
            return method.invoke(roleControllerManager, userHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IRoleControllerManager", e)
            return null
        }
    }
    
    private fun checkVoiceServiceBinding() {
        try {
            Log.i(TAG, "Checking voice service binding")
            val intent = Intent(ACTION_VOICE_INTERACTION).apply {
                setClassName(DEFAULT_ASSISTANT_PACKAGE, DEFAULT_ASSISTANT_SERVICE)
            }
            val activityManager = Utils.getActivityManager()
            if (activityManager != null) {
                Log.i(TAG, "Voice service intent created: $intent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check voice service binding", e)
        }
    }
    
    fun showAssistantSession() {
        try {
            Log.i(TAG, "Showing assistant session")
            val userId = Utils.getCurrentUserId()
            val service = getVoiceInteractionManagerService()
            if (service == null) {
                Log.e(TAG, "VoiceInteractionManagerService instance is null")
                return
            }
            XposedHelpers.callMethod(
                service,
                "showSession",
                null,
                UserHandle.getUserHandleForId(userId)
            )
            Log.i(TAG, "Assistant session shown for user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show assistant session", e)
        }
    }
    
    private fun getVoiceInteractionManagerService(): Any? {
        try {
            val binder = XposedHelpers.callStaticMethod(
                Class.forName("android.os.ServiceManager"),
                "getService",
                "voiceinteraction"
            )
            if (binder == null) {
                Log.e(TAG, "VoiceInteractionManagerService binder is null")
                return null
            }
            val iInterface = XposedHelpers.callMethod(
                binder,
                "queryLocalInterface",
                "com.android.server.voiceinteraction.IVoiceInteractionManagerService"
            )
            return iInterface ?: binder
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get VoiceInteractionManagerService instance", e)
            return null
        }
    }
    
    fun rebuildVoiceServiceBinding() {
        try {
            Log.i(TAG, "Rebuilding voice service binding")
            val userId = Utils.getCurrentUserId()
            synchronized(boundServices) { boundServices.clear() }
            correctDefaultAssistantForUser(userId)
            checkVoiceServiceBinding()
            Log.i(TAG, "Voice service binding rebuilt for user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild voice service binding", e)
        }
    }
    
    fun getBoundServiceCount(): Int {
        synchronized(boundServices) { return boundServices.size }
    }
    
    fun cleanup() {
        try {
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
            Log.i(TAG, "AssistantManager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup AssistantManager", e)
        }
    }
}