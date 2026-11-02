package com.github.b3kt.arthasbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end attach through arthas-boot. Skipped unless ARTHAS_ATTACH_PID (a math-game.jar pid without Arthas)
 * and ARTHAS_BOOT_JAR are set.
 */
class ArthasAttacherTest {

    @Test
    fun `attach, reuse, then stop`() {
        val pid = System.getenv("ARTHAS_ATTACH_PID")?.toLongOrNull()
        val boot = System.getenv("ARTHAS_BOOT_JAR")
        assumeTrue(pid != null && boot != null)

        val process = JvmProcesses.list().first { it.pid == pid }
        assertEquals("math-game.jar", process.name)

        val log = StringBuffer()
        val first = ArthasAttacher(File(boot!!), 18564, 0, log = { log.append(it).append('\n') }).attach(process)
        println(log)
        assertFalse(first.reused)
        assertEquals(pid, ArthasAttacher.javaPid(first.client))

        val second = ArthasAttacher(File(boot), 18564, 0).attach(process)
        assertTrue(second.reused)

        // Another JVM must not silently reuse the port.
        val other = JvmProcess(ProcessHandle.current().pid(), "self", null)
        val error = runCatching { ArthasAttacher(File(boot), 18564, 0).attach(other) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("already used by Arthas attached to pid $pid"))

        runCatching { first.client.exec("stop", 5_000) }
        Thread.sleep(1_000)
        assertFalse(ArthasAttacher.portOpen("127.0.0.1", 18564))
    }
}
