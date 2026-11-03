package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

enum class ProbeState(val live: Boolean) { STARTING(true), LIVE(true), DONE(false), STOPPED(false), FAILED(false) }

/** One call (or one `monitor` interval) seen by a probe. */
data class ProbeHit(
    val time: String,
    val costMs: Double?,
    val error: Boolean,
    val summary: String,
    /** Call tree of a `trace` hit; empty for other commands. */
    val trace: List<TraceNode> = emptyList(),
    val seq: Int = 0,
    val detail: List<Rendered.Text> = emptyList(),
    internal val source: JsonObject? = null,
)

data class TraceNode(
    val className: String,
    val methodName: String,
    val line: Int,
    val totalNs: Long,
    val times: Long,
    val exception: String?,
    val children: List<TraceNode>,
) {
    val throws get() = exception != null
}

/**
 * A long-running watch / trace / stack / monitor / tt job. Each probe has its own Arthas session,
 * so several can run side by side (Arthas allows one foreground job per session).
 */
class Probe internal constructor(val id: Int, val command: String) {
    val kind: String = ProbeParser.kind(command)
    val className: String? = ProbeParser.positional(command).getOrNull(0)
    val methodName: String? = ProbeParser.positional(command).getOrNull(1)
    val limit: Int? = ProbeParser.limit(command)

    /** `PaymentService.charge`, or the raw arguments when the command doesn't name a class and method. */
    val target: String = when {
        className != null && methodName != null -> "${className.substringAfterLast('.')}.$methodName"
        else -> command.substringAfter(' ', "").trim()
    }

    @Volatile var state = ProbeState.STARTING
        internal set
    @Volatile var message: String? = null
        internal set
    @Volatile var hitCount = 0
        private set
    @Volatile var errorCount = 0
        private set
    @Volatile var lastCostMs: Double? = null
        private set

    @Volatile internal var session: ArthasHttpClient.Session? = null
    @Volatile internal var stopRequested = false

    private val hits = ArrayDeque<ProbeHit>()

    /** Most recent hits, oldest first; older ones are dropped to bound memory. */
    fun hits(): List<ProbeHit> = synchronized(hits) { hits.toList() }

    /** Called from the probe's job thread only. */
    internal fun add(hit: ProbeHit): ProbeHit {
        val numbered = hit.copy(seq = hitCount + 1)
        synchronized(hits) {
            hits.addLast(numbered)
            if (hits.size > MAX_KEPT_HITS) hits.removeFirst()
        }
        hitCount++
        if (hit.error) errorCount++
        hit.costMs?.let { lastCostMs = it }
        return numbered
    }

    companion object {
        const val MAX_KEPT_HITS = 100
    }
}

/** Reads probe commands and turns their HTTP API results into [ProbeHit]s. */
object ProbeParser {

    private val KINDS = setOf("watch", "trace", "stack", "monitor", "tt")
    private val VALUE_OPTIONS = setOf(
        "-n", "-x", "-c", "-i", "-m", "-p", "-w", "--exclude-class-pattern", "--skipJDKMethod",
        "--listenerId", "--limits", "--classLoaderClass",
    )
    private val SPACES = Regex("\\s+")

    fun kind(command: String) = command.trim().substringBefore(' ')

    /** Commands that keep reporting calls until stopped. `tt` only when it records (`-t`), not `tt -l` / `tt -i`. */
    fun isProbe(command: String): Boolean {
        val kind = kind(command)
        if (kind !in KINDS || positional(command).isEmpty()) return false
        return kind != "tt" || "-t" in tokens(command)
    }

    /** Arguments that aren't options: class pattern, method pattern, express, condition. */
    fun positional(command: String): List<String> {
        val args = tokens(command).drop(1)
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg in VALUE_OPTIONS -> i++
                arg.startsWith("-") -> Unit
                else -> out += arg
            }
            i++
        }
        return out
    }

    fun limit(command: String): Int? {
        val args = tokens(command)
        val i = args.indexOf("-n")
        return if (i >= 0) args.getOrNull(i + 1)?.toIntOrNull() else null
    }

    /** Splits on whitespace, keeping quoted express / condition arguments whole. */
    internal fun tokens(command: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (ch in command) {
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; inToken = true }
                ch.isWhitespace() -> if (inToken) { out += sb.toString(); sb.clear(); inToken = false }
                else -> { sb.append(ch); inToken = true }
            }
        }
        if (inToken) out += sb.toString()
        return out
    }

    fun hits(result: JsonObject): List<ProbeHit> = when (result.str("type")) {
        "watch" -> listOf(watch(result))
        "trace" -> listOfNotNull(trace(result))
        "stack" -> listOf(stack(result))
        "monitor" -> monitor(result)
        "tt" -> timeTunnel(result)
        else -> emptyList()
    }

    private fun watch(r: JsonObject): ProbeHit {
        val point = r.str("accessPoint")
        val label = when (point) {
            "AtEnter" -> "called"
            "AtExit" -> "returned"
            "AtExceptionExit" -> "threw"
            else -> point.orEmpty()
        }
        return ProbeHit(time(r.str("ts")), r.dbl("cost"), point == "AtExceptionExit",
            "$label  ${oneLine(r.get("value").asText())}", source = r)
    }

    private fun trace(r: JsonObject): ProbeHit? {
        val root = r.getAsJsonObject("root") ?: return null
        val nodes = root.arr("children").map { traceNode(it.asJsonObject) }
        val failure = nodes.firstNotNullOfOrNull(::firstException)
        val summary = buildString {
            append("thread ").append(root.str("threadName"))
            failure?.let { append("  ·  threw ").append(it) }
        }
        return ProbeHit(time(root.str("timestamp")), nodes.sumOf { it.totalNs } / 1_000_000.0, failure != null,
            summary, trace = nodes, source = r)
    }

    private fun traceNode(n: JsonObject): TraceNode = TraceNode(
        className = n.str("className").orEmpty(),
        methodName = n.str("methodName") ?: n.str("type").orEmpty(),
        line = n.lng("lineNumber").toInt(),
        totalNs = n.lng("totalCost"),
        times = n.lng("times"),
        exception = if (n.get("throw")?.asBoolean == true) n.str("exceptions") ?: "exception" else null,
        children = n.arr("children").map { traceNode(it.asJsonObject) },
    )

    private fun firstException(n: TraceNode): String? = n.exception ?: n.children.firstNotNullOfOrNull(::firstException)

    private fun stack(r: JsonObject): ProbeHit {
        val caller = r.arr("stackTrace").drop(1).firstOrNull()?.asJsonObject
        val summary = buildString {
            append("thread ").append(r.str("threadName"))
            caller?.let { append("  ·  called from ").append(simple(it.str("className"))).append('.').append(it.str("methodName")) }
        }
        return ProbeHit(time(r.str("ts")), null, false, summary, source = r)
    }

    private fun monitor(r: JsonObject): List<ProbeHit> = r.arr("monitorDataList").map { e ->
        val d = e.asJsonObject
        val total = d.lng("total")
        val failed = d.lng("failed")
        val avg = if (total > 0) d.dbl("cost") / total else 0.0
        val source = JsonObject().apply {
            addProperty("type", "monitor")
            add("monitorDataList", JsonArray().apply { add(d) })
        }
        ProbeHit(time(d.str("timestamp")), avg, failed > 0,
            "$total calls  ·  $failed failed  ·  avg ${String.format(Locale.ROOT, "%.2f", avg)} ms", source = source)
    }

    private fun timeTunnel(r: JsonObject): List<ProbeHit> {
        val fragments = r.arr("timeFragmentList").map { it.asJsonObject } + listOfNotNull(r.getAsJsonObject("timeFragment"))
        return fragments.map { f ->
            val threw = f.get("throw")?.asBoolean == true
            val source = JsonObject().apply { addProperty("type", "tt"); add("timeFragment", f) }
            ProbeHit(time(f.str("timestamp")), f.dbl("cost"), threw,
                "index ${f.str("index")}  ·  ${if (threw) "threw" else "returned"}  ·  replay with tt -i ${f.str("index")} -p",
                source = source)
        }
    }

    private fun time(ts: String?) = ts?.substringAfter(' ')?.take(12).orEmpty()
    private fun simple(name: String?) = name.orEmpty().substringBefore("$$").substringAfterLast('.')
    private fun oneLine(s: String) = s.replace(SPACES, " ").trim().take(160)

    private fun JsonElement?.asText(): String = when {
        this == null || isJsonNull -> "null"
        isJsonPrimitive -> asString
        else -> toString()
    }

    private fun JsonObject.arr(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.lng(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0
    private fun JsonObject.dbl(key: String): Double = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() ?: 0.0
}
