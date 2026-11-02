package com.github.b3kt.arthasbridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.util.ClassUtil
import com.github.b3kt.arthasbridge.core.ArthasCommands
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.ConnectionState
import java.io.File

/**
 * Pushes the compiled .class files of the selected class (and its inner classes) into the target JVM
 * with `retransform`. Only method bodies can change; the JVM rejects new fields or methods.
 */
class RetransformAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && codeTarget(e) != null
        e.presentation.isEnabled = project != null &&
            (ArthasService.getInstance(project).state as? ConnectionState.Connected)?.isLocal == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = codeTarget(e) ?: return
        val outer = generateSequence(target.psiClass) { it.containingClass }.last()
        val files = classFiles(outer)
        if (files.isEmpty()) {
            Messages.showWarningDialog(project,
                "No compiled class file found for ${ClassUtil.getJVMClassName(outer)}. Build the project first (Build | Recompile).",
                "Hot-Swap Class")
            return
        }
        val ok = Messages.showOkCancelDialog(project,
            "Replace ${files.size} class(es) in the running JVM with your local build?\n\n" +
                files.joinToString("\n") { "  " + it.name } +
                "\n\nOnly method bodies can change. The change stays until the JVM restarts or you run 'retransform --deleteAll'.",
            "Hot-Swap Class", "Hot-Swap", Messages.getCancelButton(), Messages.getQuestionIcon())
        if (ok == Messages.OK) {
            ArthasService.getInstance(project).run(ArthasCommands.retransform(files.map { it.path }))
        }
    }

    private fun classFiles(psiClass: PsiClass): List<File> {
        val binaryName = ClassUtil.getJVMClassName(psiClass) ?: return emptyList()
        val module = ModuleUtilCore.findModuleForPsiElement(psiClass) ?: return emptyList()
        val vFile = psiClass.containingFile?.virtualFile ?: return emptyList()
        val isTest = ProjectRootManager.getInstance(module.project).fileIndex.isInTestSourceContent(vFile)
        val ext = CompilerModuleExtension.getInstance(module) ?: return emptyList()
        val outputDir = (if (isTest) ext.compilerOutputUrlForTests else ext.compilerOutputUrl)
            ?.removePrefix("file://") ?: return emptyList()

        val relative = binaryName.replace('.', '/')
        val dir = File(outputDir, relative).parentFile ?: return emptyList()
        val simple = relative.substringAfterLast('/')
        return dir.listFiles { f -> f.name == "$simple.class" || (f.name.startsWith("$simple$") && f.name.endsWith(".class")) }
            ?.sortedBy { it.name }
            .orEmpty()
    }
}
