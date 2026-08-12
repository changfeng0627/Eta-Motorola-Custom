package fuck.andes.hook.google

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.hook.google.GoogleAppHooks.Companion.TAG
import fuck.andes.hook.google.GoogleEligibilityHooks.Companion.LOG_PREFIX
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hook Google Assistant (Gemini) app functionality.
 *
 * This hooks into Google app's FloatyActivity to inject voice commands
 * and manipulate the assistant's behavior.
 */
class GoogleAppHooks(private val hookRegistrar: HookRegistrar) {

    companion object {
        private const val TAG = "GoogleAppHooks"
        private val executor = Executors.newSingleThreadScheduledExecutor()

        init {
            // Set up scheduled task to send delayed voice command
            executor.scheduleAtFixedRate({
                try {
                    GoogleEligibilityHooks.sendVoiceCommand()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending voice command", e)
                }
            }, 5, 15, TimeUnit.SECONDS)
        }
    }

    /**
     * Install hooks for Google Assistant.
     */
    fun install() {
        hookRegistrar.registerHook(
            HookRegistrar.HookTarget(
                packageName = ModuleConfig.GOOGLE_ASSISTANT_PACKAGE,
                hookClass = "com.google.android.apps.assistant.app.main.MainActivity",
                methodName = "onResume",
                hookMethod = ::hookFloatyActivityOnResume
            )
        )

        hookRegistrar.registerHook(
            HookRegistrar.HookTarget(
                packageName = ModuleConfig.GOOGLE_ASSISTANT_PACKAGE,
                hookClass = "com.google.android.apps.assistant.app.main.MainActivity",
                methodName = "onCreate",
                hookMethod = ::hookFloatyActivityOnCreate
            )
        )

        Log.i(TAG, "Google Assistant hooks installed")
    }

    /**
     * Hook FloatyActivity.onResume to inject voice command.
     */
    private fun hookFloatyActivityOnResume(param: HookSupport.MethodHookParam) {
        val activity = param.thisObject as? Activity ?: return

        Log.d(TAG, "FloatyActivity.onResume called, injecting voice command")

        // Schedule delayed voice command injection
        activity.window.decorView.postDelayed({
            try {
                GoogleEligibilityHooks.sendVoiceCommand()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject voice command", e)
            }
        }, 2000) // 2 second delay
    }

    /**
     * Hook FloatyActivity.onCreate to modify intent and extras.
     */
    private fun hookFloatyActivityOnCreate(param: HookSupport.MethodHookParam) {
        val activity = param.thisObject as? Activity ?: return
        val intent = activity.intent ?: return

        Log.d(TAG, "FloatyActivity.onCreate called, modifying intent")

        // Add or modify extras in the intent
        val extras = intent.extras ?: Bundle()
        
        // Set up the voice command intent
        val commandIntent = Intent().apply {
            component = ComponentName(
                ModuleConfig.GOOGLE_ASSISTANT_PACKAGE,
                "com.google.android.apps.assistant.app.main.MainActivity"
            )
            action = "android.intent.action.VOICE_COMMAND"
            putExtra("android.intent.extra.VOICE_COMMAND", "Open settings")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // Store the command intent for later use
        extras.putParcelable("command_intent", commandIntent)
        intent.putExtras(extras)

        Log.d(TAG, "Voice command intent prepared")
    }
}