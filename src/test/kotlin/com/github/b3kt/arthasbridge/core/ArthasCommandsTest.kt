package com.github.b3kt.arthasbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArthasCommandsTest {

    @Test
    fun `watch command uses depth and limit`() {
        assertEquals("watch com.x.Foo bar '{params, returnObj, throwExp}' -x 2 -n 5 --exclude-class-pattern *CGLIB$$*",
            ArthasCommands.watch("com.x.Foo", "bar", 2, 5))
    }

    @Test
    fun `conditions are quoted after the method`() {
        assertEquals("trace com.x.Foo bar '#cost > 100' -n 5 --exclude-class-pattern *CGLIB$$*",
            ArthasCommands.trace("com.x.Foo", "bar", 5, ArthasCommands.condition(null, 100)))
        assertEquals("watch com.x.Foo bar '{params, returnObj, throwExp}' \"params[0] == 'A'\" -x 2 -n 5 --exclude-class-pattern *CGLIB$$*",
            ArthasCommands.watch("com.x.Foo", "bar", 2, 5, "params[0] == 'A'"))
        assertEquals("(params[0] > 1) && #cost > 10", ArthasCommands.condition(" params[0] > 1 ", 10))
        assertEquals(null, ArthasCommands.condition("  ", null))
        // The probe still knows its target when a condition is added.
        val probe = Probe(1, ArthasCommands.watch("com.x.Foo", "bar", 2, 5, "params[0] == 'A'"))
        assertEquals("Foo.bar", probe.target)
        assertEquals(5, probe.limit)
    }

    @Test
    fun `probe stats label`() {
        val probe = Probe(1, "trace a.B c")
        assertEquals("0 calls", probe.stats.label("trace"))
        probe.add(ProbeHit("t", 80.0, false, "s"))
        probe.add(ProbeHit("t", 2100.0, true, "s"))
        assertEquals("2 calls  ·  avg 1.09 s  ·  max 2.10 s  ·  1 failed", probe.stats.label("trace"))
        assertEquals("2 reports  ·  avg 1.09 s  ·  1 failed", probe.stats.label("monitor"))
        assertEquals("0.25 ms", formatMs(0.25))
        assertEquals("83 ms", formatMs(83.4))
    }

    @Test
    fun `main name from java args`() {
        assertEquals("com.github.b3kt.App", ArthasCommands.mainName(listOf("-Xmx1g", "-cp", "a.jar:b.jar", "com.github.b3kt.App", "--x")))
        assertEquals("app.jar", ArthasCommands.mainName(listOf("-Dfoo=1", "-jar", "/opt/app/app.jar")))
        assertEquals("my.mod/my.Main", ArthasCommands.mainName(listOf("-p", "mods", "-m", "my.mod/my.Main")))
        assertEquals("java", ArthasCommands.mainName(emptyList()))
    }

    @Test
    fun `masks luhn-valid card numbers only`() {
        assertEquals("card=411111******1111", SensitiveDataMasker.mask("card=4111111111111111"))
        assertEquals("pan 411111******1111 ok", SensitiveDataMasker.mask("pan 4111 1111 1111 1111 ok"))
        // Epoch millis and order ids are left alone.
        assertEquals("ts=1793245331948 order=1200807833", SensitiveDataMasker.mask("ts=1793245331948 order=1200807833"))
    }
}
