package dev.sweep.assistant.settings

object SweepEnvironmentConstants {
    val IS_CLOUD_ENVIRONMENT = SweepSettingsParser.isCloudEnvironment()
    val DISABLE_FIM_AUTOCOMPLETE = IS_CLOUD_ENVIRONMENT
    val PLUGIN_ID =
        if (IS_CLOUD_ENVIRONMENT) {
            "dev.sweep.assistant.cloud"
        } else {
            "dev.sweep.assistant"
        }

    object Defaults {
        val DEFAULT_BASE_URL =
            if (IS_CLOUD_ENVIRONMENT) {
                "https://backend.app.sweep.dev" // Default to our base url
            } else {
                "" // Empty string for non-cloud environment
            }
    }
}
