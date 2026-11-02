package com.github.b3kt.arthasbridge.core

import java.util.regex.Pattern

/** A value as printed by Arthas (`@Type[...]` object view), parsed into a tree. */
sealed interface OvNode {
    data object Null : OvNode
    /** `@String[abc]`, `@Integer[1]`, or a single-line toString such as `Foo(a=1, b=2)`. */
    data class Scalar(val type: String, val text: String) : OvNode
    /** `@ArrayList[isEmpty=false;size=3]`: contents beyond the expand depth. */
    data class Collapsed(val type: String, val size: Int?) : OvNode
    data class Items(val type: String, val items: List<OvNode>) : OvNode
    data class Fields(val type: String, val fields: List<Pair<String, OvNode>>) : OvNode
    /** Anything else, e.g. an exception with its stack trace. */
    data class Raw(val text: String) : OvNode
}

/**
 * Parses the text Arthas produces for watch / tt values. Returns null when the text doesn't follow
 * the object-view format, so callers can fall back to showing it verbatim.
 */
class ObjectViewParser private constructor(private val s: String) {

    private var i = 0

    companion object {
        private val FIELD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*=(?=@|null)")
        private val COLLAPSED = Regex("""isEmpty=(true|false);size=(\d+)""")

        fun parse(text: String): OvNode? = runCatching {
            val parser = ObjectViewParser(text)
            val node = parser.value()
            parser.skipWs()
            node.takeIf { parser.i >= text.length }
        }.getOrNull()
    }

    private fun value(): OvNode {
        skipWs()
        if (s.startsWith("null", i) && (i + 4 == s.length || !s[i + 4].isLetterOrDigit())) {
            i += 4
            return OvNode.Null
        }
        if (s[i] != '@') return raw()

        var open = s.indexOf('[', i)
        require(open > i) { "type without body" }
        while (s.startsWith("[][", open)) open += 2 // array types: @Object[][...]
        val type = s.substring(i + 1, open)
        i = open + 1
        return if (i < s.length && s[i] == '\n') block(type) else inline(type)
    }

    /** `@Type[content]` on one line; content may contain nested brackets. */
    private fun inline(type: String): OvNode {
        var depth = 1
        val start = i
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) break
            }
            i++
        }
        require(i < s.length) { "unclosed $type" }
        val content = s.substring(start, i)
        i++ // ']'
        COLLAPSED.matchEntire(content)?.let { return OvNode.Collapsed(type, it.groupValues[2].toInt()) }
        if (content.isEmpty()) return OvNode.Items(type, emptyList())
        return OvNode.Scalar(type, content)
    }

    /** Multi-line `@Type[\n  item,\n  name=item,\n]`. */
    private fun block(type: String): OvNode {
        val entries = mutableListOf<Pair<String?, OvNode>>()
        while (true) {
            skipWs()
            require(i < s.length) { "unclosed $type" }
            if (s[i] == ']') {
                i++
                break
            }
            val name = fieldName()
            entries += name to value()
            skipWs()
            if (i < s.length && s[i] == ',') i++
        }
        return if (entries.isNotEmpty() && entries.all { it.first != null }) {
            OvNode.Fields(type, entries.map { it.first!! to it.second })
        } else {
            OvNode.Items(type, entries.map { it.second })
        }
    }

    private fun fieldName(): String? {
        val m = FIELD_NAME.matcher(s).region(i, s.length)
        if (!m.lookingAt()) return null
        i = m.end()
        return s.substring(m.start(), m.end() - 1)
    }

    /** Free text such as an exception; ends before the `,` / `]` that closes the list item. */
    private fun raw(): OvNode {
        val start = i
        while (i < s.length) {
            val c = s[i]
            if (c == ',' && (i + 1 == s.length || s[i + 1] == '\n') && !s.startsWith("\n\t", i + 1)) break
            if (c == '\n') {
                var j = i + 1
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && (s[j] == ',' || s[j] == ']')) break
            }
            i++
        }
        return OvNode.Raw(s.substring(start, i).trimEnd())
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}
