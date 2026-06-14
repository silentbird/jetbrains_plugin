package dev.sweep.assistant.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import java.io.File

/**
 * Project-level configuration for the autocomplete-only build.
 *
 * The original SweepConfig was a large god-object holding both chat/agent and autocomplete
 * configuration plus its own settings UI. This slimmed version keeps only the autocomplete,
 * commit-message and privacy state still referenced by the plugin, and persists it under the
 * same storage key so existing user settings continue to load.
 */
data class SweepConfigState(
    var fontSize: Float =
        JBUI.Fonts
            .label()
            .size
            .toFloat(),
    var useCustomizedCommitMessages: Boolean = true,
    // Show autocomplete badge (Tab to accept hint)
    var showAutocompleteBadge: Boolean = false,
    // Autocomplete exclusion patterns - files matching these patterns won't trigger autocomplete
    var autocompleteExclusionPatterns: Set<String> = emptySet(),
    // V2 of autocomplete exclusion patterns - ensures all users get .env excluded by default
    var autocompleteExclusionPatternsV2: Set<String> = setOf(".env"),
    // Whether to hide the autocomplete exclusion banner (user clicked "Don't show again")
    var hideAutocompleteExclusionBanner: Boolean = false,
    var debounceThresholdMs: Long = 10L,
    var autocompleteDebounceMs: Long = -1L,
)

@State(
    name = "dev.sweep.assistant.components.SweepConfig",
    storages = [Storage("SweepConfig.xml")],
)
@Service(Service.Level.PROJECT)
class SweepConfig(
    private val project: Project,
) : PersistentStateComponent<SweepConfigState>,
    Disposable {
    private var state = SweepConfigState()

    companion object {
        fun getInstance(project: Project): SweepConfig = project.getService(SweepConfig::class.java)
    }

    override fun getState(): SweepConfigState = state

    override fun loadState(state: SweepConfigState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    // ---- Autocomplete ----

    fun isAutocompleteLocalMode(): Boolean = SweepSettings.getInstance().autocompleteLocalMode

    fun isShowAutocompleteBadge(): Boolean = state.showAutocompleteBadge

    fun isHideAutocompleteExclusionBanner(): Boolean = state.hideAutocompleteExclusionBanner

    fun updateHideAutocompleteExclusionBanner(hide: Boolean) {
        state.hideAutocompleteExclusionBanner = hide
    }

    // Returns the union of v1 and v2 patterns to ensure existing users get .env added
    fun getAutocompleteExclusionPatterns(): Set<String> =
        state.autocompleteExclusionPatterns + state.autocompleteExclusionPatternsV2

    fun updateAutocompleteExclusionPatterns(patterns: Set<String>) {
        // Store in v2 field, clear v1 to avoid duplication
        state.autocompleteExclusionPatternsV2 = patterns
        state.autocompleteExclusionPatterns = emptySet()
    }

    fun getDebounceThresholdMs(): Long {
        // IDE-wide storage: delegate to SweepSettings with one-time migration from project state
        val settings = SweepSettings.getInstance()
        if (settings.autocompleteDebounceMs <= 0L) {
            val migrated =
                when {
                    state.autocompleteDebounceMs != -1L -> state.autocompleteDebounceMs
                    state.debounceThresholdMs > 200L -> state.debounceThresholdMs
                    else -> 10L
                }
            settings.autocompleteDebounceMs = migrated.coerceIn(10L, 1000L)
        }
        return settings.autocompleteDebounceMs
    }

    fun updateDebounceThresholdMs(thresholdMs: Long) {
        SweepSettings.getInstance().autocompleteDebounceMs = thresholdMs.coerceIn(10L, 1000L)
    }

    // ---- Privacy ----

    fun isPrivacyModeEnabled(): Boolean = SweepMetaData.getInstance().privacyModeEnabled

    // ---- Commit messages ----

    fun shouldUseCustomizedCommitMessages(): Boolean = state.useCustomizedCommitMessages

    fun updateUseCustomizedCommitMessages(enabled: Boolean) {
        state.useCustomizedCommitMessages = enabled
    }

    /**
     * Returns the commit-message rules to apply, taking the first non-blank rules file found in
     * priority order: project SWEEP.md > project CLAUDE.md > user ~/.sweep/SWEEP.md > user ~/.claude/CLAUDE.md.
     */
    fun getEffectiveCommitMessageRules(): String? {
        val basePath = project.basePath
        val home = System.getProperty("user.home")
        val candidates =
            listOfNotNull(
                basePath?.let { "$it/SWEEP.md" },
                basePath?.let { "$it/CLAUDE.md" },
                "$home/.sweep/SWEEP.md",
                "$home/.claude/CLAUDE.md",
            )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) {
                val text =
                    try {
                        file.readText()
                    } catch (_: Exception) {
                        null
                    }
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    // ---- Settings UI ----

    fun showConfigPopup(tabName: String? = null) {
        // The custom config popup was part of the agent UI; open the standard Sweep settings instead.
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Sweep")
    }

    override fun dispose() {}
}
