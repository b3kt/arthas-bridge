package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payloads are real Arthas 4.3.2 HTTP API results captured against math-game.jar. */
class InspectParserTest {

    private fun results(vararg json: String) = json.map { JsonParser.parseString(it).asJsonObject }

    @Test
    fun `thread list`() {
        val threads = InspectParser.threads(results(
            """{"all":true,"threadStateCount":{"RUNNABLE":9},"threadStats":[{"cpu":0.11,"daemon":true,"deltaTime":0,"group":"system","id":52,"interrupted":false,"name":"arthas-command-execute","priority":5,"state":"RUNNABLE","time":14},{"cpu":0.04,"daemon":false,"group":"main","id":3,"name":"main","state":"TIMED_WAITING","time":167}],"type":"thread"}""",
            """{"statusCode":0,"type":"status"}""",
        ))
        assertEquals(listOf(52L, 3L), threads.map { it.id })
        assertEquals(ThreadSample(3, "main", "TIMED_WAITING", 0.04, false, "main", 167), threads[1])
    }

    @Test
    fun `thread stack`() {
        val d = InspectParser.threadDetail(results(
            """{"threadInfo":{"blockedCount":0,"lockOwnerId":-1,"stackTrace":[{"fileName":"Thread.java","lineNumber":-2,"className":"java.lang.Thread","methodName":"sleepNanos0"},{"fileName":"MathGame.java","lineNumber":17,"className":"demo.MathGame","methodName":"main"}],"threadId":3,"threadName":"main","threadState":"TIMED_WAITING","waitedCount":22},"type":"thread"}""",
        ))!!
        assertEquals("main", d.name)
        assertNull(d.lockOwnerId)
        assertEquals("java.lang.Thread.sleepNanos0(Native Method)", d.frames[0].toString())
        assertEquals("demo.MathGame.main(MathGame.java:17)", d.frames[1].toString())
        assertNull(InspectParser.threadDetail(results("""{"message":"No most blocking thread found!","statusCode":1,"type":"status"}""")))
    }

    @Test
    fun `class search and details`() {
        assertEquals(listOf("demo.MathGame"), InspectParser.classNames(results(
            """{"classNames":["demo.MathGame","demo.MathGame"],"detailed":false,"type":"sc"}""", """{"rowCount":1,"type":"row_affect"}""",
        )))
        val info = InspectParser.classInfos(results(
            """{"classInfo":{"annotations":[],"classLoaderHash":"14dad5dc","classloader":["jdk.internal.loader.ClassLoaders${'$'}AppClassLoader@14dad5dc"],"codeSource":"/tmp/math-game.jar","interfaces":[],"modifier":"public","name":"demo.MathGame","superClass":["java.lang.Object"]},"detailed":true,"type":"sc"}""",
        )).single()
        assertEquals("/tmp/math-game.jar", info.codeSource)
        assertEquals(listOf("java.lang.Object"), info.superClasses)
    }

    @Test
    fun `method signatures from descriptors`() {
        val methods = InspectParser.methods(results(
            """{"methodInfo":{"constructor":true,"declaringClass":"demo.MathGame","descriptor":"()V","methodName":"<init>"},"type":"sm"}""",
            """{"methodInfo":{"constructor":false,"declaringClass":"demo.MathGame","descriptor":"(I)Ljava/util/List;","methodName":"primeFactors"},"type":"sm"}""",
            """{"methodInfo":{"constructor":false,"declaringClass":"demo.MathGame","descriptor":"([Ljava/lang/String;J[[B)V","methodName":"main"},"type":"sm"}""",
        ))
        assertEquals(listOf("MathGame()", "primeFactors(int): List", "main(String[], long, byte[][]): void"), methods.map { it.signature })
    }

    @Test
    fun `plain names search anywhere in the class name`() {
        assertEquals("*PaymentService*", InspectParser.classPattern(" PaymentService "))
        assertEquals("com.acme.*Controller", InspectParser.classPattern("com.acme.*Controller"))
    }
}
