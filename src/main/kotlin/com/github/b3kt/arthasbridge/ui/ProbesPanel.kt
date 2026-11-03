package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.ArthasListener
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.Probe
import com.github.b3kt.arthasbridge.core.ProbeHit
import com.github.b3kt.arthasbridge.core.ProbeState
import com.github.b3kt.arthasbridge.core.TraceNode
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.Locale
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Layout:
 *   [toolbar]                 | calls of the selected probe, newest first (trace hits expand into the call tree)
 *   probes (state, hits, …)   |------------------------------------------------------------------------------
 *                             | details of the selected call
 */
class ProbesPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val service = ArthasService.getInstance(project)

    private val probeModel = CollectionListModel<Probe>()
    private val probeList = JBList(probeModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ProbeRenderer()
        emptyText.text = "No probes yet"
        emptyText.appendSecondaryText("Right-click a method → Arthas → Watch or Trace,", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        emptyText.appendLine("or type a watch / trace / stack / monitor / tt -t command below.")
        addListSelectionListener { if (!it.valueIsAdjusting) showProbe(selectedValue) }
    }

    private val hitsRoot = DefaultMutableTreeNode()
    private val hitsModel = DefaultTreeModel(hitsRoot)
    private val hitsTree = Tree(hitsModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = HitRenderer()
        emptyText.text = "Select a probe"
        addTreeSelectionListener { showDetail(selectedHit()) }
    }

    private val detail: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        .apply { setViewer(true) }.console

    private var shown: Probe? = null
    /** Highest hit number in the tree; a hit can arrive both in the snapshot and as an event. */
    private var shownSeq = 0

    init {
        Disposer.register(parent, this)
        Disposer.register(this, detail)

        val toolbar = ActionManager.getInstance().createActionToolbar("ArthasBridge.Probes", actions(), true)
        toolbar.targetComponent = this
        val left = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(probeList, true), BorderLayout.CENTER)
        }
        val right = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(hitsTree, true)
            secondComponent = detail.component
        }
        add(OnePixelSplitter(false, 0.3f).apply {
            firstComponent = left
            secondComponent = right
        }, BorderLayout.CENTER)

        service.probes().forEach(probeModel::add)
        service.addListener(object : ArthasListener {
            override fun probeAdded(probe: Probe) = onEdt {
                probeModel.add(probe)
                probeList.selectedIndex = probeModel.size - 1
            }

            override fun probeChanged(probe: Probe, hit: ProbeHit?) = onEdt {
                val index = probeModel.getElementIndex(probe)
                if (index >= 0) probeModel.contentsChanged(probe)
                if (probe === shown) {
                    if (hit != null) addHit(hit) else updateEmptyText(probe)
                }
            }

            override fun probeRemoved(probe: Probe) = onEdt { probeModel.remove(probe) }
        }, this)
    }

    // ---- probe selection ----

    private fun showProbe(probe: Probe?) {
        shown = probe
        hitsRoot.removeAllChildren()
        val hits = probe?.hits().orEmpty()
        hits.forEach { hitsRoot.insert(hitNode(it), 0) }
        shownSeq = hits.lastOrNull()?.seq ?: 0
        hitsModel.reload()
        detail.clear()
        if (probe == null) {
            hitsTree.emptyText.text = "Select a probe"
            return
        }
        updateEmptyText(probe)
        hitsRoot.children().asSequence().firstOrNull()?.let { expandLatest(it as DefaultMutableTreeNode) }
    }

    private fun updateEmptyText(probe: Probe) {
        hitsTree.emptyText.text = when (probe.state) {
            ProbeState.STARTING -> "Enhancing ${probe.target}…"
            ProbeState.LIVE -> "Waiting for ${probe.target} to be called…"
            ProbeState.FAILED -> probe.message ?: "Failed"
            else -> "No calls recorded"
        }
    }

    private fun addHit(hit: ProbeHit) {
        if (hit.seq <= shownSeq) return
        shownSeq = hit.seq
        val node = hitNode(hit)
        hitsModel.insertNodeInto(node, hitsRoot, 0)
        while (hitsRoot.childCount > Probe.MAX_KEPT_HITS) hitsModel.removeNodeFromParent(hitsRoot.lastChild as DefaultMutableTreeNode)
        if (hitsTree.selectionPath == null) expandLatest(node)
    }

    private fun expandLatest(node: DefaultMutableTreeNode) {
        if (node.childCount == 0) return
        val path = TreePath(node.path)
        hitsTree.expandPath(path)
        // Open the traced method too, so its direct calls and their timings are visible.
        if (node.childCount == 1) hitsTree.expandPath(path.pathByAddingChild(node.firstChild))
    }

    private fun hitNode(hit: ProbeHit) = DefaultMutableTreeNode(hit).apply {
        hit.trace.forEach { add(traceNode(it)) }
    }

    private fun traceNode(n: TraceNode): DefaultMutableTreeNode = DefaultMutableTreeNode(n).apply {
        n.children.forEach { add(traceNode(it)) }
    }

    private fun selectedHit(): ProbeHit? {
        val path = hitsTree.selectionPath ?: return null
        return path.path.asSequence().mapNotNull { (it as DefaultMutableTreeNode).userObject as? ProbeHit }.firstOrNull()
    }

    private fun showDetail(hit: ProbeHit?) {
        detail.clear()
        hit?.detail?.forEach { detail.print(it.text, it.kind.contentType()) }
        detail.scrollTo(0)
    }

    // ---- actions ----

    private fun actions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Stop Probe", "Stop recording calls and restore the class", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) { probeList.selectedValue?.let(service::stopProbe) }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = probeList.selectedValue?.state?.live == true }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Run Again", "Start a new probe with the same command", AllIcons.Actions.Rerun) {
            override fun actionPerformed(e: AnActionEvent) { probeList.selectedValue?.let { service.run(it.command) } }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.isConnected && probeList.selectedValue?.state?.live == false
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Jump to Source", "Open the probed method in the editor", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) { probeList.selectedValue?.let(::navigate) }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = probeList.selectedValue?.className != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Copy Command", null, AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                probeList.selectedValue?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.command)) }
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = probeList.selectedValue != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Remove Probe", "Stop the probe if running and remove it from the list", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) { probeList.selectedValue?.let(service::removeProbe) }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = probeList.selectedValue != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(Separator.getInstance())
        add(object : DumbAwareAction("Clear Finished Probes", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) = service.clearFinishedProbes()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = probeModel.items.any { !it.state.live } }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
    }

    private fun navigate(probe: Probe) {
        probe.className?.let { SourceNavigation.open(project, it, probe.methodName) }
    }

    // ---- rendering ----

    private class ProbeRenderer : ColoredListCellRenderer<Probe>() {
        override fun customizeCellRenderer(list: JList<out Probe>, probe: Probe?, index: Int, selected: Boolean, hasFocus: Boolean) {
            probe ?: return
            icon = when (probe.state) {
                ProbeState.STARTING, ProbeState.LIVE -> AllIcons.Debugger.ThreadRunning
                ProbeState.DONE -> AllIcons.RunConfigurations.TestPassed
                ProbeState.STOPPED -> AllIcons.Debugger.ThreadSuspended
                ProbeState.FAILED -> AllIcons.General.Error
            }
            append(probe.kind + " ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(probe.target, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            val count = probe.limit?.let { "${probe.hitCount}/$it" } ?: "${probe.hitCount}"
            append("   $count", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            probe.lastCostMs?.let { append("  ·  last ${ms(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            if (probe.errorCount > 0) append("  ·  ${probe.errorCount} failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
            if (probe.state == ProbeState.FAILED) append("  ·  failed", SimpleTextAttributes.ERROR_ATTRIBUTES)
            toolTipText = listOfNotNull(probe.command, probe.message).joinToString("\n")
        }
    }

    private class HitRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val data = node.userObject) {
                is ProbeHit -> hit(data)
                is TraceNode -> trace(data, (node.parent as? DefaultMutableTreeNode)?.userObject as? TraceNode)
            }
        }

        private fun hit(hit: ProbeHit) {
            icon = if (hit.error) AllIcons.General.Error else AllIcons.Nodes.Method
            append("#${hit.seq}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(hit.time, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            hit.costMs?.let { append("   ${ms(it)}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }
            append("   " + hit.summary, if (hit.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        private fun trace(n: TraceNode, parent: TraceNode?) {
            icon = if (n.throws) AllIcons.General.Error else AllIcons.Nodes.Method
            val share = parent?.takeIf { it.totalNs > 0 }?.let { n.totalNs * 100.0 / it.totalNs }
            append(n.className.substringBefore("$$").substringAfterLast('.') + "." + n.methodName + "()",
                if (n.throws) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (n.line > 0) append(" :${n.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("   " + ms(n.totalNs / 1_000_000.0), costAttributes(share))
            if (n.times > 1) append("  ×${n.times}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            share?.let { append(String.format(Locale.ROOT, "   %.0f%%  ", it), costAttributes(it)); append(bar(it), costAttributes(it)) }
            n.exception?.let { append("   throws $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
        }

        /** The steps that take most of their parent's time are where to look first. */
        private fun costAttributes(share: Double?) = when {
            share == null -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            share >= 50 -> HOT
            share >= 20 -> WARM
            else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        }

        private fun bar(share: Double) = "▇".repeat(((share + 9) / 10).toInt().coerceIn(1, 10))
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, project.disposed)

    override fun dispose() = Unit

    private companion object {
        val HOT = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xC7222D, 0xFF6B6B))
        val WARM = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xA0620B, 0xE5A84B))

        fun ms(v: Double) = when {
            v >= 1000 -> String.format(Locale.ROOT, "%.2f s", v / 1000)
            v >= 10 -> String.format(Locale.ROOT, "%.0f ms", v)
            else -> String.format(Locale.ROOT, "%.2f ms", v)
        }
    }
}
