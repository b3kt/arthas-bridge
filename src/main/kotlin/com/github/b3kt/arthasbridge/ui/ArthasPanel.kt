package com.github.b3kt.arthasbridge.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.DumbAware
import com.github.b3kt.arthasbridge.settings.ArthasSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TextFieldWithStoredHistory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.github.b3kt.arthasbridge.core.ArthasCommands
import com.github.b3kt.arthasbridge.core.ArthasListener
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.ConnectionState
import com.github.b3kt.arthasbridge.core.JvmProcess
import com.github.b3kt.arthasbridge.core.JvmProcesses
import com.github.b3kt.arthasbridge.core.OutputKind
import com.github.b3kt.arthasbridge.core.Probe
import com.github.b3kt.arthasbridge.core.ProbeHit
import com.github.b3kt.arthasbridge.settings.ArthasConfigurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/**
 * Layout:
 *   [status]  JVM: [process ▾] [⟳] [Attach]  Connect to address…        [Stop Arthas] [Disconnect]
 *   [toolbar] | [Overview | Probes | Console] tabs: JVM health (OverviewPanel), live probes (ProbesPanel), console output
 *   › [command input with history]  [Run]
 */
class ArthasPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(false, true), Disposable {

    private val service = ArthasService.getInstance(project)

    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        .apply { setViewer(true) }.console

    private val status = JBLabel()
    private val processModel = DefaultComboBoxModel<JvmProcess>()
    private val processCombo = ComboBox(processModel, JBUI.scale(380)).apply {
        renderer = object : SimpleListCellRenderer<JvmProcess>() {
            override fun customize(list: JList<out JvmProcess>, value: JvmProcess?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let { "${it.pid}   ${it.name}" } ?: "No JVM found, start your app and refresh"
            }
        }
        addActionListener { render(service.state) }
    }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh JVM list"
        addActionListener { refreshProcesses() }
    }
    private val attachButton = JButton("Attach", AllIcons.Actions.Execute).apply {
        toolTipText = "Load Arthas into the selected JVM"
        addActionListener { (processCombo.selectedItem as? JvmProcess)?.let(service::attach) }
    }
    private val connectLink = ActionLink("Connect to address…") { askAddress() }.apply {
        toolTipText = "Use an Arthas that is already running, e.g. through kubectl port-forward"
    }
    private val stopButton = JButton("Stop Arthas", AllIcons.Actions.Suspend).apply {
        toolTipText = "Shut Arthas down in the target JVM and restore all enhanced classes. The app keeps running."
        addActionListener { service.stopArthas() }
    }
    private val disconnectButton = JButton("Disconnect").apply {
        toolTipText = "Leave Arthas running in the target and just disconnect the IDE"
        addActionListener { service.disconnect() }
    }

    private val input = TextFieldWithStoredHistory("ArthasBridge.commandHistory").apply {
        setHistorySize(50)
        (textEditor as? JBTextField)?.emptyText?.text = "Arthas command, e.g. sc -d *PaymentService*   (↑↓ history)"
        textEditor.addActionListener { submit() }
    }
    private val runButton = JButton("Run", AllIcons.Actions.Execute).apply { addActionListener { submit() } }

    private val overview = OverviewPanel(project, this) { command ->
        tabs.selectedComponent = console.component
        service.run(command)
    }
    private val probes = ProbesPanel(project, this)
    private val tabs: JBTabbedPane = JBTabbedPane().apply {
        addTab("Overview", AllIcons.Actions.ProfileCPU, overview)
        addTab("Probes", AllIcons.Debugger.Watch, probes)
        addTab("Console", AllIcons.Debugger.Console, console.component)
        selectedComponent = console.component
    }

    init {
        Disposer.register(parent, this)
        Disposer.register(this, console)

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(row(status, JBLabel("JVM:"), processCombo, refreshButton, attachButton, connectLink), BorderLayout.WEST)
            add(row(stopButton, disconnectButton), BorderLayout.EAST)
        }
        val commandBar = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JBLabel("›"), BorderLayout.WEST)
            add(input, BorderLayout.CENTER)
            add(runButton, BorderLayout.EAST)
        }
        val body = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
            add(commandBar, BorderLayout.SOUTH)
        }
        setContent(body)

        val toolbar = ActionManager.getInstance().createActionToolbar("ArthasBridge.Toolbar", toolbarActions(), false)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        service.addListener(object : ArthasListener {
            override fun stateChanged(state: ConnectionState) = onEdt { render(state) }
            override fun jobChanged(running: Boolean) = onEdt { render(service.state) }
            override fun output(text: String, kind: OutputKind) = console.print(text, kind.contentType())
            override fun prefill(command: String) = onEdt { input.text = command; input.textEditor.requestFocusInWindow() }
            override fun probeAdded(probe: Probe) = onEdt { tabs.selectedComponent = probes; render(service.state) }
            override fun probeChanged(probe: Probe, hit: ProbeHit?) { if (hit == null) onEdt { render(service.state) } }
            override fun probeRemoved(probe: Probe) = onEdt { render(service.state) }
        }, this)

        applyFontSize()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ArthasSettings.CHANGED, Runnable { applyFontSize() })

        printWelcome()
        render(service.state)
        refreshProcesses()
    }

    private fun submit() {
        val command = input.text.trim()
        if (command.isEmpty() || !service.isConnected) return
        input.addCurrentTextToHistory()
        input.text = ""
        service.run(command)
    }

    private fun render(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        val busy = state is ConnectionState.Busy
        when (state) {
            is ConnectionState.Disconnected -> {
                status.icon = AllIcons.Debugger.Db_disabled_breakpoint
                status.text = "Not attached"
                status.foreground = JBColor.GRAY
            }
            is ConnectionState.Busy -> {
                status.icon = AnimatedIcon.Default.INSTANCE
                status.text = state.message
                status.foreground = JBColor.foreground()
            }
            is ConnectionState.Connected -> {
                val live = service.liveProbeCount
                status.icon = if (service.jobRunning || live > 0) AnimatedIcon.Default.INSTANCE else AllIcons.Debugger.ThreadRunning
                status.text = "${state.label}  ·  Arthas ${state.version}" +
                    (if (service.jobRunning) "  ·  running…" else "") +
                    (if (live > 0) "  ·  $live probe${if (live > 1) "s" else ""} live" else "")
                status.foreground = JBColor.foreground()
            }
        }
        processCombo.isEnabled = !connected && !busy
        refreshButton.isEnabled = !connected && !busy
        attachButton.isVisible = !connected
        attachButton.isEnabled = !busy && processCombo.selectedItem != null
        connectLink.isVisible = !connected
        connectLink.isEnabled = !busy
        stopButton.isVisible = connected
        disconnectButton.isVisible = connected
        runButton.isEnabled = connected
        tabs.setTitleAt(tabs.indexOfComponent(probes), if (service.liveProbeCount > 0) "Probes (${service.liveProbeCount})" else "Probes")
        input.isEnabled = true
    }

    private fun refreshProcesses() {
        val selectedPid = (processCombo.selectedItem as? JvmProcess)?.pid
        ApplicationManager.getApplication().executeOnPooledThread {
            val processes = JvmProcesses.list()
            // Put JVMs running a main class from this project first: most likely the app being debugged.
            val (ours, others) = processes.partition(::belongsToProject)
            onEdt {
                processModel.removeAllElements()
                (ours + others).forEach(processModel::addElement)
                val preferred = (ours + others).firstOrNull { it.pid == selectedPid } ?: ours.firstOrNull() ?: others.firstOrNull()
                processCombo.selectedItem = preferred
                render(service.state)
            }
        }
    }

    private fun belongsToProject(process: JvmProcess): Boolean {
        val name = process.name
        if (!name.contains('.') || name.endsWith(".jar") || DumbService.isDumb(project)) return false
        return runCatching {
            ApplicationManager.getApplication().runReadAction(Computable { JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.projectScope(project)) != null })
        }.getOrDefault(false)
    }

    private fun askAddress() {
        val validator = object : InputValidator {
            override fun checkInput(s: String) = parseAddress(s) != null
            override fun canClose(s: String) = checkInput(s)
        }
        val value = Messages.showInputDialog(
            project,
            "Arthas HTTP address (host:port).\nFor a pod: kubectl port-forward pod/<name> 8563:8563, then use 127.0.0.1:8563",
            "Connect to Running Arthas", null, "127.0.0.1:8563", validator,
        ) ?: return
        parseAddress(value)?.let { (host, port) -> service.connect(host, port) }
    }

    private fun parseAddress(s: String): Pair<String, Int>? {
        val host = s.substringBeforeLast(':', "").trim()
        val port = s.substringAfterLast(':').trim().toIntOrNull()
        return if (host.isNotEmpty() && port != null && port in 1..65535) host to port else null
    }

    private fun toolbarActions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Stop Running Commands", "Interrupt the running command and all live probes", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) = service.stopJob()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = service.jobRunning || service.liveProbeCount > 0 }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })
        add(object : DumbAwareAction("Clear Output", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) = console.clear()
        })
        add(object : ToggleAction("Readable Output", "Plain-language summaries instead of the Arthas terminal format",
            AllIcons.Actions.PreviewDetails), DumbAware {
            override fun isSelected(e: AnActionEvent) = ArthasSettings.getInstance().state.readableOutput
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                ArthasSettings.getInstance().state.readableOutput = state
                console.print(if (state) "Readable output on.\n" else "Arthas terminal format on.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })
        add(Separator.getInstance())
        add(quickCommands())
        add(Separator.getInstance())
        add(object : DumbAwareAction("Settings", "Ports, result limits, masking", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) =
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ArthasConfigurable::class.java)
        })
        add(object : DumbAwareAction("Arthas Command Reference", null, AllIcons.Actions.Help) {
            override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse("https://arthas.aliyun.com/en/doc/commands.html")
        })
    }

    private fun quickCommands() = DefaultActionGroup("Quick Commands", true).apply {
        templatePresentation.icon = AllIcons.Actions.Lightning
        templatePresentation.description = "Common health checks: dashboard, busy threads, deadlocks, memory, profiler"
        ArthasCommands.QUICK.forEach { (label, command) -> add(runAction(label, command)) }
        add(Separator.getInstance())
        add(object : DumbAwareAction("Find Loaded Class…") {
            override fun actionPerformed(e: AnActionEvent) {
                val pattern = Messages.showInputDialog(project, "Class name or pattern (wildcards allowed):",
                    "Find Loaded Class", null, "*Controller", null) ?: return
                service.run("sc ${pattern.trim()}")
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = service.isConnected }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })
    }

    private fun runAction(label: String, command: String): AnAction = object : DumbAwareAction(label, command, null) {
        override fun actionPerformed(e: AnActionEvent) = service.run(command)
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = service.isConnected }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private fun applyFontSize() {
        val editor = (console as? ConsoleViewImpl)?.editor as? EditorImpl ?: return
        val options = ArthasSettings.getInstance().state
        editor.setFontSize(
            if (options.customFontSize) options.fontSize else EditorColorsManager.getInstance().globalScheme.consoleFontSize
        )
    }

    private fun printWelcome() {
        console.print(
            """
            |Arthas Bridge: look inside a running JVM without restarting or adding logs.
            |
            |  1. Pick your app in the JVM list (refresh if you just started it) and press Attach.
            |  2. Right-click a method in the editor → Arthas → Watch / Trace / Stack …
            |     or type any Arthas command below, e.g.  thread -n 5   sc -d *PaymentService*
            |  3. Press Stop Arthas when you are done. The app keeps running and classes are restored.
            |
            |Card-like numbers are masked in the output. Don't attach to production JVMs.
            |
            |""".trimMargin(),
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )
    }

    private fun row(vararg components: java.awt.Component) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        components.forEach { add(it) }
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, project.disposed)

    override fun dispose() = Unit
}
