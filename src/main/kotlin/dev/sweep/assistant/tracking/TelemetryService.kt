package dev.sweep.assistant.tracking

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

enum class EventType {
    INSTALL_SWEEP,
    UNINSTALL_SWEEP,
    AUTOCOMPLETE_SNOOZED,
    AUTOCOMPLETE_DISABLED,
    AUTOCOMPLETE_TUTORIAL_SHOWN,
    AUTOCOMPLETE_TUTORIAL_COMPLETED,
    AUTOCOMPLETE_KEYBINDING_CHANGED,
}

/**
 * Local no-op stub of the former telemetry service.
 *
 * The original implementation posted usage events to the Sweep backend. The autocomplete-only
 * build does not phone home, so call sites are preserved but every event is dropped.
 */
@Service(Service.Level.APP)
class TelemetryService {
    fun sendUsageEvent(
        eventType: EventType,
        userProperties: Map<String, String> = emptyMap(),
        eventProperties: Map<String, String> = emptyMap(),
    ) {
        // no-op: telemetry is disabled in the autocomplete-only build
    }

    companion object {
        fun getInstance(): TelemetryService = ApplicationManager.getApplication().getService(TelemetryService::class.java)
    }
}
