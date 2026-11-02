package com.github.b3kt.arthasbridge.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

enum class OutputKind { COMMAND, NORMAL, INFO, ERROR, HEADER, MUTED, SUCCESS }

sealed interface Rendered {
    data class Text(val text: String, val kind: OutputKind = OutputKind.NORMAL) : Rendered
    /** Decompiled source to open in an editor tab. */
    data class Source(val className: String, val source: String) : Rendered
    /** A file written by Arthas on the target machine (e.g. a profiler flame graph). */
    data class OutputFile(val path: String) : Rendered
}

/**
 * Turns one item of the Arthas HTTP API `results` array into readable text.
 * Layouts follow the Arthas terminal output where it helps people who already know Arthas.
 */
object ResultRenderer {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val ANSI = Regex("\u001B\\[[;\\d]*m")
    private val SILENT = setOf("input_status", "welcome", "command", "enhancer", "status")

    /** A `status` result is the last thing a job emits. */
    fun isJobEnd(result: JsonObject) = result.str("type") == "status"

    fun render(result: JsonObject): List<Rendered> {
        val type = result.str("type")
        if (result.str("jobId") == "0" && type == "message") return emptyList() // session greeting
        return when (type) {
            "status" -> status(result)
            "command" -> if (result.str("state") == "FAILED") error(result.str("message")) else emptyList()
            "enhancer" -> enhancer(result)
            "message" -> text(clean(result.str("message")), OutputKind.INFO)
            "version" -> text("Arthas ${result.str("version")}")
            "session" -> text("Java pid ${result.str("javaPid")}, session ${result.str("sessionId")}")
            "row_affect" -> text("Affected rows: ${result.str("rowCount")}", OutputKind.INFO)
            "watch" -> text(watch(result))
            "trace" -> text(trace(result))
            "stack" -> text(stack(result))
            "monitor" -> text(monitor(result))
            "tt" -> text(timeTunnel(result))
            "jad" -> jad(result)
            "sc" -> text(classInfo(result))
            "dashboard" -> text(dashboard(result))
            "memory" -> text(memory(result.getAsJsonObject("memoryInfo")))
            "thread" -> text(thread(result))
            "jvm" -> text(jvm(result))
            "profiler" -> profiler(result)
            in SILENT -> emptyList()
            else -> text(json(result))
        }
    }

    // ---- generic ----

    private fun text(s: String?, kind: OutputKind = OutputKind.NORMAL): List<Rendered> =
        if (s.isNullOrBlank()) emptyList() else listOf(Rendered.Text(s.trimEnd() + "\n", kind))

    private fun error(s: String?) = text(clean(s) ?: "Command failed", OutputKind.ERROR)

    private fun clean(s: String?) = s?.replace(ANSI, "")

    private fun json(result: JsonObject): String {
        val copy = result.deepCopy().apply { remove("jobId"); remove("type") }
        return gson.toJson(copy)
    }

    private fun valueText(e: JsonElement?): String = when {
        e == null || e.isJsonNull -> "null"
        e.isJsonPrimitive -> e.asString
        else -> gson.toJson(e)
    }

    private fun status(r: JsonObject): List<Rendered> {
        val code = r.get("statusCode")?.asInt ?: 0
        return if (code == 0) emptyList() else error(r.str("message"))
    }

    private fun enhancer(r: JsonObject): List<Rendered> {
        if (r.get("success")?.asBoolean != true) return emptyList() // the status result carries the reason
        val e = r.getAsJsonObject("effect") ?: return emptyList()
        return text(
            "Enhanced ${e.int("classCount")} class(es), ${e.int("methodCount")} method(s) in ${e.int("cost")} ms. " +
                "Waiting for invocations… (press Stop to end)",
            OutputKind.INFO,
        )
    }

    // ---- method observation ----

    private fun watch(r: JsonObject) = buildString {
        append("ts=").append(r.str("ts")).append("  ")
        append(r.str("className")).append('.').append(r.str("methodName"))
        append("  [").append(r.str("accessPoint")).append("]  cost=").append(ms(r.dbl("cost"))).append('\n')
        append(valueText(r.get("value")))
    }

    private fun trace(r: JsonObject): String {
        val root = r.getAsJsonObject("root") ?: return json(r)
        val sb = StringBuilder()
        sb.append("`---ts=").append(root.str("timestamp"))
            .append(";thread_name=").append(root.str("threadName"))
            .append(";id=").append(root.str("threadId"))
            .append(";is_daemon=").append(root.str("daemon"))
            .append(";priority=").append(root.str("priority"))
            .append(";TCCL=").append(root.str("classloader")).append('\n')
        val children = root.arr("children")
        children.forEachIndexed { i, c -> traceNode(sb, c.asJsonObject, "    ", i == children.size() - 1, null) }
        return sb.toString()
    }

    private fun traceNode(sb: StringBuilder, node: JsonObject, indent: String, last: Boolean, parentCost: Long?) {
        val total = node.lng("totalCost")
        val times = node.lng("times")
        sb.append(indent).append(if (last) "`---[" else "+---[")
        if (parentCost != null && parentCost > 0) {
            sb.append(String.format(Locale.ROOT, "%.2f%% ", total * 100.0 / parentCost))
        }
        if (times > 1) {
            sb.append("min=").append(nsToMs(node.lng("minCost"))).append(",max=").append(nsToMs(node.lng("maxCost")))
                .append(",total=").append(nsToMs(total)).append(",count=").append(times)
        } else {
            sb.append(nsToMs(total))
        }
        sb.append("] ").append(node.str("className")).append(':').append(node.str("methodName")).append("()")
        val line = node.lng("lineNumber")
        if (line > 0) sb.append(" #").append(line)
        if (node.get("throw")?.asBoolean == true) sb.append(" [throws ").append(node.str("exceptions") ?: "exception").append(']')
        sb.append('\n')
        val children = node.arr("children")
        val childIndent = indent + if (last) "    " else "|   "
        children.forEachIndexed { i, c -> traceNode(sb, c.asJsonObject, childIndent, i == children.size() - 1, total) }
    }

    private fun stack(r: JsonObject) = buildString {
        append("ts=").append(r.str("ts")).append(";thread_name=").append(r.str("threadName"))
            .append(";id=").append(r.str("threadId")).append(";is_daemon=").append(r.str("daemon"))
            .append(";priority=").append(r.str("priority")).append(";TCCL=").append(r.str("classloader")).append('\n')
        r.arr("stackTrace").forEachIndexed { i, f ->
            val frame = f.asJsonObject
            if (i == 0) {
                append("    @").append(frame.str("className")).append('.').append(frame.str("methodName")).append("()\n")
            } else {
                append("        at ").append(frame(frame)).append('\n')
            }
        }
    }

    private fun monitor(r: JsonObject) = buildString {
        append(row("TIMESTAMP", "CLASS", "METHOD", "TOTAL", "SUCCESS", "FAIL", "AVG-RT(ms)", "FAIL-RATE"))
        r.arr("monitorDataList").forEach { e ->
            val d = e.asJsonObject
            val total = d.lng("total")
            val failed = d.lng("failed")
            val avg = if (total > 0) d.dbl("cost") / total else 0.0
            val rate = if (total > 0) failed * 100.0 / total else 0.0
            append(row(
                d.str("timestamp")?.take(19), d.str("className"), d.str("methodName"), total, d.lng("success"), failed,
                String.format(Locale.ROOT, "%.2f", avg), String.format(Locale.ROOT, "%.2f%%", rate),
            ))
        }
    }

    private fun timeTunnel(r: JsonObject): String {
        val fragments = r.arr("timeFragmentList").map { it.asJsonObject } +
            listOfNotNull(r.getAsJsonObject("timeFragment"))
        if (fragments.isEmpty()) return json(r)
        val single = fragments.size == 1 && r.has("timeFragment")
        return buildString {
            if (r.get("first")?.asBoolean != false && !single) {
                append(row("INDEX", "TIMESTAMP", "COST(ms)", "RESULT", "CLASS", "METHOD"))
            }
            fragments.forEach { f ->
                val result = when {
                    f.get("throw")?.asBoolean == true -> "THROW"
                    f.get("return")?.asBoolean == true -> "RETURN"
                    else -> "-"
                }
                append(row(f.str("index"), f.str("timestamp")?.take(23), ms(f.dbl("cost")).removeSuffix("ms"),
                    result, f.str("className"), f.str("methodName")))
                f.arr("params").forEachIndexed { i, p ->
                    val obj = if (p.isJsonObject) p.asJsonObject.get("object") else p
                    append("    param[").append(i).append("] ").append(valueText(obj)).append('\n')
                }
                if (f.get("throw")?.asBoolean == true) {
                    append("    throw    ").append(valueText(f.get("throwExp"))).append('\n')
                } else if (f.has("returnObj")) {
                    append("    return   ").append(valueText(f.get("returnObj")).replace("\n", "\n    ")).append('\n')
                }
            }
            r.get("watchValue")?.let { append(valueText(it)).append('\n') }
        }
    }

    // ---- class inspection ----

    private fun jad(r: JsonObject): List<Rendered> {
        val source = r.str("source") ?: return text(json(r))
        val name = r.getAsJsonObject("classInfo")?.str("name") ?: "Decompiled"
        return listOf(
            Rendered.Source(name, source),
            Rendered.Text("Decompiled $name (from ${r.str("location") ?: "unknown"}) opened in the editor.\n", OutputKind.INFO),
        )
    }

    private fun classInfo(r: JsonObject): String {
        r.get("classNames")?.takeIf { it.isJsonArray }?.let { names ->
            return names.asJsonArray.joinToString("\n") { it.asString }
        }
        val c = r.getAsJsonObject("classInfo") ?: return json(r)
        return buildString {
            fun line(label: String, value: Any?) = append(label.padEnd(14)).append(value ?: "").append('\n')
            line("class", c.str("name"))
            line("code source", c.str("codeSource"))
            line("class loader", c.arr("classloader").joinToString(" -> ") { it.asString })
            line("loader hash", c.str("classLoaderHash"))
            line("modifier", c.str("modifier"))
            line("super class", c.arr("superClass").joinToString(" -> ") { it.asString })
            line("interfaces", c.arr("interfaces").joinToString(", ") { it.asString })
            line("annotations", c.arr("annotations").joinToString(", ") { it.asString })
            append('\n')
        }
    }

    // ---- JVM health ----

    private fun dashboard(r: JsonObject) = buildString {
        r.getAsJsonObject("runtimeInfo")?.let { rt ->
            append("Java ").append(rt.str("javaVersion")).append(" · ").append(rt.str("osName")).append(' ')
                .append(rt.str("osVersion")).append(" · ").append(rt.str("processors")).append(" CPUs · load ")
                .append(String.format(Locale.ROOT, "%.2f", rt.dbl("systemLoadAverage")))
                .append(" · uptime ").append(rt.lng("uptime")).append("s\n\n")
        }
        append(memory(r.getAsJsonObject("memoryInfo"))).append('\n')
        val gcs = r.arr("gcInfos")
        if (gcs.size() > 0) {
            append("GC\n")
            gcs.forEach { g ->
                val gc = g.asJsonObject
                append("  ").append(gc.str("name")?.padEnd(28)).append(" count=").append(gc.lng("collectionCount"))
                    .append("  time=").append(gc.lng("collectionTime")).append("ms\n")
            }
            append('\n')
        }
        val threads = r.arr("threads").map { it.asJsonObject }.sortedByDescending { it.dbl("cpu") }.take(10)
        if (threads.isNotEmpty()) {
            append("Top threads by CPU\n")
            append(row("ID", "NAME", "STATE", "CPU%", "TIME(ms)"))
            threads.forEach { t -> append(row(t.str("id"), t.str("name")?.take(40), t.str("state"), t.str("cpu"), t.str("time"))) }
        }
    }

    private fun memory(m: JsonObject?): String {
        if (m == null) return ""
        return buildString {
            append(row("MEMORY", "USED", "TOTAL", "MAX", "USAGE"))
            listOf("heap", "nonheap", "buffer_pool").forEach { key ->
                m.arr(key).forEach { e ->
                    val p = e.asJsonObject
                    val used = p.lng("used")
                    val max = p.lng("max")
                    val usage = if (max > 0) String.format(Locale.ROOT, "%.1f%%", used * 100.0 / max) else ""
                    append(row(p.str("name"), bytes(used), bytes(p.lng("total")), if (max > 0) bytes(max) else "-", usage))
                }
            }
        }
    }

    private fun thread(r: JsonObject): String {
        val busy = r.arr("busyThreads").map { it.asJsonObject } + listOfNotNull(r.getAsJsonObject("threadInfo"))
        if (busy.isNotEmpty()) {
            return busy.joinToString("\n") { t ->
                buildString {
                    append('"').append(t.str("name") ?: t.str("threadName")).append("\" Id=").append(t.str("id") ?: t.str("threadId"))
                    t.str("cpu")?.let { append(" cpuUsage=").append(it).append('%') }
                    append(' ').append(t.str("state") ?: t.str("threadState"))
                    t.str("lockName")?.let { append(" on ").append(it) }
                    t.str("lockOwnerName")?.let { append(" owned by \"").append(it).append('"') }
                    append('\n')
                    t.arr("stackTrace").forEach { f -> append("    at ").append(frame(f.asJsonObject)).append('\n') }
                }
            }
        }
        val stats = r.arr("threadStats")
        if (stats.size() > 0) {
            return buildString {
                append(row("ID", "NAME", "STATE", "CPU%", "TIME(ms)", "DAEMON"))
                stats.forEach { e ->
                    val t = e.asJsonObject
                    append(row(t.str("id"), t.str("name")?.take(40), t.str("state"), t.str("cpu"), t.str("time"), t.str("daemon")))
                }
            }
        }
        return json(r)
    }

    private fun jvm(r: JsonObject): String {
        val info = r.getAsJsonObject("jvmInfo") ?: return json(r)
        return buildString {
            info.entrySet().forEach { (category, items) ->
                append(category).append('\n')
                if (items.isJsonArray) {
                    items.asJsonArray.forEach { e ->
                        val item = e.asJsonObject
                        append("  ").append(item.str("name")?.padEnd(32)).append(valueText(item.get("value")).replace("\n", " ")).append('\n')
                    }
                }
                append('\n')
            }
        }
    }

    private fun profiler(r: JsonObject): List<Rendered> {
        val out = mutableListOf<Rendered>()
        r.str("executeResult")?.let { out += text(it) }
        r.str("outputFile")?.let {
            out += Rendered.Text("Profiler output: $it\n", OutputKind.INFO)
            out += Rendered.OutputFile(it)
        }
        return out.ifEmpty { text(json(r)) }
    }

    // ---- helpers ----

    private fun frame(f: JsonObject): String {
        val line = f.lng("lineNumber")
        val location = when {
            line == -2L -> "Native Method"
            f.str("fileName") == null -> "Unknown Source"
            line > 0 -> "${f.str("fileName")}:$line"
            else -> f.str("fileName")!!
        }
        return "${f.str("className")}.${f.str("methodName")}($location)"
    }

    private val COLUMN_WIDTHS = intArrayOf(26, 30, 24, 10, 10, 10, 12, 10)

    private fun row(vararg cols: Any?) = buildString {
        cols.forEachIndexed { i, c ->
            val s = c?.toString() ?: ""
            if (i == cols.size - 1) append(s) else append(s.padEnd(maxOf(COLUMN_WIDTHS.getOrElse(i) { 10 }, s.length + 1)))
        }
        append('\n')
    }

    private fun ms(v: Double) = String.format(Locale.ROOT, "%.3fms", v)
    private fun nsToMs(ns: Long) = String.format(Locale.ROOT, "%.3fms", ns / 1_000_000.0)

    private fun bytes(b: Long): String = when {
        b < 0 -> "-"
        b >= 1L shl 30 -> String.format(Locale.ROOT, "%.1fG", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.ROOT, "%.1fM", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.ROOT, "%.1fK", b / (1L shl 10).toDouble())
        else -> "${b}B"
    }

    private fun JsonObject.arr(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.lng(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0
    private fun JsonObject.int(key: String): Int = lng(key).toInt()
    private fun JsonObject.dbl(key: String): Double = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() ?: 0.0
}
