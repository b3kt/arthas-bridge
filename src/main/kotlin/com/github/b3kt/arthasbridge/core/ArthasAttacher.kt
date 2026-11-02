package com.github.b3kt.arthasbridge.core

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Loads Arthas into a local JVM with arthas-boot.jar and waits until its HTTP API answers.
 * Kept free of IntelliJ APIs so it can be tested against a real JVM.
 */
class ArthasAttacher(
    private val boot: File,
    private val httpPort: Int,
    private val telnetPort: Int,
    private val username: String? = null,
    private val password: String? = null,
    private val log: (String) -> Unit = {},
    private val cancelled: () -> Boolean = { false },
) {

    data class Result(val client: ArthasHttpClient, val reused: Boolean)

    fun attach(process: JvmProcess): Result {
        // Reuse an Arthas that already listens on the port, if it is attached to the same JVM.
        if (portOpen("127.0.0.1", httpPort)) {
            val existing = client()
            val pid = runCatching { javaPid(existing) }.getOrNull()
                ?: throw ArthasException("port $httpPort is used by another program. Change the HTTP port in Settings.")
            if (pid != process.pid) {
                throw ArthasException("port $httpPort is already used by Arthas attached to pid $pid. " +
                    "Stop that one first or change the HTTP port in Settings.")
            }
            log("Arthas is already attached to ${process.pid}, reusing it.")
            return Result(existing, reused = true)
        }

        runBoot(process)

        val c = client()
        val waitUntil = System.currentTimeMillis() + 20_000
        while (runCatching { c.exec("version", 3_000) }.isFailure) {
            if (cancelled()) throw ArthasException("cancelled")
            if (System.currentTimeMillis() > waitUntil) throw ArthasException("Arthas did not open HTTP port $httpPort")
            Thread.sleep(300)
        }
        return Result(c, reused = false)
    }

    private fun runBoot(process: JvmProcess) {
        // Prefer the target's own java so the attach API matches the target's JDK.
        val java = process.javaBin ?: File(System.getProperty("java.home"), "bin/java").path
        val command = mutableListOf(
            java, "-jar", boot.path, "--attach-only",
            "--http-port", httpPort.toString(),
            "--telnet-port", telnetPort.toString(),
            "--target-ip", "127.0.0.1",
            "--repo-mirror", "center",
        )
        if (!username.isNullOrBlank() && password != null) command += listOf("--username", username, "--password", password)
        command += process.pid.toString()

        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (line.isBlank() || line.startsWith("Picked up JAVA_TOOL_OPTIONS") || line.trimStart().startsWith("at ")) return@forEachLine
                synchronized(tail) {
                    tail.addLast(line)
                    if (tail.size > 5) tail.removeFirst()
                }
                log("  $line")
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + 120_000
        while (!proc.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (cancelled() || System.currentTimeMillis() > deadline) {
                proc.destroyForcibly()
                throw ArthasException(if (cancelled()) "cancelled" else "arthas-boot did not finish in time")
            }
        }
        reader.join(1_000)
        if (proc.exitValue() != 0) {
            val last = synchronized(tail) { tail.lastOrNull { it.contains("ERROR") } ?: tail.lastOrNull() }
            throw ArthasException(last ?: "arthas-boot exited with ${proc.exitValue()}")
        }
    }

    private fun client() = ArthasHttpClient("127.0.0.1", httpPort, username, password)

    companion object {
        fun javaPid(c: ArthasHttpClient): Long? =
            results(c, "session").firstOrNull { it.str("type") == "session" }?.str("javaPid")?.toLongOrNull()

        fun version(c: ArthasHttpClient): String =
            results(c, "version").firstOrNull { it.str("type") == "version" }?.str("version") ?: "?"

        private fun results(c: ArthasHttpClient, command: String) =
            c.exec(command).getAsJsonObject("body")?.getAsJsonArray("results")?.map { it.asJsonObject }.orEmpty()

        fun portOpen(host: String, port: Int) = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }
        }.isSuccess
    }
}
