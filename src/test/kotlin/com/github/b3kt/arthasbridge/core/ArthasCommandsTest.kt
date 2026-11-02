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
    fun `main name from java args`() {
        assertEquals("com.tiket.App", ArthasCommands.mainName(listOf("-Xmx1g", "-cp", "a.jar:b.jar", "com.tiket.App", "--x")))
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
