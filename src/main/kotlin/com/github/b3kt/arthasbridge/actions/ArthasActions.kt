package com.github.b3kt.arthasbridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import com.github.b3kt.arthasbridge.core.ArthasCommands
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.ProbeOptions
import com.github.b3kt.arthasbridge.ui.ProbeOptionsPopup
import com.github.b3kt.arthasbridge.settings.ArthasSettings
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType

/** Hides the "Arthas" submenu outside Java / Kotlin code. */
class ArthasActionGroup : DefaultActionGroup(), DumbAware {
    init {
        templatePresentation.isHideGroupIfEmpty = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** The class (and optionally method) the user right-clicked, as JVM names. */
data class CodeTarget(val className: String, val methodName: String?, val psiClass: PsiClass)

/** Resolves the code under the caret or the selected element; works for Java and Kotlin via UAST. */
internal fun codeTarget(e: AnActionEvent): CodeTarget? {
    val element = selectedElement(e) ?: return null
    val method = (element as? PsiMethod)
        ?: element.getUastParentOfType<UMethod>(strict = false)?.javaPsi
    val psiClass = method?.containingClass
        ?: (element as? PsiClass)
        ?: element.getUastParentOfType<UClass>(strict = false)?.javaPsi
        ?: return null
    val className = ClassUtil.getJVMClassName(psiClass) ?: return null
    val methodName = method?.let { if (it.isConstructor) "<init>" else it.name }
    return CodeTarget(className, methodName, psiClass)
}

private fun selectedElement(e: AnActionEvent): PsiElement? {
    val editor = e.getData(CommonDataKeys.EDITOR)
    val file = e.getData(CommonDataKeys.PSI_FILE)
    if (editor != null && file != null) {
        return file.findElementAt(editor.caretModel.offset)
    }
    val element = e.getData(CommonDataKeys.PSI_ELEMENT) ?: return null
    // Project view selects files; use the first top-level class.
    return if (element is com.intellij.psi.PsiFile) PsiTreeUtil.findChildOfType(element, PsiClass::class.java) ?: element else element
}

/** Base for "right-click → Arthas → X" actions. */
abstract class ArthasCodeAction(private val needsMethod: Boolean) : AnAction(), DumbAware {

    abstract fun command(target: CodeTarget, options: ProbeOptions): String

    /** Probes (watch, trace, …) first ask for depth / limit / condition; null runs straight away. */
    open val probeKind: String? = null

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = if (e.project != null) codeTarget(e) else null
        e.presentation.isEnabledAndVisible = target != null && (!needsMethod || target.methodName != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = codeTarget(e) ?: return
        val settings = ArthasSettings.getInstance().state
        val run = { options: ProbeOptions -> ArthasService.getInstance(project).runFromEditor(command(target, options)) }
        val kind = probeKind
        if (kind == null || !settings.askProbeOptions) return run(ProbeOptions(settings.expandDepth, settings.resultLimit))
        val label = target.className.substringAfterLast('.') + "." + target.methodName
        ProbeOptionsPopup.show(kind, label, e.getData(CommonDataKeys.EDITOR), e.dataContext, run)
    }
}

class WatchAction : ArthasCodeAction(needsMethod = true) {
    override val probeKind = "watch"
    override fun command(target: CodeTarget, options: ProbeOptions) =
        ArthasCommands.watch(target.className, target.methodName!!, options.depth, options.limit, options.condition)
}

class TraceAction : ArthasCodeAction(needsMethod = true) {
    override val probeKind = "trace"
    override fun command(target: CodeTarget, options: ProbeOptions) =
        ArthasCommands.trace(target.className, target.methodName!!, options.limit, options.condition)
}

class StackAction : ArthasCodeAction(needsMethod = true) {
    override val probeKind = "stack"
    override fun command(target: CodeTarget, options: ProbeOptions) =
        ArthasCommands.stack(target.className, target.methodName!!, options.limit, options.condition)
}

class MonitorAction : ArthasCodeAction(needsMethod = true) {
    override val probeKind = "monitor"
    override fun command(target: CodeTarget, options: ProbeOptions) =
        ArthasCommands.monitor(target.className, target.methodName!!, options.limit, options.condition)
}

class TimeTunnelAction : ArthasCodeAction(needsMethod = true) {
    override val probeKind = "tt"
    override fun command(target: CodeTarget, options: ProbeOptions) =
        ArthasCommands.timeTunnel(target.className, target.methodName!!, options.limit, options.condition)
}

class JadAction : ArthasCodeAction(needsMethod = false) {
    override fun command(target: CodeTarget, options: ProbeOptions) = ArthasCommands.jad(target.className)
}

class SearchClassAction : ArthasCodeAction(needsMethod = false) {
    override fun command(target: CodeTarget, options: ProbeOptions) = ArthasCommands.classInfo(target.className)
}
