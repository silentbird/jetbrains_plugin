package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class SweepActionManager(
    private val project: Project,
) : Disposable {
    var commitMessageAction: AnAction? = null

    companion object {
        fun getInstance(project: Project): SweepActionManager = project.getService(SweepActionManager::class.java)
    }

    override fun dispose() {
        // Clear action references to help with garbage collection
        commitMessageAction = null
    }
}
