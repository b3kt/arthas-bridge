package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.ArthasListener
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.Probe
import com.github.b3kt.arthasbridge.core.ProbeHit
import com.github.b3kt.arthasbridge.core.ProbeState
import com.github.b3kt.arthasbridge.core.stats
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.ClassUtil
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

/**
 * Marks probed methods in open Java / Kotlin editors: a gutter icon while a probe is live (click → Probes tab)
 * and the probe's numbers at the end of the method's declaration line, kept until the probe is removed.
 */
@Service(Service.Level.PROJECT)
class ProbeEditorDecorator(private val project: Project) : Disposable {

    private val service get() = ArthasService.getInstance(project)
    private val started = AtomicBoolean()
    private val pending = AtomicBoolean()
    @Volatile private var lastRefresh = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        service.addListener(object : ArthasListener {
            override fun probeAdded(probe: Probe) = schedule()
            override fun probeChanged(probe: Probe, hit: ProbeHit?) = schedule()
            override fun probeRemoved(probe: Probe) = schedule()
        }, this)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) schedule()
            }
        }, this)
        schedule()
    }

    /** Coalesces bursts of hits into at most two refreshes a second. */
    private fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        val delay = (REFRESH_INTERVAL_MS - (System.currentTimeMillis() - lastRefresh)).coerceAtLeast(0)
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater({
                pending.set(false)
                lastRefresh = System.currentTimeMillis()
                refreshAll()
            }, project.disposed)
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun refreshAll() {
        if (project.isDisposed) return
        // Wildcard patterns can't be pinned to one method in the source.
        val probes = service.probes()
            .filter { it.className?.contains('*') == false && it.methodName?.contains('*') == false }
            .filter { it.state.live || it.hitCount > 0 }
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.isDisposed || editor.project != project) continue
            clear(editor)
            if (probes.isNotEmpty()) decorate(editor, probes)
        }
    }

    private fun decorate(editor: Editor, probes: List<Probe>) {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? PsiClassOwner ?: return
        val anchors = ApplicationManager.getApplication().runReadAction(Computable { anchors(file, probes) })
        val added = mutableListOf<Disposable>()
        for ((offset, onMethod) in anchors) {
            val live = onMethod.filter { it.state.live }
            if (live.isNotEmpty()) {
                val highlighter = editor.markupModel.addRangeHighlighter(
                    offset, offset, HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE,
                )
                highlighter.gutterIconRenderer = ProbeGutter(live)
                added += Disposable { editor.markupModel.removeHighlighter(highlighter) }
            }
            val text = onMethod.joinToString("     ") { p ->
                "${p.kind}: " + if (p.hitCount == 0) "waiting for calls…" else p.stats.label(p.kind)
            }
            editor.inlayModel.addAfterLineEndElement(offset, false, StatsRenderer(text, onMethod.any { it.errorCount > 0 }))
                ?.let { added += it }
        }
        editor.putUserData(DECORATIONS, added)
    }

    /** Offset of each probed method's name in [file], with the probes on it. */
    private fun anchors(file: PsiClassOwner, probes: List<Probe>): Map<Int, List<Probe>> {
        val byClass = probes.groupBy { it.className!! }
        val out = linkedMapOf<Int, MutableList<Probe>>()
        fun visit(c: PsiClass) {
            byClass[ClassUtil.getJVMClassName(c)]?.forEach { probe ->
                val name = probe.methodName!!
                val methods = if (name == "<init>") c.constructors.toList() else c.findMethodsByName(name, false).toList()
                methods.forEach { m ->
                    // Kotlin light methods point back to the source declaration.
                    val source = m.navigationElement
                    if (source.containingFile?.virtualFile != (file as PsiFile).virtualFile) return@forEach
                    val anchor = (source as? PsiNameIdentifierOwner)?.nameIdentifier ?: source
                    out.getOrPut(anchor.textRange.startOffset) { mutableListOf() } += probe
                }
            }
            c.innerClasses.forEach(::visit)
        }
        file.classes.forEach(::visit)
        return out
    }

    private fun clear(editor: Editor) {
        editor.getUserData(DECORATIONS)?.forEach { it.dispose() }
        editor.putUserData(DECORATIONS, null)
    }

    private inner class ProbeGutter(private val probes: List<Probe>) : GutterIconRenderer() {
        override fun getIcon(): Icon = iconFor(probes.first().kind)

        override fun getTooltipText(): String = probes.joinToString("<br>", "<html>", "</html>") { p ->
            val state = if (p.state == ProbeState.STARTING) "starting" else "live"
            "<b>${p.kind}</b> ${escape(p.target)} ($state): " + if (p.hitCount == 0) "waiting for calls" else escape(p.stats.label(p.kind))
        } + "<br>Click to open in the Probes tab"

        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = service.revealProbe(probes.first())
        }

        override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup().apply {
            probes.forEach { p ->
                add(object : DumbAwareAction("Show ${p.kind} ${p.target}", null, AllIcons.Actions.Find) {
                    override fun actionPerformed(e: AnActionEvent) = service.revealProbe(p)
                })
                add(object : DumbAwareAction("Stop ${p.kind} ${p.target}", null, AllIcons.Actions.Suspend) {
                    override fun actionPerformed(e: AnActionEvent) = service.stopProbe(p)
                })
            }
        }

        override fun isNavigateAction() = true

        override fun equals(other: Any?) = other is ProbeGutter && other.probes.map { it.id } == probes.map { it.id }
        override fun hashCode() = probes.map { it.id }.hashCode()
    }

    /** Dim italic text after the line, like the IDE's own inline hints. */
    private class StatsRenderer(private val text: String, private val warn: Boolean) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            return editor.contentComponent.getFontMetrics(font(editor)).stringWidth(text) + JBUI.scale(GAP)
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, target: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val font = font(editor)
            val metrics = editor.contentComponent.getFontMetrics(font)
            g.font = font
            g.color = if (warn) WARN else {
                editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT)?.foregroundColor ?: JBColor.GRAY
            }
            g.drawString(text, target.x + JBUI.scale(GAP), target.y + (target.height - metrics.height) / 2 + metrics.ascent)
        }

        private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::clear)
    }

    companion object {
        private val DECORATIONS = Key.create<List<Disposable>>("ArthasBridge.ProbeDecorations")
        private const val REFRESH_INTERVAL_MS = 500L
        private const val GAP = 16
        private val WARN = JBColor(0xC7222D, 0xFF6B6B)

        fun iconFor(kind: String): Icon = when (kind) {
            "trace" -> AllIcons.Actions.ProfileCPU
            "stack" -> AllIcons.Debugger.Frame
            "monitor" -> AllIcons.Actions.Show
            "tt" -> AllIcons.Vcs.History
            else -> AllIcons.Debugger.Watch
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun getInstance(project: Project): ProbeEditorDecorator = project.service()
    }
}
