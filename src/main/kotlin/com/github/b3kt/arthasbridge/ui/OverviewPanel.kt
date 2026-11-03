package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.ArthasListener
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.ConnectionState
import com.github.b3kt.arthasbridge.core.JvmSnapshot
import com.github.b3kt.arthasbridge.core.ThreadSample
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.TableView
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable

/**
 * Layout:
 *   JDK · uptime · updated at                                   [x] Auto-refresh [⟳]
 *   [CPU] [Heap] [Threads] [GC]      value, detail line and a ~3 minute sparkline each
 *   Busiest threads   Find deadlock · All threads
 *   table of threads by CPU (double-click → stack in the console)
 *
 * Samples only while this tab is visible and a JVM is attached.
 */
class OverviewPanel(
    private val project: Project,
    parent: Disposable,
    /** Runs a command in the console tab and shows it. */
    private val runInConsole: (String) -> Unit,
) : JPanel(CardLayout()), Disposable {

    private val service = ArthasService.getInstance(project)

    private val info = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val autoRefresh = JBCheckBox("Auto-refresh", true).apply {
        toolTipText = "Sample every $INTERVAL_SECONDS seconds while this tab is visible"
        addActionListener { if (isSelected) refresh() }
    }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh now"
        addActionListener { refresh() }
    }

    private val cpu = Tile("CPU")
    private val heap = Tile("Heap")
    private val threads = Tile("Threads")
    private val gc = Tile("GC since last sample")
    private val tiles = JPanel(GridLayout(1, 4, JBUI.scale(8), JBUI.scale(8))).apply {
        isOpaque = false
        listOf(cpu, heap, threads, gc).forEach(::add)
        // Four in a row when docked at the bottom, two by two in a narrow side tool window.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val columns = if (width >= JBUI.scale(640)) 4 else 2
                val grid = layout as GridLayout
                if (grid.columns != columns) {
                    grid.columns = columns
                    grid.rows = 4 / columns
                    revalidate()
                }
            }
        })
    }

    private val threadModel = ListTableModel<ThreadSample>(
        column("ID", 60) { it.id.toString() },
        column("Name", 320) { it.name },
        StateColumn(),
        column("CPU", 70) { String.format(Locale.ROOT, "%.1f%%", it.cpu) },
    )
    private val threadTable = TableView(threadModel).apply {
        setShowGrid(false)
        emptyText.text = "Sampling threads…"
        toolTipText = "Double-click a thread to print its stack in the console"
    }

    private val cpuHistory = ArrayDeque<Double>()
    private val heapHistory = ArrayDeque<Double>()
    private val threadHistory = ArrayDeque<Double>()
    private val gcHistory = ArrayDeque<Double>()
    private var previous: JvmSnapshot? = null

    @Volatile private var showing = false
    @Volatile private var inFlight = false

    init {
        Disposer.register(parent, this)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(info, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(autoRefresh)
                add(refreshButton)
            }, BorderLayout.EAST)
        }
        val threadsHeader = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(10)
            add(JBLabel("Busiest threads").apply { font = JBFont.label().asBold() })
            add(ActionLink("Find deadlock") { runInConsole("thread -b") }.apply {
                toolTipText = "Show the thread holding a lock that others are blocked on (thread -b)"
            })
            add(ActionLink("All threads") { runInConsole("thread") })
        }
        val top = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(header, tiles, threadsHeader).forEach {
                it.alignmentX = LEFT_ALIGNMENT
                add(it)
            }
            header.border = JBUI.Borders.emptyBottom(8)
        }
        val content = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(threadTable), BorderLayout.CENTER)
        }
        val empty = JBPanelWithEmptyText().apply {
            emptyText.text = "Attach to a JVM to see CPU, memory, threads and GC"
        }
        add(empty, EMPTY)
        add(content, CONTENT)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val thread = threadTable.selectedObject ?: return false
                runInConsole("thread ${thread.id}")
                return true
            }
        }.installOn(threadTable)

        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                showing = isShowing
                if (showing) refresh()
            }
        }
        service.addListener(object : ArthasListener {
            override fun stateChanged(state: ConnectionState) = onEdt { onState(state) }
        }, this)
        onState(service.state)

        val poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (autoRefresh.isSelected) refresh()
        }, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS)
        Disposer.register(this) { poller.cancel(false) }
    }

    private fun onState(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        (layout as CardLayout).show(this, if (connected) CONTENT else EMPTY)
        if (connected) {
            listOf(cpuHistory, heapHistory, threadHistory, gcHistory).forEach { it.clear() }
            previous = null
            refresh()
        }
    }

    /** Takes a sample in the background; skipped when hidden, disconnected or a sample is already running. */
    private fun refresh() {
        if (!showing || !service.isConnected || inFlight) return
        inFlight = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.snapshot()?.let { s -> onEdt { show(s) } }
            } finally {
                inFlight = false
            }
        }
    }

    private fun show(s: JvmSnapshot) {
        if (!service.isConnected) return
        val prev = previous
        previous = s
        info.text = "JDK ${s.javaVersion}  ·  ${s.processors} cores  ·  up ${uptime(s.uptimeSeconds)}  ·  " +
            "updated ${LocalTime.now().format(TIME)}"

        val cpuPercent = s.cpuPercent
        cpu.update(String.format(Locale.ROOT, "%.0f%%", cpuPercent),
            String.format(Locale.ROOT, "load %.2f", s.systemLoad), cpuPercent >= 80)
        cpu.spark.set(push(cpuHistory, cpuPercent), 100.0)

        val heapPercent = s.heapPercent
        heap.update("${mb(s.heapUsed)} / ${mb(s.heapMax)} MB",
            String.format(Locale.ROOT, "%.0f%% used  ·  non-heap %s MB", heapPercent, mb(s.nonHeapUsed)), heapPercent >= 85)
        heap.spark.set(push(heapHistory, heapPercent), 100.0)

        threads.update("${s.threads.size}", "${s.blockedThreads} blocked  ·  ${s.daemonThreads} daemon", s.blockedThreads > 0)
        threads.spark.set(push(threadHistory, s.threads.size.toDouble()), null)

        val gcCount = prev?.let { s.gcCount - it.gcCount }
        val gcTime = prev?.let { s.gcTimeMs - it.gcTimeMs }
        gc.update(if (gcCount == null) "–" else "+$gcCount  ·  $gcTime ms",
            "total ${s.gcCount}  ·  ${s.gcTimeMs} ms", (gcTime ?: 0) >= INTERVAL_SECONDS * 1000 / 10)
        gc.spark.set(push(gcHistory, (gcTime ?: 0).toDouble()), null)

        val selectedId = threadTable.selectedObject?.id
        threadModel.items = s.hotThreads(HOT_THREADS)
        threadModel.items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { threadTable.setRowSelectionInterval(it, it) }
    }

    private fun push(history: ArrayDeque<Double>, value: Double): List<Double> {
        history.addLast(value)
        while (history.size > HISTORY) history.removeFirst()
        return history.toList()
    }

    // ---- components ----

    private class Tile(title: String) : JPanel() {
        private val value = JBLabel().apply { font = JBFont.h2().asBold() }
        private val detail = JBLabel().apply { font = JBFont.small() }
        val spark = Sparkline()

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(IdeBorderFactory.createRoundedBorder(JBUI.scale(8)), JBUI.Borders.empty(6, 10))
            val label = JBLabel(title).apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }
            listOf(label, value, detail, spark).forEach {
                it.alignmentX = LEFT_ALIGNMENT
                add(it)
            }
            update("–", " ", false)
        }

        fun update(value: String, detail: String, warn: Boolean) {
            this.value.text = value
            this.value.foreground = if (warn) WARN else UIUtil.getLabelForeground()
            this.detail.text = detail
            this.detail.foreground = if (warn) WARN else UIUtil.getContextHelpForeground()
            spark.color = if (warn) WARN else LINE
        }
    }

    /** Recent values, right-aligned so the newest sample is always at the right edge. */
    private class Sparkline : JComponent() {
        private var values: List<Double> = emptyList()
        private var max: Double? = null
        var color: Color = LINE

        init {
            preferredSize = JBUI.size(120, 24)
            maximumSize = JBUI.size(Int.MAX_VALUE / 2, 24)
        }

        /** [max] null scales to the largest value shown. */
        fun set(values: List<Double>, max: Double?) {
            this.values = values
            this.max = max
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            if (values.size < 2) return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.stroke = BasicStroke(JBUIScale.scale(1.5f))
                val top = (max ?: (values.max() * 1.2)).coerceAtLeast(1.0)
                val h = height - JBUI.scale(4)
                val step = width.toDouble() / (HISTORY - 1)
                val offset = HISTORY - values.size
                val xs = IntArray(values.size) { ((offset + it) * step).toInt() }
                val ys = IntArray(values.size) { JBUI.scale(2) + h - (values[it].coerceIn(0.0, top) / top * h).toInt() }
                g2.drawPolyline(xs, ys, values.size)
            } finally {
                g2.dispose()
            }
        }
    }

    private class StateColumn : ColumnInfo<ThreadSample, String>("State") {
        override fun valueOf(item: ThreadSample) = item.state
        override fun getWidth(table: JTable) = JBUI.scale(110)
        override fun getRenderer(item: ThreadSample) = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                append(value?.toString().orEmpty(),
                    if (value == "BLOCKED") SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    private fun column(name: String, width: Int, value: (ThreadSample) -> String) = object : ColumnInfo<ThreadSample, String>(name) {
        override fun valueOf(item: ThreadSample) = value(item)
        override fun getWidth(table: JTable) = JBUI.scale(width)
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, project.disposed)

    override fun dispose() = Unit

    private companion object {
        const val EMPTY = "empty"
        const val CONTENT = "content"
        const val INTERVAL_SECONDS = 3L
        /** About three minutes of samples. */
        const val HISTORY = 60
        const val HOT_THREADS = 15
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val LINE = JBColor(0x3574F0, 0x548AF7)
        val WARN = JBColor(0xC7222D, 0xFF6B6B)

        fun mb(bytes: Long) = (bytes / (1024 * 1024)).toString()

        fun uptime(seconds: Long) = when {
            seconds >= 86_400 -> "${seconds / 86_400}d ${seconds % 86_400 / 3600}h"
            seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
