package fuck.andes.config

internal enum class PowerAssistantTarget(
    val persistedValue: String,
) {
    OEM("oem"),
    GEMINI("gemini"),
    ETA("eta"),
    ;

    companion object {
        fun resolve(
            persistedValue: String?,
            legacyPowerKeyTakeover: Boolean,
        ): PowerAssistantTarget = entries.firstOrNull {
            it.persistedValue == persistedValue
        } ?: if (legacyPowerKeyTakeover) {
            GEMINI
        } else {
            OEM
        }
    }
}
