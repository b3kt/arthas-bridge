package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.ArthasCommands
import com.github.b3kt.arthasbridge.core.ProbeOptions
import com.github.b3kt.arthasbridge.settings.ArthasSettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Asks how to run a watch / trace / stack / monitor / tt started from the editor:
 *   Watch PaymentService.charge
 *   Expand depth (-x)        [2]          (watch only)
 *   Stop after N calls (-n)  [5]
 *   Only calls slower than   [   ] ms     (not for monitor)
 *   Condition (OGNL)         [params[0].amount > 1000]
 *   [x] Ask every time                      [Start]
 */
internal object ProbeOptionsPopup {

    private class Values(var depth: Int, var limit: Int, var slowerThan: String = "", var condition: String = "", var ask: Boolean = true)

    fun show(kind: String, target: String, editor: Editor?, dataContext: DataContext, onStart: (ProbeOptions) -> Unit) {
        val settings = ArthasSettings.getInstance().state
        val values = Values(settings.expandDepth, settings.resultLimit)
        var popup: JBPopup? = null
        lateinit var focus: JComponent
        lateinit var form: DialogPanel

        fun submit() {
            if (form.validateAll().isNotEmpty()) return
            form.apply()
            start(values, popup, onStart)
        }

        form = panel {
            if (kind == "watch") {
                row("Expand depth (-x):") {
                    intTextField(1..6).bindIntText(values::depth).columns(3)
                        .comment("How deep to print objects in arguments and results.")
                }
            }
            row("Stop after N calls (-n):") {
                focus = intTextField(1..1000).bindIntText(values::limit).columns(4).component
            }
            if (kind != "monitor") {
                row("Only calls slower than:") {
                    textField().bindText(values::slowerThan).columns(6)
                        .validationOnInput {
                            if (it.text.isBlank() || it.text.trim().toIntOrNull()?.let { ms -> ms >= 0 } == true) null
                            else error("Enter a number of milliseconds")
                        }
                    label("ms")
                }
            }
            row("Condition (OGNL):") {
                textField().bindText(values::condition).align(AlignX.FILL)
                    .comment("Optional, e.g. params[0].amount > 1000 or returnObj == null")
            }
            row {
                checkBox("Ask every time").bindSelected(values::ask)
                    .comment("Unchecked uses the defaults from Settings | Tools | Arthas Bridge.")
                button("Start") { submit() }.align(AlignX.RIGHT)
            }
        }

        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = submit()
        }.registerCustomShortcutSet(CommonShortcuts.ENTER, form)

        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(form, focus)
            .setTitle("${kind.replaceFirstChar { it.uppercase() }} $target")
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        if (editor != null) popup.showInBestPositionFor(editor) else popup.showInBestPositionFor(dataContext)
    }

    private fun start(values: Values, popup: JBPopup?, onStart: (ProbeOptions) -> Unit) {
        val settings = ArthasSettings.getInstance().state
        if (!values.ask) settings.askProbeOptions = false
        popup?.closeOk(null)
        val condition = ArthasCommands.condition(values.condition, values.slowerThan.trim().toIntOrNull())
        onStart(ProbeOptions(values.depth, values.limit, condition))
    }
}
