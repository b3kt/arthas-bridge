package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableRendererTest {

    private fun render(json: JsonObject, names: ParamNames = ParamNames.NONE) =
        ReadableRenderer.render(json, names)!!.filterIsInstance<Rendered.Text>().joinToString("") { it.text }

    private fun watch(value: String, accessPoint: String = "AtExit") = JsonObject().apply {
        addProperty("type", "watch")
        addProperty("className", "com.tiket.tix.payment.core.rest.controller.PaymentController")
        addProperty("methodName", "inquiryV3")
        addProperty("accessPoint", accessPoint)
        addProperty("cost", 1.129)
        addProperty("ts", "2026-10-29 11:23:10.324965")
        addProperty("value", value)
    }

    /** Shape of a real payment-core watch result (WebFlux controller returning Mono). */
    private val controllerValue = """
        @ArrayList[
            @Object[][
                null,
                @String[PostmanRuntime/2.12.2],
                @MandatoryRequest[MandatoryRequest(storeId=TIKETCOM, channelId=DESKTOP, requestId=11bb3252, serviceId=WEB, resellerId=null, identity=null, username=username, loyaltyLevel=null)],
                @PaymentCreateV3Request[PaymentCreateV3Request(referenceId=12006072, amount=12488.0, currency=IDR, items=[a, b], customer=Customer(name=Budi, email=null))],
            ],
            @MonoSubscribeOn[
                scheduler=@CachedScheduler[Schedulers.boundedElastic()],
                optimizableOperator=@MonoMapFuseable[MonoMapFuseable],
                EQUALS_BIPREDICATE=@Mono${'$'}${'$'}Lambda/0x0000007001888b50[reactor.core.publisher.Mono${'$'}${'$'}Lambda/0x0000007001888b50@64121576],
            ],
            null,
        ]""".trimIndent()

    @Test
    fun `parser understands nested object view`() {
        val node = ObjectViewParser.parse(controllerValue) as OvNode.Items
        assertEquals(3, node.items.size)
        val args = node.items[0] as OvNode.Items
        assertEquals("Object[]", args.type)
        assertEquals(4, args.items.size)
        assertEquals(OvNode.Null, args.items[0])
        assertEquals(OvNode.Scalar("String", "PostmanRuntime/2.12.2"), args.items[1])
        assertEquals("MonoSubscribeOn", (node.items[1] as OvNode.Fields).type)
    }

    @Test
    fun `watch summary names inputs, explains Mono and hides empty fields`() {
        val out = render(watch(controllerValue)) { _, _, count ->
            if (count == 4) listOf("serverHttpRequest", "userAgent", "mandatoryRequest", "request") else null
        }
        println(out)
        assertTrue(out.startsWith("● PaymentController.inquiryV3   11:23:10.324  ·  1.13 ms  ·  ✔ returned"))
        assertTrue(out.contains("userAgent") && out.contains("\"PostmanRuntime/2.12.2\""))
        assertTrue(out.contains("mandatoryRequest") && out.contains("storeId") && out.contains("TIKETCOM"))
        assertTrue(out.contains("(3 empty field(s) hidden)"))
        assertTrue(out.contains("Mono (async"))
        assertFalse(out.contains("boundedElastic"))
        assertTrue(out.contains("Error") && out.contains("none"))
        assertTrue(out.contains("name") && out.contains("Budi"))
    }

    @Test
    fun `exception is summarised with location`() {
        val value = "@ArrayList[\n    @Object[][\n        @Integer[-152675],\n    ],\n    null,\n    java.lang.IllegalArgumentException: number is: -152675, need >= 2\n\tat demo.MathGame.primeFactors(MathGame.java:46)\n\tat demo.MathGame.run(MathGame.java:24)\n,\n]"
        val out = render(watch(value, "AtExceptionExit"))
        println(out)
        assertTrue(out.contains("✖ threw an error"))
        assertTrue(out.contains("IllegalArgumentException: number is: -152675, need >= 2  (at MathGame.primeFactors line 46)"))
        assertFalse(out.contains("Result"))
    }

    @Test
    fun `unparseable value falls back to verbatim`() {
        val out = render(watch("some ] odd [ text"))
        assertTrue(out.contains("some ] odd [ text"))
    }

    @Test
    fun `trace lists slowest steps`() {
        val json = JsonParser.parseString("""{"root":{"children":[{"children":[{"className":"demo.MathGame","lineNumber":24,"methodName":"primeFactors","times":1,"totalCost":15041,"type":"method"},{"className":"demo.MathGame","lineNumber":25,"methodName":"print","times":1,"totalCost":91250,"type":"method"}],"className":"demo.MathGame","methodName":"run","times":1,"totalCost":170167,"type":"method"}],"threadName":"main","timestamp":"2026-10-29 10:42:27.527640","type":"thread"},"type":"trace"}""").asJsonObject
        val out = render(json)
        println(out)
        val slow = out.lines().indexOfFirst { it.contains("Slowest steps") }
        assertTrue(out.lines()[slow + 1].contains("53.6%") && out.lines()[slow + 1].contains("MathGame.print (line 25)"))
    }

    @Test
    fun `stack hides framework frames`() {
        val json = JsonParser.parseString("""{"stackTrace":[{"className":"com.x.Svc","methodName":"pay"},{"className":"com.x.Controller","methodName":"create","lineNumber":40},{"className":"org.springframework.web.Foo","methodName":"a"},{"className":"reactor.core.Bar","methodName":"b"},{"className":"com.x.Filter","methodName":"filter","lineNumber":12}],"threadName":"http-1","ts":"2026-10-29 10:42:30.541503","type":"stack"}""").asJsonObject
        val out = render(json)
        println(out)
        assertTrue(out.contains("← Controller.create  line 40"))
        assertTrue(out.contains("2 framework call(s)"))
        assertTrue(out.contains("← Filter.filter"))
    }

    @Test
    fun `monitor sentence`() {
        val json = JsonParser.parseString("""{"monitorDataList":[{"className":"demo.MathGame","cost":0.5,"failed":1,"methodName":"primeFactors","success":3,"timestamp":"2026-10-29 10:42:43.588596","total":4}],"type":"monitor"}""").asJsonObject
        assertTrue(render(json).contains("4 call(s), avg 0.13 ms, 1 failed (25.0%)"))
    }
}
