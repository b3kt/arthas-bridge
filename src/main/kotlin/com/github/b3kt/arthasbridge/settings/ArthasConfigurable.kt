package com.github.b3kt.arthasbridge.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class ArthasConfigurable : BoundConfigurable("Arthas Bridge") {

    private val settings = ArthasSettings.getInstance()
    private val options get() = settings.state
    private var password = ""

    override fun createPanel(): DialogPanel = panel {
        group("Arthas") {
            row("arthas-boot.jar:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileDescriptor("jar").withTitle("Select arthas-boot.jar"),
                ).bindText({ options.arthasBootPath.orEmpty() }, { options.arthasBootPath = it })
                    .align(AlignX.FILL)
                    .comment("Leave empty to auto-detect (~/.arthas/lib) or download on first attach.")
            }
            row("HTTP port:") {
                intTextField(1..65535).bindIntText({ options.httpPort }, { options.httpPort = it }).columns(6)
                    .comment("The plugin talks to Arthas through this port.")
            }
            row("Telnet port:") {
                intTextField(0..65535).bindIntText({ options.telnetPort }, { options.telnetPort = it }).columns(6)
                    .comment("0 = disabled. Set a port only if you also want to use the Arthas terminal.")
            }
        }
        group("Commands") {
            row("Object expand depth (-x):") {
                intTextField(1..6).bindIntText({ options.expandDepth }, { options.expandDepth = it }).columns(3)
            }
            row("Stop after N results (-n):") {
                intTextField(1..1000).bindIntText({ options.resultLimit }, { options.resultLimit = it }).columns(4)
                    .comment("Keeps watch / trace / tt from flooding the console or slowing the target.")
            }
            row {
                checkBox("Ask for depth, limit and condition when starting from the editor")
                    .bindSelected({ options.askProbeOptions }, { options.askProbeOptions = it })
            }
        }
        group("Output") {
            buttonsGroup {
                row {
                    radioButton("Readable summary", true)
                        .comment("Plain-language view of watch, trace, stack, monitor and tt: inputs, result, error, slowest steps.")
                }
                row {
                    radioButton("Arthas terminal format", false)
                        .comment("Exactly what the Arthas console prints. Familiar if you already use Arthas.")
                }
            }.bind(MutableProperty({ options.readableOutput }, { options.readableOutput = it }), Boolean::class.javaObjectType)
            row {
                val custom = checkBox("Font size:")
                    .bindSelected({ options.customFontSize }, { options.customFontSize = it })
                spinner(8..36).bindIntValue({ options.fontSize }, { options.fontSize = it })
                    .enabledIf(custom.selected)
                    .comment("Unchecked = same size as the IDE console (Editor | Color Scheme | Console Font).")
            }
        }
        group("Safety") {
            row {
                checkBox("Mask card-like numbers in output (keeps first 6 / last 4)")
                    .bindSelected({ options.maskSensitiveData }, { options.maskSensitiveData = it })
            }
            row {
                checkBox("Stop Arthas in the target JVM when the project closes")
                    .bindSelected({ options.stopOnProjectClose }, { options.stopOnProjectClose = it })
            }
            row {
                checkBox("Allow connecting to non-localhost Arthas")
                    .bindSelected({ options.allowRemote }, { options.allowRemote = it })
                    .comment("Arthas gives full control of the JVM. Prefer kubectl port-forward to localhost.")
            }
            row("Username:") {
                textField().bindText({ options.username.orEmpty() }, { options.username = it }).columns(16)
            }
            row("Password:") {
                passwordField().bindText({ password }, { password = it }).columns(16)
                    .comment("Only needed if Arthas was started with auth. Stored in the IDE password safe.")
            }
        }
    }

    override fun reset() {
        password = settings.password.orEmpty()
        super.reset()
    }

    override fun apply() {
        super.apply()
        settings.password = password
        ApplicationManager.getApplication().messageBus.syncPublisher(ArthasSettings.CHANGED).run()
    }
}
