package com.github.b3kt.arthasbridge.core

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against a real Arthas attached to math-game.jar. Skipped unless ARTHAS_LIVE_PORT is set:
 *   java -jar math-game.jar &
 *   java -jar arthas-boot.jar --attach-only --http-port 18563 --telnet-port 0 <pid>
 *   ARTHAS_LIVE_PORT=18563 ./gradlew test --tests '*ArthasLiveTest'
 */
class ArthasLiveTest {

    private val port = System.getenv("ARTHAS_LIVE_PORT")?.toIntOrNull()

    @Test
    fun `session job streams trace until status`() {
        assumeTrue(port != null)
        val client = ArthasHttpClient("127.0.0.1", port!!)
        val session = client.initSession()
        try {
            client.asyncExec(session, "trace demo.MathGame run -n 2")
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + 15_000
            var finished = false
            while (!finished && System.currentTimeMillis() < deadline) {
                for (r in client.pullResults(session)) {
                    val obj = r.asJsonObject
                    ResultRenderer.render(obj).filterIsInstance<Rendered.Text>().forEach { out.append(it.text) }
                    if (ResultRenderer.isJobEnd(obj)) finished = true
                }
                Thread.sleep(200)
            }
            println(out)
            assertTrue("job should end with a status result", finished)
            assertTrue(out.contains("demo.MathGame:primeFactors()"))
        } finally {
            client.closeSession(session)
        }
    }

    @Test
    fun `interrupt ends a long watch`() {
        assumeTrue(port != null)
        val client = ArthasHttpClient("127.0.0.1", port!!)
        val session = client.initSession()
        try {
            client.asyncExec(session, "watch demo.MathGame primeFactors -n 100")
            Thread.sleep(1_500)
            client.interruptJob(session)
            var finished = false
            val deadline = System.currentTimeMillis() + 5_000
            while (!finished && System.currentTimeMillis() < deadline) {
                finished = client.pullResults(session).any { ResultRenderer.isJobEnd(it.asJsonObject) }
            }
            assertTrue(finished)
        } finally {
            client.closeSession(session)
        }
    }

    @Test
    fun `probes in separate sessions run side by side`() {
        assumeTrue(port != null)
        val client = ArthasHttpClient("127.0.0.1", port!!)
        val commands = listOf(
            ArthasCommands.watch("demo.MathGame", "primeFactors", 2, 3),
            ArthasCommands.trace("demo.MathGame", "run", 2),
        )
        val sessions = commands.map { command -> client.initSession().also { client.asyncExec(it, command) } }
        try {
            val hits = IntArray(commands.size)
            val finished = BooleanArray(commands.size)
            val deadline = System.currentTimeMillis() + 20_000
            while (!finished.all { it } && System.currentTimeMillis() < deadline) {
                sessions.forEachIndexed { i, s ->
                    for (r in client.pullResults(s)) {
                        val obj = r.asJsonObject
                        hits[i] += ProbeParser.hits(obj).size
                        if (ResultRenderer.isJobEnd(obj)) finished[i] = true
                    }
                }
                Thread.sleep(200)
            }
            assertTrue("both probes should finish", finished.all { it })
            assertTrue("watch hits: ${hits[0]}", hits[0] == 3)
            assertTrue("trace hits: ${hits[1]}", hits[1] == 2)
        } finally {
            sessions.forEach(client::closeSession)
        }
    }
}

/** Prints readable output for each streaming command against live math-game (for eyeballing). */
class ArthasReadableLiveTest {

    @Test
    fun `readable output for streaming commands`() {
        val port = System.getenv("ARTHAS_LIVE_PORT")?.toIntOrNull()
        assumeTrue(port != null)
        val client = ArthasHttpClient("127.0.0.1", port!!)
        val session = client.initSession()
        try {
            for (command in listOf(
                ArthasCommands.watch("demo.MathGame", "primeFactors", 2, 2),
                ArthasCommands.trace("demo.MathGame", "run", 1),
                ArthasCommands.stack("demo.MathGame", "primeFactors", 1),
                "monitor -c 2 demo.MathGame primeFactors -n 1",
                ArthasCommands.timeTunnel("demo.MathGame", "primeFactors", 1),
            )) {
                client.asyncExec(session, command)
                val deadline = System.currentTimeMillis() + 15_000
                var finished = false
                println("> $command")
                while (!finished && System.currentTimeMillis() < deadline) {
                    for (r in client.pullResults(session)) {
                        val obj = r.asJsonObject
                        val rendered = ReadableRenderer.render(obj, ParamNames.NONE) ?: ResultRenderer.render(obj)
                        rendered.filterIsInstance<Rendered.Text>().forEach { print(it.text) }
                        if (ResultRenderer.isJobEnd(obj)) finished = true
                    }
                    Thread.sleep(200)
                }
                assertTrue(finished)
            }
        } finally {
            client.closeSession(session)
        }
    }
}
