package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class ThreadSample(
    val id: Long,
    val name: String,
    val state: String,
    val cpu: Double,
    val daemon: Boolean,
    val group: String = "",
    /** Total CPU time of the thread so far. */
    val timeMs: Long = 0,
) {
    /** Threads Arthas itself runs in the target; not interesting when looking for a busy app thread. */
    val isArthas get() = name.contains("arthas", ignoreCase = true)

    companion object {
        /** One entry of `threads` (dashboard) or `threadStats` (thread). */
        fun parse(t: JsonObject) = ThreadSample(
            id = t.lng("id"),
            name = t.str("name").orEmpty(),
            state = t.str("state").orEmpty(),
            cpu = t.get("cpu")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() ?: 0.0,
            daemon = t.get("daemon")?.asBoolean == true,
            group = t.str("group").orEmpty(),
            timeMs = t.lng("time"),
        )

        private fun JsonObject.lng(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0
    }
}

/** One `dashboard` sample of the target JVM. */
data class JvmSnapshot(
    val javaVersion: String,
    val uptimeSeconds: Long,
    val processors: Int,
    val systemLoad: Double,
    val heapUsed: Long,
    val heapMax: Long,
    val nonHeapUsed: Long,
    val gcCount: Long,
    val gcTimeMs: Long,
    val threads: List<ThreadSample>,
) {
    /** Process CPU over the sample interval, as a share of all cores (Arthas reports per-thread % of one core). */
    val cpuPercent get() = if (processors > 0) threads.sumOf { it.cpu } / processors else 0.0
    val heapPercent get() = if (heapMax > 0) heapUsed * 100.0 / heapMax else 0.0
    val blockedThreads get() = threads.count { it.state == "BLOCKED" }
    val daemonThreads get() = threads.count { it.daemon }

    fun hotThreads(limit: Int) = threads.filterNot { it.isArthas }.sortedByDescending { it.cpu }.take(limit)

    companion object {
        /**
         * `dashboard -n 2 -i 1000`: per-thread CPU needs two samples, so only the second result has real CPU numbers.
         */
        const val COMMAND = "dashboard -n 2 -i 1000"

        fun parse(dashboard: JsonObject): JvmSnapshot? {
            if (dashboard.str("type") != "dashboard") return null
            val runtime = dashboard.getAsJsonObject("runtimeInfo") ?: JsonObject()
            val memory = dashboard.getAsJsonObject("memoryInfo") ?: JsonObject()
            val heap = memory.arr("heap").map { it.asJsonObject }.firstOrNull { it.str("name") == "heap" }
            val nonHeap = memory.arr("nonheap").map { it.asJsonObject }.firstOrNull { it.str("name") == "nonheap" }
            val gcs = dashboard.arr("gcInfos").map { it.asJsonObject }
            return JvmSnapshot(
                javaVersion = runtime.str("javaVersion").orEmpty(),
                uptimeSeconds = runtime.lng("uptime"),
                processors = runtime.lng("processors").toInt(),
                systemLoad = runtime.dbl("systemLoadAverage"),
                heapUsed = heap?.lng("used") ?: 0,
                // An unbounded heap reports max = -1; the committed size is the best ceiling we have then.
                heapMax = heap?.let { h -> h.lng("max").takeIf { it > 0 } ?: h.lng("total") } ?: 0,
                nonHeapUsed = nonHeap?.lng("used") ?: 0,
                gcCount = gcs.sumOf { it.lng("collectionCount") },
                gcTimeMs = gcs.sumOf { it.lng("collectionTime") },
                threads = dashboard.arr("threads").map { ThreadSample.parse(it.asJsonObject) },
            )
        }

        private fun JsonObject.arr(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        private fun JsonObject.lng(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0
        private fun JsonObject.dbl(key: String): Double = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull() ?: 0.0
    }
}
