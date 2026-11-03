package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payload trimmed from a real Arthas 4.3.2 `dashboard -n 2 -i 1000` result against math-game.jar. */
class JvmSnapshotTest {

    private val dashboard = """{"type":"dashboard",
        "runtimeInfo":{"javaVersion":"25.0.2","processors":10,"systemLoadAverage":7.54,"uptime":3725},
        "memoryInfo":{
          "heap":[{"max":4294967296,"name":"heap","total":60817408,"used":1073741824},{"max":-1,"name":"g1_eden_space","total":1,"used":1}],
          "nonheap":[{"max":-1,"name":"nonheap","total":44892160,"used":41943040}]},
        "gcInfos":[{"collectionCount":3,"collectionTime":20,"name":"g1_young_generation"},{"collectionCount":2,"collectionTime":1,"name":"g1_concurrent_gc"}],
        "threads":[
          {"cpu":0.29,"daemon":true,"id":60,"name":"Timer-for-arthas-dashboard-a0a5","state":"RUNNABLE"},
          {"cpu":5.0,"daemon":false,"id":3,"name":"main","state":"TIMED_WAITING"},
          {"cpu":15.0,"daemon":false,"id":31,"name":"http-nio-8080-exec-7","state":"BLOCKED"}]}"""

    private fun parse(json: String) = JvmSnapshot.parse(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `parses runtime, memory, gc and threads`() {
        val s = parse(dashboard)!!
        assertEquals("25.0.2", s.javaVersion)
        assertEquals(3725, s.uptimeSeconds)
        assertEquals(1073741824, s.heapUsed)
        assertEquals(4294967296, s.heapMax)
        assertEquals(25.0, s.heapPercent, 1e-9)
        assertEquals(41943040, s.nonHeapUsed)
        assertEquals(5, s.gcCount)
        assertEquals(21, s.gcTimeMs)
        assertEquals(1, s.blockedThreads)
        assertEquals(1, s.daemonThreads)
        // Per-thread % is of one core; the process total is spread over all 10.
        assertEquals((0.29 + 5.0 + 15.0) / 10, s.cpuPercent, 1e-9)
    }

    @Test
    fun `hot threads leave out Arthas's own threads`() {
        assertEquals(listOf("http-nio-8080-exec-7", "main"), parse(dashboard)!!.hotThreads(5).map { it.name })
    }

    @Test
    fun `unbounded heap falls back to committed size`() {
        val s = parse("""{"type":"dashboard","memoryInfo":{"heap":[{"max":-1,"name":"heap","total":200,"used":50}]}}""")!!
        assertEquals(200, s.heapMax)
    }

    @Test
    fun `other results are ignored`() {
        assertNull(parse("""{"type":"status","statusCode":0}"""))
    }
}
