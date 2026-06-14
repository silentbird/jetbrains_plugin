package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.utils.SweepConstants
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page for the autocomplete-only build (Settings > Tools > Sweep).
 *
 * Two independent backends are configured here:
 *  - Autocomplete: a local model server, or a cloud OpenAI-compatible *small* model endpoint.
 *  - AI provider: a separate OpenAI-compatible chat model used for AI features (commit messages).
 *    This is kept separate so a large chat model is never used to serve inline autocomplete.
 */
class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()

    // Autocomplete
    private val enableAutocompleteCheckBox = JBCheckBox("Enable inline autocomplete")
    private val localModeCheckBox = JBCheckBox("Run a fully local model server (localhost)")
    private val localPortField = JBTextField()
    private val acUrlField = JBTextField()
    private val acApiKeyField = JBPasswordField()
    private val acModelField = JBTextField()

    // AI provider (commit messages & AI features)
    private val aiUrlField = JBTextField()
    private val aiApiKeyField = JBPasswordField()
    private val aiModelField = JBTextField()

    override fun createComponent(): JComponent {
        val panel =
            FormBuilder
                .createFormBuilder()
                .addComponent(JBLabel("<html><b>Autocomplete</b></html>"))
                .addComponent(enableAutocompleteCheckBox)
                .addComponent(localModeCheckBox)
                .addLabeledComponent("Local server port:", localPortField)
                .addComponent(
                    JBLabel(
                        "Or point autocomplete at your own self-hosted model server (any OpenAI-compatible API, " +
                            "e.g. vLLM / Ollama / llama.cpp). Used only when local mode is off.",
                    ),
                ).addLabeledComponent("Autocomplete endpoint URL:", acUrlField)
                .addLabeledComponent("Autocomplete API key:", acApiKeyField)
                .addLabeledComponent("Autocomplete model:", acModelField)
                .addSeparator()
                .addComponent(JBLabel("<html><b>AI provider</b> (commit messages &amp; AI features)</html>"))
                .addComponent(
                    JBLabel("OpenAI-compatible /chat/completions. Used for commit-message generation, not autocomplete."),
                ).addLabeledComponent("AI endpoint URL:", aiUrlField)
                .addLabeledComponent("AI API key:", aiApiKeyField)
                .addLabeledComponent("AI model:", aiModelField)
                .addComponentFillVertically(JPanel(), 0)
                .panel
        panel.border = JBUI.Borders.empty(20)
        reset()
        return panel
    }

    override fun isModified(): Boolean =
        enableAutocompleteCheckBox.isSelected != settings.nextEditPredictionFlagOn ||
            localModeCheckBox.isSelected != settings.autocompleteLocalMode ||
            localPortField.text.trim() != settings.autocompleteLocalPort.toString() ||
            acUrlField.text.trim() != settings.customAutocompleteUrl ||
            String(acApiKeyField.password).trim() != settings.customAutocompleteApiKey ||
            acModelField.text.trim() != settings.customAutocompleteModel ||
            aiUrlField.text.trim() != settings.aiProviderUrl ||
            String(aiApiKeyField.password).trim() != settings.aiProviderApiKey ||
            aiModelField.text.trim() != settings.aiProviderModel

    override fun apply() {
        settings.nextEditPredictionFlagOn = enableAutocompleteCheckBox.isSelected
        settings.autocompleteLocalMode = localModeCheckBox.isSelected
        localPortField.text
            .trim()
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?.let { settings.autocompleteLocalPort = it }
        settings.customAutocompleteUrl = acUrlField.text.trim()
        settings.customAutocompleteApiKey = String(acApiKeyField.password).trim()
        settings.customAutocompleteModel = acModelField.text.trim()
        settings.aiProviderUrl = aiUrlField.text.trim()
        settings.aiProviderApiKey = String(aiApiKeyField.password).trim()
        settings.aiProviderModel = aiModelField.text.trim()
    }

    override fun reset() {
        enableAutocompleteCheckBox.isSelected = settings.nextEditPredictionFlagOn
        localModeCheckBox.isSelected = settings.autocompleteLocalMode
        localPortField.text = settings.autocompleteLocalPort.toString()
        acUrlField.text = settings.customAutocompleteUrl
        acApiKeyField.text = settings.customAutocompleteApiKey
        acModelField.text = settings.customAutocompleteModel
        aiUrlField.text = settings.aiProviderUrl
        aiApiKeyField.text = settings.aiProviderApiKey
        aiModelField.text = settings.aiProviderModel
    }

    override fun getDisplayName(): String = SweepConstants.PLUGIN_NAME
}
