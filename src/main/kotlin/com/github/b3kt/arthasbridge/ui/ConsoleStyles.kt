package com.github.b3kt.arthasbridge.ui

import com.github.b3kt.arthasbridge.core.OutputKind
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font

private val HEADER = ConsoleViewContentType("ARTHAS_HEADER", TextAttributes(null, null, null, null, Font.BOLD))
private val MUTED = ConsoleViewContentType("ARTHAS_MUTED", TextAttributes(JBColor(0x6C707E, 0x868A91), null, null, null, Font.PLAIN))
private val SUCCESS = ConsoleViewContentType("ARTHAS_SUCCESS", TextAttributes(JBColor(0x067D17, 0x6AAB73), null, null, null, Font.PLAIN))

internal fun OutputKind.contentType() = when (this) {
    OutputKind.COMMAND -> ConsoleViewContentType.USER_INPUT
    OutputKind.INFO -> ConsoleViewContentType.SYSTEM_OUTPUT
    OutputKind.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
    OutputKind.NORMAL -> ConsoleViewContentType.NORMAL_OUTPUT
    OutputKind.HEADER -> HEADER
    OutputKind.MUTED -> MUTED
    OutputKind.SUCCESS -> SUCCESS
}
