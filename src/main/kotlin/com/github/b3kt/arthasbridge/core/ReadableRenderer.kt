package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

/** Looks up parameter names in the project sources so inputs can be labelled with them. */
fun interface ParamNames {
    fun of(className: String, method: String, count: Int): List<String>?

    companion object {
        val NONE = ParamNames { _, _, _ -> null }
    }
}

/**
 * Plain-language summaries of watch / tt / trace / stack / monitor results, for people who don't read
 * the Arthas object format. Returns null for other result types so the terminal renderer handles them.
 */
object ReadableRenderer {

    private const val LABEL_WIDTH = 20
    private const val MAX_ITEMS = 10
    private const val MAX_DEPTH = 4
    private val TO_STRING = Regex("""^([A-Za-z_$][\w$.]*)\((.*)\)$""", RegexOption.DOT_MATCHES_ALL)
    private val FRAME = Regex("""at ([\w$.]+)\.([\w$<>]+)\(([^:)]*)(?::(\d+))?\)""")
    private val FRAMEWORK = listOf(
        "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "reactor.", "io.netty.",
        "io.micrometer.", "io.opentelemetry.", "brave.", "org.springframework.", "org.apache.", "org.reactivestreams.",
        "com.fasterxml.", "org.hibernate.", "com.mongodb.", "org.mongodb.", "feign.", "okhttp3.", "retrofit2.",
    )

    fun render(result: JsonObject, names: ParamNames): List<Rendered>? = when (result.str("type")) {
        "watch" -> watch(result, names)
        "tt" -> timeTunnel(result, names)
        "trace" -> trace(result)
        "stack" -> stack(result)
        "monitor" -> monitor(result)
        else -> null
    }

    // ---- watch ----

    private fun watch(r: JsonObject, names: ParamNames): List<Rendered> {
        val out = Out()
        val className = r.str("className").orEmpty()
        val method = r.str("methodName").orEmpty()
        val failed = r.str("accessPoint") == "AtExceptionExit"
        out.header(className, method, "${time(r.str("ts"))}  ·  ${ms(r.dbl("cost"))}  ·  ")
        when (r.str("accessPoint")) {
            "AtExit" -> out.ok("✔ returned")
            "AtExceptionExit" -> out.err("✖ threw an error")
            "AtEnter" -> out.text("→ called")
            else -> out.text(r.str("accessPoint").orEmpty())
        }
        out.nl()

        val text = r.get("value").asText()
        val node = ObjectViewParser.parse(text)
        val parts = (node as? OvNode.Items)?.items
        if (node is OvNode.Items && parts != null && parts.size in 2..3 && parts[0].isArgs()) {
            inputs(out, parts[0], names.of(className, method, parts[0].size()), atExit = r.str("accessPoint") != "AtEnter")
            if (!failed) field(out, "Result", parts[1], 2, 0)
            if (parts.size == 3) error(out, parts[2])
        } else if (node != null) {
            field(out, "Value", node, 2, 0)
        } else {
            out.muted(text.prependIndent("   ")).nl()
        }
        return out.nl().build()
    }

    private fun OvNode.isArgs() = (this is OvNode.Items && type == "Object[]") || (this is OvNode.Collapsed && type == "Object[]")

    private fun OvNode.size() = when (this) {
        is OvNode.Items -> items.size
        is OvNode.Collapsed -> size ?: 0
        else -> 0
    }

    private fun inputs(out: Out, args: OvNode, names: List<String>?, atExit: Boolean = false) {
        when {
            args is OvNode.Collapsed -> out.label("Inputs", 2).muted("${args.size} value(s), raise the expand depth in Settings to see them").nl()
            args !is OvNode.Items || args.items.isEmpty() -> out.label("Inputs", 2).muted("(none)").nl()
            else -> {
                out.label("Inputs", 2)
                // Arthas reads the argument slots after the call, so a reassigned parameter shows its last value.
                if (atExit) out.muted("(values when the method finished)")
                out.nl()
                args.items.forEachIndexed { i, arg -> field(out, names?.getOrNull(i) ?: "#${i + 1}", arg, 4, 0) }
            }
        }
    }

    private fun error(out: Out, node: OvNode) {
        if (node == OvNode.Null) {
            out.label("Error", 2).muted("none").nl()
        } else {
            out.label("Error", 2).err(exceptionSummary(node.flatText())).nl()
        }
    }

    // ---- time tunnel ----

    private fun timeTunnel(r: JsonObject, names: ParamNames): List<Rendered> {
        val fragments = r.arr("timeFragmentList").map { it.asJsonObject } + listOfNotNull(r.getAsJsonObject("timeFragment"))
        if (fragments.isEmpty()) return emptyList()
        val out = Out()
        for (f in fragments) {
            val className = f.str("className").orEmpty()
            val method = f.str("methodName").orEmpty()
            val threw = f.get("throw")?.asBoolean == true
            out.header(className, method, "#${f.str("index")}  ·  ${time(f.str("timestamp"))}  ·  ${ms(f.dbl("cost"))}  ·  ")
            if (threw) out.err("✖ threw an error") else out.ok("✔ returned")
            out.nl()

            val params = f.arr("params").map { p -> node(if (p.isJsonObject) p.asJsonObject.get("object") else p) }
            inputs(out, OvNode.Items("Object[]", params), names.of(className, method, params.size))
            if (threw) {
                out.label("Error", 2).err(exceptionSummary(f.get("throwExp").asText())).nl()
            } else if (f.has("returnObj")) {
                field(out, "Result", node(f.get("returnObj")), 2, 0)
            }
            val index = f.str("index")
            out.muted("  Details: tt -i $index    Call again: tt -i $index -p").nl().nl()
        }
        return out.build()
    }

    private fun node(e: JsonElement?): OvNode {
        val text = e.asText()
        if (e == null || e.isJsonNull || text == "null") return OvNode.Null
        return ObjectViewParser.parse(text) ?: OvNode.Scalar(if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) "Number" else "", text)
    }

    // ---- trace ----

    private fun trace(r: JsonObject): List<Rendered> {
        val root = r.getAsJsonObject("root") ?: return ResultRenderer.render(r)
        val top = root.arr("children").firstOrNull()?.asJsonObject ?: return ResultRenderer.render(r)
        val total = top.lng("totalCost")
        val out = Out()
        out.header(top.str("className").orEmpty(), top.str("methodName").orEmpty(),
            "${time(root.str("timestamp"))}  ·  total ${nsMs(total)}  ·  thread ${root.str("threadName")}")
        out.nl()

        val steps = mutableListOf<JsonObject>()
        fun collect(n: JsonObject) = n.arr("children").forEach { c -> steps += c.asJsonObject; collectAll(c.asJsonObject, steps) }
        collect(top)
        val slowest = steps.filter { it.lng("totalCost") > 0 }.sortedByDescending { it.lng("totalCost") }.take(3)
        if (slowest.isEmpty()) {
            out.muted("  No calls inside this method were recorded; the time is spent in its own code.").nl()
        } else {
            out.label("Slowest steps", 2).nl()
            slowest.forEach { s ->
                val pct = if (total > 0) s.lng("totalCost") * 100.0 / total else 0.0
                val times = s.lng("times")
                val line = s.lng("lineNumber").takeIf { it > 0 }?.let { " (line $it)" }.orEmpty()
                out.text(String.format(Locale.ROOT, "    %5.1f%%  %10s   ", pct, nsMs(s.lng("totalCost"))))
                    .text("${simple(s.str("className"))}.${s.str("methodName")}$line")
                if (times > 1) out.muted("  called $times times")
                if (s.get("throw")?.asBoolean == true) out.err("  threw an error")
                out.nl()
            }
        }
        out.muted("  Full call tree:").nl()
        ResultRenderer.render(r).filterIsInstance<Rendered.Text>().forEach { out.muted(it.text.prependIndent("  ")) }
        return out.nl().build()
    }

    private fun collectAll(n: JsonObject, into: MutableList<JsonObject>) {
        n.arr("children").forEach { c -> into += c.asJsonObject; collectAll(c.asJsonObject, into) }
    }

    // ---- stack ----

    private fun stack(r: JsonObject): List<Rendered> {
        val frames = r.arr("stackTrace").map { it.asJsonObject }
        val first = frames.firstOrNull() ?: return ResultRenderer.render(r)
        val out = Out()
        out.header(first.str("className").orEmpty(), first.str("methodName").orEmpty(),
            "${time(r.str("ts"))}  ·  thread ${r.str("threadName")}")
        out.nl().label("Called by", 2).nl()
        var hidden = 0
        fun flushHidden() {
            if (hidden > 0) out.muted("    ⋮  $hidden framework call(s) (Spring, Reactor, Netty, JDK…)").nl()
            hidden = 0
        }
        for (f in frames.drop(1)) {
            val cls = f.str("className").orEmpty()
            if (FRAMEWORK.any(cls::startsWith) || cls.contains("$$")) {
                hidden++
                continue
            }
            flushHidden()
            val line = f.lng("lineNumber").takeIf { it > 0 }?.let { "  line $it" }.orEmpty()
            out.text("    ← ${simple(cls)}.${f.str("methodName")}").muted("$line  ($cls)").nl()
        }
        flushHidden()
        return out.nl().build()
    }

    // ---- monitor ----

    private fun monitor(r: JsonObject): List<Rendered> {
        val out = Out()
        r.arr("monitorDataList").forEach { e ->
            val d = e.asJsonObject
            val total = d.lng("total")
            val failed = d.lng("failed")
            val avg = if (total > 0) d.dbl("cost") / total else 0.0
            out.header(d.str("className").orEmpty(), d.str("methodName").orEmpty(), "${time(d.str("timestamp"))}  ·  ")
            out.text("$total call(s), avg ${ms(avg)}, ")
            if (failed > 0) {
                out.err(String.format(Locale.ROOT, "%d failed (%.1f%%)", failed, failed * 100.0 / total))
            } else {
                out.ok("none failed")
            }
            out.nl()
        }
        return out.build()
    }

    // ---- values ----

    /** Writes `label  value`, expanding objects and lists below the label. */
    private fun field(out: Out, label: String, node: OvNode, indent: Int, depth: Int) {
        out.label(label, indent)
        val children = children(node)
        if (children == null || depth >= MAX_DEPTH) {
            val (text, muted) = simpleValue(node)
            if (muted) out.muted(text) else out.text(text)
            out.nl()
            return
        }
        out.text(typeName(node)).nl()
        val shown = children.filter { it.second != OvNode.Null }
        shown.take(if (node is OvNode.Items) MAX_ITEMS else Int.MAX_VALUE)
            .forEach { (name, value) -> field(out, name, value, indent + 2, depth + 1) }
        val more = if (node is OvNode.Items) shown.size - MAX_ITEMS else 0
        val empty = children.size - shown.size
        val notes = listOfNotNull(
            more.takeIf { it > 0 }?.let { "+$it more" },
            empty.takeIf { it > 0 }?.let { "$it empty field(s) hidden" },
        )
        if (notes.isNotEmpty()) out.label("", indent + 2).muted("(${notes.joinToString(", ")})").nl()
    }

    /** Nested entries of an object or list, or null for a value printed on one line. */
    private fun children(node: OvNode): List<Pair<String, OvNode>>? = when (node) {
        is OvNode.Fields -> if (isAsync(node.type)) null else node.fields
        is OvNode.Items -> node.items.takeIf { it.isNotEmpty() }?.mapIndexed { i, n -> "[$i]" to n }
        is OvNode.Scalar -> if (isAsync(node.type)) null else toStringFields(node.text)
        else -> null
    }

    private fun simpleValue(node: OvNode): Pair<String, Boolean> = when (node) {
        OvNode.Null -> "(empty)" to true
        is OvNode.Raw -> node.text.lineSequence().first().take(160) to false
        is OvNode.Collapsed -> "${typeName(node)} with ${node.size} item(s), raise the expand depth in Settings to see them" to true
        is OvNode.Items -> "empty ${typeName(node)}" to true
        is OvNode.Fields -> asyncNote(node.type) to true
        is OvNode.Scalar -> when {
            isAsync(node.type) -> asyncNote(node.type) to true
            node.type == "String" || node.type == "Character" -> "\"${node.text.take(160)}\"" to false
            node.text.contains("\$\$Lambda") -> "(function)" to true
            else -> node.text.take(160) to false
        }
    }

    private fun isAsync(type: String) =
        type.startsWith("Mono") || type.startsWith("Flux") || type.startsWith("CompletableFuture")

    private fun asyncNote(type: String): String {
        val kind = if (type.startsWith("Flux")) "Flux" else if (type.startsWith("Mono")) "Mono" else "Future"
        return "$kind (async: the value is produced later, so it can't be shown here. Watch the method that produces it.)"
    }

    private fun typeName(node: OvNode): String = when (node) {
        is OvNode.Fields -> simple(node.type)
        is OvNode.Items -> listName(node.type) + " of ${node.items.size}"
        is OvNode.Collapsed -> listName(node.type)
        is OvNode.Scalar -> TO_STRING.matchEntire(node.text)?.groupValues?.get(1)?.let(::simple) ?: simple(node.type)
        else -> ""
    }

    private fun listName(type: String) = when {
        type.endsWith("[]") -> "array"
        type.contains("Map") -> "map"
        type.contains("Set") -> "set"
        type.contains("List") -> "list"
        else -> simple(type)
    }

    /** Lombok / data-class style `Foo(a=1, b=Bar(c=2))` into fields; null when it isn't that shape. */
    private fun toStringFields(text: String): List<Pair<String, OvNode>>? {
        val body = TO_STRING.matchEntire(text)?.groupValues?.get(2) ?: return null
        if (body.isBlank()) return emptyList()
        val parts = splitTopLevel(body)
        if (parts.any { !it.contains('=') }) return null
        return parts.map { part ->
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=')
            key to if (value == "null") OvNode.Null else OvNode.Scalar("", value)
        }
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0 && s.getOrNull(i + 1) == ' ') {
                    parts += s.substring(start, i)
                    start = i + 2
                }
            }
        }
        parts += s.substring(start)
        return parts
    }

    private fun OvNode.flatText(): String = when (this) {
        is OvNode.Raw -> text
        is OvNode.Scalar -> text
        else -> toString()
    }

    /** `java.lang.IllegalStateException: msg\n\tat a.b.C.m(C.java:12)` to `IllegalStateException: msg  (at C.m line 12)` */
    private fun exceptionSummary(text: String): String {
        if (text.isBlank() || text == "null") return "none"
        val firstLine = text.lineSequence().first().trim()
        val type = firstLine.substringBefore(':').trim()
        val message = firstLine.substringAfter(':', "").trim()
        val where = FRAME.find(text)?.let { m ->
            val line = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { " line $it" }.orEmpty()
            "  (at ${simple(m.groupValues[1])}.${m.groupValues[2]}$line)"
        }.orEmpty()
        return simple(type) + (if (message.isNotEmpty()) ": $message" else "") + where
    }

    // ---- helpers ----

    /** `com.x.PaymentController$$SpringCGLIB$$0` to `PaymentController` */
    private fun simple(name: String?) = name.orEmpty().substringBefore("$$").substringAfterLast('.')

    private fun time(ts: String?) = ts?.substringAfter(' ')?.take(12).orEmpty()

    private fun ms(v: Double) = when {
        v >= 1000 -> String.format(Locale.ROOT, "%.2f s", v / 1000)
        v >= 10 -> String.format(Locale.ROOT, "%.0f ms", v)
        else -> String.format(Locale.ROOT, "%.2f ms", v)
    }

    private fun nsMs(ns: Long) = ms(ns / 1_000_000.0)

    private fun JsonElement?.asText(): String = when {
        this == null || isJsonNull -> "null"
        isJsonPrimitive -> asString
        else -> toString()
    }

    private fun JsonObject.arr(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.lng(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0
    private fun JsonObject.dbl(key: String): Double = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() ?: 0.0

    /** Collects styled text segments, merging neighbours of the same kind. */
    private class Out {
        private val parts = mutableListOf<Rendered.Text>()

        fun add(text: String, kind: OutputKind): Out {
            if (text.isEmpty()) return this
            val last = parts.lastOrNull()
            if (last != null && last.kind == kind) parts[parts.size - 1] = Rendered.Text(last.text + text, kind)
            else parts += Rendered.Text(text, kind)
            return this
        }

        fun text(s: String) = add(s, OutputKind.NORMAL)
        fun muted(s: String) = add(s, OutputKind.MUTED)
        fun ok(s: String) = add(s, OutputKind.SUCCESS)
        fun err(s: String) = add(s, OutputKind.ERROR)
        fun nl() = add("\n", parts.lastOrNull()?.kind ?: OutputKind.NORMAL)

        fun label(label: String, indent: Int) =
            muted(" ".repeat(indent) + label.padEnd(maxOf(LABEL_WIDTH - indent, label.length + 2)))

        fun header(className: String, method: String, meta: String) =
            add("● ${simple(className)}.$method", OutputKind.HEADER).muted("   $meta")

        fun build(): List<Rendered> = parts.toList()
    }
}
