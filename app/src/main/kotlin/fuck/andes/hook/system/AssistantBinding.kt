package fuck.andes.hook.system

import fuck.andes.config.PowerAssistantTarget
import fuck.andes.core.ModuleConfig

internal data class AssistantBinding(
    val target: PowerAssistantTarget,
    val packageName: String,
    val componentName: String,
    val displayName: String,
)

internal fun assistantBindingFor(target: PowerAssistantTarget): AssistantBinding? = when (target) {
    PowerAssistantTarget.OEM -> null
    PowerAssistantTarget.GEMINI -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.GOOGLE_PACKAGE,
        componentName = ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        displayName = "Gemini",
    )
    PowerAssistantTarget.ETA -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.ETA_PACKAGE,
        componentName = ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT,
        displayName = "Eta",
    )
}

internal fun shouldConfigureAssistant(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): Boolean = autoConfigEnabled && target != PowerAssistantTarget.OEM

internal fun isAssistantConfigurationCurrent(
    autoConfigEnabled: Boolean,
    expectedTarget: PowerAssistantTarget,
    currentTarget: PowerAssistantTarget,
): Boolean = shouldConfigureAssistant(autoConfigEnabled, currentTarget) &&
    expectedTarget == currentTarget