package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads are real Arthas 4.3.2 HTTP API results captured against math-game.jar. */
class ResultRendererTest {

    private fun render(json: String) = ResultRenderer.render(JsonParser.parseString(json).asJsonObject)
    private fun text(json: String) = render(json).filterIsInstance<Rendered.Text>().joinToString("") { it.text }

    @Test
    fun `watch shows header and value`() {
        val out = text("""{"accessPoint":"AtExit","className":"demo.MathGame","cost":0.109708,"jobId":9,"methodName":"primeFactors","ts":"2026-10-29 10:42:25.515005","type":"watch","value":"@ArrayList[\n    @Integer[2],\n]"}""")
        assertTrue(out.startsWith("ts=2026-10-29 10:42:25.515005  demo.MathGame.primeFactors  [AtExit]  cost=0.110ms"))
        assertTrue(out.contains("@Integer[2]"))
    }

    @Test
    fun `trace renders tree with percentages`() {
        val out = text("""{"jobId":10,"nodeCount":3,"root":{"children":[{"children":[{"className":"demo.MathGame","lineNumber":24,"methodName":"primeFactors","times":1,"totalCost":15041,"type":"method"},{"className":"demo.MathGame","lineNumber":25,"methodName":"print","times":1,"totalCost":91250,"type":"method"}],"className":"demo.MathGame","lineNumber":-1,"methodName":"run","times":1,"totalCost":170167,"type":"method"}],"classloader":"App","daemon":false,"priority":5,"threadId":1,"threadName":"main","timestamp":"2026-10-29 10:42:27.527640","type":"thread"},"type":"trace"}""")
        val lines = out.lines()
        assertTrue(lines[0].startsWith("`---ts=2026-10-29 10:42:27.527640;thread_name=main;id=1"))
        assertEquals("    `---[0.170ms] demo.MathGame:run()", lines[1])
        assertEquals("        +---[8.84% 0.015ms] demo.MathGame:primeFactors() #24", lines[2])
        assertEquals("        `---[53.62% 0.091ms] demo.MathGame:print() #25", lines[3])
    }

    @Test
    fun `failed status is an error without ansi codes`() {
        val out = render("""{"jobId":14,"message":"No class or method is affected, try:\n1. Execute `\u001b[32msm CLASS_NAME\u001b[m`","statusCode":-1,"type":"status"}""")
        val t = out.single() as Rendered.Text
        assertEquals(OutputKind.ERROR, t.kind)
        assertFalse(t.text.contains("\u001b"))
    }

    @Test
    fun `successful status and bookkeeping results are silent`() {
        assertTrue(render("""{"jobId":9,"statusCode":0,"type":"status"}""").isEmpty())
        assertTrue(render("""{"inputStatus":"ALLOW_INPUT","jobId":0,"type":"input_status"}""").isEmpty())
        assertTrue(render("""{"command":"x","jobId":9,"state":"SCHEDULED","type":"command"}""").isEmpty())
    }

    @Test
    fun `jad opens source in editor`() {
        val out = render("""{"classInfo":{"name":"demo.MathGame"},"location":"/tmp/math-game.jar","source":"class MathGame {}","type":"jad"}""")
        assertEquals(Rendered.Source("demo.MathGame", "class MathGame {}"), out[0])
    }

    @Test
    fun `tt lists fragments with params and return`() {
        val out = text("""{"first":true,"jobId":12,"timeFragmentList":[{"className":"demo.MathGame","cost":0.099583,"index":1000,"methodName":"primeFactors","params":[{"expand":1,"object":118602}],"return":true,"returnObj":"@ArrayList[]","throw":false,"timestamp":"2026-10-29 10:42:32.548639"}],"type":"tt"}""")
        assertTrue(out.contains("1000"))
        assertTrue(out.contains("RETURN"))
        assertTrue(out.contains("param[0] 118602"))
    }

    @Test
    fun `profiler stop exposes output file`() {
        val out = render("""{"action":"stop","executeResult":"OK\n","outputFile":"/tmp/x.html","type":"profiler"}""")
        assertTrue(out.contains(Rendered.OutputFile("/tmp/x.html")))
    }
}
