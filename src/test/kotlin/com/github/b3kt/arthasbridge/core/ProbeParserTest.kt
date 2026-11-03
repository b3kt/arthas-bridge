package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {

    private fun hits(json: String) = ProbeParser.hits(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `recognises probe commands`() {
        assertTrue(ProbeParser.isProbe(ArthasCommands.watch("demo.MathGame", "primeFactors", 2, 5)))
        assertTrue(ProbeParser.isProbe("trace demo.MathGame run"))
        assertTrue(ProbeParser.isProbe("tt -t demo.MathGame primeFactors -n 3"))
        assertFalse(ProbeParser.isProbe("tt -l"))
        assertFalse(ProbeParser.isProbe("tt -i 1000"))
        assertFalse(ProbeParser.isProbe("thread -n 5"))
        assertFalse(ProbeParser.isProbe("watch"))
    }

    @Test
    fun `reads target and limit, skipping options and quoted express`() {
        val probe = Probe(1, "watch com.x.PaymentService charge '{params, returnObj}' -x 2 -n 50 --exclude-class-pattern *CGLIB\$\$*")
        assertEquals("watch", probe.kind)
        assertEquals("com.x.PaymentService", probe.className)
        assertEquals("charge", probe.methodName)
        assertEquals("PaymentService.charge", probe.target)
        assertEquals(50, probe.limit)
        assertNull(Probe(2, "trace a.B c").limit)
    }

    @Test
    fun `watch hit marks exceptions`() {
        val ok = hits("""{"accessPoint":"AtExit","className":"demo.MathGame","cost":0.109708,"methodName":"primeFactors","ts":"2026-10-29 10:42:25.515005","type":"watch","value":"@ArrayList[\n    @Integer[2],\n]"}""").single()
        assertEquals("10:42:25.515", ok.time)
        assertEquals(0.109708, ok.costMs!!, 1e-9)
        assertFalse(ok.error)
        assertEquals("returned  @ArrayList[ @Integer[2], ]", ok.summary)

        val failed = hits("""{"accessPoint":"AtExceptionExit","cost":1.0,"ts":"2026-10-29 10:42:25.515005","type":"watch","value":"boom"}""").single()
        assertTrue(failed.error)
    }

    @Test
    fun `trace hit keeps the call tree and total cost`() {
        val hit = hits("""{"root":{"children":[{"children":[{"className":"demo.MathGame","lineNumber":24,"methodName":"primeFactors","times":1,"totalCost":15041,"type":"method","throw":true,"exceptions":"java.lang.IllegalArgumentException"},{"className":"demo.MathGame","lineNumber":25,"methodName":"print","times":2,"totalCost":91250,"type":"method"}],"className":"demo.MathGame","methodName":"run","times":1,"totalCost":170167,"type":"method"}],"threadName":"main","timestamp":"2026-10-29 10:42:27.527640","type":"thread"},"type":"trace"}""").single()
        assertEquals(0.170167, hit.costMs!!, 1e-9)
        assertTrue(hit.error)
        assertEquals("thread main  ·  threw java.lang.IllegalArgumentException", hit.summary)
        val run = hit.trace.single()
        assertEquals("run", run.methodName)
        assertEquals(listOf("primeFactors", "print"), run.children.map { it.methodName })
        assertEquals(24, run.children[0].line)
        assertEquals(2L, run.children[1].times)
    }

    @Test
    fun `tt gives one hit per fragment with replay hint`() {
        val hit = hits("""{"first":true,"timeFragmentList":[{"className":"demo.MathGame","cost":0.099583,"index":1000,"methodName":"primeFactors","params":[],"return":true,"returnObj":"@ArrayList[]","throw":false,"timestamp":"2026-10-29 10:42:32.548639"}],"type":"tt"}""").single()
        assertTrue(hit.summary.contains("tt -i 1000 -p"))
        assertEquals("tt", hit.source!!.get("type").asString)
    }

    @Test
    fun `monitor hit summarises the interval`() {
        val hit = hits("""{"monitorDataList":[{"className":"demo.MathGame","cost":10.0,"failed":1,"methodName":"run","success":3,"timestamp":"2026-10-29 10:42:40","total":4}],"type":"monitor"}""").single()
        assertEquals("4 calls  ·  1 failed  ·  avg 2.50 ms", hit.summary)
        assertTrue(hit.error)
    }

    @Test
    fun `probe keeps a bounded, numbered history`() {
        val probe = Probe(1, "watch a.B c")
        repeat(Probe.MAX_KEPT_HITS + 5) { probe.add(ProbeHit("t", 1.0, it == 0, "s")) }
        assertEquals(Probe.MAX_KEPT_HITS + 5, probe.hitCount)
        assertEquals(1, probe.errorCount)
        assertEquals(Probe.MAX_KEPT_HITS, probe.hits().size)
        assertEquals(Probe.MAX_KEPT_HITS + 5, probe.hits().last().seq)
    }
}
