package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.ArthasCommands
import com.github.b3kt.arthasbridge.core.ArthasListener
import com.github.b3kt.arthasbridge.core.ArthasService
import com.github.b3kt.arthasbridge.core.ConnectionState
import com.github.b3kt.arthasbridge.core.InspectParser
import com.github.b3kt.arthasbridge.core.LoadedClassInfo
import com.github.b3kt.arthasbridge.core.LoadedMethod
import com.github.b3kt.arthasbridge.settings.ArthasSettings
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
import com.intellij.ui.CollectionListModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Layout:
 *   [class name or pattern] [Search]                       12 classes
 *   loaded classes  |  where the class came from: code source, class loaders, hierarchy
 *                   |  [toolbar: Decompile · Source · Watch · Trace · Monitor · Stack · Copy]
 *                   |  methods
 */
class ClassesPanel(private val project: Project, parent: Disposable) : JPanel(CardLayout()), Disposable {

    private val service = ArthasService.getInstance(project)

    private val search = SearchTextField("ArthasBridge.classSearch").apply {
        textEditor.emptyText.text = "Class name or pattern, e.g. PaymentService or com.acme.*Controller"
        addKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) runSearch()
            }
        })
    }
    private val searchButton = JButton("Search").apply { addActionListener { runSearch() } }
    private val count = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val classModel = CollectionListModel<String>()
    private val classList = JBList(classModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ClassRenderer()
        emptyText.text = "Search the classes loaded in the JVM"
        emptyText.appendLine("Unlike the IDE, this shows what is actually running, including generated classes.")
        addListSelectionListener { if (!it.valueIsAdjusting) selectedValue?.let(::loadClass) }
    }

    private val infoHolder = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 8) }
    private val methodModel = CollectionListModel<LoadedMethod>()
    private val methodList = JBList(methodModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = MethodRenderer()
        emptyText.text = "Select a class"
    }

    private var selectedClass: String? = null
    private var searchRequest = 0
    private var classRequest = 0

    init {
        Disposer.register(parent, this)

        val bar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(search, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(searchButton)
                add(count)
            }, BorderLayout.EAST)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ArthasBridge.Classes", actions(), true)
        toolbar.targetComponent = this
        val detail = JPanel(BorderLayout()).apply {
            add(infoHolder, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(toolbar.component, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(methodList, true), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        val content = JPanel(BorderLayout()).apply {
            add(bar, BorderLayout.NORTH)
            add(OnePixelSplitter(false, 0.35f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(classList, true)
                secondComponent = detail
            }, BorderLayout.CENTER)
        }
        add(JBPanelWithEmptyText().apply { emptyText.text = "Attach to a JVM to search its loaded classes" }, EMPTY)
        add(content, CONTENT)

        service.addListener(object : ArthasListener {
            override fun stateChanged(state: ConnectionState) = onEdt { onState(state) }
        }, this)
        onState(service.state)
    }

    private fun onState(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        (layout as CardLayout).show(this, if (connected) CONTENT else EMPTY)
        if (!connected) {
            classModel.removeAll()
            showClass(null, emptyList(), emptyList(), null)
            count.text = ""
        }
    }

    // ---- loading ----

    private fun runSearch() {
        val pattern = InspectParser.classPattern(search.text)
        if (pattern.isEmpty() || !service.isConnected) return
        search.addCurrentTextToHistory()
        val id = ++searchRequest
        searchButton.isEnabled = false
        count.text = "Searching…"
        background {
            val result = service.query("sc $pattern")
            val names = InspectParser.classNames(result.results).sorted()
            onEdt {
                if (id != searchRequest) return@onEdt
                searchButton.isEnabled = true
                classModel.replaceAll(names.take(MAX_SHOWN))
                classList.emptyText.text = result.error ?: "No loaded class matches $pattern"
                count.text = when {
                    names.size > MAX_SHOWN -> "first $MAX_SHOWN of ${names.size} classes, narrow the pattern"
                    else -> "${names.size} class${if (names.size == 1) "" else "es"}"
                }
                if (names.size == 1) classList.selectedIndex = 0
            }
        }
    }

    private fun loadClass(name: String) {
        val id = ++classRequest
        selectedClass = name
        methodModel.removeAll()
        methodList.emptyText.text = "Loading…"
        background {
            val info = service.query("sc -d $name")
            val methods = service.query("sm $name")
            onEdt {
                if (id != classRequest) return@onEdt
                showClass(name, InspectParser.classInfos(info.results), InspectParser.methods(methods.results), info.error ?: methods.error)
            }
        }
    }

    private fun showClass(name: String?, infos: List<LoadedClassInfo>, methods: List<LoadedMethod>, error: String?) {
        selectedClass = name
        infoHolder.removeAll()
        val info = infos.firstOrNull()
        if (info != null) infoHolder.add(infoForm(info, infos.size), BorderLayout.CENTER)
        infoHolder.revalidate()
        infoHolder.repaint()
        methodModel.replaceAll(methods.sortedWith(compareBy({ !it.constructor }, { it.name })))
        methodList.emptyText.text = error ?: if (name == null) "Select a class" else "No methods"
    }

    private fun infoForm(info: LoadedClassInfo, loaderCount: Int): JComponent {
        fun value(text: String) = JBLabel(text.ifBlank { "–" }).apply { setCopyable(true) }
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Class:", value("${info.modifier} ${info.name}".trim()).apply { font = font.deriveFont(Font.BOLD) })
            .addLabeledComponent("Loaded from:", value(info.codeSource ?: "JDK or generated at runtime"))
            .addLabeledComponent("Class loader:", value(info.classLoaders.joinToString("  →  ") { it.substringBefore('@') }))
            .addLabeledComponent("Extends:", value(info.superClasses.joinToString("  →  ")))
        if (info.interfaces.isNotEmpty()) builder.addLabeledComponent("Implements:", value(info.interfaces.joinToString(", ")))
        if (info.annotations.isNotEmpty()) builder.addLabeledComponent("Annotations:", value(info.annotations.joinToString(", ")))
        if (loaderCount > 1) {
            builder.addComponent(JBLabel("Loaded by $loaderCount class loaders; showing the first.").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        return builder.panel
    }

    // ---- actions ----

    private fun actions() = DefaultActionGroup().apply {
        add(classAction("Decompile", "Show the bytecode that is actually running as Java source (jad)", AllIcons.Actions.Preview) {
            service.run(ArthasCommands.jad(it))
        })
        add(classAction("Jump to Source", "Open the class or selected method in the editor", AllIcons.Actions.EditSource) {
            SourceNavigation.open(project, it, methodList.selectedValue?.takeUnless { m -> m.constructor }?.name)
        })
        add(Separator.getInstance())
        add(methodAction("Watch", "Record arguments, return value and exceptions of each call", AllIcons.Debugger.Watch) { c, m, s ->
            ArthasCommands.watch(c, m, s.expandDepth, s.resultLimit)
        })
        add(methodAction("Trace", "Time each call made inside the method", AllIcons.Actions.ProfileCPU) { c, m, s ->
            ArthasCommands.trace(c, m, s.resultLimit)
        })
        add(methodAction("Monitor", "Count calls, failures and average time every 5 seconds", AllIcons.Actions.Show) { c, m, s ->
            ArthasCommands.monitor(c, m, s.resultLimit)
        })
        add(methodAction("Stack", "Show who calls the method", AllIcons.Debugger.Frame) { c, m, s ->
            ArthasCommands.stack(c, m, s.resultLimit)
        })
        add(Separator.getInstance())
        add(classAction("Copy Name", null, AllIcons.Actions.Copy) {
            val method = methodList.selectedValue
            CopyPasteManager.getInstance().setContents(StringSelection(if (method != null) "$it#${method.name}" else it))
        })
    }

    private fun classAction(text: String, description: String?, icon: Icon, run: (String) -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) { selectedClass?.let(run) }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = selectedClass != null && service.isConnected }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    /** Starts a probe on the selected method; it shows up in the Probes tab. */
    private fun methodAction(
        text: String, description: String, icon: Icon,
        command: (String, String, ArthasSettings.Options) -> String,
    ) = object : DumbAwareAction("$text Method", description, icon) {
        override fun actionPerformed(e: AnActionEvent) {
            val c = selectedClass ?: return
            val m = methodList.selectedValue ?: return
            service.run(command(c, m.name, ArthasSettings.getInstance().state))
        }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedClass != null && methodList.selectedValue != null && service.isConnected
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    // ---- rendering ----

    private class ClassRenderer : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            val generated = value.contains("$$") || value.contains("\$Proxy") || value.contains("\$\$Lambda")
            icon = if (generated) AllIcons.Nodes.AnonymousClass else AllIcons.Nodes.Class
            append(value.substringAfterLast('.'), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  " + value.substringBeforeLast('.', ""), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private class MethodRenderer : ColoredListCellRenderer<LoadedMethod>() {
        override fun customizeCellRenderer(list: JList<out LoadedMethod>, value: LoadedMethod?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = AllIcons.Nodes.Method
            val signature = value.signature
            append(signature.substringBefore('('), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("(" + signature.substringAfter('('), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private fun background(block: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread(block)

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) block()
    }, project.disposed)

    override fun dispose() = Unit

    private companion object {
        const val EMPTY = "empty"
        const val CONTENT = "content"
        const val MAX_SHOWN = 500
    }
}
