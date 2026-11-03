package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.ArthasListener
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.ConnectionState
import com.github.b3kt.arthasbridge.core.InspectParser
import com.github.b3kt.arthasbridge.core.StackFrame
import com.github.b3kt.arthasbridge.core.ThreadDetail
import com.github.b3kt.arthasbridge.core.ThreadSample
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Layout:
 *   [filter] [state ▾] [x] Hide Arthas threads  [⟳]   37 threads · 0 blocked   Find deadlock
 *   threads table (sortable)  |  stack of the selected thread (double-click a frame → source)
 *
 * Loads when the tab is first shown and on refresh; sampling CPU takes half a second.
 */
class ThreadsPanel(private val project: Project, parent: Disposable) : JPanel(CardLayout()), Disposable {

    private val service = ArthasService.getInstance(project)

    private val filter = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter by name"
        textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
    }
    private val stateFilter = ComboBox(arrayOf(ALL_STATES, "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING")).apply {
        addActionListener { applyFilter() }
    }
    private val hideArthas = JBCheckBox("Hide Arthas threads", true).apply { addActionListener { applyFilter() } }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh (samples CPU for half a second)"
        addActionListener { load() }
    }
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val threadModel = ListTableModel<ThreadSample>(
        arrayOf(
            column("ID", 60, compareBy { it.id }) { it.id.toString() },
            column("Name", 300, compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) { it.name },
            column("Group", 90, compareBy { it.group }) { it.group },
            StateColumn(),
            column("CPU", 70, compareBy { it.cpu }) { String.format(Locale.ROOT, "%.1f%%", it.cpu) },
            column("CPU time", 90, compareBy { it.timeMs }) { "${it.timeMs} ms" },
            column("Daemon", 60, compareBy { it.daemon }) { if (it.daemon) "yes" else "" },
        ),
        emptyList(),
        4, // sorted by CPU
        SortOrder.DESCENDING,
    )
    private val threadTable = TableView(threadModel).apply {
        setShowGrid(false)
        emptyText.text = "Loading threads…"
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selectedObject?.let(::loadStack) }
    }
    private var allThreads: List<ThreadSample> = emptyList()

    private val stackHeader = JBLabel().apply { border = JBUI.Borders.empty(4, 6) }
    private val frameModel = CollectionListModel<StackFrame>()
    private var projectClasses: Set<String> = emptySet()
    private val frameList = JBList(frameModel).apply {
        emptyText.text = "Select a thread to see its stack"
        cellRenderer = FrameRenderer()
        toolTipText = "Double-click or press Enter to open the source"
    }

    @Volatile private var loaded = false
    private var stackRequest = 0

    init {
        Disposer.register(parent, this)

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(filter)
            add(stateFilter)
            add(hideArthas)
            add(refreshButton)
            add(summary)
            add(ActionLink("Find deadlock") { findDeadlock() }.apply {
                toolTipText = "Select the thread holding a lock that others are blocked on (thread -b)"
            })
        }
        val stackPanel = JPanel(BorderLayout()).apply {
            add(stackHeader, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(frameList, true), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                add(ActionLink("Copy stack") { copyStack() })
            }, BorderLayout.SOUTH)
        }
        val content = JPanel(BorderLayout()).apply {
            add(bar, BorderLayout.NORTH)
            add(OnePixelSplitter(false, 0.55f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(threadTable, true)
                secondComponent = stackPanel
            }, BorderLayout.CENTER)
        }
        add(JBPanelWithEmptyText().apply { emptyText.text = "Attach to a JVM to list its threads" }, EMPTY)
        add(content, CONTENT)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent) = openFrame()
        }.installOn(frameList)
        frameList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openFrame()
            }
        })

        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && !loaded) load()
        }
        service.addListener(object : ArthasListener {
            override fun stateChanged(state: ConnectionState) = onEdt { onState(state) }
        }, this)
        onState(service.state)
    }

    private fun onState(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        (layout as CardLayout).show(this, if (connected) CONTENT else EMPTY)
        loaded = false
        if (!connected) {
            allThreads = emptyList()
            threadModel.items = emptyList()
            frameModel.removeAll()
            stackHeader.text = ""
        } else if (isShowing) {
            load()
        }
    }

    // ---- loading ----

    private fun load() {
        if (!service.isConnected) return
        loaded = true
        refreshButton.isEnabled = false
        threadTable.emptyText.text = "Loading threads…"
        background {
            val result = service.query("thread --all -i 500")
            val threads = InspectParser.threads(result.results)
            onEdt {
                refreshButton.isEnabled = true
                threadTable.emptyText.text = result.error ?: "No threads match the filter"
                val selectedId = threadTable.selectedObject?.id
                allThreads = threads
                applyFilter()
                selectThread(selectedId)
            }
        }
    }

    private fun applyFilter() {
        val text = filter.text.trim()
        val state = stateFilter.selectedItem as String
        val visible = allThreads.filter { t ->
            (text.isEmpty() || t.name.contains(text, ignoreCase = true)) &&
                (state == ALL_STATES || t.state == state) &&
                !(hideArthas.isSelected && t.isArthas)
        }
        val selectedId = threadTable.selectedObject?.id
        threadModel.items = visible
        selectThread(selectedId)
        val blocked = allThreads.count { it.state == "BLOCKED" }
        summary.text = "${allThreads.size} threads  ·  ${allThreads.count { it.state == "RUNNABLE" }} runnable  ·  $blocked blocked"
        summary.foreground = if (blocked > 0) WARN else UIUtil.getContextHelpForeground()
    }

    private fun selectThread(id: Long?) {
        val row = threadModel.items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val viewRow = threadTable.convertRowIndexToView(row)
        threadTable.setRowSelectionInterval(viewRow, viewRow)
    }

    private fun loadStack(thread: ThreadSample) = loadStack("thread ${thread.id}")

    private fun loadStack(command: String) {
        val request = ++stackRequest
        stackHeader.text = "Loading…"
        background {
            val result = service.query(command)
            val detail = InspectParser.threadDetail(result.results)
            val ours = detail?.let { d -> SourceNavigation.projectClasses(project, d.frames.map { it.className }) }.orEmpty()
            onEdt {
                if (request != stackRequest) return@onEdt
                projectClasses = ours
                showStack(detail, result.error)
            }
        }
    }

    private fun showStack(detail: ThreadDetail?, error: String?) {
        frameModel.removeAll()
        if (detail == null) {
            stackHeader.text = error ?: "Thread has ended"
            return
        }
        frameModel.add(detail.frames)
        stackHeader.text = buildString {
            append("<html><b>").append(escape(detail.name)).append("</b>  #").append(detail.id).append("  ·  ").append(detail.state)
            detail.lockName?.let { append("  ·  waiting on ").append(escape(it)) }
            detail.lockOwnerName?.let { append(" held by <b>").append(escape(it)).append("</b>") }
            append("</html>")
        }
        // The first frame of your own code is usually the one worth reading.
        val firstOurs = detail.frames.indexOfFirst { it.className in projectClasses }
        if (firstOurs >= 0) frameList.ensureIndexIsVisible(firstOurs)
    }

    private fun findDeadlock() {
        threadTable.clearSelection()
        val request = ++stackRequest
        stackHeader.text = "Looking for a blocking thread…"
        frameModel.removeAll()
        background {
            val result = service.query("thread -b")
            val detail = InspectParser.threadDetail(result.results)
            val ours = detail?.let { d -> SourceNavigation.projectClasses(project, d.frames.map { it.className }) }.orEmpty()
            onEdt {
                if (request != stackRequest) return@onEdt
                projectClasses = ours
                if (detail == null) {
                    stackHeader.text = if (result.error?.contains("No most blocking", ignoreCase = true) == true) {
                        "No thread is blocking others."
                    } else {
                        result.error ?: "No thread is blocking others."
                    }
                } else {
                    showStack(detail, null)
                }
            }
        }
    }

    private fun openFrame(): Boolean {
        val frame = frameList.selectedValue ?: return false
        SourceNavigation.open(project, frame.className, frame.methodName, frame.line)
        return true
    }

    private fun copyStack() {
        if (frameModel.isEmpty) return
        val text = frameModel.items.joinToString("\n") { "\tat $it" }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    // ---- rendering ----

    private inner class FrameRenderer : ColoredListCellRenderer<StackFrame>() {
        override fun customizeCellRenderer(list: JList<out StackFrame>, frame: StackFrame?, index: Int, selected: Boolean, hasFocus: Boolean) {
            frame ?: return
            val ours = frame.className in projectClasses
            icon = if (ours) AllIcons.Nodes.Method else AllIcons.Nodes.EmptyNode
            val attributes = if (ours) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            append(frame.className.substringAfterLast('.') + "." + frame.methodName, attributes)
            val location = frame.toString().substringAfterLast('(').removeSuffix(")")
            append("  ($location)", if (frame.line > 0) SimpleTextAttributes.LINK_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  " + frame.className.substringBeforeLast('.', ""), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private class StateColumn : ColumnInfo<ThreadSample, String>("State") {
        override fun valueOf(item: ThreadSample) = item.state
        override fun getWidth(table: JTable) = JBUI.scale(110)
        override fun getComparator(): Comparator<ThreadSample> = compareBy { it.state }
        override fun getRenderer(item: ThreadSample) = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                append(value?.toString().orEmpty(),
                    if (value == "BLOCKED") SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    private fun column(name: String, width: Int, comparator: Comparator<ThreadSample>, value: (ThreadSample) -> String) =
        object : ColumnInfo<ThreadSample, String>(name) {
            override fun valueOf(item: ThreadSample) = value(item)
            override fun getWidth(table: JTable) = JBUI.scale(width)
            override fun getComparator() = comparator
        }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun background(block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread(block)

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, project.disposed)

    override fun dispose() = Unit

    private companion object {
        const val EMPTY = "empty"
        const val CONTENT = "content"
        const val ALL_STATES = "All states"
        val WARN = JBColor(0xC7222D, 0xFF6B6B)
    }
}
