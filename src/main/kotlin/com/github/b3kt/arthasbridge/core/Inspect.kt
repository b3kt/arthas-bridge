package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Results of a one-shot command; [error] is the failure message when the command didn't succeed. */
class QueryResult(val results: List<JsonObject>, val error: String?)

data class StackFrame(val className: String, val methodName: String, val fileName: String?, val line: Int) {
    val isNative get() = line == -2

    override fun toString(): String {
        val location = when {
            isNative -> "Native Method"
            fileName == null -> "Unknown Source"
            line > 0 -> "$fileName:$line"
            else -> fileName
        }
        return "$className.$methodName($location)"
    }
}

data class ThreadDetail(
    val id: Long,
    val name: String,
    val state: String,
    val lockName: String?,
    val lockOwnerId: Long?,
    val lockOwnerName: String?,
    val blockedCount: Long,
    val waitedCount: Long,
    val frames: List<StackFrame>,
)

data class LoadedClassInfo(
    val name: String,
    val modifier: String,
    val codeSource: String?,
    val classLoaders: List<String>,
    val classLoaderHash: String?,
    val superClasses: List<String>,
    val interfaces: List<String>,
    val annotations: List<String>,
)

data class LoadedMethod(val className: String, val name: String, val descriptor: String, val constructor: Boolean) {
    /** `primeFactors(int): List`, from the JVM descriptor `(I)Ljava/util/List;`. */
    val signature: String
        get() {
            val params = InspectParser.descriptorTypes(descriptor.substringAfter('(').substringBefore(')'))
            val result = InspectParser.descriptorTypes(descriptor.substringAfter(')')).singleOrNull() ?: "void"
            val label = if (constructor) className.substringAfterLast('.') else name
            return "$label(${params.joinToString(", ")})" + if (constructor) "" else ": $result"
        }
}

/** Turns `thread`, `sc` and `sm` results into data for the Threads and Classes tabs. */
object InspectParser {

    fun threads(results: List<JsonObject>): List<ThreadSample> =
        results.filter { it.str("type") == "thread" }.flatMap { it.arr("threadStats") }.map { ThreadSample.parse(it.asJsonObject) }

    fun threadDetail(results: List<JsonObject>): ThreadDetail? {
        val info = results.firstNotNullOfOrNull { it.getAsJsonObject("threadInfo") } ?: return null
        return ThreadDetail(
            id = info.lng("threadId"),
            name = info.str("threadName").orEmpty(),
            state = info.str("threadState").orEmpty(),
            lockName = info.str("lockName"),
            lockOwnerId = info.lng("lockOwnerId").takeIf { it >= 0 && info.has("lockOwnerId") },
            lockOwnerName = info.str("lockOwnerName"),
            blockedCount = info.lng("blockedCount"),
            waitedCount = info.lng("waitedCount"),
            frames = info.arr("stackTrace").map { f ->
                val o = f.asJsonObject
                StackFrame(o.str("className").orEmpty(), o.str("methodName").orEmpty(), o.str("fileName"), o.lng("lineNumber").toInt())
            },
        )
    }

    /** Class names matched by `sc`; the same name loaded by two class loaders is listed once. */
    fun classNames(results: List<JsonObject>): List<String> =
        results.filter { it.str("type") == "sc" }.flatMap { it.arr("classNames") }.map { it.asString }.distinct()

    /** `sc -d` gives one entry per class loader that loaded the class. */
    fun classInfos(results: List<JsonObject>): List<LoadedClassInfo> =
        results.mapNotNull { it.getAsJsonObject("classInfo") }.map { c ->
            LoadedClassInfo(
                name = c.str("name").orEmpty(),
                modifier = c.str("modifier").orEmpty(),
                codeSource = c.str("codeSource")?.takeIf { it.isNotBlank() },
                classLoaders = c.arr("classloader").map { it.asString },
                classLoaderHash = c.str("classLoaderHash"),
                superClasses = c.arr("superClass").map { it.asString },
                interfaces = c.arr("interfaces").map { it.asString },
                annotations = c.arr("annotations").map { it.asString },
            )
        }

    fun methods(results: List<JsonObject>): List<LoadedMethod> =
        results.mapNotNull { it.getAsJsonObject("methodInfo") }.map { m ->
            LoadedMethod(m.str("declaringClass").orEmpty(), m.str("methodName").orEmpty(), m.str("descriptor").orEmpty(),
                m.get("constructor")?.asBoolean == true)
        }.distinctBy { it.name + it.descriptor }

    /** A plain name searches anywhere in the class name, like the IDE's class search. */
    fun classPattern(query: String): String {
        val q = query.trim()
        return if (q.any { it == '*' || it == '?' } || q.isEmpty()) q else "*$q*"
    }

    /** Simple type names from a run of JVM field descriptors, e.g. `ILjava/util/List;[B` → `int, List, byte[]`. */
    internal fun descriptorTypes(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            var dims = 0
            while (i < s.length && s[i] == '[') { dims++; i++ }
            if (i >= s.length) break
            val base = when (s[i]) {
                'L' -> {
                    val end = s.indexOf(';', i).let { if (it < 0) s.length else it }
                    s.substring(i + 1, end).substringAfterLast('/').substringAfterLast('$').also { i = end }
                }
                'Z' -> "boolean"
                'B' -> "byte"
                'C' -> "char"
                'S' -> "short"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                'V' -> "void"
                else -> s[i].toString()
            }
            out += base + "[]".repeat(dims)
            i++
        }
        return out
    }

    private fun JsonObject.arr(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.lng(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0
}
