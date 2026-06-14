package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Local stub of the former remote feature-flag service.
 *
 * The original implementation polled the Sweep backend every 15 minutes for feature flags.
 * After the agent/backend was removed, the autocomplete-only build has no remote source of
 * flags, so every lookup simply returns the caller-supplied default. The public API is kept
 * intact so existing call sites compile unchanged.
 */
@Service(Service.Level.PROJECT)
class FeatureFlagService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)
    }

    fun isFeatureEnabled(flagKey: String): Boolean = false

    fun getFeatureFlag(flagKey: String): String? = null

    fun getNumericFeatureFlag(
        flagKey: String,
        defaultValue: Int,
    ): Int = defaultValue

    fun getStringFeatureFlag(
        flagKey: String,
        defaultValue: String,
    ): String = defaultValue

    fun getAllFeatureFlags(): Map<String, String> = emptyMap()

    fun isInitialized(): Boolean = true

    fun refreshFeatureFlags() {
        // no-op: flags are no longer fetched from a backend
    }

    override fun dispose() {}
}
